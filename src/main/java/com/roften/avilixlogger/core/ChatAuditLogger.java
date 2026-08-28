package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.TickTask;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Single normalization path for vanilla chat, signed commands and modded chat routers. */
public final class ChatAuditLogger {
    private static final Set<String> PRIVATE_COMMANDS = Set.of(
            "msg", "tell", "w", "whisper", "pm", "m", "t", "message", "dm");
    private static final Set<String> REPLY_COMMANDS = Set.of("r", "reply");
    private static final Set<String> SECRET_COMMANDS = Set.of(
            "login", "register", "reg", "changepassword", "password", "passwd",
            "2fa", "otp", "pin", "auth", "l", "changepass");

    private static volatile Method chatParseOutgoing;
    private static volatile Method parseResultChannel;
    private static volatile Method parseResultMessage;
    private static volatile boolean parserLookupDone;
    private static final AtomicLong PACKET_SEQUENCE = new AtomicLong();
    private static final Map<PendingKey, Long> PENDING_PUBLIC = new ConcurrentHashMap<>();

    private ChatAuditLogger() {}

    public static void clearPending() {
        PENDING_PUBLIC.clear();
    }

    public static void discardPlayer(UUID playerId) {
        if (playerId != null) PENDING_PUBLIC.keySet().removeIf(key -> playerId.equals(key.actor));
    }

    /**
     * Packet-level fallback for chat mods that bypass NeoForge's ServerChatEvent completely.
     * Normal messages are confirmed by {@link #publicMessage(ServerPlayer, String)} and never
     * written twice.
     */
    public static void packetMessage(ServerPlayer player, String raw) {
        String message = raw == null ? "" : raw.trim();
        if (player == null || message.isBlank()) return;
        runOnServer(player, () -> {
            if (!(player.level() instanceof ServerLevel level)) return;
            PendingKey key = new PendingKey(player.getUUID(), message);
            long token = PACKET_SEQUENCE.incrementAndGet();
            PENDING_PUBLIC.put(key, token);
            var server = level.getServer();
            server.tell(new TickTask(server.getTickCount() + 20, () -> {
                if (PENDING_PUBLIC.remove(key, token)) appendOnServer(player, normalizePublic(message));
            }));
        });
    }

    public static void publicMessage(ServerPlayer player, String raw) {
        String message = raw == null ? "" : raw.trim();
        if (player == null || message.isBlank()) return;
        PENDING_PUBLIC.remove(new PendingKey(player.getUUID(), message));
        append(player, normalizePublic(message));
    }

    public static void command(ServerPlayer player, String rawCommand) {
        String command = trimSlash(rawCommand);
        if (command.isBlank()) return;

        int firstSpace = command.indexOf(' ');
        String label = firstSpace < 0 ? command : command.substring(0, firstSpace);
        String args = firstSpace < 0 ? "" : command.substring(firstSpace + 1).trim();
        String bareLabel = bareCommandLabel(label);

        if (SECRET_COMMANDS.contains(bareLabel)) {
            append(player, "[Команда] /" + label + (args.isBlank() ? "" : " <скрыто>"));
            return;
        }
        if (REPLY_COMMANDS.contains(bareLabel)) {
            append(player, "[ЛС → последний собеседник] " + args);
            return;
        }
        if (PRIVATE_COMMANDS.contains(bareLabel)) {
            int targetEnd = args.indexOf(' ');
            String target = targetEnd < 0 ? args : args.substring(0, targetEnd);
            String message = targetEnd < 0 ? "" : args.substring(targetEnd + 1).trim();
            append(player, "[ЛС → " + (target.isBlank() ? "?" : target) + "] " + message);
            return;
        }
        append(player, "[Команда] /" + command);
    }

    private static void append(ServerPlayer player, String text) {
        if (player == null || text == null || text.isBlank()) return;
        runOnServer(player, () -> appendOnServer(player, text));
    }

    private static void appendOnServer(ServerPlayer player, String text) {
        try {
            if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logChat.get()) return;
            if (!(player.level() instanceof ServerLevel level)) return;

            LogEntry entry = new LogEntry();
            entry.ts = System.currentTimeMillis();
            entry.dim = level.dimension().location().toString();
            entry.type = ActionType.CHAT_MESSAGE;
            entry.actorUuid = player.getUUID();
            entry.actorName = player.getGameProfile().getName();
            entry.x = player.getBlockX();
            entry.y = player.getBlockY();
            entry.z = player.getBlockZ();
            entry.extra = text;
            LoggerRuntime.storage(level).append(entry);
        } catch (Throwable ignored) {}
    }

    private static void runOnServer(ServerPlayer player, Runnable action) {
        if (player == null || action == null) return;
        try {
            var server = player.getServer();
            if (server == null || server.isSameThread()) action.run();
            else server.execute(action);
        } catch (Throwable ignored) {}
    }

    private static String normalizePublic(String raw) {
        if (raw == null) return "";
        String text = raw.trim();
        if (text.isBlank()) return "";
        try {
            Object result = parseAvilixChat(text);
            if (result == null) return "[Общий] " + text;
            Object channel = parseResultChannel.invoke(result);
            String message = String.valueOf(parseResultMessage.invoke(result)).trim();
            String tag = "";
            try { tag = String.valueOf(channel.getClass().getField("shortTag").get(channel)).trim(); }
            catch (Throwable ignored) {}
            return "[" + (tag.isBlank() ? "Чат" : tag) + "] " + message;
        } catch (Throwable ignored) {
            return "[Общий] " + text;
        }
    }

    private static Object parseAvilixChat(String raw) throws Exception {
        if (!parserLookupDone) {
            synchronized (ChatAuditLogger.class) {
                if (!parserLookupDone) {
                    parserLookupDone = true;
                    try {
                        Class<?> channel = Class.forName("com.roften.multichat.chat.ChatChannel");
                        chatParseOutgoing = channel.getMethod("parseOutgoing", String.class);
                        Class<?> result = Class.forName("com.roften.multichat.chat.ChatChannel$ParseResult");
                        parseResultChannel = result.getMethod("channel");
                        parseResultMessage = result.getMethod("message");
                    } catch (Throwable ignored) {
                        chatParseOutgoing = null;
                        parseResultChannel = null;
                        parseResultMessage = null;
                    }
                }
            }
        }
        if (chatParseOutgoing == null || parseResultChannel == null || parseResultMessage == null) return null;
        return chatParseOutgoing.invoke(null, raw);
    }

    private static String trimSlash(String command) {
        if (command == null) return "";
        String out = command.trim();
        while (out.startsWith("/")) out = out.substring(1);
        return out.trim();
    }

    private static String bareCommandLabel(String label) {
        if (label == null) return "";
        String out = label.toLowerCase(Locale.ROOT);
        int namespace = out.indexOf(':');
        return namespace >= 0 ? out.substring(namespace + 1) : out;
    }

    private record PendingKey(UUID actor, String message) {}
}
