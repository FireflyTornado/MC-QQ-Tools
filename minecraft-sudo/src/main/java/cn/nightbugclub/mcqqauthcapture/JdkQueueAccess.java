package cn.nightbugclub.mcqqauthcapture;

import net.minecraftforge.fml.unsafe.UnsafeHacks;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.invoke.MethodType.methodType;

/** Read-only access to the locks used by Java 17's standard work queues. */
final class JdkQueueAccess {
    private static final MethodHandles.Lookup JDK_LOOKUP = jdkLookup();
    private static final MethodHandle TRY_TERMINATE = tryTerminateHandle();
    private static final ClassValue<Layout> LAYOUTS = new ClassValue<>() {
        @Override
        protected Layout computeValue(Class<?> type) {
            try {
                Class<?> owner = queueOwner(type);
                if (owner == null) {
                    return Layout.UNSUPPORTED;
                }
                MethodHandle snapshot = type.getMethod("toArray").getDeclaringClass() == owner ? null
                        : lookup().in(owner).findSpecial(owner, "toArray", methodType(Object[].class), owner);
                if (owner == LinkedBlockingQueue.class) {
                    // fullyLock() always takes the producer lock first.
                    return new Layout(lockField(owner, "putLock"), lockField(owner, "takeLock"), null, snapshot);
                }
                Field iterators = owner == ArrayBlockingQueue.class ? owner.getDeclaredField("itrs") : null;
                return new Layout(lockField(owner, "lock"), null, iterators, snapshot);
            } catch (ReflectiveOperationException | SecurityException unavailable) {
                return Layout.UNSUPPORTED;
            }
        }
    };

    private JdkQueueAccess() {}

    static MethodHandles.Lookup lookup() throws IllegalAccessException {
        if (JDK_LOOKUP == null) {
            throw new IllegalAccessException("JDK queue internals are unavailable");
        }
        return JDK_LOOKUP;
    }

    private static MethodHandles.Lookup jdkLookup() {
        try {
            // Resolve only the existing queue operations below. No JDK fields or modules are changed.
            Field singleton = Unsafe.class.getDeclaredField("theUnsafe");
            if (singleton.trySetAccessible()) {
                Unsafe access = (Unsafe) singleton.get(null);
                Field lookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
                return (MethodHandles.Lookup) access.getObject(access.staticFieldBase(lookup),
                        access.staticFieldOffset(lookup));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            // Use the public queue operation if this JDK layout is unavailable.
        }
        return null;
    }

    private static MethodHandle tryTerminateHandle() {
        try {
            return lookup().findVirtual(ThreadPoolExecutor.class, "tryTerminate", methodType(void.class));
        } catch (ReflectiveOperationException unavailable) {
            return null;
        }
    }

    static boolean canTryTerminate() {
        return TRY_TERMINATE != null;
    }

    static void tryTerminate(ThreadPoolExecutor pool) {
        try {
            TRY_TERMINATE.invokeExact(pool);
        } catch (Throwable failure) {
            throw unchecked(failure);
        }
    }

    static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError(failure);
    }

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

    private record Layout(Field first, Field second, Field iterators, MethodHandle snapshot) {
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

        Object[] snapshot(BlockingQueue<?> queue) {
            if (layout.snapshot() == null) {
                return queue.toArray();
            }
            try {
                // A subclass's toArray() can reorder/filter entries or run user code.
                return (Object[]) layout.snapshot().invoke(queue);
            } catch (Throwable failure) {
                throw unchecked(failure);
            }
        }
    }
}
