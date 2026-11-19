package cn.nightbugclub.mcqqauth;

import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Server;
import org.bukkit.ban.ProfileBanList;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class BanListMonitorTest {
    @TempDir
    Path directory;

    private final Plugin plugin = mock(Plugin.class);
    private final Logger logger = mock(Logger.class);
    private final ProfileBanList list = mock(ProfileBanList.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final Clock clock = mock(Clock.class);
    private final BlockingQueue<Runnable> mainTasks = new LinkedBlockingQueue<>();

    @BeforeEach
    void setUp() {
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(logger);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.<ProfileBanList>getBanList(BanList.Type.PROFILE)).thenReturn(list);
        when(clock.instant()).thenReturn(Instant.parse("2026-11-19T02:00:00Z"));
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
            mainTasks.add(call.getArgument(1));
            return mock(BukkitTask.class);
        });
    }

    @Test
    void fileCreationAndReplacementRefreshTheLiveListAndDuplicateWritesDoNotLogChanges() throws Exception {
        Path banFile = directory.resolve("banned-players.json");
        when(list.getEntries()).thenReturn(Set.of());
        try (var monitor = new BanListMonitor(plugin, banFile, clock)) {
            monitor.start();
            runQueuedRefresh();
            verify(logger).info(contains("当前有效封禁 0 条"));

            var playerBan = ban(null);
            when(list.getEntries()).thenReturn(Set.of(playerBan));
            Files.writeString(banFile, "[");
            runQueuedRefresh();
            verify(logger).info(contains("0 → 1"));

            Files.writeString(banFile, "[]");
            runQueuedRefresh();
            verify(logger, times(1)).info(contains("名单已变化"));

            when(list.getEntries()).thenReturn(Set.of());
            Path replacement = Files.writeString(directory.resolve("replacement.json"), "[]");
            Files.move(replacement, banFile, StandardCopyOption.REPLACE_EXISTING);
            runQueuedRefresh();
            verify(logger).info(contains("1 → 0"));
        }
    }

    @Test
    void scheduledExpirationRemovesTheEffectiveBanWithoutAFileEvent() throws Exception {
        Instant expiration = clock.instant().plusMillis(1001);
        var playerBan = ban(expiration);
        when(list.getEntries()).thenReturn(Set.of(playerBan));
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), anyLong()))
                .thenReturn(mock(BukkitTask.class));
        try (var monitor = new BanListMonitor(plugin, directory.resolve("banned-players.json"), clock)) {
            monitor.start();
            runQueuedRefresh();
            verify(logger).info(contains("当前有效封禁 1 条"));
            var timeout = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTaskLater(eq(plugin), timeout.capture(), eq(21L));

            when(clock.instant()).thenReturn(expiration);
            timeout.getValue().run();
            verify(logger).info(contains("1 → 0"));
            verify(scheduler, times(1)).runTaskLater(eq(plugin), any(Runnable.class), anyLong());
        }
    }

    private void runQueuedRefresh() throws InterruptedException {
        Runnable task = mainTasks.poll(5, TimeUnit.SECONDS);
        assertNotNull(task, "Expected a refresh on the Bukkit scheduler");
        task.run();
    }

    @SuppressWarnings("unchecked")
    private static BanEntry<PlayerProfile> ban(Instant expiration) {
        BanEntry<PlayerProfile> entry = mock(BanEntry.class);
        PlayerProfile profile = mock(PlayerProfile.class);
        when(profile.getUniqueId()).thenReturn(UUID.randomUUID());
        when(profile.getName()).thenReturn("Steve");
        when(entry.getBanTarget()).thenReturn(profile);
        when(entry.getExpiration()).thenReturn(expiration == null ? null : Date.from(expiration));
        return entry;
    }
}
