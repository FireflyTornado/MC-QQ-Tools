package cn.nightbugclub.mcqqsudotransformer;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.lang.invoke.LambdaMetafactory;

final class MethodReferences {
    private static final String METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    private MethodReferences() {}

    static MethodInsnNode target(Handle bootstrap, Object[] arguments) {
        if (bootstrap.getTag() != Opcodes.H_INVOKESTATIC || !METAFACTORY.equals(bootstrap.getOwner())
                || arguments.length < 3 || !(arguments[1] instanceof Handle target)) {
            return null;
        }
        if ("altMetafactory".equals(bootstrap.getName())) {
            // javac's deserializer expects the original implementation handle.
            if (arguments.length < 4 || !(arguments[3] instanceof Integer flags)
                    || (flags & LambdaMetafactory.FLAG_SERIALIZABLE) != 0) {
                return null;
            }
        } else if (!"metafactory".equals(bootstrap.getName())) {
            return null;
        }
        int opcode = switch (target.getTag()) {
            case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
            case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
            case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
            default -> -1;
        };
        return opcode == -1 ? null
                : new MethodInsnNode(opcode, target.getOwner(), target.getName(), target.getDesc(), target.isInterface());
    }

    static void rewrite(ClassNode owner, InvokeDynamicInsnNode factory, MethodInsnNode target, AbstractInsnNode call) {
        Type signature = Type.getMethodType(target.desc);
        Type[] parameters = signature.getArgumentTypes();
        if (target.getOpcode() != Opcodes.INVOKESTATIC) {
            var withReceiver = new Type[parameters.length + 1];
            withReceiver[0] = Type.getObjectType(target.owner);
            System.arraycopy(parameters, 0, withReceiver, 1, parameters.length);
            parameters = withReceiver;
        }
        Type[] captured = Type.getArgumentTypes(factory.desc);
        if (captured.length > parameters.length) {
            return;
        }
        // A static lambda implementation must exactly match the factory's captured parameter types.
        System.arraycopy(captured, 0, parameters, 0, captured.length);
        Type result = signature.getReturnType();
        String descriptor = Type.getMethodDescriptor(result, parameters);

        boolean interfaceOwner = (owner.access & Opcodes.ACC_INTERFACE) != 0;
        int visibility = interfaceOwner && (owner.version & 0xffff) <= Opcodes.V1_8
                ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
        String name = bridgeName(owner);
        var bridge = new MethodNode(visibility | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, descriptor, null, null);
        int local = 0;
        for (Type parameter : parameters) {
            bridge.instructions.add(new VarInsnNode(parameter.getOpcode(Opcodes.ILOAD), local));
            local += parameter.getSize();
        }
        bridge.instructions.add(call);
        bridge.instructions.add(new InsnNode(result.getOpcode(Opcodes.IRETURN)));
        bridge.maxLocals = local;
        bridge.maxStack = Math.max(local, result.getSize());
        owner.methods.add(bridge);

        factory.bsmArgs = factory.bsmArgs.clone();
        factory.bsmArgs[1] = new Handle(Opcodes.H_INVOKESTATIC, owner.name, name, descriptor, interfaceOwner);
    }

    private static String bridgeName(ClassNode owner) {
        int index = owner.methods.size();
        while (true) {
            String name = "$mcqqsudo$methodReference$" + index++;
            if (owner.methods.stream().noneMatch(method -> name.equals(method.name))) {
                return name;
            }
        }
    }
}
