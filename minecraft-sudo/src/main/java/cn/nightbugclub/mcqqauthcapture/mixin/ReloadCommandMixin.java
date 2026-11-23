package cn.nightbugclub.mcqqauthcapture.mixin;

import cn.nightbugclub.mcqqauthcapture.OutputCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@Mixin(targets = "net.minecraft.server.commands.ReloadCommand", remap = false)
abstract class ReloadCommandMixin {
    @Redirect(method = {
            "m_138235_(Ljava/util/Collection;Lnet/minecraft/commands/CommandSourceStack;)V",
            "reloadPacks(Ljava/util/Collection;Lnet/minecraft/commands/CommandSourceStack;)V"
    }, at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/CompletableFuture;exceptionally(Ljava/util/function/Function;)Ljava/util/concurrent/CompletableFuture;"))
    private static <T> CompletableFuture<T> mcqqauth$trackReload(CompletableFuture<T> reload,
                                                                Function<Throwable, ? extends T> recovery) {
        return OutputCapture.trackReload(reload, recovery);
    }
}
