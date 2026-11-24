package cn.nightbugclub.mcqqauthcapture;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Links transformed call sites to their original JDK method with optional callback capture. */
public final class StageOutput {
    private StageOutput() {}

    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type,
                                     MethodHandle original) throws ReflectiveOperationException {
        var lookup = MethodHandles.lookup();
        int callbackIndex = CompletionStage.class.isAssignableFrom(type.parameterType(0)) ? 1 : 0;
        var invoke = lookup.findStatic(StageOutput.class, "invoke",
                MethodType.methodType(CompletionStage.class, MethodHandle.class, int.class, Object[].class));
        var capturing = MethodHandles.insertArguments(invoke, 0, original, callbackIndex)
                .asCollector(Object[].class, type.parameterCount()).asType(type);
        var active = lookup.findStatic(StageOutput.class, "hasRequest", MethodType.methodType(boolean.class));
        return new ConstantCallSite(MethodHandles.guardWithTest(active, capturing, original.asType(type)));
    }

    private static boolean hasRequest() {
        return OutputCapture.scheduledOutput() != null;
    }

    private static CompletionStage<?> invoke(MethodHandle original, int callbackIndex, Object[] arguments)
            throws Throwable {
        Object callback = arguments[callbackIndex];
        if (callback == null || callback instanceof CommandOutputContext.Task
                || callback instanceof CommandOutputContext.Callback<?>
                || (callbackIndex == 1 && !jdkStage(arguments[0]))) {
            return (CompletionStage<?>) original.invokeWithArguments(arguments);
        }

        return CommandOutputContext.registerStage((event, settled) -> {
            arguments[callbackIndex] = wrap(original.type().parameterType(callbackIndex), callback, event, settled);
            if (arguments.length == callbackIndex + 2) {
                arguments[callbackIndex + 1] = ExecutorOutput.observeExecutor(event, (Executor) arguments[callbackIndex + 1]);
            }
            return (CompletionStage<?>) original.invokeWithArguments(arguments);
        });
    }

    private static boolean jdkStage(Object receiver) {
        // Includes the implementation returned by minimalCompletionStage().
        return receiver instanceof CompletableFuture<?>
                && receiver.getClass().getNestHost() == CompletableFuture.class;
    }

    @SuppressWarnings("unchecked")
    private static Object wrap(Class<?> type, Object callback, Object event, CompletableFuture<Throwable> settled) {
        if (type == Runnable.class) {
            return (Runnable) () -> ScheduledOutput.callStage(event, () -> {
                ((Runnable) callback).run();
                return null;
            }, settled);
        }
        if (type == Supplier.class) {
            return (Supplier<Object>) () -> ScheduledOutput.callStage(event, (Supplier<Object>) callback, settled);
        }
        if (type == Consumer.class) {
            return (Consumer<Object>) value -> ScheduledOutput.callStage(event, () -> {
                ((Consumer<Object>) callback).accept(value);
                return null;
            }, settled);
        }
        if (type == Function.class) {
            return (Function<Object, Object>) value -> ScheduledOutput.callStage(
                    event, () -> ((Function<Object, Object>) callback).apply(value), settled);
        }
        if (type == BiConsumer.class) {
            return (BiConsumer<Object, Object>) (value, failure) -> ScheduledOutput.callStage(event, () -> {
                ((BiConsumer<Object, Object>) callback).accept(value, failure);
                return null;
            }, settled);
        }
        if (type == BiFunction.class) {
            return (BiFunction<Object, Object, Object>) (value, failure) -> ScheduledOutput.callStage(
                    event, () -> ((BiFunction<Object, Object, Object>) callback).apply(value, failure), settled);
        }
        throw new IllegalArgumentException("Unsupported completion callback type: " + type);
    }
}
