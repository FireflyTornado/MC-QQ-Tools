package cn.nightbugclub.mcqqauthcapture.mixin;

import cn.nightbugclub.mcqqauthcapture.ScheduledOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.Queue;
import java.util.function.Consumer;

@Mixin(targets = "net.minecraft.world.level.timers.TimerQueue", remap = false)
abstract class TimerQueueMixin {
    @Unique
    private Object mcqqauth$event;

    @Redirect(method = {
            "m_82261_(Ljava/lang/String;JLnet/minecraft/world/level/timers/TimerCallback;)V",
            "schedule(Ljava/lang/String;JLnet/minecraft/world/level/timers/TimerCallback;)V"
    }, at = @At(value = "INVOKE", target = "Ljava/util/Queue;add(Ljava/lang/Object;)Z"))
    private boolean mcqqauth$enqueue(Queue<Object> queue, Object event) {
        boolean inserted = queue.add(event);
        if (inserted) {
            ScheduledOutput.enqueue(event);
        }
        return inserted;
    }

    @Redirect(method = {"m_82259_(Ljava/lang/String;)I", "remove(Ljava/lang/String;)I"},
            at = @At(value = "INVOKE", target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V"))
    private void mcqqauth$cancel(Collection<Object> events, Consumer<Object> remove) {
        events.forEach(event -> {
            remove.accept(event);
            ScheduledOutput.cancel(event);
        });
    }

    @Redirect(method = {"m_82256_(Ljava/lang/Object;J)V", "tick(Ljava/lang/Object;J)V"},
            at = @At(value = "INVOKE", target = "Ljava/util/Queue;remove()Ljava/lang/Object;"))
    private Object mcqqauth$next(Queue<?> queue) {
        return mcqqauth$event = queue.remove();
    }

    @Group(name = "callback", min = 1, max = 1)
    @Redirect(method = {"m_82256_(Ljava/lang/Object;J)V", "tick(Ljava/lang/Object;J)V"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/timers/TimerCallback;m_5821_(Ljava/lang/Object;Lnet/minecraft/world/level/timers/TimerQueue;J)V"))
    private void mcqqauth$handleSrg(@Coerce Object callback, Object server, @Coerce Object queue, long tick) {
        mcqqauth$run(callback, server, queue, tick, "m_5821_");
    }

    @Group(name = "callback", min = 1, max = 1)
    @Redirect(method = {"m_82256_(Ljava/lang/Object;J)V", "tick(Ljava/lang/Object;J)V"}, require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/timers/TimerCallback;handle(Ljava/lang/Object;Lnet/minecraft/world/level/timers/TimerQueue;J)V"))
    private void mcqqauth$handleNamed(@Coerce Object callback, Object server, @Coerce Object queue, long tick) {
        mcqqauth$run(callback, server, queue, tick, "handle");
    }

    @Unique
    private void mcqqauth$run(Object callback, Object server, Object queue, long tick, String methodName) {
        Object event = mcqqauth$event;
        mcqqauth$event = null;
        ScheduledOutput.run(event, () -> {
            try {
                var loader = queue.getClass().getClassLoader();
                var callbackType = loader.loadClass("net.minecraft.world.level.timers.TimerCallback");
                var queueType = loader.loadClass("net.minecraft.world.level.timers.TimerQueue");
                callbackType.getMethod(methodName, Object.class, queueType, long.class)
                        .invoke(callback, server, queue, tick);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (e.getCause() instanceof Error error) {
                    throw error;
                }
                throw new IllegalStateException(e.getCause());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot invoke scheduled Minecraft callback", e);
            }
        });
    }
}
