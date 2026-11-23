package cn.nightbugclub.mcqqauthcapture.mixin;

import cn.nightbugclub.mcqqauthcapture.BukkitTaskOutput;
import cn.nightbugclub.mcqqauthcapture.CommandOutputContext;
import cn.nightbugclub.mcqqauthcapture.ScheduledOutput;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

@Mixin(targets = "org.bukkit.craftbukkit.v1_20_R1.scheduler.CraftTask", remap = false)
abstract class CraftTaskMixin implements BukkitTaskOutput {
    @Shadow @Final private Runnable rTask;
    @Shadow @Final private Consumer<BukkitTask> cTask;

    @Inject(method = "<init>(Lorg/bukkit/plugin/Plugin;Ljava/lang/Object;IJ)V", at = @At("RETURN"))
    private void mcqqauth$trackOneShot(Plugin plugin, Object action, int id, long period, CallbackInfo callback) {
        // CraftScheduler validates the action before constructing a task. Its one-shot period is -1.
        if (plugin != null && id > 0 && period == -1
                && !(action instanceof CommandOutputContext.Task)
                && !(action instanceof CommandOutputContext.Callback<?>)) {
            ScheduledOutput.enqueueTask(this);
        }
    }

    @Redirect(method = "run()V", at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"))
    private void mcqqauth$runTask(Runnable action) {
        ScheduledOutput.run(this, action);
    }

    @Redirect(method = "run()V", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private void mcqqauth$runConsumer(Consumer<Object> action, Object task) {
        ScheduledOutput.run(this, () -> action.accept(task));
    }

    @Inject(method = "cancel0()Z", at = @At("RETURN"))
    private void mcqqauth$cancelTask(CallbackInfoReturnable<Boolean> callback) {
        mcqqauth$cancelOutput();
    }

    @Unique
    @Override
    public void mcqqauth$cancelOutput() {
        ScheduledOutput.cancel(this);
        if (rTask instanceof CommandOutputContext.Task task) {
            task.close();
        }
        if (cTask instanceof CommandOutputContext.Callback<?> callback) {
            callback.close();
        }
    }
}
