package cn.nightbugclub.mcqqauthcapture;

import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

final class SudoService {
    private static final String CONFIG_FILE = "config.yml";
    private final Path directory;
    private final Logger log;
    private SudoChannel channel;
    private SudoCommands commands;
    private ConsoleOutput consoleOutput;

    SudoService(Path directory, Logger log) {
        this.directory = directory;
        this.log = log;
    }

    void start(Server server) {
        stop();
        try {
            Files.createDirectories(directory);
            createDefault(CONFIG_FILE);
            createDefault(SudoCommands.ALLOWLIST_FILE);
            var config = new YamlConfiguration();
            config.load(directory.resolve(CONFIG_FILE).toFile());
            SudoChannel.Settings settings = SudoChannel.Settings.read(config);
            if (settings == null) {
                return;
            }
            int minMs = reconnectDelay(config, "sync.reconnect-min-ms", 1000);
            int maxMs = reconnectDelay(config, "sync.reconnect-max-ms", 30000);
            if (maxMs < minMs) {
                throw new IllegalArgumentException("sync.reconnect-max-ms 必须大于等于 sync.reconnect-min-ms");
            }
            if (server == null || !(MohistCommandFeedback.minecraftServer(server) instanceof Executor executor)) {
                throw new IllegalStateException("Mohist server executor is unavailable");
            }
            var feedback = new MohistCommandFeedback(server);
            feedback.open(new CommandOutput());
            commands = new SudoCommands(server, Set.of(), feedback::open);
            try {
                int count = commands.reloadAllowlist(directory.resolve(SudoCommands.ALLOWLIST_FILE));
                log.info("/sudo 已加载 " + count + " 条允许规则。");
            } catch (IOException | SecurityException | IllegalArgumentException e) {
                log.warning(reloadFailure(e));
            }
            channel = new SudoChannel(settings, commands, executor::execute, log, minMs, maxMs);
            try {
                consoleOutput = ConsoleOutput.install();
            } catch (SecurityException denied) {
                log.warning("/sudo 无法接入 JVM 标准输出和错误流。");
            }
            channel.start();
        } catch (IOException | InvalidConfigurationException | SecurityException e) {
            stop();
            log.warning("/sudo 已关闭，请检查 " + directory + " 下的文件权限和 YAML 格式。");
        } catch (IllegalArgumentException e) {
            stop();
            log.warning("/sudo 已关闭。 " + e.getMessage());
        } catch (ReflectiveOperationException | IllegalStateException | LinkageError e) {
            stop();
            log.warning("/sudo 已关闭，Mohist 命令执行接口不可用（" + e.getClass().getSimpleName() + "）。");
        }
    }

    void stop() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (consoleOutput != null) {
            consoleOutput.close();
            consoleOutput = null;
        }
        commands = null;
    }

    boolean canReload(Server server, CommandSender sender) {
        return server != null && sender instanceof ConsoleCommandSender && sender == server.getConsoleSender()
                && (commands == null || !commands.isDispatching());
    }

    int reload(Server server, CommandSender sender) {
        if (!canReload(server, sender) || !server.isPrimaryThread()) {
            sender.sendMessage("[MCQQSudo] 此指令仅限 Minecraft 服务端控制台使用。");
            return 0;
        }
        if (commands == null) {
            sender.sendMessage("[MCQQSudo] /sudo 未启用，请检查 Mod 配置。");
            return 0;
        }
        try {
            int count = commands.reloadAllowlist(directory.resolve(SudoCommands.ALLOWLIST_FILE));
            sender.sendMessage("[MCQQSudo] 允许清单已重载，共 " + count + " 条规则。");
            return 1;
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            sender.sendMessage("[MCQQSudo] " + reloadFailure(e));
            return 0;
        }
    }

    private void createDefault(String name) throws IOException {
        Path destination = directory.resolve(name);
        if (Files.notExists(destination)) {
            try (var template = SudoService.class.getResourceAsStream("/" + name)) {
                if (template == null) {
                    throw new IOException("Missing /sudo resource " + name);
                }
                Files.copy(template, destination);
            } catch (FileAlreadyExistsException ignored) {
                // Keep a file created during startup.
            }
        }
    }

    private static int reconnectDelay(YamlConfiguration config, String key, int fallback) {
        Object value = config.get(key, fallback);
        if (!(value instanceof Integer delay) || delay < 1 || delay > 300_000) {
            throw new IllegalArgumentException(key + " 必须为 1 至 300000 之间的整数");
        }
        return delay;
    }

    private static String reloadFailure(Exception failure) {
        String detail = failure instanceof IllegalArgumentException
                ? failure.getMessage() : "无法读取 sudo-allowlist.txt，请检查文件路径、权限和 UTF-8 编码。";
        return "/sudo 已暂停。清单加载失败。 " + detail + " 修正后请在控制台执行 mcqqsudo reload。";
    }
}
