package com.roften.avilixlogger.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.CartAuditContext;
import com.roften.avilixlogger.core.CreateContraptionKind;
import com.roften.avilixlogger.core.LogEntry;
import com.roften.avilixlogger.core.LogQuery;
import com.roften.avilixlogger.core.LoggerRuntime;
import com.roften.avilixlogger.core.NbtSerde;
import com.roften.avilixlogger.core.PermissionUtil;
import com.roften.avilixlogger.core.RollbackCoordinator;
import com.roften.avilixlogger.core.RollbackEngine;
import com.roften.avilixlogger.net.S2CRollbackPreviewPayload;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class EntryRollbackCommand {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z")
            .withZone(ZoneId.systemDefault());
    private static final long PREVIEW_TTL_MS = 120_000;
    private static final Map<UUID, Object> REQUESTS = new HashMap<>();
    private static final Map<UUID, Plan> PREVIEWS = new HashMap<>();

    private record Plan(long id, ServerLevel level, BlockState expected,
                        RollbackEngine.PreparedBlockSnapshot snapshot, long expiresAt) {}

    private EntryRollbackCommand() {}

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return literal("rollback-entry").requires(EntryRollbackCommand::permitted)
                .then(literal("preview").then(argument("id", LongArgumentType.longArg(1))
                        .executes(c -> preview(c.getSource(), c.getSource().getPlayerOrException(), LongArgumentType.getLong(c, "id")))))
                .then(literal("confirm").then(argument("id", LongArgumentType.longArg(1))
                        .executes(c -> confirm(c.getSource(), c.getSource().getPlayerOrException(), LongArgumentType.getLong(c, "id")))));
    }

    private static boolean permitted(CommandSourceStack source) {
        return PermissionUtil.has(source, "avilixlogger.command.admin", 2);
    }

    private static int preview(CommandSourceStack source, ServerPlayer player, long id) {
        if (!LoggerConfig.isEnabled()) return fail(source, "Logger отключён.");
        UUID actor = player.getUUID();
        clear(actor);
        Object request = new Object();
        REQUESTS.put(actor, request);
        var server = source.getServer();
        var storage = LoggerRuntime.storage(player.serverLevel());
        if (!RollbackCoordinator.submitEntryLookup(() -> {
            try {
                var query = new LogQuery();
                query.dim = "*";
                query.exactId = id;
                query.requireDetails = true;
                query.untilTs = Long.MAX_VALUE;
                query.limit = 1;
                var rows = storage.queryReverse(query);
                if (rows.isEmpty()) {
                    query.requireDetails = false;
                    rows = storage.queryReverse(query);
                }
                var row = rows.stream().filter(candidate -> candidate.id == id).findFirst().orElse(null);
                var before = row == null ? null : smallState(row.blockBefore);
                var after = row == null ? null : smallState(row.blockAfter);
                server.execute(() -> {
                    if (!REQUESTS.remove(actor, request) || server.getPlayerList().getPlayer(actor) != player) return;
                    if (!LoggerConfig.isEnabled() || !permitted(source)) {
                        fail(source, "Запись отключена или права на откат изменились.");
                    } else if (row == null) {
                        fail(source, "Запись #" + id + " отсутствует в доступной истории.");
                    } else {
                        prepare(source, actor, row, before, after);
                    }
                });
            } catch (RuntimeException failure) {
                AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Entry rollback lookup failed for {}", id, failure);
                server.execute(() -> {
                    if (REQUESTS.remove(actor, request)) fail(source, "Не удалось прочитать запись из хранилища.");
                });
            }
        })) {
            REQUESTS.remove(actor, request);
            return fail(source, "Очередь подготовки откатов занята. Повторите позже.");
        }
        source.sendSystemMessage(Component.literal("Проверяю запись #" + id + "…"));
        return 1;
    }

    private static CompoundTag smallState(String snbt) {
        return snbt == null || snbt.length() > 4096 ? null : NbtSerde.fromSnbt(snbt);
    }

    private static void prepare(CommandSourceStack source, UUID actor, LogEntry row, CompoundTag before, CompoundTag after) {
        BlockPos pos = new BlockPos(row.x, row.y, row.z);
        String location = row.dim + " · " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        String summary = "Запись #" + row.id + " · " + TIME.format(Instant.ofEpochMilli(row.ts)) + "\n" + location;
        if (CreateContraptionKind.fromSource(row.source) != CreateContraptionKind.UNKNOWN) {
            source.sendSystemMessage(Component.literal(summary));
            CartCommands.previewEntry(source, row);
            return;
        }
        if (row.type != ActionType.BLOCK_PLACE && row.type != ActionType.BLOCK_BREAK) {
            fail(source, "Для записи #" + row.id + " отдельный откат этого типа не поддерживается.");
            return;
        }
        if (before == null || after == null) {
            fail(source, "Для записи #" + row.id + " нужны оба снимка состояния блока.");
            return;
        }
        ServerLevel level = null;
        for (var candidate : source.getServer().getAllLevels()) {
            if (candidate.dimension().location().toString().equals(row.dim)) {
                level = candidate;
                break;
            }
        }
        if (level == null || !level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) {
            fail(source, "Координаты вне доступного загруженного мира · " + location);
            return;
        }
        BlockState previous = NbtSerde.readBlockState(level, before);
        BlockState expected = NbtSerde.readBlockState(level, after);
        if (!simple(previous, before) || !simple(expected, after)
                || present(row.beBefore) || present(row.beAfter)
                || present(row.containerSlotsBefore) || present(row.containerSlotsAfter)) {
            fail(source, "Этой записи нужен откат области. Из строки восстанавливаются простые блоки с полными снимками.");
            return;
        }
        if (previous.equals(expected) || !level.getBlockState(pos).equals(expected)) {
            fail(source, "Текущее состояние отличается от сохранённого результата или изменения нет · " + location);
            return;
        }
        var snapshot = new RollbackEngine.PreparedBlockSnapshot(pos, row.blockBefore, null, null,
                row.type, before, null, null);
        long expires = System.currentTimeMillis() + PREVIEW_TTL_MS;
        PREVIEWS.put(actor, new Plan(row.id, level, expected, snapshot, expires));
        var player = source.getPlayer();
        if (player != null && player.connection.hasChannel(S2CRollbackPreviewPayload.TYPE)) {
            var kind = previous.isAir() ? S2CRollbackPreviewPayload.Kind.REMOVE : S2CRollbackPreviewPayload.Kind.BLOCK;
            PacketDistributor.sendToPlayer(player, new S2CRollbackPreviewPayload(true, Long.toString(row.id), row.dim,
                    pos.getX(), pos.getY(), pos.getZ(), pos.getX(), pos.getY(), pos.getZ(), row.ts, expires,
                    List.of(new S2CRollbackPreviewPayload.Marker(pos, kind, before.getString("Name")))));
        }
        source.sendSystemMessage(Component.literal(summary + "\nОбъём · 1 блок\nБудет восстановлено · "
                + after.getString("Name") + " → " + before.getString("Name") + "\n")
                .append(Component.literal("[Подтвердить откат]").withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/log rollback-entry confirm " + row.id))))
                .append(Component.literal(" · действует 2 минуты")));
    }

    private static boolean simple(BlockState state, CompoundTag saved) {
        return state != null && (state.isAir() || state.getBlock().getClass() == Block.class)
                && state.getFluidState().isEmpty() && !state.hasBlockEntity()
                && saved.equals(NbtUtils.writeBlockState(state));
    }

    private static boolean present(String snapshot) {
        return snapshot != null && !snapshot.isBlank();
    }

    private static int confirm(CommandSourceStack source, ServerPlayer player, long id) {
        var plan = PREVIEWS.get(player.getUUID());
        if (plan == null || plan.id() != id || plan.expiresAt() < System.currentTimeMillis())
            return fail(source, "Нет действующего предпросмотра для записи #" + id + ".");
        if (!LoggerConfig.isEnabled() || RollbackCoordinator.hasActiveRollback() || CartAuditContext.rollbackActive)
            return fail(source, "Logger отключён или уже выполняется откат.");
        PREVIEWS.remove(player.getUUID());
        if (player.connection.hasChannel(S2CRollbackPreviewPayload.TYPE)) PacketDistributor.sendToPlayer(player, S2CRollbackPreviewPayload.clear());
        var pos = plan.snapshot().pos();
        if (!plan.level().hasChunkAt(pos) || !plan.level().getBlockState(pos).equals(plan.expected()))
            return fail(source, "Блок изменился после предпросмотра или чанк выгружен.");
        var report = RollbackEngine.applyPreparedBlockBatch(plan.level(), List.of(plan.snapshot()));
        if (report.applied != 1) return fail(source, "Блок не восстановлен · " + report.skippedReasons);
        source.sendSystemMessage(Component.literal("Блок восстановлен по записи #" + id + ".").withStyle(ChatFormatting.GREEN));
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    public static void clear(UUID actor) {
        REQUESTS.remove(actor);
        PREVIEWS.remove(actor);
    }

    public static void clearAll() {
        REQUESTS.clear();
        PREVIEWS.clear();
    }
}
