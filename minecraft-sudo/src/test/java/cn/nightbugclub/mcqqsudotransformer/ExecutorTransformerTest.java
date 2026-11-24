package cn.nightbugclub.mcqqsudotransformer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorTransformerTest {
    private static final String BRIDGE = "cn/nightbugclub/mcqqauthcapture/ExecutorOutput";

    @Test
    void rewritesMatchedCallsAndLeavesPeriodicTasksAlone() throws IOException {
        var reader = new ClassReader(classBytes(Submitter.class));
        assertTrue(ExecutorTransformer.hasMatch(reader));
        var node = new ClassNode();
        reader.accept(node, 0);
        new ExecutorTransformer(Set.of(Submitter.class.getName())).transform(node, null);
        var calls = node.methods.stream().flatMap(method -> Arrays.stream(method.instructions.toArray()))
                .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast).toList();
        assertEquals(3, calls.stream().filter(call -> call.owner.equals(BRIDGE)
                && call.getOpcode() == Opcodes.INVOKESTATIC).count());
        assertTrue(calls.stream().anyMatch(call -> call.name.equals("scheduleAtFixedRate")
                && call.getOpcode() == Opcodes.INVOKEINTERFACE));
    }

    @Test
    void discoversClassesInForgeModsAndSkipsTheBootstrapJar(@TempDir Path mods) throws IOException {
        String name = Submitter.class.getName().replace('.', '/') + ".class";
        String stageName = CallbackRegistrar.class.getName().replace('.', '/') + ".class";
        jar(mods.resolve("mcqqsudo.jar"), name, classBytes(Submitter.class), false);
        jar(mods.resolve("ordinary-library.jar"), stageName, classBytes(CallbackRegistrar.class), false);
        assertTrue(ExecutorTransformer.scan(mods).isEmpty());
        jar(mods.resolve("fixture.jar"), name, classBytes(Submitter.class), true);
        jar(mods.resolve("stage-fixture.jar"), stageName, classBytes(CallbackRegistrar.class), true);
        assertEquals(Set.of(Submitter.class.getName(), CallbackRegistrar.class.getName()), ExecutorTransformer.scan(mods));
    }

    @Test
    void linksStaticConcreteAndInterfaceCallsWithTheirExactDescriptors() throws IOException {
        var reader = new ClassReader(classBytes(CallbackRegistrar.class));
        assertTrue(ExecutorTransformer.hasMatch(reader));
        var node = new ClassNode();
        reader.accept(node, 0);
        new ExecutorTransformer(Set.of(CallbackRegistrar.class.getName())).transform(node, null);
        var instructions = node.methods.stream().flatMap(method -> Arrays.stream(method.instructions.toArray())).toList();
        var calls = instructions.stream().filter(InvokeDynamicInsnNode.class::isInstance)
                .map(InvokeDynamicInsnNode.class::cast).filter(call -> call.bsm.getOwner().equals(StageCalls.BRIDGE)).toList();
        assertEquals(3, calls.size());
        assertEquals(Opcodes.H_INVOKESTATIC, ((Handle) calls.get(0).bsmArgs[0]).getTag());
        assertEquals(Opcodes.H_INVOKEVIRTUAL, ((Handle) calls.get(1).bsmArgs[0]).getTag());
        assertEquals("(Ljava/util/concurrent/CompletableFuture;Ljava/util/function/Function;)"
                + "Ljava/util/concurrent/CompletableFuture;", calls.get(1).desc);
        assertEquals(Opcodes.H_INVOKEINTERFACE, ((Handle) calls.get(2).bsmArgs[0]).getTag());
        assertEquals("(Ljava/util/concurrent/CompletionStage;Ljava/util/function/BiConsumer;Ljava/util/concurrent/Executor;)"
                + "Ljava/util/concurrent/CompletionStage;", calls.get(2).desc);
        assertTrue(instructions.stream().anyMatch(instruction ->
                instruction instanceof MethodInsnNode call && call.name.equals("complete")));
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        String classFile = "/" + type.getName().replace('.', '/') + ".class";
        try (var source = type.getResourceAsStream(classFile)) {
            assertNotNull(source);
            return source.readAllBytes();
        }
    }

    private static void jar(Path path, String entry, byte[] content, boolean mod) throws IOException {
        try (var out = new ZipOutputStream(Files.newOutputStream(path))) {
            if (mod) {
                out.putNextEntry(new ZipEntry("META-INF/mods.toml"));
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry(entry));
            out.write(content);
            out.closeEntry();
        }
    }

    static class Submitter {
        void submit(ScheduledExecutorService executor, Runnable action) {
            executor.execute(action);
            executor.submit(action);
            executor.schedule(action, 1, TimeUnit.SECONDS);
            executor.scheduleAtFixedRate(action, 1, 1, TimeUnit.SECONDS);
        }
    }

    static class CallbackRegistrar {
        CompletionStage<Integer> register(CompletionStage<Integer> input, Executor executor) {
            var work = CompletableFuture.supplyAsync(() -> 7);
            work.thenApply(value -> value + 1);
            work.complete(9);
            return input.whenCompleteAsync((value, failure) -> {}, executor);
        }
    }
}
