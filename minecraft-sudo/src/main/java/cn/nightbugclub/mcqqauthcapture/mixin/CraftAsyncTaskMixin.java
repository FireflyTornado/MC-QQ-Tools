package cn.nightbugclub.mcqqauthcapture.mixin;

import cn.nightbugclub.mcqqauthcapture.BukkitTaskOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.LinkedList;

@Mixin(targets = "org.bukkit.craftbukkit.v1_20_R1.scheduler.CraftAsyncTask", remap = false)
abstract class CraftAsyncTaskMixin {
    @Redirect(method = "cancel0()Z",
            at = @At(value = "INVOKE", target = "Ljava/util/LinkedList;isEmpty()Z"))
    private boolean mcqqauth$cancelPending(LinkedList<?> workers) {
        boolean pending = workers.isEmpty();
        if (pending) {
            ((BukkitTaskOutput) this).mcqqauth$cancelOutput();
        }
        return pending;
    }
}
