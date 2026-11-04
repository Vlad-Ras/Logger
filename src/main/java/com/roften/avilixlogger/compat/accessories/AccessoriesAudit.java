package com.roften.avilixlogger.compat.accessories;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.lang.reflect.*;
import java.util.*;

/** Native mutation boundaries and the mod's existing previous-item observation, without a new tick. */
public final class AccessoriesAudit {
    private static volatile Api api;
    private static final ThreadLocal<Action> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<HandAction> RETURNING = new ThreadLocal<>();
    private AccessoriesAudit() {}

    public static void install() {
        var info = ModList.get().getModContainerById("accessories");
        if (info.isEmpty()) return;
        if (!info.get().getModInfo().getVersion().toString().equals("1.1.0-beta.54+1.21.1")) {
            AvilixLoggerMod.LOGGER.warn("Accessories audit disabled: unsupported version {}", info.get().getModInfo().getVersion()); return;
        }
        try { api = new Api(); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            AvilixLoggerMod.LOGGER.warn("Accessories audit disabled: incompatible optional API", failure);
        }
    }
    private static boolean enabled(LivingEntity entity) {
        return api != null && entity != null && entity.level() instanceof ServerLevel level && level.getServer().isSameThread()
                && CaptureTypePolicy.enabled(3) && LoggerConfig.isEnabled() && LoggerConfig.VALUES.logEntities.get()
                && !CartAuditContext.restoring();
    }
    private static boolean covered(LivingEntity entity, Object owner) {
        for (var current = ACTIVE.get(); current != null; current = current.parent)
            if (current.entity == entity && (current.owner == null || current.owner == owner)) return true;
        return false;
    }
    public static boolean handles(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        return api != null && api.menu.isInstance(menu);
    }
    public static boolean opened(net.minecraft.server.level.ServerPlayer player, net.minecraft.world.inventory.AbstractContainerMenu menu) {
        if (!handles(menu) || !player.server.isSameThread() || !LoggerConfig.isEnabled() || !LoggerConfig.VALUES.logContainers.get()
                || !CaptureTypePolicy.enabled(2) || CartAuditContext.restoring()) return false;
        try {
            var target = (LivingEntity) api.menuTarget.invoke(menu); if (target == null) target = player;
            var tag = holderSnapshot(api.peekHolder(target), target);
            var row = row(target, "menu-open"); row.type = ActionType.ENTITY_CONTAINER_OPEN;
            row.actorUuid = CauseContext.actorUuid(player); row.actorName = CauseContext.actorName(player);
            row.extra += "; menu=" + menu.getClass().getSimpleName();
            row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, tag); LoggerRuntime.storage(player.serverLevel()).append(row); return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("menu-open"); return false; }
    }
    public static Action menu(net.minecraft.server.level.ServerPlayer player, String source) {
        if (player == null || !handles(player.containerMenu) || !enabled(player)) return null;
        Action action = null;
        try {
            var menu = player.containerMenu;
            var target = (LivingEntity) api.menuTarget.invoke(menu);
            if (target == null) target = player;
            action = entity(target, source);
            if (action != null) {
                action.editor = player; action.menu = menu;
                action.editorSnapshot(action.before);
            }
            return action;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            if (action != null) action.cancel();
            failure("menu-before"); return null;
        }
    }
    public static Action inventory(Object inventory, String source) {
        if (api == null || !api.inventory.isInstance(inventory)) return null;
        try { return container(api.inventoryOwner.get(inventory), source); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("inventory-before"); return null; }
    }
    public static Action container(Object owner, String source) {
        if (api == null || !api.container.isInstance(owner)) return null;
        try {
            var entity = (LivingEntity) api.capEntity.invoke(api.capability.get(owner));
            return begin(entity, owner, null, source);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("container-before"); return null; }
    }
    public static Action capability(Object capability, String source) {
        if (api == null) return null;
        try { return entity((LivingEntity) api.capEntity.invoke(capability), source); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("capability-before"); return null; }
    }
    public static boolean updating(Object owner) {
        if (api == null) return false;
        try { return api.update.getBoolean(owner); }
        catch (ReflectiveOperationException | RuntimeException failure) { return false; }
    }
    public static Action holder(Object holder, Object capability, String source) {
        if (api == null) return null;
        try { return begin((LivingEntity) api.capEntity.invoke(capability), null, holder, source); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("holder-init"); return null; }
    }
    public static Action entity(LivingEntity entity, String source) {
        if (!enabled(entity)) return null;
        try {
            var action = begin(entity, null, api.peekHolder(entity), source);
            if (action != null) action.followAttached = true;
            return action;
        }
        catch (RuntimeException | LinkageError failure) { failure("holder-before"); return null; }
    }
    /** Uses the packet's actual loaded target; it does not resolve players or load chunks. */
    public static LivingEntity packetTarget(Object packet, net.minecraft.world.entity.player.Player player) {
        if (api == null || !(player.level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return null;
        try {
            Integer id = (Integer) api.toggleTarget.invoke(packet);
            return id == null ? player : level.getEntity(id) instanceof LivingEntity entity ? entity : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("packet-target"); return null; }
    }
    private static Action begin(LivingEntity entity, Object owner, Object holder, String source) {
        if (!enabled(entity) || covered(entity, owner)) return null;
        try {
            var before = owner == null ? holderSnapshot(holder, entity) : containerSnapshot(owner, entity);
            var action = new Action(entity, owner, holder, before, source, ACTIVE.get());
            ACTIVE.set(action); return action;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("snapshot-before"); return null; }
    }
    private static CompoundTag stack(ItemStack stack, LivingEntity entity) {
        var tag = stack.isEmpty() ? new CompoundTag() : NbtSerde.snapshotItemStack(stack, entity.registryAccess());
        if (tag == null) throw new IllegalStateException("Accessories item codec failed");
        return tag;
    }
    private static CompoundTag inventorySnapshot(Container inventory, LivingEntity entity) {
        if (inventory == null || inventory.getContainerSize() > 4096) throw new IllegalStateException("Accessories inventory limit");
        var tag = EntityContainerSerde.snapshot(inventory, entity.registryAccess());
        if (tag == null) throw new IllegalStateException("Accessories inventory codec failed");
        return tag;
    }
    private static CompoundTag containerSnapshot(Object owner, LivingEntity entity) throws ReflectiveOperationException {
        var tag = new CompoundTag(); tag.putString("Provider", "accessories");
        tag.putString("SlotType", (String) api.slotName.get(owner));
        // Raw fields: upstream getAccessories/getSize/renderOptions may call update and mutate the game.
        tag.put("Accessories", inventorySnapshot((Container) api.accessories.get(owner), entity));
        tag.put("Cosmetics", inventorySnapshot((Container) api.cosmetics.get(owner), entity));
        Object base = api.baseSize.get(owner); if (base instanceof Integer size) tag.putInt("BaseSize", size);
        var render = new ListTag();
        for (Object option : limited((Collection<?>) api.render.get(owner))) render.add(ByteTag.valueOf(Boolean.TRUE.equals(option)));
        tag.put("RenderOptions", render);
        var modifiers = new ListTag();
        var values = new ArrayList<AttributeModifier>();
        for (Object value : limited(((Map<?, ?>) api.modifiers.get(owner)).values())) values.add((AttributeModifier) value);
        values.sort(Comparator.comparing(value -> value.id().toString()));
        for (var modifier : values) {
            var saved = modifier.save(); saved.putBoolean("Persistent", ((Set<?>) api.persistent.get(owner)).contains(modifier));
            saved.putBoolean("Cached", ((Set<?>) api.cached.get(owner)).contains(modifier)); modifiers.add(saved);
        }
        tag.put("Modifiers", modifiers); return tag;
    }
    private static Collection<?> limited(Collection<?> values) {
        if (values == null || values.size() > 4096) throw new IllegalStateException("Accessories state limit");
        return values;
    }
    private static CompoundTag holderSnapshot(Object holder, LivingEntity entity) throws ReflectiveOperationException {
        var tag = new CompoundTag(); tag.putString("Provider", "accessories");
        var containers = new CompoundTag();
        if (holder == null) { tag.putBoolean("AttachmentAbsent", true); tag.put("MainHand", stack(entity.getMainHandItem(), entity));
            if (entity instanceof net.minecraft.server.level.ServerPlayer player) tag.put("PlayerInventory", inventorySnapshot(player.getInventory(), entity));
            return tag;
        }
        var entries = (Map<?, ?>) api.slots.get(holder);
        if (entries.size() > 4096) throw new IllegalStateException("Accessories container limit");
        int slots = 0;
        for (var entry : entries.entrySet()) {
            var container = containerSnapshot(entry.getValue(), entity);
            slots += container.getCompound("Accessories").getInt("Size") + container.getCompound("Cosmetics").getInt("Size");
            if (slots > 4096) throw new IllegalStateException("Accessories aggregate slot limit");
            containers.put((String) entry.getKey(), container);
        }
        tag.put("Containers", containers);
        var invalid = new ListTag();
        for (Object value : limited((Collection<?>) api.invalid.get(holder))) invalid.add(stack((ItemStack) value, entity));
        tag.put("InvalidStacks", invalid);
        var settings = new CompoundTag();
        for (Field field : api.settings) {
            Object value = field.get(holder);
            if (value instanceof Boolean flag) settings.putBoolean(field.getName(), flag);
            else if (value instanceof Integer number) settings.putInt(field.getName(), number);
            else if (value instanceof Enum<?> constant) settings.putString(field.getName(), constant.name());
            else if (value instanceof Set<?> set) {
                var list = new ListTag(); var names = new ArrayList<String>();
                for (Object member : limited(set)) names.add((String) member);
                Collections.sort(names); for (String name : names) list.add(StringTag.valueOf(name));
                settings.put(field.getName(), list);
            }
        }
        tag.put("Settings", settings);
        tag.put("MainHand", stack(entity.getMainHandItem(), entity));
        if (entity instanceof net.minecraft.server.level.ServerPlayer player) tag.put("PlayerInventory", inventorySnapshot(player.getInventory(), entity));
        return tag;
    }
    private static CompoundTag itemAt(CompoundTag inventory, int slot) {
        var items = inventory.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) if (items.getCompound(i).getInt("Slot") == slot) {
            var result = items.getCompound(i).copy(); result.remove("Slot"); return result;
        }
        return new CompoundTag();
    }
    private static void acknowledge(Object owner, CompoundTag before, CompoundTag after, LivingEntity entity, boolean baseline) throws ReflectiveOperationException {
        for (int side = 0; side < 2; side++) {
            Object inventory = (side == 0 ? api.accessories : api.cosmetics).get(owner);
            if (!(inventory instanceof AccessoriesInventoryState state)) continue;
            var old = before.getCompound(side == 0 ? "Accessories" : "Cosmetics");
            var now = after.getCompound(side == 0 ? "Accessories" : "Cosmetics");
            for (int slot = 0; slot < now.getInt("Size"); slot++) {
                var value = itemAt(now, slot);
                if (baseline || slot >= old.getInt("Size") || !value.equals(itemAt(old, slot))) {
                    var previous = stack((ItemStack) api.previous.invoke(inventory, slot), entity);
                    if (!previous.equals(value)) state.avilixlogger$acknowledged().put(slot, value);
                    else state.avilixlogger$acknowledged().remove(slot);
                }
            }
        }
    }
    /** Called only when the upstream mod itself updates a previous stack; covers in-place component mutations and cosmetics. */
    public static Observation observe(Object inventory, int slot) {
        if (api == null || !(inventory instanceof Container contents) || slot < 0 || slot >= contents.getContainerSize()) return null;
        try {
            Object owner = api.inventoryOwner.get(inventory);
            var entity = (LivingEntity) api.capEntity.invoke(api.capability.get(owner));
            if (!enabled(entity)) { if (inventory instanceof AccessoriesInventoryState state) state.avilixlogger$acknowledged().remove(slot); return null; }
            if (covered(entity, owner)) return null;
            var current = contents.getItem(slot);
            var previous = (ItemStack) api.previous.invoke(inventory, slot);
            CompoundTag acknowledged = inventory instanceof AccessoriesInventoryState state ? state.avilixlogger$acknowledged().remove(slot) : null;
            // setPreviousItem(EMPTY) for KEEP-on-death is a native re-equip marker, not removal.
            if (acknowledged == null && ItemStack.matches(previous, current)) return null;
            var first = acknowledged == null ? stack(previous, entity) : acknowledged;
            var last = stack(current, entity);
            if (first.equals(last)) return null;
            String name = (String) api.slotName.get(owner), side = (String) api.inventoryName.get(inventory);
            var before = new CompoundTag(); before.putString("Provider", "accessories"); before.putString("SlotType", name);
            before.putString("Inventory", side); before.putInt("Slot", slot); before.putString("SlotPath", name.replace(':', '-') + "/" + slot);
            var after = before.copy(); before.put("Item", first); after.put("Item", last);
            var row = row(entity, "observed:" + side); // Tick-time observation never borrows an unrelated editor.
            row.actorUuid = null; row.actorName = "SYSTEM";
            row.extra += "; slot=" + name + "/" + slot + "; full nested components";
            return new Observation(entity, row, before, after);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("observation"); return null; }
    }
    public static HandAction hand(LivingEntity entity, String source) {
        if (!enabled(entity) || covered(entity, null)) return null;
        try { return new HandAction(entity, source); }
        catch (RuntimeException | LinkageError failure) { failure("hand-before"); return null; }
    }
    public static HandAction returned(net.minecraft.server.level.ServerPlayer player, ItemStack input) {
        if (!enabled(player)) return null;
        try {
            var action = new HandAction(player, "give-returned-item");
            action.suppressRow = covered(player, null); action.parentReturn = RETURNING.get(); action.returnScope = true;
            action.evidence.put("NativeInput", stack(input, player)); RETURNING.set(action); return action;
        } catch (RuntimeException | LinkageError failure) { failure("return-before"); return null; }
    }
    public static boolean dropped(net.minecraft.world.entity.item.ItemEntity item) {
        var action = RETURNING.get();
        if (action == null || !enabled(action.entity) || item.level() != action.entity.level()) return false;
        try {
            var row = new LogEntry(); row.ts = System.currentTimeMillis(); row.type = ActionType.ENTITY_SPAWN;
            EntityLogIdentity.capture(row, item); row.dim = item.level().dimension().location().toString();
            var pos = item.blockPosition(); row.x = pos.getX(); row.y = pos.getY(); row.z = pos.getZ();
            row.actorUuid = action.row.actorUuid; row.actorName = action.row.actorName; row.source = "mod:accessories:return-drop";
            row.extra = "wearer=" + action.entity.getUUID() + "; return-scope world item"; row.count = item.getItem().getCount();
            var tag = stack(item.getItem(), action.entity); row.deferSnapshot(LogEntry.SnapshotField.ITEM, tag);
            var link = new CompoundTag(); link.putUUID("WearerUUID", action.entity.getUUID()); link.putUUID("ItemEntityUUID", item.getUUID());
            row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, link); LoggerRuntime.storage((ServerLevel) item.level()).append(row); return true;
        } catch (RuntimeException | LinkageError failure) { failure("return-drop"); return false; }
    }
    private static CompoundTag handSnapshot(LivingEntity entity) {
        var tag = new CompoundTag(); tag.putString("Provider", "accessories");
        tag.put("MainHand", stack(entity.getMainHandItem(), entity));
        if (entity instanceof net.minecraft.server.level.ServerPlayer player) tag.put("PlayerInventory", inventorySnapshot(player.getInventory(), entity));
        return tag;
    }
    public static final class HandAction {
        private final LivingEntity entity; private final CompoundTag before; private final LogEntry row; private boolean done;
        private final CompoundTag evidence = new CompoundTag();
        private boolean suppressRow, returnScope; private HandAction parentReturn;
        private HandAction(LivingEntity entity, String source) { this.entity = entity; before = handSnapshot(entity); row = row(entity, source); }
        public void finish(boolean threw) {
            if (done) return; done = true;
            if (returnScope && RETURNING.get() == this) { if (parentReturn == null) RETURNING.remove(); else RETURNING.set(parentReturn); }
            if (!enabled(entity) || suppressRow) return;
            try { var after = handSnapshot(entity); if (!before.equals(after) || !evidence.isEmpty()) { if (!evidence.isEmpty()) after.put("Outcome", evidence); append(entity, row, before, after, threw); } }
            catch (RuntimeException | LinkageError failure) { failure("hand-after"); }
        }
    }
    private static LogEntry row(LivingEntity entity, String source) {
        var row = new LogEntry(); row.ts = System.currentTimeMillis(); row.type = ActionType.ENTITY_EQUIPMENT_CHANGE;
        EntityLogIdentity.capture(row, entity); row.dim = entity.level().dimension().location().toString();
        var pos = entity.blockPosition(); row.x = pos.getX(); row.y = pos.getY(); row.z = pos.getZ();
        var cause = CauseContext.peek(); row.actorUuid = cause == null ? null : cause.actorUuid();
        row.actorName = cause == null || cause.actorUuid() == null ? "SYSTEM" : cause.actorName();
        row.source = "mod:accessories:" + source; row.extra = "provider=accessories; operation=" + source + "; audit only";
        return row;
    }
    private static void append(LivingEntity entity, LogEntry row, CompoundTag before, CompoundTag after, boolean threw) {
        // Resolve storage on the server thread; the worker receives detached tags and strings only.
        var storage = LoggerRuntime.storage((ServerLevel) entity.level());
        AsyncLogProcessor.submit(PayloadSizeEstimator.estimateTags(before, after), () -> {
            var changes = new ListTag();
            collectChanges(before, after, "", changes);
            after.put("SlotChanges", changes);
            after.putBoolean("NativeThrew", threw);
            row.extra += "; changedSlots=" + changes.size();
            for (int i = 0; i < Math.min(6, changes.size()); i++) {
                var change = changes.getCompound(i);
                row.extra += "; " + change.getString("Path") + ": " + label(change.getCompound("Before")) + " -> " + label(change.getCompound("After"));
            }
            if (changes.isEmpty()) row.extra += "; state/settings/outcome change; see full before/after";
            if (threw) row.extra += "; NativeThrew";
            row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE, before); row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, after);
            storage.append(row);
        });
    }
    private static void collectChanges(CompoundTag before, CompoundTag after, String path, ListTag changes) {
        if (before.contains("Item", Tag.TAG_COMPOUND) || after.contains("Item", Tag.TAG_COMPOUND)) {
            addChange(before.getCompound("Item"), after.getCompound("Item"), after.getString("Inventory") + ":" + after.getString("SlotPath"), changes);
        }
        var names = new TreeSet<String>(); names.addAll(before.getCompound("Containers").getAllKeys()); names.addAll(after.getCompound("Containers").getAllKeys());
        for (String name : names) collectChanges(before.getCompound("Containers").getCompound(name), after.getCompound("Containers").getCompound(name), name + "/", changes);
        if (path.isEmpty() && (before.contains("SlotType") || after.contains("SlotType"))) path = after.getString("SlotType") + "/";
        for (String side : List.of("Accessories", "Cosmetics", "PlayerInventory", "EditorInventory")) {
            var first = before.getCompound(side); var last = after.getCompound(side);
            int size = Math.max(first.getInt("Size"), last.getInt("Size"));
            for (int slot = 0; slot < size; slot++) addChange(itemAt(first, slot), itemAt(last, slot), side + ":" + path + slot, changes);
        }
        if (before.contains("MainHand") || after.contains("MainHand")) addChange(before.getCompound("MainHand"), after.getCompound("MainHand"), "main-hand", changes);
        if (before.contains("Cursor") || after.contains("Cursor")) addChange(before.getCompound("Cursor"), after.getCompound("Cursor"), "cursor", changes);
    }
    private static void addChange(CompoundTag before, CompoundTag after, String path, ListTag changes) {
        if (before.equals(after)) return;
        var change = new CompoundTag(); change.putString("Path", path); change.put("Before", before); change.put("After", after); changes.add(change);
    }
    private static String label(CompoundTag item) {
        return item.isEmpty() ? "empty" : item.getString("id") + " x" + (item.contains("count") ? item.getInt("count") : item.getByte("Count")) + " (full components saved)";
    }
    private static void failure(String path) { AdaptiveLogDiagnostics.adapterFailure("accessories-" + path); }
    public static final class Observation {
        private final LivingEntity entity; private final LogEntry row; private final CompoundTag before, after;
        private boolean done;
        private Observation(LivingEntity entity, LogEntry row, CompoundTag before, CompoundTag after) {
            this.entity = entity; this.row = row; this.before = before; this.after = after;
        }
        public void finish(boolean threw) {
            if (done) return; done = true;
            if (!enabled(entity)) return;
            try { append(entity, row, before, after, threw); }
            catch (RuntimeException | LinkageError failure) { failure("observation-append"); }
        }
    }
    public static final class Action {
        private final LivingEntity entity; private final Object owner, holder;
        private final CompoundTag before; private final LogEntry row;
        private final Action parent; private boolean done;
        private boolean followAttached;
        private net.minecraft.server.level.ServerPlayer editor;
        private net.minecraft.world.inventory.AbstractContainerMenu menu;
        private final CompoundTag evidence = new CompoundTag();
        private Action(LivingEntity entity, Object owner, Object holder, CompoundTag before, String source, Action parent) {
            this.entity = entity; this.owner = owner; this.holder = holder; this.before = before; this.parent = parent;
            this.row = row(entity, source);
        }
        private void editorSnapshot(CompoundTag tag) {
            tag.putUUID("EditorUUID", editor.getUUID());
            if (editor != entity) tag.put("EditorInventory", inventorySnapshot(editor.getInventory(), editor));
            tag.put("Cursor", stack(menu.getCarried(), editor));
        }
        private void cancel() {
            done = true;
            if (ACTIVE.get() == this) { if (parent == null) ACTIVE.remove(); else ACTIVE.set(parent); }
        }
        public void deathResult(java.util.Collection<ItemStack> drops, net.minecraft.world.damagesource.DamageSource damage) {
            try {
                evidence.putString("DamageType", damage.typeHolder().unwrapKey().map(key -> key.location().toString()).orElse(damage.getMsgId()));
                evidence.putBoolean("NativeResultNull", drops == null);
                if (damage.getEntity() != null) { evidence.putUUID("CausingEntity", damage.getEntity().getUUID()); String name = EntityLogIdentity.name(damage.getEntity()); if (name != null) evidence.putString("CausingName", name); }
                if (damage.getDirectEntity() != null) evidence.putUUID("DirectEntity", damage.getDirectEntity().getUUID());
                var items = new ListTag();
                if (drops != null) for (ItemStack item : drops) items.add(stack(item, entity));
                evidence.put("ReturnedDrops", items); evidence.putString("OutcomeMeaning", "native return, not proof of world spawn");
            } catch (RuntimeException | LinkageError failure) { failure("death-result"); }
        }
        public void finish(boolean threw) {
            if (done) return; done = true;
            if (ACTIVE.get() == this) { if (parent == null) ACTIVE.remove(); else ACTIVE.set(parent); }
            if (!enabled(entity)) return;
            try {
                Object currentHolder = followAttached ? api.peekHolder(entity) : holder;
                var after = owner == null ? holderSnapshot(currentHolder, entity) : containerSnapshot(owner, entity);
                if (editor != null) editorSnapshot(after);
                if (before.equals(after) && evidence.isEmpty()) return;
                if (!evidence.isEmpty()) after.put("Outcome", evidence);
                if (owner != null) acknowledge(owner, before, after, entity, false);
                else if (currentHolder != null) for (var entry : ((Map<?, ?>) api.slots.get(currentHolder)).entrySet()) {
                    String name = (String) entry.getKey();
                    acknowledge(entry.getValue(), before.getCompound("Containers").getCompound(name), after.getCompound("Containers").getCompound(name), entity, row.source.endsWith(":death") || row.source.endsWith(":restore") || row.source.contains("holder-"));
                }
                append(entity, row, before, after, threw);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { failure("snapshot-after"); }
        }
    }
    private static final class Api {
        final Class<?> inventory, container, menu;
        final Field update, inventoryOwner, inventoryName, capability, slotName, accessories, cosmetics, render, modifiers, persistent, cached, baseSize, slots, invalid;
        final List<Field> settings = new ArrayList<>();
        final Method capEntity, previous, toggleTarget, menuTarget;
        final net.neoforged.neoforge.attachment.AttachmentType<?> attachment;
        Object peekHolder(LivingEntity entity) { return entity.hasData(attachment) ? entity.getData(attachment) : null; }
        Api() throws ReflectiveOperationException {
            var loader = AccessoriesAudit.class.getClassLoader();
            inventory = Class.forName("io.wispforest.accessories.impl.ExpandedSimpleContainer", false, loader);
            container = Class.forName("io.wispforest.accessories.impl.AccessoriesContainerImpl", false, loader);
            if (!AccessoriesInventoryState.class.isAssignableFrom(inventory)) throw new IllegalStateException("Accessories mutation hooks unavailable");
            menu = Class.forName("io.wispforest.accessories.menu.variants.AccessoriesMenuBase", false, loader);
            menuTarget = menu.getMethod("targetEntity");
            var holder = Class.forName("io.wispforest.accessories.impl.AccessoriesHolderImpl", false, loader);
            var cap = Class.forName("io.wispforest.accessories.api.AccessoriesCapability", false, loader);
            capEntity = cap.getMethod("entity"); previous = inventory.getMethod("getPreviousItem", int.class);
            attachment = (net.neoforged.neoforge.attachment.AttachmentType<?>) Class.forName("io.wispforest.accessories.neoforge.AccessoriesForge", false, loader).getField("HOLDER_ATTACHMENT_TYPE").get(null);
            toggleTarget = Class.forName("io.wispforest.accessories.networking.server.SyncCosmeticToggle", false, loader).getMethod("entityId");
            if (capEntity.getReturnType() != LivingEntity.class || previous.getReturnType() != ItemStack.class
                    || toggleTarget.getReturnType() != Integer.class || !Container.class.isAssignableFrom(inventory)) throw new IllegalStateException("Accessories API types");
            inventoryOwner = field(inventory, "container"); inventoryName = field(inventory, "name");
            update = field(container, "update"); capability = field(container, "capability"); slotName = field(container, "slotName");
            accessories = field(container, "accessories"); cosmetics = field(container, "cosmeticAccessories");
            render = field(container, "renderOptions"); modifiers = field(container, "modifiers");
            persistent = field(container, "persistentModifiers"); cached = field(container, "cachedModifiers"); baseSize = field(container, "baseSize");
            slots = field(holder, "slotContainers"); invalid = field(holder, "invalidStacks");
            for (String name : List.of("equipControl", "showAdvancedOptions", "showUnusedSlots", "showCosmetics", "columnAmount", "widgetType",
                    "showGroupFilter", "mainWidgetPosition", "sideWidgetPosition", "showCraftingGrid", "isGroupFiltersOpen", "filteredGroups")) settings.add(field(holder, name));
        }
        private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
            var field = type.getDeclaredField(name); field.setAccessible(true); return field;
        }
    }
}
