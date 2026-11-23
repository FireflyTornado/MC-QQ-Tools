package cn.nightbugclub.mcqqauthcapture;

import java.lang.reflect.Method;
import java.util.Optional;

final class VanillaOutput {
    private VanillaOutput() {}

    static String broadcast(Object message, Object chatType) throws ReflectiveOperationException {
        return decorate(chatType, get(message, "decoratedContent", "m_245692_"));
    }

    static String packet(Object connection, Object packet, String broadcast) throws ReflectiveOperationException {
        Object player;
        String text;
        String kind;
        switch (packet.getClass().getSimpleName()) {
            case "ClientboundSystemChatPacket" -> {
                kind = Boolean.TRUE.equals(get(packet, "overlay", "f_240374_")) ? "动作栏" : "";
                text = text(get(packet, "content", "f_237849_"));
            }
            case "ClientboundDisguisedChatPacket" -> {
                player = player(connection);
                Object component = get(packet, "message", "f_244491_");
                Object type = get(packet, "chatType", "f_244252_");
                text = decorateForPlayer(player, type, component);
                return text.equals(broadcast) ? null : target("", player, text);
            }
            case "ClientboundSetTitleTextPacket" -> {
                kind = "标题";
                text = text(get(packet, "getText", "m_179399_"));
            }
            case "ClientboundSetSubtitleTextPacket" -> {
                kind = "字幕";
                text = text(get(packet, "getText", "m_179385_"));
            }
            case "ClientboundSetActionBarTextPacket" -> {
                kind = "动作栏";
                text = text(get(packet, "getText", "m_179210_"));
            }
            case "ClientboundSoundPacket", "ClientboundSoundEntityPacket" -> {
                kind = "声音";
                Object sound = get(packet, "getSound",
                        packet.getClass().getSimpleName().equals("ClientboundSoundPacket") ? "m_263229_" : "m_263456_");
                text = get(get(sound, "value", "m_203334_"), "getLocation", "m_11660_").toString();
            }
            case "ClientboundLevelParticlesPacket" -> {
                kind = "粒子";
                text = (String) get(get(packet, "getParticle", "m_132322_"), "writeToString", "m_5942_");
            }
            default -> {
                return null;
            }
        }
        return target(kind, player(connection), text);
    }

    private static Object player(Object connection) throws ReflectiveOperationException {
        return get(connection, "getPlayer", "m_142253_");
    }

    private static String decorateForPlayer(Object player, Object networkType, Object text)
            throws ReflectiveOperationException {
        Object server = get(player, "getServer", "m_20194_");
        Object registry = get(server, "registryAccess", "m_206579_");
        ClassLoader loader = networkType.getClass().getClassLoader();
        Class<?> registryType = loader.loadClass("net.minecraft.core.RegistryAccess");
        Optional<?> bound = (Optional<?>) method(networkType.getClass(), "resolve", "m_242652_", registryType)
                .invoke(networkType, registry);
        return bound.isPresent() ? decorate(bound.get(), text) : text(text);
    }

    private static String decorate(Object chatType, Object component) throws ReflectiveOperationException {
        Class<?> type = chatType.getClass().getClassLoader().loadClass("net.minecraft.network.chat.Component");
        return text(method(chatType.getClass(), "decorate", "m_240977_", type).invoke(chatType, component));
    }

    private static String text(Object component) throws ReflectiveOperationException {
        Class<?> type = component.getClass().getClassLoader().loadClass("net.minecraft.network.chat.Component");
        return (String) type.getMethod("getString").invoke(component);
    }

    private static String target(String kind, Object player, String text) throws ReflectiveOperationException {
        String name = (String) get(player, "getScoreboardName", "m_6302_");
        return "[" + (kind.isEmpty() ? "" : kind + " → ") + name + "] " + text;
    }

    private static Object get(Object receiver, String named, String srg) throws ReflectiveOperationException {
        return method(receiver.getClass(), named, srg).invoke(receiver);
    }

    private static Method method(Class<?> type, String named, String srg, Class<?>... parameters)
            throws NoSuchMethodException {
        try {
            return type.getMethod(named, parameters);
        } catch (NoSuchMethodException e) {
            return type.getMethod(srg, parameters);
        }
    }
}
