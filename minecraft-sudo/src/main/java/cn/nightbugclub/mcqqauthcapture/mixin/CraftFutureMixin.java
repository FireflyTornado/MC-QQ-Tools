package cn.nightbugclub.mcqqauthcapture.mixin;

import cn.nightbugclub.mcqqauthcapture.ScheduledOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.Callable;

@Mixin(targets = "org.bukkit.craftbukkit.v1_20_R1.scheduler.CraftFuture", remap = false)
abstract class CraftFutureMixin {
    @Redirect(method = "run()V", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/Callable;call()Ljava/lang/Object;"))
    private Object mcqqauth$runCallable(Callable<?> callable) throws Exception {
        return ScheduledOutput.callFuture(this, callable);
    }

    @Inject(method = {"cancel(Z)Z", "cancel0()Z"}, at = @At("RETURN"))
    private void mcqqauth$cancelFuture(CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ()) {
            ScheduledOutput.cancel(this);
        }
    }
}
