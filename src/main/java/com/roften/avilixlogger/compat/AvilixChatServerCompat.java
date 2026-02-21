package com.roften.avilixlogger.compat;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.LogEntry;
import com.roften.avilixlogger.core.LoggerRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;

import java.lang.reflect.Method;

/**
 * Compatibility hook for AvilixChat (and other chat routers) that cancel {@link ServerChatEvent}
 * and deliver messages via {@link ServerPlayer#sendSystemMessage} instead of vanilla chat packets.
 *
 * We only log when the event is canceled to avoid double-logging with {@link com.roften.avilixlogger.mixin.ServerChatListenerMixin}.
 */
@EventBusSubscriber(modid = AvilixLoggerMod.MOD_ID)
public final class AvilixChatServerCompat {
    private AvilixChatServerCompat() {}

    // Lazy reflection cache (no hard dependency on AvilixChat)
    private static volatile Method CHAT_PARSE_OUTGOING;
    private static volatile Method PARSERESULT_CHANNEL;
    private static volatile Method PARSERESULT_MESSAGE;
    private static volatile boolean PARSE_LOOKUP_DONE;

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onServerChat(ServerChatEvent event) {
        try {
            if (event == null) return;
            if (!event.isCanceled()) return; // compat path: only when some mod (AvilixChat) fully handles broadcast
            if (!LoggerConfig.VALUES.enabled.get()) return;
            if (!LoggerConfig.VALUES.logChat.get()) return;

            final ServerPlayer player = event.getPlayer();
            if (player == null) return;
            if (!(player.level() instanceof ServerLevel sl)) return;

            String raw = null;
            try {
                raw = event.getRawText();
            } catch (Throwable ignored) {
            }
            if (raw == null || raw.isBlank()) return;

            // Best-effort AvilixChat channel parsing (works only if AvilixChat is present)
            String extra = raw;
            try {
                Object pr = tryParseAvilixChat(raw);
                if (pr != null) {
                    Object ch = PARSERESULT_CHANNEL.invoke(pr);
                    String msg = (String) PARSERESULT_MESSAGE.invoke(pr);
                    String tag = "";
                    try {
                        // enum ChatChannel has public field shortTag
                        tag = (String) ch.getClass().getField("shortTag").get(ch);
                    } catch (Throwable ignored2) {
                    }
                    if (msg != null) msg = msg.trim();
                    if (tag != null && !tag.isBlank() && msg != null && !msg.isBlank()) {
                        extra = "[" + tag + "] " + msg;
                    } else if (msg != null && !msg.isBlank()) {
                        extra = msg;
                    }
                }
            } catch (Throwable ignored) {
            }

            LogEntry e = new LogEntry();
            e.ts = System.currentTimeMillis();
            e.dim = sl.dimension().location().toString();
            e.type = ActionType.CHAT_MESSAGE;
            e.actorUuid = player.getUUID();
            e.actorName = player.getGameProfile().getName();
            e.x = player.getBlockX();
            e.y = player.getBlockY();
            e.z = player.getBlockZ();
            e.extra = extra;
            LoggerRuntime.storage(sl).append(e);
        } catch (Throwable ignored) {
        }
    }

    private static Object tryParseAvilixChat(String raw) throws Exception {
        if (!PARSE_LOOKUP_DONE) {
            synchronized (AvilixChatServerCompat.class) {
                if (!PARSE_LOOKUP_DONE) {
                    PARSE_LOOKUP_DONE = true;
                    try {
                        Class<?> chatChannel = Class.forName("com.roften.multichat.chat.ChatChannel");
                        CHAT_PARSE_OUTGOING = chatChannel.getMethod("parseOutgoing", String.class);
                        Class<?> parseResult = Class.forName("com.roften.multichat.chat.ChatChannel$ParseResult");
                        PARSERESULT_CHANNEL = parseResult.getMethod("channel");
                        PARSERESULT_MESSAGE = parseResult.getMethod("message");
                    } catch (Throwable t) {
                        CHAT_PARSE_OUTGOING = null;
                        PARSERESULT_CHANNEL = null;
                        PARSERESULT_MESSAGE = null;
                    }
                }
            }
        }
        if (CHAT_PARSE_OUTGOING == null || PARSERESULT_CHANNEL == null || PARSERESULT_MESSAGE == null) return null;
        return CHAT_PARSE_OUTGOING.invoke(null, raw);
    }
}
