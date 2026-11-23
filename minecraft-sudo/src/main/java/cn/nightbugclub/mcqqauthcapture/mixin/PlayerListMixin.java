package cn.nightbugclub.mcqqauthcapture.mixin;

import cn.nightbugclub.mcqqauthcapture.OutputCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.players.PlayerList", remap = false)
abstract class PlayerListMixin {
    @Inject(method = {
            "m_243063_(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/network/chat/ChatType$Bound;)V",
            "broadcastChatMessage(Lnet/minecraft/network/chat/PlayerChatMessage;Lnet/minecraft/commands/CommandSourceStack;Lnet/minecraft/network/chat/ChatType$Bound;)V"
    }, at = @At("HEAD"))
    private void mcqqauth$captureBroadcast(@Coerce Object message, @Coerce Object source,
                                          @Coerce Object chatType, CallbackInfo callback) {
        OutputCapture.broadcast(message, chatType);
    }
}
