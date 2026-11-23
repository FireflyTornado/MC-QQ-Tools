package cn.nightbugclub.mcqqauthcapture.mixin;

import cn.nightbugclub.mcqqauthcapture.OutputCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.function.Consumer;

@Mixin(targets = "net.minecraft.commands.arguments.MessageArgument", remap = false)
abstract class MessageArgumentMixin {
    @ModifyVariable(method = {
            "m_247736_(Ljava/util/function/Consumer;Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/network/chat/PlayerChatMessage;)V",
            "chain(Ljava/util/function/Consumer;Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/network/chat/PlayerChatMessage;)V"
    }, at = @At("HEAD"), argsOnly = true, index = 0)
    private static <T> Consumer<T> mcqqauth$trackSigned(Consumer<T> callback) {
        return OutputCapture.track(callback);
    }

    @ModifyVariable(method = {
            "m_247098_(Ljava/util/function/Consumer;Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/network/chat/PlayerChatMessage;)V",
            "chainUnsigned(Ljava/util/function/Consumer;Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/network/chat/PlayerChatMessage;)V"
    }, at = @At("HEAD"), argsOnly = true, index = 0)
    private static <T> Consumer<T> mcqqauth$trackUnsigned(Consumer<T> callback) {
        return OutputCapture.track(callback);
    }
}
