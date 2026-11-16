package cn.nightbugclub.mcqqauthcapture.mixin;

import cn.nightbugclub.mcqqauthcapture.OutputCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.network.ServerGamePacketListenerImpl", remap = false)
abstract class PlayerConnectionMixin {
    @Inject(method = {
            "m_243119_(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V",
            "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V"
    }, at = @At("HEAD"))
    private void mcqqauth$capturePacket(@Coerce Object packet, @Coerce Object listener, CallbackInfo callback) {
        OutputCapture.packet(this, packet);
    }
}
