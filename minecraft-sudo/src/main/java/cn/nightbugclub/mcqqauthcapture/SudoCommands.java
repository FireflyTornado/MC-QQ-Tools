package cn.nightbugclub.mcqqauthcapture;

import org.bukkit.Server;
import org.bukkit.command.CommandException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;

final class SudoCommands {
    static final String ALLOWLIST_FILE = "sudo-allowlist.txt";
    private static final Pattern COMMAND_LABEL = Pattern.compile("[A-Za-z0-9_.-]+(?::[A-Za-z0-9_.-]+)?");

    record Result(String status, String output, boolean truncated) {
        Result(String status, String output) {
            this(status, output, false);
        }

        Result(String status) {
            this(status, "");
        }
    }

    private final Server server;
    private final Function<CommandOutput, MohistCommandFeedback.Capture> openFeedback;
    // Reload and dispatch are serialized on the Minecraft main thread.
    private Set<String> allowed;
    private boolean dispatching;

    SudoCommands(Server server, Set<String> allowed) {
        this(server, allowed, new MohistCommandFeedback(server)::open);
    }

    SudoCommands(Server server, Set<String> allowed, Function<CommandOutput, MohistCommandFeedback.Capture> openFeedback) {
        this.server = server;
        this.allowed = Set.copyOf(allowed);
        this.openFeedback = openFeedback;
    }

    int reloadAllowlist(Path file) throws IOException {
        try {
            allowed = readAllowlist(file);
        } catch (IOException | IllegalArgumentException | SecurityException e) {
            allowed = Set.of();
            throw e;
        }
        return allowed.size();
    }

    boolean isDispatching() {
        return dispatching;
    }

    static Set<String> readAllowlist(Path file) throws IOException {
        var prefixes = new HashSet<String>();
        try (var reader = Files.newBufferedReader(file)) {
            int lineNumber = 0;
            for (String line; (line = reader.readLine()) != null; ) {
                if (++lineNumber == 1 && line.startsWith("\uFEFF")) {
                    line = line.substring(1);
                }
                String prefix = line.strip();
                if (prefix.isEmpty() || prefix.startsWith("#")) {
                    continue;
                }
                if (!validCommand(prefix) || !COMMAND_LABEL.matcher(label(prefix)).matches()) {
                    throw new IllegalArgumentException(ALLOWLIST_FILE + " 第 " + lineNumber + " 行的命令前缀无效");
                }
                prefixes.add(prefix);
            }
        }
        return Set.copyOf(prefixes);
    }

    Result execute(String command) {
        return execute(command, System.currentTimeMillis()).join();
    }

    CompletableFuture<Result> execute(String command, long captureDeadlineMs) {
        return execute(command, captureDeadlineMs, null);
    }

    CompletableFuture<Result> execute(String command, long captureDeadlineMs, ScheduledOutput scheduled) {
        if (!validCommand(command) || allowed.stream().noneMatch(
                prefix -> command.equals(prefix) || command.startsWith(prefix + " "))) {
            return CompletableFuture.completedFuture(new Result("rejected"));
        }

        String target = label(command);
        // Configured Bukkit aliases can expand to multiple commands.
        if (server.getCommandAliases().keySet().stream().anyMatch(target::equalsIgnoreCase)) {
            return CompletableFuture.completedFuture(new Result("rejected"));
        }
        CommandOutput output = scheduled == null ? new CommandOutput() : scheduled.output();
        MohistCommandFeedback.Capture feedback;
        try {
            feedback = openFeedback.apply(output);
        } catch (IllegalStateException e) {
            return CompletableFuture.completedFuture(new Result("error", "服务端命令反馈接口不可用，指令未执行。"));
        }
        MohistCommandFeedback.Session session = feedback.beginExtendedCapture().apply(scheduled);
        CommandException dispatchFailure = null;
        boolean dispatched = false;
        CompletableFuture<Boolean> complete;
        dispatching = true;
        try {
            var sender = feedback.sender();
            if (scheduled != null) {
                scheduled.markDispatchStarted();
            }
            dispatched = server.dispatchCommand(sender, command);
        } catch (RuntimeException | Error failure) {
            if (scheduled != null) {
                scheduled.markDispatchFailure();
            }
            if (failure instanceof CommandException commandFailure) {
                dispatchFailure = commandFailure;
            } else {
                throw failure;
            }
        } finally {
            dispatching = false;
            complete = session.finish();
        }
        boolean found = dispatched;
        CommandException failure = dispatchFailure;
        return complete.completeOnTimeout(false, Math.max(0, captureDeadlineMs - System.currentTimeMillis()),
                        TimeUnit.MILLISECONDS)
                .thenApply(captured -> {
                    var initial = output.finishInitial();
                    if (scheduled == null) {
                        output.close();
                    }
                    return commandResult(initial, found, failure, captured);
                });
    }

    private static Result commandResult(BoundedCommandOutput.Snapshot output, boolean dispatched, CommandException failure,
                                        boolean captured) {
        boolean empty = output.text().isEmpty() && !output.truncated();
        if (!dispatched && empty && failure == null && captured) {
            return new Result("error", "服务端未找到该命令。");
        }
        if (failure != null && empty && captured) {
            throw failure;
        }
        if (failure != null) {
            return new Result("error", output.text(), output.truncated());
        }
        return new Result(!captured ? "partial" : dispatched ? "ok" : "error", output.text(), output.truncated());
    }

    private static String label(String command) {
        int space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }

    private static boolean validCommand(String command) {
        return !command.isEmpty() && command.codePointCount(0, command.length()) <= 256
                && !command.startsWith(" ") && !command.endsWith(" ")
                && command.codePoints().noneMatch(SudoCommands::invalidCharacter);
    }

    private static boolean invalidCharacter(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE,
                 Character.UNASSIGNED, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
            default -> false;
        };
    }
}
