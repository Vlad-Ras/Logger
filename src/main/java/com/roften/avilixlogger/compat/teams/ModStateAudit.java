package com.roften.avilixlogger.compat.teams;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;

/** Server-only, bounded mutation scopes; worker closures own only detached evidence. */
public final class ModStateAudit {
    @FunctionalInterface public interface Snapshot { CompoundTag read() throws ReflectiveOperationException; }
    private static final ThreadLocal<Action> ACTIVE = new ThreadLocal<>();
    private ModStateAudit() {}
    public static boolean enabled(MinecraftServer server, ActionType type) {
        if (server == null || !server.isSameThread() || !LoggerConfig.isEnabled() || !CaptureTypePolicy.allows(type, null, null) || CartAuditContext.restoring()) return false;
        return switch (type) {
            case CLAIM_ACTION, PROTECTION_CHANGE, MACHINE_STATE_CHANGE -> LoggerConfig.VALUES.logBlocks.get();
            case MACHINE_CONTENT_CHANGE, CONTAINER_OPEN, GUI_CHANGE, EXPERIENCE_CHANGE -> LoggerConfig.VALUES.logContainers.get();
            case ITEM_TRANSFORM -> LoggerConfig.VALUES.logItemTransactions.get();
            case ENTITY_ATTACK -> LoggerConfig.VALUES.logEntityAttacks.get();
            case CHAT_MESSAGE -> LoggerConfig.VALUES.logChat.get();
            default -> LoggerConfig.VALUES.logEntities.get();
        };
    }
    public static boolean capturing(Object target, String key) {
        for (var current = ACTIVE.get(); current != null; current = current.parent) if (current.target == target && current.key.equals(key)) return true;
        return false;
    }
    public static Action begin(MinecraftServer server, String provider, String operation, Object target, String key, ActionType type, Snapshot snapshot) {
        if (!enabled(server, type) || !CaptureTypePolicy.allows(type, "mod:" + provider + ":", null)) return null;
        for (Action current = ACTIVE.get(); current != null; current = current.parent)
            if (current.target == target && current.key.equals(key)) return null;
        try {
            var action = new Action(server, provider, operation, target, key, type, snapshot, snapshot.read(), ACTIVE.get());
            ACTIVE.set(action); return action;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure(provider); return null; }
    }
    public static void receipt(MinecraftServer server, String provider, String operation, ActionType type, CompoundTag before, CompoundTag after) {
        if (!enabled(server, type) || !CaptureTypePolicy.allows(type, "mod:" + provider + ":", null)) return;
        try { append(server, row(server, provider, operation, type), before, after, false); }
        catch (RuntimeException | LinkageError failure) { failure(provider); }
    }
    private static LogEntry row(MinecraftServer server, String provider, String operation, ActionType type) {
        var row = new LogEntry(); row.ts = System.currentTimeMillis(); row.type = type;
        var cause = CauseContext.peek(); row.actorUuid = cause == null ? null : cause.actorUuid();
        row.actorName = cause == null || cause.actorUuid() == null ? "SYSTEM" : cause.actorName();
        row.dim = server.overworld().dimension().location().toString(); row.source = "mod:" + provider + ":" + operation;
        row.extra = "provider=" + provider + "; operation=" + operation + "; audit only";
        return row;
    }
    private static void append(MinecraftServer server, LogEntry row, CompoundTag before, CompoundTag after, boolean threw) {
        if (after.contains("Dimension", Tag.TAG_STRING)) row.dim = after.getString("Dimension");
        else if (before.contains("Dimension", Tag.TAG_STRING)) row.dim = before.getString("Dimension");
        if (after.contains("X") || before.contains("X")) {
            var location = after.contains("X") ? after : before;
            row.x = location.getInt("X"); row.y = location.getInt("Y"); row.z = location.getInt("Z");
        }
        if (after.contains("ChunkX") || before.contains("ChunkX")) {
            CompoundTag location = after.contains("ChunkX") ? after : before;
            long x = (long)location.getInt("ChunkX") * 16, z = (long)location.getInt("ChunkZ") * 16;
            if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE || z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) return;
            row.x = (int)x; row.z = (int)z;
        }
        if(after.hasUUID("EntityUUID")){row.entityUuid=after.getUUID("EntityUUID");row.entityType=after.getString("EntityType");row.entityName=after.getString("EntityName");}
        var focus=after.getCompound("FocusStack");if(focus.isEmpty())focus=before.getCompound("FocusStack");
        if(!focus.isEmpty()){row.deferSnapshot(LogEntry.SnapshotField.ITEM,focus);row.count=focus.getInt("count");}
        var storage = LoggerRuntime.storage(server.overworld());
        AsyncLogProcessor.submit(PayloadSizeEstimator.estimateTags(before, after), () -> {
            if(before.equals(after))return;
            if (before.contains("BlockState")) row.blockBefore = before.getCompound("BlockState").toString();
            if (after.contains("BlockState")) row.blockAfter = after.getCompound("BlockState").toString();
            if(row.type==ActionType.CLAIM_ACTION)row.extra += "; " + com.roften.avilixlogger.core.ModAuditDetails.claimSummary(before,after);
            if(after.contains("Block",Tag.TAG_STRING))row.extra += "; block="+after.getString("Block");
            if(after.contains("StorageOperation",Tag.TAG_COMPOUND))row.extra += "; "+com.roften.avilixlogger.core.ModAuditDetails.storageSummary(after.getCompound("StorageOperation"));
            var changed = new java.util.TreeSet<String>(); changed.addAll(before.getAllKeys()); changed.addAll(after.getAllKeys());
            changed.removeIf(key -> java.util.Objects.equals(before.get(key), after.get(key)));
            row.extra += "; changed=" + String.join(",", changed);
            var differences = new ListTag(); collectDifferences(before, after, "", 0, differences);
            var evidence = after.copy();
            evidence.put("Changes", differences); evidence.putBoolean("ChangesMayBeTruncated", differences.size() == 256);
            for (int i = 0; i < Math.min(6, differences.size()); i++) {
                var difference = differences.getCompound(i);
                row.extra += "; " + difference.getString("Path") + ": " + label(difference.get("Before")) + " -> " + label(difference.get("After"));
            }
            if (after.contains("MessageText")) row.extra += "; text=" + after.getString("MessageText");
            for (String key : new String[]{"PartyId", "TeamId", "OwnerUUID", "PlayerUUID", "SubId", "Option", "GroupId", "ChunkX", "ChunkZ"}) {
                Tag value = after.contains(key) ? after.get(key) : before.get(key);
                if (value != null) row.extra += "; " + key + "=" + value.getAsString();
            }
            evidence.putBoolean("NativeThrew", threw || after.getBoolean("NativeThrew")); if (threw || after.getBoolean("NativeThrew")) row.extra += "; NativeThrew";
            row.extra="ui="+com.roften.avilixlogger.core.ModAuditDetails.summary(row.type,before,after,differences.size())+"|audit|"+row.extra;
            row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE, before); row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, evidence); storage.append(row);
        });
    }
    private static void collectDifferences(Tag before, Tag after, String path, int depth, ListTag changes) {
        if (java.util.Objects.equals(before, after) || changes.size() >= 256) return;
        if (depth < 16 && before instanceof CompoundTag first && after instanceof CompoundTag last) {
            var keys = new java.util.TreeSet<String>(); keys.addAll(first.getAllKeys()); keys.addAll(last.getAllKeys());
            for (String key : keys) { if (changes.size() >= 256) break; collectDifferences(first.get(key), last.get(key), path.isEmpty() ? key : path + "." + key, depth + 1, changes); }
            return;
        }
        if(depth < 16 && before instanceof ListTag first && after instanceof ListTag last) {
            for(int i=0;i<Math.max(first.size(),last.size()) && changes.size()<256;i++)
                collectDifferences(i<first.size()?first.get(i):null,i<last.size()?last.get(i):null,path+"["+i+"]",depth+1,changes);
            return;
        }
        var change = new CompoundTag(); change.putString("Path", path); change.putBoolean("BeforePresent", before != null); change.putBoolean("AfterPresent", after != null);
        if (before != null) change.put("Before", before); if (after != null) change.put("After", after); changes.add(change);
    }
    private static String label(Tag value) {
        if (value == null) return "absent";
        if (value instanceof ListTag list) return "list[" + list.size() + "]";
        if (value instanceof CompoundTag compound) {
            if(compound.contains("Stack"))return label(compound.get("Stack"));
            if(compound.contains("id",Tag.TAG_STRING))return compound.getString("id")+(compound.contains("count")?" x"+compound.getInt("count"):compound.contains("amount")?" "+compound.getInt("amount")+"mB":"");
            return "state["+compound.size()+"]";
        }
        String text = value.getAsString().replace('\n', ' ').replace('\r', ' ');
        return text.length() > 120 ? text.substring(0, 120) + "…" : text;
    }
    private static void failure(String provider) { AdaptiveLogDiagnostics.adapterFailure(provider + "-state"); }
    public static final class Action {
        private final MinecraftServer server;
        private final Object target; private final String key;
        private final LogEntry row; private final Action parent;
        private final Snapshot snapshot; private final CompoundTag before;
        private boolean done;
        private Action(MinecraftServer server, String provider, String operation, Object target, String key, ActionType type, Snapshot snapshot, CompoundTag before, Action parent) {
            this.server = server; this.target = target; this.key = key; this.snapshot = snapshot; this.before = before; this.parent = parent;
            row = row(server, provider, operation, type);
        }
        public void finish(boolean threw) {
            if (done) return; done = true;
            if (ACTIVE.get() == this) { if (parent == null) ACTIVE.remove(); else ACTIVE.set(parent); }
            if (!enabled(server, row.type)) return;
            try { var after = snapshot.read(); append(server, row, before, after, threw); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure(row.source); }
        }
    }
}
