package cn.nightbugclub.mcqqauthcapture;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.function.Predicate;

import static java.lang.invoke.MethodType.methodType;

/** Follows Java 17's linked removal traversal and observes each successful native unlink. */
final class LinkedQueueRemoval {
    private static final int BATCH_SIZE = 64;
    private static final BlockingLayout QUEUE = BlockingLayout.load(LinkedBlockingQueue.class);
    private static final BlockingLayout DEQUE = BlockingLayout.load(LinkedBlockingDeque.class);
    private static final TransferLayout TRANSFER = TransferLayout.load();

    private LinkedQueueRemoval() {}

    static <T> boolean removeIf(BlockingQueue<T> queue, Predicate<? super T> filter) {
        Class<?> type = queue.getClass();
        if (type == LinkedTransferQueue.class) {
            return TRANSFER == null ? queue.removeIf(filter) : removeTransfer(queue, filter);
        }
        BlockingLayout layout = type == LinkedBlockingQueue.class ? QUEUE : DEQUE;
        var locks = JdkQueueAccess.locks(queue);
        return layout == null || locks == null ? queue.removeIf(filter) : removeBatches(queue, filter, layout, locks);
    }

    private static <T> boolean removeBatches(BlockingQueue<T> queue, Predicate<? super T> filter,
                                             BlockingLayout layout, JdkQueueAccess.Locks locks) {
        Object next;
        Object ancestor;
        Object[] batch;
        boolean changed = false;
        locks.lock();
        try {
            ancestor = layout.head().get(queue);
            next = layout.findPred() == null || ancestor == null ? ancestor : layout.next().get(ancestor);
            batch = new Object[Math.min(BATCH_SIZE, queue.size())];
            if (batch.length == 0) {
                return false;
            }
            int count = 0;
            // Read the first batch under the same lock as the initial cursor and size.
            for (; next != null && count < batch.length; next = layout.successor(queue, next)) {
                batch[count++] = next;
            }
        } finally {
            locks.unlock();
        }

        while (true) {
            long selected = 0;
            for (int i = 0; i < batch.length && batch[i] != null; i++) {
                T task = item(layout.item(), batch[i]);
                if (task != null && filter.test(task)) {
                    selected |= 1L << i;
                }
            }
            if (selected != 0) {
                Set<Object> removed = Collections.newSetFromMap(new IdentityHashMap<>());
                locks.lock();
                try {
                    while (selected != 0) {
                        int i = Long.numberOfTrailingZeros(selected);
                        selected &= selected - 1;
                        Object node = batch[i];
                        Object task = layout.item().get(node);
                        if (task != null) {
                            ancestor = layout.unlink(queue, node, ancestor);
                            removed.add(task);
                            changed = true;
                        }
                    }
                    ExecutorOutput.retainDiscarded(queue, removed, () -> locks.snapshot(queue));
                } finally {
                    locks.unlock();
                    ScheduledOutput.discardQueuedWork(null, queue, removed);
                }
            }
            if (next == null) {
                return changed;
            }
            locks.lock();
            try {
                for (int i = 0; i < batch.length; i++) {
                    batch[i] = next;
                    if (next != null) {
                        next = layout.successor(queue, next);
                    }
                }
            } finally {
                locks.unlock();
            }
        }
    }

