package com.roften.avilixlogger.compat.opac;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.compat.teams.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.fml.ModList;
import java.util.*;
import java.util.stream.Stream;

/** Actual per-chunk/config/party mutations, without loading data or enumerating the server. */
public final class OpenPacStateAudit {
    private static volatile boolean available;
    private static Class<?> serverImpersonation;
    private OpenPacStateAudit() {}
    public static boolean available() { return available; }
    public static void install() {
        var mod = ModList.get().getModContainerById("openpartiesandclaims"); if (mod.isEmpty()) return;
        try {
            if (!mod.get().getModInfo().getVersion().toString().equals("0.33.0")) throw new IllegalStateException("Unsupported OPAC version");
            var manager = Class.forName("xaero.pac.common.server.claims.ServerClaimsManager", false, OpenPacStateAudit.class.getClassLoader());
            if (!OpenPacMutationHooks.class.isAssignableFrom(manager)) throw new IllegalStateException("OPAC mutation hooks unavailable");
            serverImpersonation = Class.forName("xaero.pac.common.server.claims.player.impersonation.ServerPlayerClaimImpersonationInfo", false, OpenPacStateAudit.class.getClassLoader());
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { AvilixLoggerMod.LOGGER.warn("OPAC state audit disabled", failure); }
    }
    public static void bindGroup(Object group) {
        try { if (StateAccess.field(group, "data") instanceof OpenPacGroupOwner bound) bound.avilixlogger$group(group); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("group-owner"); }
    }
    public static ModStateAudit.Action groupData(Object data, String operation) {
        return data instanceof OpenPacGroupOwner bound && bound.avilixlogger$group() != null ? group(bound.avilixlogger$group(), false, operation) : null;
    }
    public static ModStateAudit.Action impersonation(Object info, String operation) {
        if (!available || !serverImpersonation.isInstance(info)) return null;
        try {
            Object serverData = StateAccess.field(info, "serverData");
            var server = (MinecraftServer)StateAccess.call(serverData, "getServer");
            return ModStateAudit.begin(server, "openpartiesandclaims", operation, info, "impersonation", ActionType.PROTECTION_CHANGE, () -> {
                var tag = new CompoundTag(); tag.put("PerformedAsUUID", StateAccess.encode(StateAccess.field(info, "playerId")));
                var subs = new CompoundTag(); var entries = (Map<?, ?>)StateAccess.field(info, "subIndices");
                if (entries.size() > 4096) throw new IllegalStateException("Impersonation mode budget");
                for (var entry : entries.entrySet()) subs.put((String)StateAccess.call(entry.getKey(), "getId"), StateAccess.encode(entry.getValue()));
                tag.put("SubIndices", subs); return tag;
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("impersonation-before"); return null; }
    }
    private static MinecraftServer server(Object manager) throws ReflectiveOperationException { return (MinecraftServer) StateAccess.call(manager, "getServer"); }
    private static boolean loaded(Object manager) throws ReflectiveOperationException { return Boolean.TRUE.equals(StateAccess.call(manager, "isLoaded")); }
    private static void failure(String path) { AdaptiveLogDiagnostics.adapterFailure("opac-" + path); }
    public static ModStateAudit.Action claim(Object manager, ResourceLocation dimension, int x, int z, String operation) {
        if (!available) return null;
        try {
            if (!loaded(manager)) return null;
            var server = server(manager);
            return ModStateAudit.begin(server, "openpartiesandclaims", operation, manager, dimension + ":" + x + ":" + z, ActionType.CLAIM_ACTION,
                    () -> claimSnapshot(manager, dimension, x, z));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("claim-before"); return null; }
    }
    private static CompoundTag claimSnapshot(Object manager, ResourceLocation dimension, int x, int z) throws ReflectiveOperationException {
        var tag = new CompoundTag(); tag.putString("Provider", "openpartiesandclaims"); tag.putString("Dimension", dimension.toString());
        tag.putInt("ChunkX", x); tag.putInt("ChunkZ", z);
        Object claim = StateAccess.call(manager, "get", new Class<?>[]{ResourceLocation.class, int.class, int.class}, dimension, x, z);
        tag.putBoolean("Claimed", claim != null);
        if (claim != null) {
            tag.putString("OwnerUUID", StateAccess.call(claim, "getPlayerId").toString());
            tag.putInt("SubConfigIndex", (int)StateAccess.call(claim, "getSubConfigIndex"));
            tag.putBoolean("Forceloadable", (boolean)StateAccess.call(claim, "isForceloadable"));
        }
        return tag;
    }
    public static ModStateAudit.Action anchor(Object dimensionClaims, ChunkPos pos, String operation) {
        if (!available) return null;
        try {
            Object manager = StateAccess.field(dimensionClaims, "claimsManager"); if (!(manager instanceof OpenPacMutationHooks) || !loaded(manager)) return null;
            return ModStateAudit.begin(server(manager), "openpartiesandclaims", operation, dimensionClaims, "anchor:" + pos.x + ":" + pos.z, ActionType.CLAIM_ACTION, () -> {
                ResourceLocation dimension = (ResourceLocation)StateAccess.field(dimensionClaims, "dimension");
                var tag = claimSnapshot(manager, dimension, pos.x, pos.z);
                tag.putString("AnchorOwnerUUID", StateAccess.field(dimensionClaims, "playerId").toString());
                tag.putBoolean("Anchor", ((Map<?, ?>)StateAccess.field(dimensionClaims, "anchors")).containsKey(pos)); return tag;
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("anchor-before"); return null; }
    }
    private static CompoundTag configIdentity(Object config) throws ReflectiveOperationException {
        var tag = new CompoundTag(); tag.putString("Provider", "openpartiesandclaims");
        tag.put("PlayerUUID", StateAccess.encode(StateAccess.call(config, "getPlayerId")));
        tag.putString("ConfigType", ((Enum<?>)StateAccess.call(config, "getType")).name());
        tag.put("SubId", StateAccess.encode(StateAccess.call(config, "getSubId"))); tag.putInt("SubIndex", (int)StateAccess.call(config, "getSubIndex")); return tag;
    }
    private static CompoundTag configSnapshot(Object config) throws ReflectiveOperationException {
        var tag = configIdentity(config); tag.put("Storage", StateAccess.encode(StateAccess.field(config, "storage")));
        var subs = new CompoundTag(); var map = (Map<?, ?>)StateAccess.field(config, "subByID");
        if (map.size() > 4096) throw new IllegalStateException("Subconfig audit budget");
        for (var entry : map.entrySet()) {
            var sub = configIdentity(entry.getValue()); sub.put("Storage", StateAccess.encode(StateAccess.field(entry.getValue(), "storage"))); subs.put((String)entry.getKey(), sub);
        }
        tag.put("SubConfigs", subs); return tag;
    }
    public static ModStateAudit.Action storage(Object config) {
        if (!available) return null;
        try { if (StateAccess.field(config, "storage") == null) return null; }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("storage-read"); return null; }
        return config(config, "replace-config-storage");
    }
    public static ModStateAudit.Action config(Object config, String operation) {
        if (!available) return null;
        try {
            Object manager = StateAccess.field(config, "manager"); if (!loaded(manager)) return null;
            return ModStateAudit.begin(server(manager), "openpartiesandclaims", operation, config, "config", ActionType.PROTECTION_CHANGE, () -> configSnapshot(config));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("config-before"); return null; }
    }
    public static ModStateAudit.Action configManager(Object manager, String field) {
        if (!available) return null;
        try {
            if (!loaded(manager)) return null;
            return ModStateAudit.begin(server(manager), "openpartiesandclaims", "replace-" + field, manager, field, ActionType.PROTECTION_CHANGE, () -> {
                var tag = new CompoundTag(); tag.putString("ConfigSlot", field); Object config = StateAccess.field(manager, field);
                tag.put("Config", config == null ? StateAccess.encode(null) : configSnapshot(config)); return tag;
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("config-manager"); return null; }
    }
    public static ModStateAudit.Action option(Object config, Object option, String operation) {
        if (!available) return null;
        try {
            Object manager = StateAccess.field(config, "manager"); if (!loaded(manager)) return null;
            String id = (String)StateAccess.call(option, "getId");
            return ModStateAudit.begin(server(manager), "openpartiesandclaims", operation, config, "config", ActionType.PROTECTION_CHANGE, () -> {
                var tag = configSnapshot(config); tag.putString("Option", id); return tag;
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("option-before"); return null; }
    }
    public static ModStateAudit.Action group(Object groupOrManager, boolean managerGroup, String operation) {
        if (!available) return null;
        try {
            Object config = managerGroup ? StateAccess.field(groupOrManager, "config") : StateAccess.field(groupOrManager, "storageConfig");
            Object groupRegistry = managerGroup ? groupOrManager : StateAccess.field(config, "playerGroups");
            if (groupRegistry == null || !loaded(groupRegistry)) return null;
            Object manager = StateAccess.field(config, "manager"); if (!loaded(manager)) return null;
            return ModStateAudit.begin(server(manager), "openpartiesandclaims", operation, groupOrManager, "groups", ActionType.PROTECTION_CHANGE, () -> {
                var tag = configIdentity(config);
                if (managerGroup) {
                    var groups = new CompoundTag(); var values = (Iterable<?>)StateAccess.call(groupOrManager, "getAllCustom"); int count = 0;
                    for (Object group : values) { if (++count > 4096) throw new IllegalStateException("Group audit budget"); groups.put((String)StateAccess.call(group, "getId"), groupSnapshot(group)); }
                    tag.put("Groups", groups);
                } else tag.merge(groupSnapshot(groupOrManager));
                return tag;
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("group-before"); return null; }
    }
    private static CompoundTag groupSnapshot(Object group) throws ReflectiveOperationException {
        var tag = new CompoundTag(); tag.putString("GroupId", (String)StateAccess.call(group, "getId"));
        tag.put("IncludedGroups", StateAccess.encode(StateAccess.call(group, "getDirectGroupIds")));
        var members = new ArrayList<CompoundTag>();
        for (Object member : (Set<?>)StateAccess.call(group, "getDirectMembersInternal")) {
            if (members.size() >= 4096) throw new IllegalStateException("Group member budget");
            var entry = new CompoundTag(); entry.put("UUID", StateAccess.encode(StateAccess.call(member, "getId"))); entry.put("Name", StateAccess.encode(StateAccess.call(member, "getDisplayName"))); members.add(entry);
        }
        members.sort(Comparator.comparing(CompoundTag::toString)); var list = new ListTag(); list.addAll(members); tag.put("Members", list); return tag;
    }
    private static CompoundTag partySnapshot(Object manager, Object party) throws ReflectiveOperationException {
        var tag = new CompoundTag(); UUID id = (UUID)StateAccess.call(party, "getId"); tag.putString("PartyId", id.toString()); tag.putString("Provider", "openpartiesandclaims");
        tag.putBoolean("Registered", StateAccess.call(manager, "getPartyById", new Class<?>[]{UUID.class}, id) == party);
        Object owner = StateAccess.call(party, "getOwner"); tag.putString("OwnerUUID", StateAccess.call(owner, "getUUID").toString());
        for (String method : new String[]{"getTypedMemberInfoStream", "getTypedInvitedPlayersStream", "getTypedAllyPartiesStream"}) {
            var entries = new ArrayList<CompoundTag>();
            try (var stream = (Stream<?>)StateAccess.call(party, method)) {
                var iterator = stream.limit(4097).iterator();
                while (iterator.hasNext()) {
                    if (entries.size() >= 4096) throw new IllegalStateException("Party audit budget");
                    Object item = iterator.next(); var entry = new CompoundTag();
                    if (method.equals("getTypedAllyPartiesStream")) entry.putString("PartyId", StateAccess.call(item, "getPartyId").toString());
                    else {
                        entry.putString("UUID", StateAccess.call(item, "getUUID").toString()); entry.put("Name", StateAccess.encode(StateAccess.call(item, "getUsername")));
                        if (method.equals("getTypedMemberInfoStream")) entry.putString("Rank", ((Enum<?>)StateAccess.call(item, "getRank")).name());
                    }
                    entries.add(entry);
                }
            }
            var indexed = new CompoundTag();
            for (var entry : entries) indexed.put(entry.contains("UUID") ? entry.getString("UUID") : entry.getString("PartyId"), entry);
            tag.put(method, indexed);
        }
        return tag;
    }
    public static ModStateAudit.Action party(Object party, String operation) {
        if (!available) return null;
        try {
            Object manager = StateAccess.field(party, "managedBy"); if (manager == null || !loaded(manager)) return null;
            return ModStateAudit.begin(server(manager), "openpartiesandclaims", operation, party, "party", ActionType.PARTY_CHANGE, () -> partySnapshot(manager, party));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("party-before"); return null; }
    }
    public static ModStateAudit.Action lifecycle(Object manager, Object party, String operation) {
        if (!available || party == null) return null;
        try {
            if (!loaded(manager)) return null;
            return ModStateAudit.begin(server(manager), "openpartiesandclaims", operation, party, "party", ActionType.PARTY_CHANGE, () -> partySnapshot(manager, party));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("party-lifecycle"); return null; }
    }
    public static ModStateAudit.Action modes(Object data, String operation) {
        if (!available) return null;
        try {
            ServerPlayer player = (ServerPlayer)StateAccess.field(data, "player"); if (player == null) return null;
            return ModStateAudit.begin(player.server, "openpartiesandclaims", operation, data, "modes", ActionType.PROTECTION_CHANGE, () -> {
                var tag = new CompoundTag(); tag.putString("Provider", "openpartiesandclaims"); tag.putString("PlayerUUID", player.getUUID().toString());
                for (String field : new String[]{"claimsModeratorMode", "claimsAdminMode", "claimsNonallyMode", "partiesAdminMode", "partiesImpersonatedPlayerProfile"}) {
                    Object value = StateAccess.field(data, field);
                    if (value instanceof com.mojang.authlib.GameProfile profile) { var p = new CompoundTag(); p.putString("UUID", profile.getId().toString()); p.put("Name", StateAccess.encode(profile.getName())); tag.put(field, p); }
                    else tag.put(field, StateAccess.encode(value));
                }
                Object mode = StateAccess.field(data, "claimingMode"); tag.put("ClaimingMode", mode == null ? StateAccess.encode(null) : StateAccess.encode(StateAccess.call(mode, "getId"))); return tag;
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("modes-before"); return null; }
    }
}
