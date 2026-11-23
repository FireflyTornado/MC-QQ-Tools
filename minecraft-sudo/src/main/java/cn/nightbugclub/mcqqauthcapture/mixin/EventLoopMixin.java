package cn.nightbugclub.mcqqauthcapture.mixin;

import cn.nightbugclub.mcqqauthcapture.OutputCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "net.minecraft.util.thread.BlockableEventLoop", remap = false)
abstract class EventLoopMixin {
    @Redirect(method = {"m_6367_(Ljava/lang/Runnable;)V", "doRunTask(Ljava/lang/Runnable;)V"},
            at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"))
    private void mcqqauth$isolateQueuedTask(Runnable task) {
        OutputCapture.runQueuedTask(task);
    }
}
