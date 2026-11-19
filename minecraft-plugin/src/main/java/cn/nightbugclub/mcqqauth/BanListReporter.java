package cn.nightbugclub.mcqqauth;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

final class BanListReporter implements AutoCloseable {
    private static final Gson JSON = new GsonBuilder().serializeNulls()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES).create();
    private static final int MAX_ACK_LENGTH = 4096;
    private static final String CONFLICT_HEADER = "X-MCQQ-Banlist-Conflict";

    record Settings(URI url, String token, int connectTimeoutMs, int requestTimeoutMs, int retryMinMs, int retryMaxMs) {
        static Settings read(ConfigurationSection config) {
            String urlKey = "banlist-watch.upload-url";
            String tokenKey = "banlist-watch.token";
            Object urlValue = config.get(urlKey);
            Object tokenValue = config.get(tokenKey);
            if ("".equals(urlValue) && "".equals(tokenValue)) {
                return null;
            }
            if (!(urlValue instanceof String) || !(tokenValue instanceof String token) || !PluginSettings.validToken(token)
                    || token.equals(config.getString("api.token")) || token.equals(config.getString("sudo.token"))
                    || token.equals("REPLACE_WITH_MC_BANLIST_TOKEN")) {
                throw new IllegalArgumentException("需要 HTTPS 上报地址和独立的 banlist-watch.token");
            }
            URI url = PluginSettings.secureUrl(PluginSettings.parseUrl(config, urlKey), "https", urlKey);
            if (url.getRawPath().endsWith("/")) {
                throw new IllegalArgumentException(urlKey + " 的路径末尾不能有斜杠");
            }
            int min = PluginSettings.positive(config.getInt("sync.reconnect-min-ms"), "sync.reconnect-min-ms");
            int max = PluginSettings.positive(config.getInt("sync.reconnect-max-ms"), "sync.reconnect-max-ms");
            if (max < min) {
                throw new IllegalArgumentException("sync.reconnect-max-ms 必须不小于初始等待");
            }
            return new Settings(url, token,
                    PluginSettings.positive(config.getInt("api.connect-timeout-ms"), "api.connect-timeout-ms"),
                    PluginSettings.positive(config.getInt("api.request-timeout-ms"), "api.request-timeout-ms"), min, max);
        }
    }

    private record Profile(String name, Long expiresAt) {}

    private record Snapshot(Map<String, Long> entries, Map<String, Profile> profiles) {
        int size() {
            return entries.size() + profiles.size();
        }
    }

    private record Upload(long generation, String clientId, long sequence, Map<String, Long> entries,
                          Map<String, Profile> profiles) {}

    private final String clientId = UUID.randomUUID().toString().replace("-", "");
    private final Settings settings;
    private final HttpClient http;
    private final Logger logger;
    private final ReconnectBackoff backoff;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "MCQQAuth-banlist-upload");
        thread.setDaemon(true);
        return thread;
    });

    // Guard the handoff between the Bukkit thread and the reporting worker.
    private Snapshot latest;
    private Snapshot acknowledged;
    private boolean scheduled;
    private volatile boolean closed;

    // Only the reporting worker changes an in-flight snapshot or its cursor.
    private Snapshot inFlight;
    private long generation;
    private long sequence;
    private boolean retrying;
    private boolean retriedStartupConflict;

    BanListReporter(Settings settings, Logger logger) {
        this(settings, logger, HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(settings.connectTimeoutMs()))
                .proxy(ProxySelector.of(null)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    BanListReporter(Settings settings, Logger logger, HttpClient http) {
        this.settings = settings;
        this.logger = logger;
        this.http = http;
        backoff = new ReconnectBackoff(settings.retryMinMs(), settings.retryMaxMs());
    }

    synchronized void submit(Set<BanListMonitor.PlayerBan> snapshot) {
        if (closed) {
            return;
        }
        Map<String, Long> entries = new TreeMap<>();
        Map<String, Profile> profiles = new TreeMap<>();
        for (var ban : snapshot) {
            String name = ban.name() == null ? null : ban.name().toLowerCase(Locale.ROOT);
            Long next = ban.expiration() == null ? null : ban.expiration().toEpochMilli();
            if (ban.uuid() != null) {
                profiles.put(ban.uuid().toString(), new Profile(name, next));
                continue;
            }
            Long previous = entries.get(name);
            if (!entries.containsKey(name) || previous != null && (next == null || next > previous)) {
                entries.put(name, next);
            }
        }
        latest = new Snapshot(Collections.unmodifiableMap(entries), Collections.unmodifiableMap(profiles));
        scheduleIfChanged();
    }

    private void scheduleIfChanged() {
        if (!closed && !scheduled && !latest.equals(acknowledged)) {
            scheduled = true;
            worker.execute(this::upload);
        }
    }

    private void upload() {
        synchronized (this) {
            if (closed) {
                return;
            }
            if (inFlight == null) {
                inFlight = latest;
            }
        }
        try {
            if (generation == 0) {
                long current = cursor(request().GET().build()).generation();
                if (current == Long.MAX_VALUE) {
                    throw new RejectedUpload("服务端会话序号已达上限");
                }
                generation = current + 1;
            }
            if (sequence == Long.MAX_VALUE) {
                throw new RejectedUpload("上报序号已达上限");
            }
            long next = sequence + 1;
            var upload = new Upload(generation, clientId, next, inFlight.entries(), inFlight.profiles());
            var reply = cursor(request().header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(JSON.toJson(upload))).build());
            if (reply.generation() != generation || reply.sequence() != next) {
                throw new IOException("封禁快照确认序号不匹配");
            }
            sequence = next;
        } catch (RejectedUpload e) {
            // The server only marks a first report for a generation won by a different client.
            if (e.generationClaimed && sequence == 0 && generation != 0 && !retriedStartupConflict) {
                retriedStartupConflict = true;
                generation = 0;
                inFlight = null;
                retry(e);
                return;
            }
            if (!closed) {
                logger.warning("封禁名单上报已停止：" + e.getMessage() + "。请检查配置或上报实例后重启插件。");
                close();
            }
            return;
        } catch (IOException e) {
            retry(e);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        if (!closed && (sequence == 1 || retrying)) {
            logger.info("封禁名单快照已获 API 确认，当前有效封禁 " + inFlight.size() + " 条。");
        }
        retrying = false;
        backoff.reset();
        synchronized (this) {
            acknowledged = inFlight;
            inFlight = null;
            scheduled = false;
            scheduleIfChanged();
        }
    }

    private HttpRequest.Builder request() {
        return HttpRequest.newBuilder(settings.url())
                .timeout(Duration.ofMillis(settings.requestTimeoutMs()))
                .header("Authorization", "Bearer " + settings.token())
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache");
    }

    private AuthProtocol.BanlistCursor cursor(HttpRequest request) throws IOException, InterruptedException {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        int status = response.statusCode();
        if (status == 408 || status == 429 || status >= 500) {
            throw new IOException("HTTP " + status);
        }
        if (status != 200) {
            boolean generationClaimed = status == 409
                    && response.headers().firstValue(CONFLICT_HEADER).filter("generation-claimed"::equals).isPresent();
            throw new RejectedUpload("HTTP " + status, generationClaimed);
        }
        if (response.body().length() > MAX_ACK_LENGTH) {
            throw new IOException("封禁快照确认超过大小上限");
        }
        return AuthProtocol.banlistCursor(response.body());
    }

    private synchronized void retry(IOException error) {
        if (closed) {
            return;
        }
        if (!retrying) {
            logger.warning("封禁名单暂未同步，将重试（" + error.getClass().getSimpleName() + "）");
            retrying = true;
        }
        worker.schedule(this::upload, backoff.nextDelayMs(), MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        closed = true;
        worker.shutdownNow();
    }

    private static final class RejectedUpload extends IOException {
        private final boolean generationClaimed;

        RejectedUpload(String message) {
            this(message, false);
        }

        RejectedUpload(String message, boolean generationClaimed) {
            super(message);
            this.generationClaimed = generationClaimed;
        }
    }
}
