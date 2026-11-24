package cn.nightbugclub.mcqqsudotransformer;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import cpw.mods.modlauncher.api.TransformerVoteResult;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

final class ExecutorTransformer implements ITransformer<ClassNode> {
    private static final System.Logger LOG = System.getLogger("MCQQSudo-Transformer");
    static final String BRIDGE = "cn/nightbugclub/mcqqauthcapture/ExecutorOutput";
    private static final String CONCURRENT = "java/util/concurrent/";
    private static final String EXECUTOR = CONCURRENT + "Executor";
    private static final String SERVICE = CONCURRENT + "ExecutorService";
    private static final String SCHEDULER = CONCURRENT + "ScheduledExecutorService";
    private static final String THREAD_POOL = CONCURRENT + "ThreadPoolExecutor";
    private static final String SCHEDULED_POOL = CONCURRENT + "ScheduledThreadPoolExecutor";
    private static final String RUNNABLE = "Ljava/lang/Runnable;";
    private static final String CALLABLE = "L" + CONCURRENT + "Callable;";
    private static final String FUTURE = "L" + CONCURRENT + "Future;";
    private static final String SCHEDULED_FUTURE = "L" + CONCURRENT + "ScheduledFuture;";
    private static final String DELAY = "JL" + CONCURRENT + "TimeUnit;";
    private static final Set<String> SERVICES = Set.of(SERVICE, SCHEDULER, THREAD_POOL,
            SCHEDULED_POOL, CONCURRENT + "AbstractExecutorService");
    private static final Set<String> SUBMIT = Set.of("(" + RUNNABLE + ")" + FUTURE,
            "(" + RUNNABLE + "Ljava/lang/Object;)" + FUTURE, "(" + CALLABLE + ")" + FUTURE);
    private static final Set<String> SCHEDULE = Set.of("(" + RUNNABLE + DELAY + ")" + SCHEDULED_FUTURE,
            "(" + CALLABLE + DELAY + ")" + SCHEDULED_FUTURE);

    private final Set<String> classes;

    ExecutorTransformer(Set<String> classes) {
        this.classes = Set.copyOf(classes);
    }

    static Set<String> scan(Path mods) {
        if (!Files.isDirectory(mods)) {
            return Set.of();
        }
        var classes = new HashSet<String>();
        try (var files = Files.list(mods)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).toList()) {
                try (var jar = new ZipFile(file.toFile())) {
                    if (jar.getEntry("META-INF/mods.toml") == null) {
                        continue;
                    }
                    var entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        var entry = entries.nextElement();
                        String name = entry.getName();
                        if (name.endsWith(".class") && !name.startsWith("META-INF/") && !name.equals("module-info.class")) {
                            try (var input = jar.getInputStream(entry)) {
                                var reader = new ClassReader(input);
                                if (hasMatch(reader)) {
                                    classes.add(reader.getClassName().replace('/', '.'));
                                }
                            }
                        }
                    }
                } catch (IOException | IllegalArgumentException failure) {
                    LOG.log(Level.WARNING, "Could not scan Forge mod {0}: {1}", file.getFileName(), failure.getMessage());
                }
            }
        } catch (IOException failure) {
            LOG.log(Level.WARNING, "Could not scan the mods directory: {0}", failure.getMessage());
            return Set.of();
        }
        return classes;
    }

    static boolean hasMatch(ClassReader reader) {
        boolean[] matched = {false};
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                        matched[0] |= matches(opcode, owner, name, descriptor);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                                                       Object... arguments) {
                        MethodInsnNode target = MethodReferences.target(bootstrap, arguments);
                        if (target != null) {
                            matched[0] |= matches(target.getOpcode(), target.owner, target.name, target.desc);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return matched[0];
    }

    private static boolean matches(int opcode, String owner, String name, String descriptor) {
        return receiver(opcode, owner, name, descriptor) != null || StageCalls.matches(opcode, owner, name, descriptor);
    }

    private static String receiver(int opcode, String owner, String name, String descriptor) {
        if (opcode != Opcodes.INVOKEVIRTUAL && opcode != Opcodes.INVOKEINTERFACE) {
            return null;
        }
        return switch (name) {
            case "execute" -> (owner.equals(EXECUTOR) || SERVICES.contains(owner))
                    && descriptor.equals("(" + RUNNABLE + ")V") ? EXECUTOR : null;
            case "submit" -> SERVICES.contains(owner) && SUBMIT.contains(descriptor) ? SERVICE : null;
            case "schedule" -> (owner.equals(SCHEDULER) || owner.equals(SCHEDULED_POOL))
                    && SCHEDULE.contains(descriptor) ? SCHEDULER : null;
            default -> null;
        };
    }

    @Override
    public ClassNode transform(ClassNode node, ITransformerVotingContext context) {
        for (var method : List.copyOf(node.methods)) {
            for (var instruction : method.instructions.toArray()) {
                if (instruction instanceof MethodInsnNode call) {
                    var replacement = rewriteCall(call);
                    if (replacement != null) {
                        method.instructions.set(call, replacement);
                    }
                } else if (instruction instanceof InvokeDynamicInsnNode factory) {
                    MethodInsnNode target = MethodReferences.target(factory.bsm, factory.bsmArgs);
                    var replacement = target == null ? null : rewriteCall(target);
                    if (replacement != null) {
                        MethodReferences.rewrite(node, factory, target, replacement);
                    }
                }
            }
        }
        return node;
    }

    private static AbstractInsnNode rewriteCall(MethodInsnNode call) {
        String receiver = receiver(call.getOpcode(), call.owner, call.name, call.desc);
        if (receiver != null) {
            String descriptor = "(L" + receiver + ";" + call.desc.substring(1);
            return new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, call.name, descriptor, false);
        }
        return StageCalls.matches(call.getOpcode(), call.owner, call.name, call.desc) ? StageCalls.rewrite(call) : null;
    }

    @Override
    public TransformerVoteResult castVote(ITransformerVotingContext context) {
        return TransformerVoteResult.YES;
    }

    @Override
    @SuppressWarnings("rawtypes")
    public Set<Target> targets() {
        return classes.stream().map(Target::targetClass).collect(Collectors.toUnmodifiableSet());
    }
}
