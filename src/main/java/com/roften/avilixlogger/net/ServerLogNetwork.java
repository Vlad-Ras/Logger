package com.roften.avilixlogger.net;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.roften.avilixlogger.core.PermissionUtil;
import com.roften.avilixlogger.core.ServerLogFiles;
import com.roften.avilixlogger.net.ServerLogPayloads.Action;
import com.roften.avilixlogger.net.ServerLogPayloads.Query;
import com.roften.avilixlogger.net.ServerLogPayloads.View;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class ServerLogNetwork {
    private static final String PERMISSION = "avilixlogger.serverlogs.download";
    private static final int MAX_REQUESTS = 4;
    private static final int IDLE_TICKS = 600;
    private static final Map<UUID, Transfer> TRANSFERS = new HashMap<>();
    private static final ThreadPoolExecutor IO = new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_REQUESTS), task -> {
                var thread = new Thread(task, "avilixlogger-server-log-export");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    private ServerLogNetwork() {}

    private static final class Transfer {
        final long request;
        final ServerGamePacketListenerImpl connection;
        ServerLogFiles.Snapshot file;
        boolean busy;
        long nextOffset;
        int lastTick;

        Transfer(Query query, ServerPlayer player) {
            request = query.request();
            connection = player.connection;
            lastTick = player.getServer().getTickCount();
        }
    }

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("server-logs")
                .requires(source -> source.getEntity() instanceof ServerPlayer player && allowed(player))
                .executes(context -> {
                    context.getSource().sendSystemMessage(Component.literal("Откройте «Разделы → Логи сервера»."));
                    return 1;
                });
    }

    private static boolean allowed(ServerPlayer player) {
        return PermissionUtil.hasOperatorNode(player, PERMISSION);
    }

    static void handle(Query request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || request.request() <= 0
                    || !player.connection.hasChannel(View.TYPE)) return;
            UUID id = player.getUUID();
            var active = TRANSFERS.get(id);
            if (request.action() == Action.CANCEL) {
                if (same(active, request, player)) TRANSFERS.remove(id);
                return;
            }
            if (!allowed(player)) {
                logout(player);
                send(player, View.error(request.request(), "Требуется право " + PERMISSION + " и статус оператора."));
                return;
            }

            if (request.action() == Action.NEXT) {
                if (same(active, request, player) && !active.busy && active.file != null && request.offset() == active.nextOffset) {
                    read(player, active, active.file, active.nextOffset);
                }
                return;
            }
            if (active != null || TRANSFERS.size() >= MAX_REQUESTS) {
                send(player, View.error(request.request(), "Выгрузка уже выполняется. Повторите запрос позже."));
                return;
            }
            if (request.action() == Action.START && !ServerLogFiles.allowedName(request.name())) {
                send(player, View.error(request.request(), "Этот файл недоступен для выгрузки."));
                return;
            }
            var transfer = new Transfer(request, player);
            TRANSFERS.put(id, transfer);
            Path root = FMLPaths.GAMEDIR.get();
            if (request.action() == Action.LIST) {
                submit(player, transfer, () -> new Reply(null, View.list(transfer.request, ServerLogFiles.list(root))));
                return;
            }
            submit(player, transfer, () -> {
                var file = ServerLogFiles.open(root, request.name());
                return new Reply(file, View.data(transfer.request, request.name(), 0, file.size(), ServerLogFiles.read(file, 0)));
            });
        });
    }

    private static boolean same(Transfer transfer, Query request, ServerPlayer player) {
        return transfer != null && transfer.request == request.request() && transfer.connection == player.connection;
    }

    private static void read(ServerPlayer player, Transfer transfer, ServerLogFiles.Snapshot file, long offset) {
        submit(player, transfer, () -> new Reply(file, View.data(transfer.request, file.path().getFileName().toString(),
                offset, file.size(), ServerLogFiles.read(file, offset))));
    }

    private record Reply(ServerLogFiles.Snapshot file, View view) {}
    @FunctionalInterface private interface Read { Reply run() throws IOException; }

    private static void submit(ServerPlayer player, Transfer transfer, Read read) {
        transfer.busy = true;
        var server = player.getServer();
        var id = player.getUUID();
        transfer.lastTick = server.getTickCount();
        try {
            IO.execute(() -> {
                Reply reply;
                try {
                    reply = read.run();
                } catch (IOException | RuntimeException failure) {
                    reply = new Reply(null, View.error(transfer.request,
                            "Файл недоступен, изменился, либо превышает 128 МиБ. Обновите список."));
                }
                var result = reply;
                server.execute(() -> {
                    if (TRANSFERS.get(id) != transfer) return;
                    var current = server.getPlayerList().getPlayer(id);
                    if (current == null || current.connection != transfer.connection || !allowed(current)) {
                        TRANSFERS.remove(id);
                        if (current != null && current.connection == transfer.connection)
                            send(current, View.error(transfer.request, "Права на выгрузку изменились."));
                        return;
                    }
                    var view = result.view();
                    transfer.file = result.file();
                    transfer.busy = false;
                    transfer.nextOffset = view.offset() + view.data().length;
                    if (transfer.file == null || transfer.nextOffset >= view.total()) TRANSFERS.remove(id);
                    send(current, view);
                });
            });
        } catch (RejectedExecutionException ignored) {
            TRANSFERS.remove(id, transfer);
            send(player, View.error(transfer.request, "Очередь выгрузки занята. Повторите запрос позже."));
        }
    }

    public static void expire(MinecraftServer server) {
        if (TRANSFERS.isEmpty()) return;
        int now = server.getTickCount();
        var entries = TRANSFERS.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            var transfer = entry.getValue();
            if (now - transfer.lastTick < IDLE_TICKS) continue;
            var current = server.getPlayerList().getPlayer(entry.getKey());
            entries.remove();
            if (current != null && current.connection == transfer.connection)
                send(current, View.error(transfer.request, "Время ожидания выгрузки истекло."));
        }
    }

    private static void send(ServerPlayer player, View view) {
        if (player.connection.hasChannel(View.TYPE)) PacketDistributor.sendToPlayer(player, view);
    }

    static void logout(ServerPlayer player) {
        var active = TRANSFERS.get(player.getUUID());
        if (active != null && active.connection == player.connection) TRANSFERS.remove(player.getUUID());
    }

    static void stop() {
        TRANSFERS.clear();
        IO.getQueue().clear();
    }
}
