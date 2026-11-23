package cn.nightbugclub.mcqqauthcapture;

import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SudoServiceTest {
    @TempDir
    Path configDirectory;

    @Test
    void createsDisabledDefaultsAndPreservesExistingRules() throws Exception {
        var service = new SudoService(configDirectory, mock(Logger.class));
        Path allowlist = configDirectory.resolve(SudoCommands.ALLOWLIST_FILE);
        try (var channels = mockConstruction(SudoChannel.class)) {
            service.start(null);
            assertTrue(Files.readString(configDirectory.resolve("config.yml")).contains("enabled: false"));
            assertEquals(Set.of(), SudoCommands.readAllowlist(allowlist));
            Files.writeString(allowlist, "list\n");
            service.start(null);
            assertEquals(Set.of("list"), SudoCommands.readAllowlist(allowlist));
            assertTrue(channels.constructed().isEmpty());
        }
    }

    @Test
    void startsIndependentlyAndOnlyTheLocalConsoleCanReloadRules() throws Exception {
        Server server = mock(Server.class, withSettings().extraInterfaces(MohistHandle.class));
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        RemoteConsoleCommandSender remote = mock(RemoteConsoleCommandSender.class);
        var service = new SudoService(configDirectory, mock(Logger.class));
        when(((MohistHandle) server).getServer()).thenReturn(Runnable::run);
        when(server.getConsoleSender()).thenReturn(console);
        when(server.isPrimaryThread()).thenReturn(true);
        when(server.dispatchCommand(remote, "list")).thenReturn(true);
        when(server.dispatchCommand(remote, "say hi")).thenReturn(true);
        when(server.dispatchCommand(remote, "mcqqsudo reload")).thenAnswer(call -> {
            assertEquals(0, service.reload(server, console));
            return true;
        });

        Files.writeString(configDirectory.resolve("config.yml"), """
                sudo:
                  enabled: true
                  token: test-sudo-token
                  websocket-url: wss://localhost:1/internal/mc/sudo/ws
                """);
        Path allowlist = configDirectory.resolve(SudoCommands.ALLOWLIST_FILE);
        Files.writeString(allowlist, "list\nmcqqsudo reload\n");
        var commands = new AtomicReference<SudoCommands>();
        try (var feedback = mockConstruction(MohistCommandFeedback.class, (capture, context) ->
                when(capture.open()).thenReturn(new MohistCommandFeedback.Capture(
                        remote, new BoundedCommandOutput()::snapshot)));
             var channels = mockConstruction(SudoChannel.class, (channel, context) ->
                     commands.set((SudoCommands) context.arguments().get(1)))) {
            service.start(server);
            SudoChannel channel = channels.constructed().get(0);
            verify(channel).start();
            assertEquals("ok", commands.get().execute("list").status());

            Files.writeString(allowlist, "say\n");
            assertFalse(service.canReload(server, mock(Player.class)));
            assertFalse(service.canReload(server, remote));
            assertEquals("ok", commands.get().execute("mcqqsudo reload").status());
            assertEquals("rejected", commands.get().execute("say hi").status());
            assertEquals(1, service.reload(server, console));
            assertEquals("ok", commands.get().execute("say hi").status());
            assertEquals("rejected", commands.get().execute("list").status());

            Files.writeString(allowlist, "say\n/invalid\n");
            assertEquals(0, service.reload(server, console));
            verify(console).sendMessage(contains("第 2 行"));
            assertEquals("rejected", commands.get().execute("say hi").status());
            Files.delete(allowlist);
            assertEquals(0, service.reload(server, console));
            verify(console).sendMessage(contains("无法读取"));
            assertFalse(Files.exists(allowlist));

            Files.writeString(allowlist, "list\n");
            assertEquals(1, service.reload(server, console));
            assertEquals("ok", commands.get().execute("list").status());
            service.stop();
            verify(channel).close();
            assertEquals(0, service.reload(server, console));
        }
    }

    public interface MohistHandle {
        Executor getServer();
    }
}
