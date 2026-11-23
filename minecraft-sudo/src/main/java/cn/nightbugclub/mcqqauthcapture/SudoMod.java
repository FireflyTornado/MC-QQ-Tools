package cn.nightbugclub.mcqqauthcapture;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.logging.Logger;

@Mod(SudoMod.MOD_ID)
public final class SudoMod {
    static final String MOD_ID = "mcqqsudo";
    private static final Logger LOG = Logger.getLogger("MCQQSudo");
    private final SudoService service = new SudoService(FMLPaths.CONFIGDIR.get().resolve("mcqqauth-sudo"), LOG);

    public SudoMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        service.start(Bukkit.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        service.stop();
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            // Forge's signature references the runtime-only Minecraft CommandSourceStack type.
            var dispatcher = (CommandDispatcher<?>) RegisterCommandsEvent.class.getMethod("getDispatcher").invoke(event);
            registerReload(dispatcher);
        } catch (ReflectiveOperationException | ClassCastException e) {
            LOG.warning("无法注册 mcqqsudo reload（" + e.getClass().getSimpleName() + "）。");
        }
    }

    @SuppressWarnings("unchecked")
    private void registerReload(CommandDispatcher<?> dispatcher) {
        ((CommandDispatcher<Object>) dispatcher).register(LiteralArgumentBuilder.<Object>literal(MOD_ID)
                .requires(source -> service.canReload(Bukkit.getServer(), sender(source)))
                .then(LiteralArgumentBuilder.<Object>literal("reload").executes(context -> {
                    CommandSender sender = sender(context.getSource());
                    return sender == null ? 0 : service.reload(Bukkit.getServer(), sender);
                })));
    }

    private static CommandSender sender(Object source) {
        try {
            return (CommandSender) source.getClass().getMethod("getBukkitSender").invoke(source);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }
}
