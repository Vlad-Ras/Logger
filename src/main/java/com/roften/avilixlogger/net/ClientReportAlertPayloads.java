package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public final class ClientReportAlertPayloads {
    static final int MAX_ALERTS = 32;
    private static final int MAX_LINE_LENGTH = 384;

    private ClientReportAlertPayloads() {}

    public enum Action { READ, ENABLE, DISABLE }

    public record Query(Action action) implements CustomPacketPayload {
        public static final Type<Query> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "client_report_alert_query"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = StreamCodec.of(
                (buf, query) -> buf.writeEnum(query.action()), buf -> new Query(buf.readEnum(Action.class)));

        @Override public Type<Query> type() { return TYPE; }
    }

    public record View(boolean enabled, List<String> alerts) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "client_report_alert_view"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.of((buf, view) -> {
            buf.writeBoolean(view.enabled());
            buf.writeVarInt(view.alerts().size());
            for (String alert : view.alerts()) buf.writeUtf(alert, MAX_LINE_LENGTH);
        }, buf -> {
            boolean enabled = buf.readBoolean();
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_ALERTS) throw new IllegalArgumentException("Invalid alert count");
            var alerts = new ArrayList<String>(count);
            for (int i = 0; i < count; i++) alerts.add(buf.readUtf(MAX_LINE_LENGTH));
            return new View(enabled, alerts);
        });

        public View {
            alerts = List.copyOf(alerts);
        }

        @Override public Type<View> type() { return TYPE; }
    }
}
