package cn.nightbugclub.mcqqsudotransformer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class ExecutorTransformerTest {
    private static final String BRIDGE = "cn/nightbugclub/mcqqauthcapture/ExecutorOutput";

    @Test
    void rewritesMatchedCallsAndLeavesPeriodicTasksAlone() throws IOException {
        var reader = new ClassReader(submitter());
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
    void discoversForgeModClassesOnlyWithTheBridgePresent(@TempDir Path mods) throws IOException {
        String name = Submitter.class.getName().replace('.', '/') + ".class";
        jar(mods.resolve("fixture.jar"), name, submitter(), true);
        assertTrue(ExecutorTransformer.scan(mods).isEmpty());
        jar(mods.resolve("mcqqsudo.jar"), BRIDGE + ".class", new byte[0], true);
        jar(mods.resolve("ordinary-library.jar"), "unreadable.class", new byte[0], false);
        assertEquals(Set.of(Submitter.class.getName()), ExecutorTransformer.scan(mods));
    }

    private static byte[] submitter() throws IOException {
        String classFile = "/" + Submitter.class.getName().replace('.', '/') + ".class";
        try (var source = Submitter.class.getResourceAsStream(classFile)) {
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
}
