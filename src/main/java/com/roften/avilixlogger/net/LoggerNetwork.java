package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.core.ChatLogPager;
import com.roften.avilixlogger.core.LastQueryManager;
import com.roften.avilixlogger.core.LogEntry;
import com.roften.avilixlogger.core.LogQuery;
import com.roften.avilixlogger.core.LoggerRuntime;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NeoForge (1.21+) payload-based networking for optional client GUI.
 *
 * Design goal:
 * - If the player has the client mod: /log gui opens an in-game screen and pulls log pages from server.
 * - If not: everything still works via chat commands. No disconnects.
 */
public final class LoggerNetwork {
    private LoggerNetwork() {}

    /** Tracks which players actually have the client mod. */
    private static final Map<UUID, Boolean> CLIENT_PRESENT = new ConcurrentHashMap<>();

    /** Per-player GUI filter state (button-driven). */
    private static final Map<UUID, GuiFilters> GUI_FILTERS = new ConcurrentHashMap<>();

    public static boolean isClientPresent(ServerPlayer player) {
        return player != null && Boolean.TRUE.equals(CLIENT_PRESENT.get(player.getUUID()));
    }

    public static void clear(ServerPlayer player) {
        if (player != null) {
            CLIENT_PRESENT.remove(player.getUUID());
            GUI_FILTERS.remove(player.getUUID());
        }
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // IMPORTANT: client is optional. Channels must be OPTIONAL, иначе кикает клиентов без мода.
        PayloadRegistrar r = event.registrar(AvilixLoggerMod.MOD_ID);

        // Try to set network version if API supports it
        try {
            Object rr = r.getClass().getMethod("versioned", String.class).invoke(r, "1");
            if (rr instanceof PayloadRegistrar pr) r = pr;
        } catch (Throwable ignored) {}

        // Mark as optional if API supports it
        try {
            Object rr = r.getClass().getMethod("optional").invoke(r);
            if (rr instanceof PayloadRegistrar pr) r = pr;
        } catch (Throwable ignored) {}

        // Client -> Server
        r.playToServer(C2SHelloPayload.TYPE, C2SHelloPayload.STREAM_CODEC, LoggerNetwork::handleHello);
        r.playToServer(C2SOpenGuiPayload.TYPE, C2SOpenGuiPayload.STREAM_CODEC, LoggerNetwork::handleOpenGuiRequest);
        r.playToServer(C2SRequestPagePayload.TYPE, C2SRequestPagePayload.STREAM_CODEC, LoggerNetwork::handleRequestPage);
        r.playToServer(C2SRequestDetailsPayload.TYPE, C2SRequestDetailsPayload.STREAM_CODEC, LoggerNetwork::handleRequestDetails);

        // Server -> Client (отправляются только тем, у кого есть клиент-мод; запрос приходит с клиента)
        r.playToClient(S2COpenGuiPayload.TYPE, S2COpenGuiPayload.STREAM_CODEC, LoggerNetwork::handleOpenGuiClient);
        r.playToClient(S2CLogPagePayload.TYPE, S2CLogPagePayload.STREAM_CODEC, LoggerNetwork::handleLogPageClient);
        r.playToClient(S2CLogDetailsPayload.TYPE, S2CLogDetailsPayload.STREAM_CODEC, LoggerNetwork::handleDetailsClient);
    }

    // -------- client handlers (reflection-dispatched) --------

