package cn.nightbugclub.mcqqauthcapture;

import net.minecraftforge.fml.unsafe.UnsafeHacks;

import java.lang.reflect.Field;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantLock;

/** Read-only access to the locks used by Java 17's standard work queues. */
final class JdkQueueAccess {
    private static final ClassValue<Layout> LAYOUTS = new ClassValue<>() {
        @Override
        protected Layout computeValue(Class<?> type) {
            try {
                Class<?> owner = queueOwner(type);
                if (owner == null || type.getMethod("toArray").getDeclaringClass() != owner) {
                    return Layout.UNSUPPORTED;
                }
                if (owner == LinkedBlockingQueue.class) {
                    // fullyLock() always takes the producer lock first.
                    return new Layout(owner, lockField(owner, "putLock"), lockField(owner, "takeLock"), null);
                }
                Field iterators = owner == ArrayBlockingQueue.class ? owner.getDeclaredField("itrs") : null;
                return new Layout(owner, lockField(owner, "lock"), null, iterators);
            } catch (ReflectiveOperationException | SecurityException unavailable) {
                return Layout.UNSUPPORTED;
            }
        }
    };

    private JdkQueueAccess() {}

    static Locks locks(BlockingQueue<?> queue) {
        Layout layout = LAYOUTS.get(queue.getClass());
        if (layout == Layout.UNSUPPORTED) {
            return null;
        }
        try {
            ReentrantLock first = UnsafeHacks.getField(layout.first(), queue);
            ReentrantLock second = layout.second() == null ? null : UnsafeHacks.getField(layout.second(), queue);
            return new Locks(layout, first, second);
        } catch (LinkageError | RuntimeException unavailable) {
            return null;
        }
    }

    static boolean delayedQueue(Class<?> type) {
        return type.getEnclosingClass() == ScheduledThreadPoolExecutor.class
                && type.getSimpleName().equals("DelayedWorkQueue");
    }

    private static Class<?> queueOwner(Class<?> type) {
        if (LinkedBlockingQueue.class.isAssignableFrom(type)) {
            return LinkedBlockingQueue.class;
        }
        if (LinkedBlockingDeque.class.isAssignableFrom(type)) {
            return LinkedBlockingDeque.class;
        }
        if (type == ArrayBlockingQueue.class || type == PriorityBlockingQueue.class || delayedQueue(type)) {
            return type;
        }
        return null;
    }

    private static Field lockField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != ReentrantLock.class) {
            throw new NoSuchFieldException(name);
        }
        return field;
    }

    private record Layout(Class<?> owner, Field first, Field second, Field iterators) {
        private static final Layout UNSUPPORTED = new Layout(null, null, null, null);
    }

    record Locks(Layout layout, ReentrantLock first, ReentrantLock second) {
        void lock() {
            first.lock();
            if (second != null) {
                second.lock();
            }
        }

        void unlock() {
            if (second != null) {
                second.unlock();
            }
            first.unlock();
        }

        boolean hasArrayIterators(BlockingQueue<?> queue) {
            return UnsafeHacks.getField(layout.iterators(), queue) != null;
        }

        boolean inheritsOperation(BlockingQueue<?> queue, String method, Class<?>... parameters) {
            try {
                return queue.getClass().getMethod(method, parameters).getDeclaringClass() == layout.owner();
            } catch (NoSuchMethodException | SecurityException unavailable) {
                return false;
            }
        }

        boolean ownsIterator(Object iterator) {
            return iterator.getClass().getEnclosingClass() == layout.owner();
        }
    }
}
