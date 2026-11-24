package cn.nightbugclub.mcqqsudotransformer;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.Map;

final class StageCalls {
    static final String BRIDGE = "cn/nightbugclub/mcqqauthcapture/StageOutput";
    private static final String FUTURE = "java/util/concurrent/CompletableFuture";
    private static final String STAGE = "java/util/concurrent/CompletionStage";
    private static final String EXECUTOR = "Ljava/util/concurrent/Executor;";
    private static final String RUNNABLE = "Ljava/lang/Runnable;";
    private static final String FUNCTION = "Ljava/util/function/Function;";
    private static final Map<String, String> CALLBACKS = Map.of(
            "thenRun", RUNNABLE,
            "thenAccept", "Ljava/util/function/Consumer;",
            "thenApply", FUNCTION,
            "thenCompose", FUNCTION,
            "whenComplete", "Ljava/util/function/BiConsumer;",
            "handle", "Ljava/util/function/BiFunction;",
            "exceptionally", FUNCTION,
            "exceptionallyCompose", FUNCTION);
    private static final Handle BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC, BRIDGE, "bootstrap",
            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;"
                    + "Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;", false);

    private StageCalls() {}

    static boolean matches(int opcode, String owner, String name, String descriptor) {
        if (opcode == Opcodes.INVOKESTATIC && owner.equals(FUTURE)) {
            String callback = switch (name) {
                case "runAsync" -> RUNNABLE;
                case "supplyAsync" -> "Ljava/util/function/Supplier;";
                default -> null;
            };
            return callback != null && signatureMatches(descriptor, FUTURE, callback, true);
        }
        if ((opcode != Opcodes.INVOKEVIRTUAL || !owner.equals(FUTURE))
                && (opcode != Opcodes.INVOKEINTERFACE || !owner.equals(STAGE))) {
            return false;
        }
        boolean async = name.endsWith("Async");
        String callback = CALLBACKS.get(async ? name.substring(0, name.length() - "Async".length()) : name);
        return callback != null && signatureMatches(descriptor, owner, callback, async);
    }

    private static boolean signatureMatches(String descriptor, String owner, String callback, boolean async) {
        String result = ")L" + owner + ";";
        return descriptor.equals("(" + callback + result)
                || (async && descriptor.equals("(" + callback + EXECUTOR + result));
    }

    static InvokeDynamicInsnNode rewrite(MethodInsnNode call) {
        int kind = switch (call.getOpcode()) {
            case Opcodes.INVOKESTATIC -> Opcodes.H_INVOKESTATIC;
            case Opcodes.INVOKEINTERFACE -> Opcodes.H_INVOKEINTERFACE;
            default -> Opcodes.H_INVOKEVIRTUAL;
        };
        String descriptor = kind == Opcodes.H_INVOKESTATIC ? call.desc : "(L" + call.owner + ";" + call.desc.substring(1);
        var original = new Handle(kind, call.owner, call.name, call.desc, call.itf);
        return new InvokeDynamicInsnNode(call.name, descriptor, BOOTSTRAP, original);
    }
}