    private static void handleOpenGuiClient(S2COpenGuiPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> invokeClientHook("open", new Class<?>[0], new Object[0]));
    }

    private static void handleLogPageClient(S2CLogPagePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> invokeClientHook("acceptPage", new Class<?>[] { S2CLogPagePayload.class }, new Object[] { payload }));
    }

    private static void handleDetailsClient(S2CLogDetailsPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> invokeClientHook("acceptDetails", new Class<?>[] { S2CLogDetailsPayload.class }, new Object[] { payload }));
    }

    private static void invokeClientHook(String method, Class<?>[] sig, Object[] args) {
        try {
            // Avoid classloading on dedicated server.
            Class<?> c = Class.forName("com.roften.avilixlogger.client.gui.LogViewerClientHooks");
            c.getMethod(method, sig).invoke(null, args);
        } catch (Throwable ignored) {
            // No client mod / wrong side / or a mismatched version — GUI just won't be available.
        }
    }

    // -------- handlers --------

    private static void handleHello(C2SHelloPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) {
                CLIENT_PRESENT.put(sp.getUUID(), true);
            }
        });
    }

    private static void handleOpenGuiRequest(C2SOpenGuiPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!Boolean.TRUE.equals(CLIENT_PRESENT.get(sp.getUUID()))) return; // client addon absent
            if (!hasGuiPermission(sp)) return;
            PacketDistributor.sendToPlayer(sp, new S2COpenGuiPayload());
        });
    }

    private static void handleRequestPage(C2SRequestPagePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            if (!hasGuiPermission(sp)) {
                PacketDistributor.sendToPlayer(sp, new S2CLogPagePayload("Logger", 1, false, false,
                        List.of(new LogRow(0L, sp.serverLevel().dimension().location().toString(), 0, 0, 0,
                                Component.literal("Нет прав на GUI (avilixlogger.gui)").withStyle(net.minecraft.ChatFormatting.RED), false, new long[0]))));
                return;
            }

            GuiFilters gf = payload.filters() == null ? GuiFilters.DEFAULT : payload.filters();
            GuiFilters prevGf = GUI_FILTERS.get(sp.getUUID());

            // Build (or reuse) a base query. If filters changed, restart pagination.
            LastQueryManager.State st = LastQueryManager.get(sp);
            if (st == null || prevGf == null || !prevGf.equals(gf) || payload.nav() == C2SRequestPagePayload.Nav.FIRST) {
                LogQuery q = buildBaseGuiQuery(sp, gf);
                st = LastQueryManager.set(sp, q, "Логи (GUI)");
                GUI_FILTERS.put(sp.getUUID(), gf);
            }

            // Apply navigation.
            switch (payload.nav()) {
                case NEXT -> {
                    if (st.hasNext() && st.nextCursorCandidate() > 0) {
                        LastQueryManager.next(sp, st.nextCursorCandidate());
                    }
                }
                case PREV -> {
                    if (st.hasPrev()) LastQueryManager.prev(sp);
                }
                case FIRST -> LastQueryManager.first(sp);
            }
            st = LastQueryManager.get(sp);
            if (st == null) return;

            ServerLevel lvl = safeLevel(sp, st.baseQuery.dim);
            if (lvl == null) lvl = sp.serverLevel();

            // Use latest filter state (may have been updated above).
            gf = GUI_FILTERS.getOrDefault(sp.getUUID(), gf);
            Page page = buildPage(lvl, st, payload.aggregated(), gf);
            PacketDistributor.sendToPlayer(sp, new S2CLogPagePayload(page.title, page.pageIndex, page.hasPrev, page.hasNext, page.rows));
        });
    }

    private static void handleRequestDetails(C2SRequestDetailsPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!hasGuiPermission(sp)) return;

            // Resolve level
            ServerLevel lvl = safeLevel(sp, payload.dimHint());
            if (lvl == null) {
                LastQueryManager.State st = LastQueryManager.get(sp);
                lvl = st != null ? safeLevel(sp, st.baseQuery.dim) : null;
            }
            if (lvl == null) lvl = sp.serverLevel();

            List<Component> lines = switch (payload.mode()) {
                case RAW -> buildRawLines(lvl, payload.entryId(), payload.rawIds());
                case DETAILS -> buildDetailsLines(lvl, payload.entryId(), payload.rawIds());
                case JSON -> buildJsonLines(lvl, payload.entryId());
            };
            PacketDistributor.sendToPlayer(sp, new S2CLogDetailsPayload(payload.entryId(), payload.mode(), lines));
        });
    }

    // -------- server-side helpers --------

    private static ServerLevel safeLevel(ServerPlayer sp, String dimId) {
        if (sp == null || sp.server == null || dimId == null || dimId.isBlank()) return null;
        try {
            var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, ResourceLocation.parse(dimId));
            return sp.server.getLevel(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LogQuery buildBaseGuiQuery(ServerPlayer sp, GuiFilters gf) {
        LogQuery q = new LogQuery();
        ServerLevel level = sp.serverLevel();
        q.dim = level.dimension().location().toString();

        long now = System.currentTimeMillis();
        q.untilTs = now;

        // Time: custom minutes override presets.
        int customTime = gf != null ? gf.customTimeMinutes() : -1;
        if (customTime >= 0) {
            q.sinceTs = now - (customTime * 60_000L);
        } else {
            // Presets (minutes): 5m, 30m, 2h, 1d, 7d
            int[] mins = new int[] { 5, 30, 120, 1440, 10080 };
            int tidx = gf != null ? gf.timePresetIdx() : 1;
            if (tidx < 0 || tidx >= mins.length) tidx = 1;
            q.sinceTs = now - (mins[tidx] * 60_000L);
        }

        // Radius: custom blocks override presets. Radius=0 means WORLD.
        int r;
        int customRadius = gf != null ? gf.customRadiusBlocks() : -1;
        if (customRadius >= 0) {
            r = customRadius;
        } else {
            // Presets (blocks): 5, 20, 50, WORLD
            int[] radii = new int[] { 5, 20, 50, 0 };
            int ridx = gf != null ? gf.radiusPresetIdx() : 1;
            if (ridx < 0 || ridx >= radii.length) ridx = 1;
            r = radii[ridx];
        }
        if (r > 0) {
            q.minPos = sp.blockPosition().offset(-r, -r, -r);
            q.maxPos = sp.blockPosition().offset(r, r, r);
        } else {
            q.minPos = null;
            q.maxPos = null;
        }

        // Actor
        if (gf != null && gf.actor() != null && !gf.actor().isBlank()) {
            q.actorName = gf.actor().trim();
        }

        // Planes: reuse gf.train() as an OWNER filter (like AirPlanesLogger lookup)
        if (gf != null && gf.typePresetIdx() == 8 && gf.train() != null && !gf.train().isBlank()) {
            q.owner = gf.train().trim();
        }

        // Types (preset index)
        q.types = mapTypePreset(gf != null ? gf.typePresetIdx() : 0);

        // GUI has its own page size; keep chat page size independent.
        q.limit = Math.max(1, com.roften.avilixlogger.LoggerConfig.VALUES.guiPageSize.get());
        return q;
    }

    private static java.util.EnumSet<com.roften.avilixlogger.core.ActionType> mapTypePreset(int presetIdx) {
        // 0: ALL
        if (presetIdx <= 0) return null;
        return switch (presetIdx) {
            case 1 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.BLOCK_BREAK,
                    com.roften.avilixlogger.core.ActionType.BLOCK_PLACE,
                    com.roften.avilixlogger.core.ActionType.BLOCK_INTERACT,
                    com.roften.avilixlogger.core.ActionType.BLOCK_ENTITY_NBT_CHANGE
            );
            case 2 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.CONTAINER_OPEN,
                    com.roften.avilixlogger.core.ActionType.ENTITY_CONTAINER_OPEN,
                    com.roften.avilixlogger.core.ActionType.CONTAINER_PUT,
                    com.roften.avilixlogger.core.ActionType.CONTAINER_TAKE,
                    com.roften.avilixlogger.core.ActionType.BLOCK_ENTITY_NBT_CHANGE
            );
            case 3 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.ENTITY_SPAWN,
                    com.roften.avilixlogger.core.ActionType.ENTITY_DEATH,
                    com.roften.avilixlogger.core.ActionType.ENTITY_MOUNT,
                    com.roften.avilixlogger.core.ActionType.ENTITY_DISMOUNT,
                    com.roften.avilixlogger.core.ActionType.PLAYER_DEATH
            );
            case 4 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.ITEM_DROP,
                    com.roften.avilixlogger.core.ActionType.ITEM_PICKUP,
                    com.roften.avilixlogger.core.ActionType.ITEM_CRAFT,
                    com.roften.avilixlogger.core.ActionType.ITEM_SMELT
            );
            // 5: TRAINS, 6: CANNON — we still keep a broad type set and then refine with extra filtering.
            case 5 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.BLOCK_INTERACT,
                    com.roften.avilixlogger.core.ActionType.BLOCK_ENTITY_NBT_CHANGE,
                    com.roften.avilixlogger.core.ActionType.ENTITY_SPAWN,
                    com.roften.avilixlogger.core.ActionType.ENTITY_DEATH
            );
            case 6 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.BLOCK_PLACE,
                    com.roften.avilixlogger.core.ActionType.BLOCK_INTERACT,
                    com.roften.avilixlogger.core.ActionType.BLOCK_ENTITY_NBT_CHANGE
            );
            case 7 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.CHAT_MESSAGE
            );
            case 8 -> java.util.EnumSet.of(
                    // Dedicated plane actions
                    com.roften.avilixlogger.core.ActionType.PLANE_PLACE,
                    com.roften.avilixlogger.core.ActionType.PLANE_REMOVE,
                    com.roften.avilixlogger.core.ActionType.PLANE_MOUNT,
                    com.roften.avilixlogger.core.ActionType.PLANE_PICKUP,
                    com.roften.avilixlogger.core.ActionType.ENTITY_OWNER_SET,
                    // Plus generic entity lifecycle, refined to "only planes" by GUI extra filtering.
                    com.roften.avilixlogger.core.ActionType.ENTITY_SPAWN,
                    com.roften.avilixlogger.core.ActionType.ENTITY_DEATH,
                    com.roften.avilixlogger.core.ActionType.ENTITY_MOUNT,
                    com.roften.avilixlogger.core.ActionType.ENTITY_DISMOUNT
            );
            default -> null;
        };
    }

    /**
     * Produces a page similarly to {@link ChatLogPager} but returns components instead of sending to chat.
     */
    private static Page buildPage(ServerLevel level, LastQueryManager.State state, boolean aggregated, GuiFilters gf) {
        int size = Math.max(1, com.roften.avilixlogger.LoggerConfig.VALUES.guiPageSize.get());

        LogQuery q = state.baseQuery.copy();
        q.beforeId = state.currentBeforeId();

        // GUI can aggregate spammy sequences (drops/places/container click-spam). To avoid returning
        // too few rows after aggregation, we overfetch.
        int desired = size + 1;
        int fetchLimit = Math.min(5000, desired * 12);
        if (q.owner != null && !q.owner.isBlank()) fetchLimit = Math.min(8000, desired * 25);
        q.limit = fetchLimit;

        List<LogEntry> raw = LoggerRuntime.storage(level).queryReverse(q);
        if (raw == null) raw = List.of();

        List<LogEntry> filtered = raw;

        // Optional owner post-filter (planes)
        if (q.owner != null && !q.owner.isBlank()) {
            ArrayList<LogEntry> tmp = new ArrayList<>(Math.min(desired, raw.size()));
            for (LogEntry e : raw) {
                if (com.roften.avilixlogger.core.PlaneLogFilters.matchesOwner(e, q.owner)) {
                    tmp.add(e);
                    if (tmp.size() >= desired) break;
                }
            }
            filtered = tmp;
        }

        // GUI-only extra filters that aren't part of LogQuery (train name, cannon, create-train events).
        filtered = applyGuiExtraFilters(filtered, gf, desired);

        boolean hasNext;
        long nextCursorCandidate;
        List<LogRow> rows;
        if (aggregated) {
            // Aggregate ONLY for GUI output. Chat commands remain raw.
            AggregationResult agg = aggregateForGui(level, filtered, size);
            hasNext = agg.hasNext;
            nextCursorCandidate = agg.nextCursorCandidate;
            rows = agg.rows;
        } else {
            // Raw mode (GUI only): just take first pageSize rows.
            List<LogRow> rr = new ArrayList<>(Math.min(size, filtered.size()));
            int n = 0;
            for (LogEntry e : filtered) {
                rr.add(LogRow.single(e.id, e.dim, e.x, e.y, e.z, com.roften.avilixlogger.core.LogText.toChatLine(level, e)));
                nextCursorCandidate = e.id;
                n++;
                if (n >= size) break;
            }
            hasNext = filtered.size() > size;
            nextCursorCandidate = (rr.isEmpty() ? 0L : rr.get(rr.size() - 1).id());
            rows = List.copyOf(rr);
        }

        state.nextCursorCandidate = nextCursorCandidate;
        state.hasNext = hasNext;

        boolean hasPrev = state.cursors.size() > 1;

        List<com.roften.avilixlogger.net.LogRow> outRows = new ArrayList<>();
        if (rows.isEmpty()) {
            outRows.add(new com.roften.avilixlogger.net.LogRow(0L, level.dimension().location().toString(), 0, 0, 0, Component.literal("Нет записей."), false, new long[0]));
        } else {
            outRows.addAll(rows);
        }

        return new Page(state.title == null ? "Логи" : state.title, state.pageIndex(), hasPrev, hasNext, outRows);
    }

    private static List<LogEntry> applyGuiExtraFilters(List<LogEntry> in, GuiFilters gf, int desired) {
        if (in == null || in.isEmpty()) return List.of();
        if (gf == null) return in;

        final String train = gf.train() == null ? "" : gf.train().trim();
        final boolean wantTrainName = !train.isBlank();
        final String planeNeedle = gf.planeName() == null ? "" : gf.planeName().trim();
        final boolean wantPlaneName = !planeNeedle.isBlank();
        final int typePreset = gf.typePresetIdx();
        final boolean wantCreateTrainsOnly = typePreset == 5;
        final boolean wantCannonOnly = typePreset == 6;

        // Planes: filter generic entity events down to plane-related ones.
        final boolean wantPlanesOnly = typePreset == 8;

        if (!wantTrainName && !wantPlaneName && !wantCreateTrainsOnly && !wantCannonOnly && !wantPlanesOnly) return in;

        ArrayList<LogEntry> out = new ArrayList<>(Math.min(desired, in.size()));
        for (LogEntry e : in) {
            String extra = e.extra;
            if (extra == null) extra = "";

            if (wantCannonOnly) {
                // Heuristic: our cannon hooks write marker strings to extra.
                if (!extra.contains("schematic_cannon") && !extra.contains("schematicannon") && !extra.contains("create_cannon")) continue;
            }
            if (wantCreateTrainsOnly) {
                if (!extra.contains("create_train") && !extra.contains("carriage_contraption") && !extra.contains("train")) continue;
            }

            if (wantPlanesOnly) {
                // Always include dedicated plane action types (they're already plane-only).
                boolean dedicatedPlaneAction = e.type == com.roften.avilixlogger.core.ActionType.PLANE_PLACE
                        || e.type == com.roften.avilixlogger.core.ActionType.PLANE_REMOVE
                        || e.type == com.roften.avilixlogger.core.ActionType.PLANE_MOUNT
                        || e.type == com.roften.avilixlogger.core.ActionType.PLANE_PICKUP
                        || e.type == com.roften.avilixlogger.core.ActionType.ENTITY_OWNER_SET;

                if (!dedicatedPlaneAction) {
                    // For generic entity events, require that the entityType/extra looks like a plane/aircraft.
                    String et = e.entityType == null ? "" : e.entityType;
                    String etLower = et.toLowerCase(java.util.Locale.ROOT);
                    String exLower = extra.toLowerCase(java.util.Locale.ROOT);
                    boolean looksPlane = etLower.contains("aircraft") || etLower.contains("airplane") || etLower.contains("plane")
                            || exLower.contains("aircraft") || exLower.contains("airplane") || exLower.contains("plane")
                            || exLower.contains("immersive_aircraft") || etLower.contains("immersive_aircraft");
                    if (!looksPlane) continue;
                }

                // If user typed something into the "owner" box while in plane preset, interpret it as owner needle.
                if (wantTrainName) {
                    if (!com.roften.avilixlogger.core.PlaneLogFilters.matchesOwner(e, train)) continue;
                }
                // Optional plane name filter.
                if (wantPlaneName) {
                    if (!com.roften.avilixlogger.core.PlaneLogFilters.matchesPlaneName(e, planeNeedle)) continue;
                }
            } else {
                // Non-plane presets: keep original meaning of the "train" needle.
                if (wantTrainName) {
                    if (!extra.toLowerCase(java.util.Locale.ROOT).contains(train.toLowerCase(java.util.Locale.ROOT))) continue;
                }
            }
            out.add(e);
            if (out.size() >= desired) break;
        }
        return out;
    }

    private static boolean hasGuiPermission(ServerPlayer sp) {
        try {
            return com.roften.avilixlogger.core.PermissionUtil.has(sp.createCommandSourceStack(), "avilixlogger.gui", 2);
        } catch (Throwable t) {
            return sp.hasPermissions(2);
        }
    }

    private static List<LogEntry> fetchByIdsBestEffort(ServerLevel level, long anchorId, long[] ids) {
        if (level == null) return List.of();
        if (ids == null || ids.length == 0) {
            LogQuery q = new LogQuery();
            q.dim = level.dimension().location().toString();
            q.beforeId = anchorId + 1;
            q.limit = 64;
            List<LogEntry> got = LoggerRuntime.storage(level).queryReverse(q);
            if (got == null) return List.of();
            for (LogEntry e : got) if (e.id == anchorId) return List.of(e);
            return got.isEmpty() ? List.of() : List.of(got.get(0));
        }

        long max = anchorId;
        for (long id : ids) if (id > max) max = id;
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.beforeId = max + 1;
        q.limit = Math.min(2000, Math.max(64, ids.length * 16));
        List<LogEntry> got = LoggerRuntime.storage(level).queryReverse(q);
        if (got == null || got.isEmpty()) return List.of();
        java.util.Set<Long> want = new java.util.HashSet<>();
        for (long id : ids) want.add(id);
        java.util.ArrayList<LogEntry> out = new java.util.ArrayList<>(ids.length);
        for (LogEntry e : got) if (want.contains(e.id)) out.add(e);
        return out;
    }

    private static List<Component> buildRawLines(ServerLevel level, long entryId, long[] rawIds) {
        List<LogEntry> es = fetchByIdsBestEffort(level, entryId, rawIds);
        if (es.isEmpty()) return List.of(Component.literal("(нет данных)").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        List<Component> out = new ArrayList<>(es.size());
        for (LogEntry e : es) {
            out.add(com.roften.avilixlogger.core.LogText.toChatLine(level, e));
        }
        return List.copyOf(out);
    }

    private static List<Component> buildDetailsLines(ServerLevel level, long entryId, long[] rawIds) {
        List<LogEntry> es = fetchByIdsBestEffort(level, entryId, rawIds);
        if (es.isEmpty()) return List.of(Component.literal("(нет данных)").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));

        // If this is a burst (multiple events), include a small timeline section for clarity.
        List<LogEntry> chronological = null;
        if (es.size() > 1) {
            chronological = new ArrayList<>(es);
            chronological.sort(java.util.Comparator.comparingLong(a -> a.ts));
        }

        // If it's a container burst: summarize put/take.
        boolean hasContainer = false;
        java.util.LinkedHashMap<String, ItemAgg> put = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, ItemAgg> take = new java.util.LinkedHashMap<>();
        for (LogEntry e : es) {
            if (e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_PUT || e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_TAKE) {
                hasContainer = true;
                int c = Math.max(1, e.count);
                String key = safeItemKey(e.itemStackNbt);
                Component name = safeItemComponent(level, e.itemStackNbt);
                if (e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_PUT) addAgg(put, key, name, c);
                else addAgg(take, key, name, c);
            }
        }

        // If a single entry with BE snapshots, compute NBT diff.
        LogEntry first = es.get(0);
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Details").withStyle(net.minecraft.ChatFormatting.GOLD));

        if (chronological != null) {
            out.add(Component.literal("Timeline:").withStyle(net.minecraft.ChatFormatting.YELLOW));
            long t0 = chronological.get(0).ts;
            int limit = Math.min(120, chronological.size());
            for (int i = 0; i < limit; i++) {
                LogEntry e = chronological.get(i);
                double dt = (e.ts - t0) / 1000.0;
                String stamp = String.format(java.util.Locale.ROOT, "t+%.1fs ", dt);
                Component shortLine = com.roften.avilixlogger.core.LogText.toChatLine(level, e).copy().withStyle(net.minecraft.ChatFormatting.GRAY);
                out.add(Component.literal(stamp).withStyle(net.minecraft.ChatFormatting.DARK_GRAY).append(shortLine));
            }
            if (chronological.size() > limit) {
                out.add(Component.literal("… +" + (chronological.size() - limit) + " events").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            }
            out.add(Component.literal(" "));
        }

        if (hasContainer) {
            out.add(Component.literal("Изменения контейнера:").withStyle(net.minecraft.ChatFormatting.YELLOW));
            appendSignedList(out, "+ ", put, net.minecraft.ChatFormatting.GREEN);
            appendSignedList(out, "- ", take, net.minecraft.ChatFormatting.RED);
            return List.copyOf(out);
        }

        // If strict slot snapshots exist on the primary entry, build diff from them.
        if (first.containerSlotsBefore != null && first.containerSlotsAfter != null
                && !first.containerSlotsBefore.isBlank() && !first.containerSlotsAfter.isBlank()) {
            var agg = com.roften.avilixlogger.core.ContainerSlotDiffUtil.diffAggregated(first.containerSlotsBefore, first.containerSlotsAfter, level.registryAccess());
            if (agg != null && !agg.isEmpty()) {
                out.add(Component.literal("Изменения контейнера:").withStyle(net.minecraft.ChatFormatting.YELLOW));
                java.util.LinkedHashMap<String, ItemAgg> put2 = new java.util.LinkedHashMap<>();
                java.util.LinkedHashMap<String, ItemAgg> take2 = new java.util.LinkedHashMap<>();
                for (var ent : agg.entrySet()) {
                    int delta = ent.getValue();
                    if (delta == 0) continue;
                    String key = safeItemKey(ent.getKey());
                    Component nm = safeItemComponent(level, ent.getKey());
                    if (delta > 0) addAgg(put2, key, nm, delta);
                    else addAgg(take2, key, nm, Math.abs(delta));
                }
                appendSignedList(out, "+ ", put2, net.minecraft.ChatFormatting.GREEN);
                appendSignedList(out, "- ", take2, net.minecraft.ChatFormatting.RED);
                return List.copyOf(out);
            }
        }

        if (first.beBefore != null && first.beAfter != null && !first.beBefore.isBlank() && !first.beAfter.isBlank()) {
            var deltas = com.roften.avilixlogger.core.InventoryDiffUtil.diff(first.beBefore, first.beAfter, level.registryAccess());
            if (!deltas.isEmpty()) {
                out.add(Component.literal("Diff контейнера (NBT):").withStyle(net.minecraft.ChatFormatting.YELLOW));
                int shown = 0;
                for (var d : deltas) {
                    int dc = d.deltaCount();
                    String name = (d.representative() == null || d.representative().isEmpty()) ? "?" : d.representative().getHoverName().getString();
                    Component ln = Component.literal((dc > 0 ? "+" : "") + dc + " " + name)
                            .withStyle(dc > 0 ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED);
                    out.add(ln);
                    if (++shown >= 200) break;
                }
                return List.copyOf(out);
            }
        }

        // Fallback: show the raw single line as details.
        out.add(com.roften.avilixlogger.core.LogText.toChatLine(level, first).copy().withStyle(net.minecraft.ChatFormatting.GRAY));
        return List.copyOf(out);
    }

    private static List<Component> buildJsonLines(ServerLevel level, long entryId) {
        if (level == null) return List.of(Component.literal("{}").withStyle(net.minecraft.ChatFormatting.GRAY));
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.beforeId = entryId + 1;
        q.limit = 64;
        List<LogEntry> got = LoggerRuntime.storage(level).queryReverse(q);
        if (got == null) got = List.of();
        LogEntry target = null;
        for (LogEntry e : got) if (e.id == entryId) { target = e; break; }
        if (target == null) target = got.isEmpty() ? null : got.get(0);
        if (target == null) return List.of(Component.literal("{}").withStyle(net.minecraft.ChatFormatting.GRAY));

        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", target.id);
        m.put("ts", target.ts);
        m.put("dim", target.dim);
        m.put("x", target.x);
        m.put("y", target.y);
        m.put("z", target.z);
        m.put("type", String.valueOf(target.type));
        m.put("actorUuid", target.actorUuid == null ? null : target.actorUuid.toString());
        m.put("actorName", target.actorName);
        // Target fields are not part of LogEntry in this project; keep entity info instead.
        m.put("entityType", target.entityType);
        m.put("entityUuid", target.entityUuid == null ? null : target.entityUuid.toString());
        m.put("count", target.count);
        m.put("blockBefore", target.blockBefore);
        m.put("blockAfter", target.blockAfter);
        m.put("itemStackNbt", target.itemStackNbt);
        m.put("extra", target.extra);
        m.put("beBefore", target.beBefore);
        m.put("beAfter", target.beAfter);

        String json = com.roften.avilixlogger.core.GzipJson.GSON.toJson(m);
        // Return as a single line component (clipboard-friendly). GUI will copy it.
        return List.of(Component.literal(json).withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    private static void appendSignedList(List<Component> out, String prefix, java.util.LinkedHashMap<String, ItemAgg> map, net.minecraft.ChatFormatting color) {
        if (map == null || map.isEmpty()) return;
        // sort by count desc
        java.util.List<ItemAgg> list = new java.util.ArrayList<>(map.values());
        list.sort((a, b) -> Integer.compare(b.count, a.count));
        for (var it : list) {
            out.add(Component.literal(prefix).withStyle(color)
                    .append(it.name.copy().withStyle(color))
                    .append(Component.literal(" x" + it.count).withStyle(color)));
        }
    }

    private static final class ItemAgg {
        final Component name;
        int count;
        ItemAgg(Component name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    private static void addAgg(java.util.LinkedHashMap<String, ItemAgg> map, String key, Component name, int count) {
        if (map == null) return;
        if (key == null || key.isBlank()) key = "?";
        ItemAgg agg = map.get(key);
        if (agg == null) map.put(key, new ItemAgg(name == null ? Component.literal("?") : name, count));
        else agg.count += count;
    }

    /** Stable aggregation key for itemStackNbt. */
    private static String safeItemKey(String itemSnbtOrId) {
        if (itemSnbtOrId == null) return "?";
        String s = itemSnbtOrId.trim();
        if (s.isEmpty()) return "?";

        // Plain registry id
        if (!s.startsWith("{") && s.contains(":")) return s;

        // Extract id from SNBT (supports both " and ' quoting, and even unquoted forms).
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\bid\\s*:\\s*(?:\\\"|')?([a-z0-9_.-]+:[a-z0-9_./-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(s);
            if (m.find()) {
                String id = m.group(1);
                // keep NBT variants separate (avoid collapsing differently-tagged items)
                if (s.contains("tag:") || s.contains("components:")) id += "#" + Integer.toHexString(s.hashCode());
                return id;
            }
        } catch (Throwable ignored) {
        }

        return s;
    }

    /**
     * Returns a translatable component when possible (so client language applies).
     * Accepts either full ItemStack SNBT or a plain item id (minecraft:stone).
     */
    private static Component safeItemComponent(ServerLevel level, String itemSnbtOrId) {
        try {
            if (itemSnbtOrId == null || itemSnbtOrId.isBlank()) return Component.literal("?");
            String s = itemSnbtOrId.trim();

            // Plain id
            if (!s.startsWith("{") && s.contains(":")) {
                var rl = net.minecraft.resources.ResourceLocation.tryParse(s);
                if (rl != null) {
                    var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
                    if (it != null) return Component.translatable(it.getDescriptionId());
                }
                return Component.literal(s);
            }

            // Full SNBT
            net.minecraft.world.item.ItemStack st = com.roften.avilixlogger.core.NbtSerde.readItemStack(s, level.registryAccess());
            if (st != null && !st.isEmpty()) return st.getHoverName().copy();

            // Last resort: try to extract id from SNBT (supports both " and ' quoting, and unquoted forms).
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\bid\\s*:\\s*(?:\\\"|')?([a-z0-9_.-]+:[a-z0-9_./-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(s);
            if (m.find()) {
                String id = m.group(1);
                var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
                if (rl != null) {
                    var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
                    if (it != null) return Component.translatable(it.getDescriptionId());
                }
                return Component.literal(id);
            }
            return Component.literal("?");
        } catch (Throwable t) {
            return Component.literal("?");
        }
    }

    // -------- GUI-only aggregation --------

    private static final long GUI_BURST_WINDOW_MS = 1200L;
    private static final long GUI_BURST_MAX_MS = 5000L;
    private static final int GUI_INLINE_TOP_N = 5;

    private static AggregationResult aggregateForGui(ServerLevel level, List<LogEntry> in, int pageSize) {
        if (in == null || in.isEmpty()) {
            return new AggregationResult(List.of(), false, 0L);
        }

        List<com.roften.avilixlogger.net.LogRow> out = new ArrayList<>(pageSize);

        // input is newest -> older
        Burst burst = null;
        long nextCursorCandidate = 0L;

        for (int i = 0; i < in.size(); i++) {
            LogEntry e = in.get(i);

            BurstKey key = BurstKey.of(e);
            if (key == null) {
                // flush pending burst
                if (burst != null) {
                    out.add(burst.toRow(level));
                    nextCursorCandidate = burst.oldestId();
                    burst = null;
                    if (out.size() >= pageSize) break;
                }
                out.add(com.roften.avilixlogger.net.LogRow.single(
                        e.id,
                        e.dim,
                        e.x,
                        e.y,
                        e.z,
                        com.roften.avilixlogger.core.LogText.toChatLine(level, e)
                ));
                nextCursorCandidate = e.id;
                if (out.size() >= pageSize) break;
                continue;
            }

            if (burst == null || !burst.canAccept(e, key)) {
                if (burst != null) {
                    out.add(burst.toRow(level));
                    nextCursorCandidate = burst.oldestId();
                    if (out.size() >= pageSize) break;
                }
                burst = new Burst(key, e);
            } else {
                burst.add(e);
            }
        }

        if (out.size() < pageSize && burst != null) {
            out.add(burst.toRow(level));
            nextCursorCandidate = burst.oldestId();
        }

        // if we didn't exhaust the input, there is more to show
        boolean hasNext = false;
        if (out.size() >= pageSize) {
            // We may have cut mid-input; consider that as next page exists if there are remaining rows.
            // Also keep "nextCursorCandidate" as the oldest id we included.
            // If we included exactly pageSize but still have input remaining -> next.
            hasNext = true;
        } else {
            // We used all available input; no next.
            hasNext = false;
        }

        return new AggregationResult(List.copyOf(out), hasNext, nextCursorCandidate);
    }

    private record AggregationResult(List<com.roften.avilixlogger.net.LogRow> rows, boolean hasNext, long nextCursorCandidate) {}

    private enum BurstKind {
        CONTAINER,
        ITEM,
        BLOCK,
    }

    private record BurstKey(BurstKind kind, String dim, java.util.UUID actor, int kx, int kz, com.roften.avilixlogger.core.ActionType type, String itemOrBlockKey) {
        static BurstKey of(LogEntry e) {
            if (e == null || e.type == null) return null;
            if (e.actorUuid == null) return null; // don't aggregate "system"; keep raw
            String dim = e.dim == null ? "" : e.dim;

            // Container: merge PUT+TAKE into one burst for the same container.
            if (e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_PUT || e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_TAKE) {
                return new BurstKey(BurstKind.CONTAINER, dim, e.actorUuid, e.x, e.z, e.type, null);
            }

            // Items
            if (e.type == com.roften.avilixlogger.core.ActionType.ITEM_DROP || e.type == com.roften.avilixlogger.core.ActionType.ITEM_PICKUP) {
                String key = e.itemStackNbt == null ? "" : e.itemStackNbt;
                int cx = e.x >> 4;
                int cz = e.z >> 4;
                return new BurstKey(BurstKind.ITEM, dim, e.actorUuid, cx, cz, e.type, key);
            }

            // Blocks
            if (e.type == com.roften.avilixlogger.core.ActionType.BLOCK_BREAK || e.type == com.roften.avilixlogger.core.ActionType.BLOCK_PLACE) {
                String key = (e.type == com.roften.avilixlogger.core.ActionType.BLOCK_PLACE) ? (e.blockAfter == null ? "" : e.blockAfter)
                        : (e.blockBefore == null ? "" : e.blockBefore);
                int cx = e.x >> 4;
                int cz = e.z >> 4;
                return new BurstKey(BurstKind.BLOCK, dim, e.actorUuid, cx, cz, e.type, key);
            }

            return null;
        }
    }

    private static final class Burst {
        private final BurstKey key;
        private final List<LogEntry> events = new ArrayList<>();
        private long newestTs;
        private long oldestTs;
        private long newestId;
        private long oldestId;

        Burst(BurstKey key, LogEntry first) {
            this.key = key;
            add(first);
        }

        boolean canAccept(LogEntry e, BurstKey k) {
            if (e == null || k == null) return false;
            // Must match aggregation key.
            if (!k.equals(this.key)) return false;
            // Window based on newest event time.
            long dt = this.newestTs - e.ts;
            if (dt < 0) dt = 0;
            return dt <= GUI_BURST_WINDOW_MS && (this.newestTs - this.oldestTs) <= GUI_BURST_MAX_MS;
        }

        void add(LogEntry e) {
            if (e == null) return;
            events.add(e);
            if (events.size() == 1) {
                newestTs = oldestTs = e.ts;
                newestId = oldestId = e.id;
            } else {
                // list is newest->older; we append in that order
                oldestTs = e.ts;
                oldestId = e.id;
            }
        }

        long oldestId() { return oldestId; }

        com.roften.avilixlogger.net.LogRow toRow(ServerLevel level) {
            if (events.size() == 1) {
                LogEntry e = events.get(0);
                return com.roften.avilixlogger.net.LogRow.single(e.id, e.dim, e.x, e.y, e.z, com.roften.avilixlogger.core.LogText.toChatLine(level, e));
            }

            LogEntry first = events.get(0);
            String actor = (first.actorName != null && !first.actorName.isBlank()) ? first.actorName : "?";
            long durMs = Math.max(0L, newestTs - oldestTs);
            String dur = String.format(java.util.Locale.ROOT, "%.1fs", durMs / 1000.0);

            net.minecraft.network.chat.MutableComponent line;

            switch (key.kind) {
                case ITEM -> {
                    int total = 0;
                    java.util.LinkedHashMap<String, ItemAgg> top = new java.util.LinkedHashMap<>();
                    for (LogEntry e : events) {
                        total += Math.max(1, e.count);
                        int c = Math.max(1, e.count);
                        addAgg(top, safeItemKey(e.itemStackNbt), safeItemComponent(level, e.itemStackNbt), c);
                    }
                    String verb = (key.type == com.roften.avilixlogger.core.ActionType.ITEM_DROP) ? "выбросил" : "подобрал";
                    line = Component.literal(actor + " " + verb + " x" + total + " (" + dur + ") ");
                    line = line.append(Component.literal("[")
                            .append(Component.empty().append(summarizeTop(top, GUI_INLINE_TOP_N))
                                    .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY)))
                            .append(Component.literal("]").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY))));
                }
                case BLOCK -> {
                    int total = events.size();
                    String verb = (key.type == com.roften.avilixlogger.core.ActionType.BLOCK_PLACE) ? "поставил" : "сломал";
                    String block = safeBlockName(level, key.itemOrBlockKey);
                    line = Component.literal(actor + " " + verb + " x" + total + " " + block + " (" + dur + ")");
                }
                case CONTAINER -> {
                    java.util.LinkedHashMap<String, ItemAgg> put = new java.util.LinkedHashMap<>();
                    java.util.LinkedHashMap<String, ItemAgg> take = new java.util.LinkedHashMap<>();
                    for (LogEntry e : events) {
                        int c = Math.max(1, e.count);
                        if (e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_PUT) {
                            addAgg(put, safeItemKey(e.itemStackNbt), safeItemComponent(level, e.itemStackNbt), c);
                        } else if (e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_TAKE) {
                            addAgg(take, safeItemKey(e.itemStackNbt), safeItemComponent(level, e.itemStackNbt), c);
                        }
                    }
                    net.minecraft.network.chat.MutableComponent both = Component.empty();
                    if (!put.isEmpty()) {
                        both.append(Component.literal("+").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GREEN)));
                        both.append(Component.empty().append(summarizeTop(put, GUI_INLINE_TOP_N))
                                .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY)));
                    }
                    if (!put.isEmpty() && !take.isEmpty()) both.append(Component.literal(", ").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY)));
                    if (!take.isEmpty()) {
                        both.append(Component.literal("-").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.RED)));
                        both.append(Component.empty().append(summarizeTop(take, GUI_INLINE_TOP_N))
                                .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY)));
                    }

                    line = Component.literal(actor + " изменил контейнер (" + dur + ") ")
                            .append(Component.literal("[").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY)))
                            .append(both)
                            .append(Component.literal("]").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY)));
                }
                default -> {
                    line = Component.literal(actor + " сделал x" + events.size() + " (" + dur + ")");
                }
            }

            long[] rawIds = new long[events.size()];
            for (int i = 0; i < events.size(); i++) rawIds[i] = events.get(i).id;
            return new com.roften.avilixlogger.net.LogRow(first.id, first.dim, first.x, first.y, first.z, line, true, rawIds);
        }

        private static String safeBlockName(ServerLevel level, String blockStateSnbtOrId) {
            try {
                if (blockStateSnbtOrId == null || blockStateSnbtOrId.isBlank()) return "?";
                // Try to extract Name:"minecraft:stone" from BlockState SNBT.
                String s = blockStateSnbtOrId;
                int idx = s.indexOf("Name:\\\"");
                if (idx >= 0) {
                    int q1 = s.indexOf('"', idx + 5);
                    if (q1 >= 0) {
                        int q2 = s.indexOf('"', q1 + 1);
                        if (q2 > q1) {
                            return s.substring(q1 + 1, q2);
                        }
                    }
                }
                // Fallback: already an id.
                return s.length() > 64 ? s.substring(0, 64) : s;
            } catch (Throwable t) {
                return "?";
            }
        }

        private static Component summarizeTop(java.util.LinkedHashMap<String, ItemAgg> map, int topN) {
            if (map.isEmpty()) return Component.empty();
            java.util.List<ItemAgg> list = new java.util.ArrayList<>(map.values());
            list.sort((a, b) -> Integer.compare(b.count, a.count));
            net.minecraft.network.chat.MutableComponent out = Component.empty();
            int shown = 0;
            int rest = 0;
            for (int i = 0; i < list.size(); i++) {
                ItemAgg it = list.get(i);
                if (shown < topN) {
                    if (shown > 0) out.append(Component.literal(", "));
                    out.append(it.name.copy());
                    out.append(Component.literal(" x" + it.count));
                    shown++;
                } else {
                    rest++;
                }
            }
            if (rest > 0) {
                out.append(Component.literal(" +" + rest + " видов"));
            }
            return out;
        }
    }

    private record Page(String title, int pageIndex, boolean hasPrev, boolean hasNext, List<com.roften.avilixlogger.net.LogRow> rows) {}
}
