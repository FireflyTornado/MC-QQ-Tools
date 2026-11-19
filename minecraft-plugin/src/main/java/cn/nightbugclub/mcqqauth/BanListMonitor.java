package cn.nightbugclub.mcqqauth;

import org.bukkit.BanList;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchService;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

final class BanListMonitor implements AutoCloseable {
    private final Plugin plugin;
    private final Path fileName;
    private final Clock clock;
    private final WatchService watcher;
    private final AtomicBoolean refreshQueued = new AtomicBoolean();
    private volatile boolean closed;

    // The effective snapshot and the expiration task are owned by the Bukkit thread.
    private Set<PlayerBan> current;
    private BukkitTask expirationTask;

    BanListMonitor(Plugin plugin, Path file, Clock clock) throws IOException {
        Path absoluteFile = file.toAbsolutePath().normalize();
        this.plugin = plugin;
        this.fileName = absoluteFile.getFileName();
        this.clock = clock;
        watcher = absoluteFile.getFileSystem().newWatchService();
        try {
            absoluteFile.getParent().register(watcher, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
        } catch (IOException | RuntimeException e) {
            try {
                watcher.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    void start() {
        queueRefresh();
        Thread worker = new Thread(this::watchForChanges, "MCQQAuth-banlist-watch");
        worker.setDaemon(true);
        worker.start();
    }

    private void watchForChanges() {
        try (watcher) {
            while (!closed) {
                var key = watcher.take();
                boolean changed = key.pollEvents().stream()
                        .anyMatch(event -> event.kind() == OVERFLOW || fileName.equals(event.context()));
                if (changed) {
                    queueRefresh();
                }
                if (!key.reset()) {
                    if (!closed) {
                        plugin.getLogger().warning("封禁名单文件的目录监听已失效。");
                    }
                    return;
                }
            }
        } catch (ClosedWatchServiceException ignored) {
            // Closing the service wakes up the watcher during plugin shutdown.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            plugin.getLogger().warning("封禁名单文件监听失败：" + e.getMessage());
        }
    }

    private void queueRefresh() {
        if (closed || !refreshQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                refreshQueued.set(false);
                refresh();
            });
        } catch (IllegalPluginAccessException e) {
            refreshQueued.set(false);
            if (!closed && plugin.isEnabled()) {
                throw e;
            }
        }
    }

    private void refresh() {
        if (closed) {
            return;
        }
        Instant now = clock.instant();
        ProfileBanList list = plugin.getServer().getBanList(BanList.Type.PROFILE);
        Set<PlayerBan> active = new HashSet<>();
        for (var entry : list.getEntries()) {
            var date = entry.getExpiration();
            Instant expiration = date == null ? null : date.toInstant();
            if (expiration == null || expiration.isAfter(now)) {
                var profile = entry.getBanTarget();
                active.add(new PlayerBan(profile.getUniqueId(), profile.getName(), expiration));
            }
        }
        if (current == null) {
            plugin.getLogger().info("封禁名单文件监听已初始化，当前有效封禁 " + active.size() + " 条。");
        } else if (!current.equals(active)) {
            plugin.getLogger().info("玩家封禁名单已变化，有效封禁 " + current.size() + " → " + active.size() + " 条。");
        }
        current = active;
        scheduleExpiration(now);
    }

    private void scheduleExpiration(Instant now) {
        cancelExpiration();
        current.stream().map(PlayerBan::expiration).filter(Objects::nonNull).min(Instant::compareTo)
                .ifPresent(expiration -> {
                    long millis = expiration.toEpochMilli() - now.toEpochMilli();
                    long ticks = millis <= 0 ? 1 : (millis - 1) / 50 + 1;
                    expirationTask = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                        expirationTask = null;
                        refresh();
                    }, ticks);
                });
    }

    private void cancelExpiration() {
        if (expirationTask != null) {
            expirationTask.cancel();
            expirationTask = null;
        }
    }

    @Override
    public void close() {
        closed = true;
        cancelExpiration();
        try {
            watcher.close();
        } catch (IOException e) {
            plugin.getLogger().warning("无法关闭封禁名单文件监听：" + e.getMessage());
        }
    }

    private record PlayerBan(UUID uuid, String name, Instant expiration) {}
}