    private static <T> boolean removeTransfer(BlockingQueue<T> queue, Predicate<? super T> filter) {
        Set<Object> removed = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean changed = false;
        Object predecessor = null;
        try {
            for (Object node = TRANSFER.head().getVolatile(queue); node != null;) {
                Object next = TRANSFER.next().getVolatile(node);
                boolean data = (boolean) TRANSFER.data().get(node);
                T task = volatileItem(TRANSFER.item(), node);
                if (!data && task == null) {
                    break;
                }
                if (data && task != null && filter.test(task) && TRANSFER.match(node, task)) {
                    removed.add(task);
                    changed = true;
                    TRANSFER.unlink(queue, predecessor, node);
                }
                if (next == node) {
                    predecessor = null;
                    node = TRANSFER.head().getVolatile(queue);
                } else {
                    predecessor = node;
                    node = next;
                }
            }
            return changed;
        } finally {
            ExecutorOutput.retainDiscarded(queue, removed, queue::toArray);
            ScheduledOutput.discardQueuedWork(null, queue, removed);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T item(VarHandle field, Object node) {
        return (T) field.get(node);
    }

    @SuppressWarnings("unchecked")
    private static <T> T volatileItem(VarHandle field, Object node) {
        return (T) field.getVolatile(node);
    }

    private record BlockingLayout(VarHandle head, VarHandle next, VarHandle item, MethodHandle successor,
                                  MethodHandle findPred, MethodHandle unlink) {
        static BlockingLayout load(Class<?> owner) {
            try {
                var lookup = JdkQueueAccess.lookup();
                boolean queue = owner == LinkedBlockingQueue.class;
                FieldLayout fields = FieldLayout.load(owner, queue ? "head" : "first");
                Class<?> node = fields.head().varType();
                var successor = lookup.findVirtual(owner, "succ", methodType(node, node));
                var findPred = queue ? lookup.findVirtual(owner, "findPred", methodType(node, node, node)) : null;
                var unlink = lookup.findVirtual(owner, "unlink", queue ? methodType(void.class, node, node)
                        : methodType(void.class, node));
                return new BlockingLayout(fields.head(), fields.next(), fields.item(), successor, findPred, unlink);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return null;
            }
        }

        Object successor(BlockingQueue<?> queue, Object node) {
            try {
                return successor.invoke(queue, node);
            } catch (Throwable failure) {
                throw JdkQueueAccess.unchecked(failure);
            }
        }

        Object unlink(BlockingQueue<?> queue, Object node, Object ancestor) {
            try {
                if (findPred != null) {
                    ancestor = findPred.invoke(queue, node, ancestor);
                    unlink.invoke(queue, node, ancestor);
                } else {
                    unlink.invoke(queue, node);
                }
                return ancestor;
            } catch (Throwable failure) {
                throw JdkQueueAccess.unchecked(failure);
            }
        }
    }

    private record TransferLayout(VarHandle head, VarHandle next, VarHandle item, VarHandle data,
                                  MethodHandle match, MethodHandle unlink) {
        static TransferLayout load() {
            try {
                var lookup = JdkQueueAccess.lookup();
                Class<?> owner = LinkedTransferQueue.class;
                FieldLayout fields = FieldLayout.load(owner, "head");
                Class<?> node = fields.head().varType();
                var data = lookup.findVarHandle(node, "isData", boolean.class);
                var match = lookup.findVirtual(node, "tryMatch", methodType(boolean.class, Object.class, Object.class));
                var unlink = lookup.findVirtual(owner, "unsplice", methodType(void.class, node, node));
                return new TransferLayout(fields.head(), fields.next(), fields.item(), data, match, unlink);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return null;
            }
        }

        boolean match(Object node, Object task) {
            try {
                return (boolean) match.invoke(node, task, null);
            } catch (Throwable failure) {
                throw JdkQueueAccess.unchecked(failure);
            }
        }

        void unlink(BlockingQueue<?> queue, Object predecessor, Object node) {
            try {
                unlink.invoke(queue, predecessor, node);
            } catch (Throwable failure) {
                throw JdkQueueAccess.unchecked(failure);
            }
        }
    }

    private record FieldLayout(VarHandle head, VarHandle next, VarHandle item) {
        static FieldLayout load(Class<?> owner, String headName) throws ReflectiveOperationException {
            var lookup = JdkQueueAccess.lookup();
            Class<?> node = owner.getDeclaredField(headName).getType();
            return new FieldLayout(lookup.findVarHandle(owner, headName, node), lookup.findVarHandle(node, "next", node),
                    lookup.findVarHandle(node, "item", Object.class));
        }
    }
}
