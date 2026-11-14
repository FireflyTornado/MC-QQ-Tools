package cn.nightbugclub.mcqqauth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;

final class SudoChannel implements AutoCloseable {
    private static final long TTL_MS = 30_000;
    private static final long CLOCK_SKEW_MS = 1_000;
    private static final Gson JSON = new Gson();

    interface Connection {
        boolean isOpen();
        void sendSudoResult(String message);
    }

    record Settings(String token) {
        static Settings read(ConfigurationSection config, String authToken) {
            Object enabled = config.get("sudo.enabled");
            if (Boolean.FALSE.equals(enabled)) {
                return null;
            }
            if (!Boolean.TRUE.equals(enabled)) {
                throw new IllegalArgumentException("sudo.enabled 必须为布尔值");
            }
            String token = config.getString("sudo.token", "");
            if (!config.isString("sudo.token") || token.length() > 4096 || !token.matches("[A-Za-z0-9._~+/-]+=*")
                    || token.equals(authToken) || token.equals("REPLACE_WITH_MC_SUDO_PLUGIN_TOKEN")) {
                throw new IllegalArgumentException("sudo.token 必须为独立的有效凭据");
            }
            return new Settings(token);
        }
    }

    record Request(String id, String command, long deadlineMs) {
        static Request parse(JsonObject json) {
            var id = json.getAsJsonPrimitive("id");
            var command = json.getAsJsonPrimitive("command");
            var deadline = json.getAsJsonPrimitive("deadline_ms");
            if (!id.isString() || !id.getAsString().matches("[0-9a-f]{32}") || !command.isString()
                    || !deadline.isNumber() || !deadline.getAsString().matches("[0-9]{1,15}")) {
                throw new IllegalArgumentException("Invalid sudo request");
            }
            return new Request(id.getAsString(), command.getAsString(), deadline.getAsLong());
        }
    }

    // Accessed under the channel lock after validating the request deadline.
    static final class RequestHistory {
        private static final int MAX_RECENT = 1024;
        private final LinkedHashMap<String, Long> recent = new LinkedHashMap<>();
        private long blockedUntil;

        String admit(Request request, long now, boolean running) {
            recent.values().removeIf(deadline -> deadline <= now);
            if (recent.containsKey(request.id)) {
                return "rejected";
            }
            if (recent.size() >= MAX_RECENT || now < blockedUntil) {
                // Keep refusing untracked requests until every refused request has expired.
                blockedUntil = Math.max(blockedUntil, request.deadlineMs);
                return "busy";
            }
            recent.put(request.id, request.deadlineMs);
            return running ? "busy" : null;
        }
    }

    private final SudoCommands commands;
    private final Consumer<Runnable> onMain;
    private final Logger log;

    // Guard the connection and execution slot across the network and server threads.
    private boolean closed;
    private Connection active;
    private Request running;
    private boolean executing;
    private final RequestHistory history = new RequestHistory();

    SudoChannel(SudoCommands commands, Consumer<Runnable> onMain, Logger log) {
        this.commands = commands;
        this.onMain = onMain;
        this.log = log;
    }

    synchronized void attach(Connection source) {
        if (!closed) {
            active = source;
        }
    }

    synchronized void detach(Connection source) {
        if (active == source) {
            active = null;
            if (!executing) {
                running = null;
            }
        }
    }

    void receive(Connection source, JsonObject message) {
        Request request;
        try {
            request = Request.parse(message);
        } catch (RuntimeException e) {
            log.warning("/sudo 收到格式无效的命令消息");
            var id = message.get("id");
            if (id != null && id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                    && id.getAsString().matches("[0-9a-f]{32}")) {
                reply(source, id.getAsString(), new SudoCommands.Result("error"));
            }
            return;
        }
        String rejection;
        synchronized (this) {
            if (active != source || !source.isOpen()) {
                return;
            }
            long now = System.currentTimeMillis();
            // Reserve a clock-skew margin at execution time so a slow clock cannot start work late.
            if (request.deadlineMs <= now + CLOCK_SKEW_MS || request.deadlineMs - now > TTL_MS + CLOCK_SKEW_MS) {
                rejection = "expired";
            } else {
                rejection = history.admit(request, now, running != null);
                if (rejection == null) {
                    running = request;
                }
            }
        }
        if (rejection != null) {
            reply(source, request.id, new SudoCommands.Result(rejection));
            return;
        }
        try {
            onMain.accept(() -> execute(source, request));
        } catch (RuntimeException e) {
            finish(source, request, new SudoCommands.Result("error"));
        }
    }

    private void execute(Connection source, Request request) {
        boolean canRun;
        synchronized (this) {
            if (active != source || !source.isOpen() || running != request) {
                return;
            }
            canRun = System.currentTimeMillis() + CLOCK_SKEW_MS < request.deadlineMs;
            // Claim execution under the same lock used to invalidate queued work on disconnect.
            executing = canRun;
        }
        SudoCommands.Result result = new SudoCommands.Result("expired");
        try {
            if (canRun) {
                result = commands.execute(request.command);
            }
        } catch (RuntimeException e) {
            result = new SudoCommands.Result("error");
            log.warning("/sudo #" + request.id + " 执行异常（" + e.getClass().getSimpleName() + "）");
        } finally {
            finish(source, request, result);
        }
    }

    private synchronized void finish(Connection source, Request request, SudoCommands.Result result) {
        if (running == request) {
            running = null;
            executing = false;
            log.info("/sudo #" + request.id + " result=" + result.status());
            reply(source, request.id, result);
        }
    }

    private synchronized void reply(Connection source, String requestId, SudoCommands.Result result) {
        if (active != source || !source.isOpen()) {
            return;
        }
        JsonObject response = new JsonObject();
        response.addProperty("type", "sudo_result");
        response.addProperty("id", requestId);
        response.addProperty("status", result.status());
        response.addProperty("output", result.output());
        if (result.truncated()) {
            response.addProperty("truncated", true);
        }
        source.sendSudoResult(JSON.toJson(response));
    }

    @Override
    public synchronized void close() {
        closed = true;
        detach(active);
    }
}
