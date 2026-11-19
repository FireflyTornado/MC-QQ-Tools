package cn.nightbugclub.mcqqauth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BanListReporterTest {
    private static final String TOKEN = "test-only-banlist-token";

    @Test
    void sendsBaselineAndRetriesIdenticalReportBeforePublishingLatestChange() throws Exception {
        Logger log = mock(Logger.class);
        try (LocalApi api = new LocalApi(); var reporter = new BanListReporter(api.settings(), log)) {
            var steve = ban("Steve", null);
            var alex = ban("Alex", Instant.ofEpochMilli(5_000));
            reporter.submit(Set.of(steve, alex, ban("STEVE", Instant.ofEpochMilli(6_000))));
            Exchange generation = api.next();
            assertEquals("GET", generation.method());
            generation.reply(200, "{\"generation\":5}");

            Exchange first = api.next();
            assertEquals("PUT", first.method());
            JsonObject initial = first.json();
            assertEquals(6, initial.get("generation").getAsLong());
            assertEquals(1, initial.get("sequence").getAsLong());
            assertTrue(initial.get("client_id").getAsString().matches("[0-9a-f]{32}"));
            assertTrue(initial.getAsJsonObject("entries").get("steve").isJsonNull());
            assertEquals(5_000L, initial.getAsJsonObject("entries").get("alex").getAsLong());

            reporter.submit(Set.of(alex));
            reporter.submit(Set.of());
            first.reply(503, "{}");
            Exchange retry = api.next();
            assertEquals(first.body(), retry.body());
            retry.reply(200, "{\"generation\":6,\"sequence\":2}");
            Exchange repeat = api.next();
            assertEquals(first.body(), repeat.body());
            repeat.reply(200, "{\"generation\":6,\"sequence\":1}");

            Exchange changed = api.next();
            assertEquals(2, changed.json().get("sequence").getAsLong());
            assertEquals(initial.get("client_id"), changed.json().get("client_id"));
            assertTrue(changed.json().getAsJsonObject("entries").isEmpty());
            changed.reply(200, "{\"generation\":6,\"sequence\":2}");
            reporter.submit(Set.of());
            assertNull(api.requests.poll(300, MILLISECONDS));
        }
    }

    @Test
    void rejectedWriterDoesNotClaimANewerSessionOnItsOwn() throws Exception {
        Logger log = mock(Logger.class);
        try (LocalApi api = new LocalApi(); var reporter = new BanListReporter(api.settings(), log)) {
            reporter.submit(Set.of());
            api.next().reply(200, "{\"generation\":0}");
            api.next().reply(409, "{}");
            verify(log, timeout(2000)).warning(contains("HTTP 409"));
            reporter.submit(Set.of(ban("Steve", null)));
            assertNull(api.requests.poll(300, MILLISECONDS));
        }
    }

    @Test
    void acceptsOnlyAnIndependentTokenAndHttpsUploadAddress() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("banlist-watch.upload-url", "");
        config.set("banlist-watch.token", "");
        assertNull(BanListReporter.Settings.read(config));
        config.set("banlist-watch.upload-url", "http://localhost/internal/mc/banlist");
        config.set("banlist-watch.token", TOKEN);
        assertThrows(IllegalArgumentException.class, () -> BanListReporter.Settings.read(config));
        config.set("banlist-watch.upload-url", "https://localhost/internal/mc/banlist");
        config.set("api.token", TOKEN);
        assertThrows(IllegalArgumentException.class, () -> BanListReporter.Settings.read(config));
        config.set("api.token", "independent-auth-token");
        config.set("api.connect-timeout-ms", 5000);
        config.set("api.request-timeout-ms", 8000);
        config.set("sync.reconnect-min-ms", 1000);
        config.set("sync.reconnect-max-ms", 30000);
        assertEquals(TOKEN, BanListReporter.Settings.read(config).token());
    }

    private static BanListMonitor.PlayerBan ban(String player, Instant expiration) {
        return new BanListMonitor.PlayerBan(UUID.randomUUID(), player, expiration);
    }

    private record Reply(int status, String body) {}

    private record Exchange(String method, String token, String body, CompletableFuture<Reply> response) {
        void reply(int status, String body) {
            response.complete(new Reply(status, body));
        }

        JsonObject json() {
            return JsonParser.parseString(body).getAsJsonObject();
        }
    }

    private static final class LocalApi implements AutoCloseable {
        private final BlockingQueue<Exchange> requests = new LinkedBlockingQueue<>();
        private final HttpServer server;

        LocalApi() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/mc/banlist", this::handle);
            server.start();
        }

        BanListReporter.Settings settings() {
            var url = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/internal/mc/banlist");
            return new BanListReporter.Settings(url, TOKEN, 2000, 4000, 100, 200);
        }

        Exchange next() throws InterruptedException {
            Exchange exchange = requests.poll(5, SECONDS);
            assertNotNull(exchange, "Expected an upload request");
            assertEquals("Bearer " + TOKEN, exchange.token());
            return exchange;
        }

        private void handle(HttpExchange request) throws IOException {
            try {
                var exchange = new Exchange(request.getRequestMethod(), request.getRequestHeaders().getFirst("Authorization"),
                        new String(request.getRequestBody().readAllBytes(), UTF_8), new CompletableFuture<>());
                requests.add(exchange);
                Reply reply = exchange.response().orTimeout(5, SECONDS).join();
                byte[] body = reply.body().getBytes(UTF_8);
                request.sendResponseHeaders(reply.status(), body.length);
                request.getResponseBody().write(body);
            } finally {
                request.close();
            }
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
