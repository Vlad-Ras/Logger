package com.roften.avilixlogger.compat.ftbteams;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.compat.teams.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import java.util.*;

/** Server mutations of one existing team at a time. Native save/serialize events are never invoked. */
public final class FtbTeamsAudit {
    private static volatile boolean available;
    private static final ThreadLocal<Object> SENDING = new ThreadLocal<>();
    private FtbTeamsAudit() {}
    public static void install() {
        var mod = ModList.get().getModContainerById("ftbteams"); if (mod.isEmpty()) return;
        try {
            if (!mod.get().getModInfo().getVersion().toString().equals("2101.1.11")) throw new IllegalStateException("Unsupported FTB Teams version");
            var type = Class.forName("dev.ftb.mods.ftbteams.data.AbstractTeam", false, FtbTeamsAudit.class.getClassLoader());
            if (!FtbTeamState.class.isAssignableFrom(type)) throw new IllegalStateException("FTB team hooks unavailable");
            available = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { AvilixLoggerMod.LOGGER.warn("FTB Teams audit disabled", failure); }
    }
    public static void bindProperties(Object team) {
        try { if (StateAccess.field(team, "properties") instanceof FtbPropertyOwner owner) owner.avilixlogger$owner(team); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("property-owner"); }
    }
    public static ModStateAudit.Action properties(Object collection, String operation) {
        return collection instanceof FtbPropertyOwner owner && owner.avilixlogger$owner() != null ? team(owner.avilixlogger$owner(), operation) : null;
    }
    private static void failure(String path) { AdaptiveLogDiagnostics.adapterFailure("ftbteams-" + path); }
    private static Object manager(Object team) throws ReflectiveOperationException { return StateAccess.field(team, "manager"); }
    private static MinecraftServer server(Object team) throws ReflectiveOperationException { return (MinecraftServer)StateAccess.call(manager(team), "getServer"); }
    private static boolean registered(Object team) throws ReflectiveOperationException {
        return ((Map<?, ?>)StateAccess.field(manager(team), "teamMap")).get(StateAccess.field(team, "id")) == team;
    }
    private static CompoundTag state(Object team) throws ReflectiveOperationException {
        var tag = new CompoundTag(); tag.putString("Provider", "ftbteams"); tag.putString("TeamId", StateAccess.field(team, "id").toString());
        tag.putString("TeamType", ((Enum<?>)StateAccess.call(team, "getType")).name()); tag.putBoolean("Registered", registered(team));
        tag.putBoolean("Valid", (boolean)StateAccess.call(team, "isValid")); tag.put("Ranks", StateAccess.encode(StateAccess.field(team, "ranks")));
        Object properties = StateAccess.field(team, "properties");
        int size = (int)StateAccess.call(properties, "size"); if (size > 4096) throw new IllegalStateException("FTB property audit budget");
        var values = (CompoundTag)StateAccess.call(properties, "write", new Class<?>[]{CompoundTag.class}, new CompoundTag());
        tag.put("Properties", values.copy()); tag.put("Extra", ((CompoundTag)StateAccess.field(team, "extraData")).copy());
        tag.putString("OwnerUUID", StateAccess.call(team, "getOwner").toString());
        if (tag.getString("TeamType").equals("PLAYER")) {
            tag.putString("PlayerName", (String)StateAccess.field(team, "playerName")); tag.putBoolean("Online", (boolean)StateAccess.field(team, "online"));
            Object effective = StateAccess.field(team, "effectiveTeam"); if (effective != null) tag.putString("EffectiveTeamId", StateAccess.field(effective, "id").toString());
        }
        if (tag.sizeInBytes() > 4 * 1024 * 1024) throw new IllegalStateException("FTB state audit byte budget");
        return tag;
    }
    private static CompoundTag observedState(Object team) throws ReflectiveOperationException {
        var tag = state(team); ((FtbTeamState)team).avilixlogger$baseline(tag.copy()); return tag;
    }
    public static ModStateAudit.Action team(Object team, String operation) {
        if (!available || !(team instanceof FtbTeamState)) return null;
        try {
            var server = server(team);
            if (!ModStateAudit.enabled(server, ActionType.PARTY_CHANGE) || !registered(team)) return null;
            return ModStateAudit.begin(server, "ftbteams", operation, team, "team", ActionType.PARTY_CHANGE, () -> observedState(team));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("team-before"); return null; }
    }
    /** This fallback observes persisted dirty state, not the editor of a change that may have happened earlier. */
    public static void dirty(Object team) {
        if (!available || !(team instanceof FtbTeamState tracked) || ModStateAudit.capturing(team, "team")) return;
        try {
            var server = server(team); if (!ModStateAudit.enabled(server, ActionType.PARTY_CHANGE) || !registered(team)) { tracked.avilixlogger$baseline(null); return; }
            var previous = tracked.avilixlogger$baseline(); var now = observedState(team);
            if (previous == null || previous.equals(now)) return;
            try (var cause = CauseContext.unattributed()) { ModStateAudit.receipt(server, "ftbteams", "dirty-state-observation", ActionType.PARTY_CHANGE, previous, now); }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("dirty"); }
    }
    public static void created(Object team, boolean threw) {
        if (!available || !(team instanceof FtbTeamState)) return;
        try {
            var server = server(team); if (!ModStateAudit.enabled(server, ActionType.PARTY_CHANGE) || !registered(team)) return;
            var after = observedState(team); after.putBoolean("NativeThrew", threw);
            var before = new CompoundTag(); before.putString("TeamId", after.getString("TeamId")); before.putBoolean("Registered", false);
            ModStateAudit.receipt(server, "ftbteams", "created", ActionType.PARTY_CHANGE, before, after);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("created"); }
    }
    public static void loaded(Object team) {
        if (!available || !(team instanceof FtbTeamState tracked)) return;
        try { if (ModStateAudit.enabled(server(team), ActionType.PARTY_CHANGE)) tracked.avilixlogger$baseline(state(team)); else tracked.avilixlogger$baseline(null); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("load-baseline"); }
    }
    public static ModStateAudit.Action redirect(Object manager, ServerPlayer player) {
        if (!available) return null;
        return ModStateAudit.begin(player.server, "ftbteams", "chat-redirection", manager, "redirect:" + player.getUUID(), ActionType.PARTY_CHANGE, () -> {
            var tag = new CompoundTag(); tag.putString("PlayerUUID", player.getUUID().toString());
            tag.putBoolean("Redirected", ((Set<?>)StateAccess.field(manager, "chatRedirected")).contains(player.getUUID())); return tag;
        });
    }
    public static Runnable packetTask(Object context, Runnable task) {
        if (!available || !com.roften.avilixlogger.LoggerConfig.isEnabled()) return task;
        return () -> {
            ServerPlayer player = null;
            try { Object target = StateAccess.call(context, "getPlayer"); if (target instanceof ServerPlayer p && p.server.isSameThread()) player = p; }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("packet-cause"); }
            try (var cause = player == null ? CauseContext.unattributed() : CauseContext.push(player, CauseContext.Kind.OTHER, player.blockPosition(), ItemStack.EMPTY)) { task.run(); }
        };
    }
    private static CompoundTag history(Object team) throws ReflectiveOperationException {
        var tag = new CompoundTag(); tag.putString("TeamId", StateAccess.field(team, "id").toString());
        var entries = (List<?>)StateAccess.field(team, "messageHistory"); if (entries.size() > 4096) throw new IllegalStateException("Team history audit budget");
        var list = new ListTag(); var server = server(team);
        for (Object entry : entries) {
            var value = new CompoundTag(); value.putString("SenderUUID", StateAccess.call(entry, "sender").toString()); value.putLong("Date", (long)StateAccess.call(entry, "date"));
            value.putString("Json", Component.Serializer.toJson((Component)StateAccess.call(entry, "text"), server.registryAccess())); list.add(value);
        }
        tag.put("Messages", list); return tag;
    }
    public static ModStateAudit.Action historyChange(Object team) {
        if (!available || !(team instanceof FtbTeamState) || SENDING.get() == team) return null;
        try {
            var server = server(team);
            if (!ModStateAudit.enabled(server, ActionType.CHAT_MESSAGE) || !registered(team)) return null;
            return ModStateAudit.begin(server, "ftbteams", "history-change", team, "messages", ActionType.CHAT_MESSAGE, () -> history(team));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("history-before"); return null; }
    }
    public static ChatAction chat(Object team, UUID from, Component text) {
        if (!available || !(team instanceof FtbTeamState) || SENDING.get() == team) return null;
        try {
            var server = server(team); if (!ModStateAudit.enabled(server, ActionType.CHAT_MESSAGE) || !registered(team)) return null;
            return new ChatAction(server, team, from, text);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("chat-before"); return null; }
    }
    public static final class ChatAction {
        private final MinecraftServer server; private final Object team, previous;
        private final CompoundTag message; private boolean done;
        private ChatAction(MinecraftServer server, Object team, UUID from, Component text) throws ReflectiveOperationException {
            this.server = server; this.team = team; previous = SENDING.get(); message = new CompoundTag();
            message.putString("TeamId", StateAccess.field(team, "id").toString()); message.putString("SubmittedSenderUUID", from.toString());
            message.putString("MessageText", text.getString()); message.putString("MessageJson", Component.Serializer.toJson(text, server.registryAccess()));
            SENDING.set(team);
        }
        public void finish(boolean threw) {
            if (done) return; done = true;
            if (SENDING.get() == team) { if (previous == null) SENDING.remove(); else SENDING.set(previous); }
            message.putBoolean("NativeThrew", threw); message.putString("Phase", threw ? "native-send-threw" : "native-send-returned");
            message.putString("Meaning", "native send operation, not client delivery confirmation");
            ModStateAudit.receipt(server, "ftbteams", "team-chat", ActionType.CHAT_MESSAGE, new CompoundTag(), message);
        }
    }
}
