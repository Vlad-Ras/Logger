package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class ClientReportViewPayloads {
    public static final int MAX_COMMANDS = 48;
    public static final int COMMAND_LENGTH = 256;
    private static final Pattern NICKNAME = Pattern.compile("[A-Za-z0-9_]{1,16}");

    private ClientReportViewPayloads() {}

    public static boolean validName(String value) {
        return NICKNAME.matcher(value).matches();
    }

    public enum Action { REQUEST, CACHED, COMMANDS }

    public record Query(long id, String name, Action action) implements CustomPacketPayload {
        public static final Type<Query> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "client_report_view_query"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = StreamCodec.of(
                (buf, query) -> {
                    buf.writeLong(query.id());
                    buf.writeUtf(query.name(), 16);
                    buf.writeEnum(query.action());
                }, buf -> new Query(buf.readLong(), buf.readUtf(16), buf.readEnum(Action.class)));

        @Override public Type<Query> type() { return TYPE; }
    }

    public record View(long id, String message, String player, long receivedAt, List<String> commands, List<String> matches,
                       ClientReportPayloads.Report report) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "client_report_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.of(View::encode, View::decode);

        public View {
            commands = List.copyOf(commands);
            matches = List.copyOf(matches);
            if (commands.size() > MAX_COMMANDS || matches.size() > 128) throw new IllegalArgumentException("Too many report command rows");
        }

        public static View message(long id, String message) {
            return new View(id, message, "", 0, List.of(), List.of(), null);
        }

        private static void encode(RegistryFriendlyByteBuf buf, View view) {
            buf.writeLong(view.id());
            buf.writeUtf(view.message(), 512);
            buf.writeUtf(view.player(), 16);
            buf.writeLong(view.receivedAt());
            buf.writeVarInt(view.commands().size());
            for (String command : view.commands()) buf.writeUtf(command, COMMAND_LENGTH);
            buf.writeVarInt(view.matches().size());
            for (String pack : view.matches()) buf.writeUtf(pack, 128);
            buf.writeBoolean(view.report() != null);
            if (view.report() != null) ClientReportPayloads.Report.CODEC.encode(buf, view.report());
        }

        private static View decode(RegistryFriendlyByteBuf buf) {
            long id = buf.readLong();
            String message = buf.readUtf(512);
            String player = buf.readUtf(16);
            long receivedAt = buf.readLong();
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_COMMANDS) throw new IllegalArgumentException("Invalid report command row count");
            List<String> commands = new ArrayList<>(count);
            for (int i = 0; i < count; i++) commands.add(buf.readUtf(COMMAND_LENGTH));
            int matchCount = buf.readVarInt();
            if (matchCount < 0 || matchCount > 128) throw new IllegalArgumentException("Invalid report match count");
            List<String> matches = new ArrayList<>(matchCount);
            for (int i = 0; i < matchCount; i++) matches.add(buf.readUtf(128));
            var report = buf.readBoolean() ? ClientReportPayloads.Report.CODEC.decode(buf) : null;
            return new View(id, message, player, receivedAt, commands, matches, report);
        }

        @Override public Type<View> type() { return TYPE; }
    }
}
