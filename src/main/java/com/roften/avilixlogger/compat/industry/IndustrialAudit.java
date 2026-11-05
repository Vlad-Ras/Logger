package com.roften.avilixlogger.compat.industry;

import com.roften.avilixlogger.compat.machines.MachineAudit;
import com.roften.avilixlogger.compat.teams.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.lang.ref.WeakReference;
import java.util.*;

/** Physical native machines only. No capability lookup, controller resolution or new world traversal. */
public final class IndustrialAudit {
    private static final Map<Object, WeakReference<BlockEntity>> ITEMS = new WeakHashMap<>();
    private static final ThreadLocal<Simulation> SIMULATION = new ThreadLocal<>();
    private static final Set<String> SETTINGS_BEHAVIOURS = Set.of("EnchanterBehaviour", "ClassicEnchanterBehaviour",
            "PrinterBehaviour", "ExperienceHatchBehaviour", "EntityFilteringBehaviour", "FilteringBehaviour",
            "ScrollOptionBehaviour", "ScrollValueBehaviour");
    private static final ClassValue<String[]> SETTINGS_FIELDS = new ClassValue<>() {
        @Override protected String[] computeValue(Class<?> type) { return fields(type.getSimpleName()); }
    };
    private IndustrialAudit() {}
    private static final class Versions {
        static final boolean CEI = exact("create_enchantment_industry", "2.4.2");
        static final boolean CDG = exact("createdieselgenerators", "1.21.1-1.3.15");
        static boolean exact(String id, String version) {
            return ModList.get().getModContainerById(id).map(c -> c.getModInfo().getVersion().toString().equals(version)).orElse(false);
        }
    }
    public static boolean supportsDiesel(){return Versions.CDG;}
    public static String provider(Object value) {
        if (!(value instanceof IndustrialState)) return null;
        String type = value.getClass().getName();
        if (type.startsWith("plus.dragons.createenchantmentindustry.") && Versions.CEI) return "create_enchantment_industry";
        if (type.startsWith("com.jesz.createdieselgenerators.") && Versions.CDG) return "createdieselgenerators";
        return null;
    }
    public static boolean handles(Object value) { return provider(value) != null && !((IndustrialState)value).avilixlogger$industrial().failed && MachineAudit.level(value) != null; }
    public static Data data(BlockEntity be) { return ((IndustrialState)be).avilixlogger$industrial(); }
    public static boolean simulating() { return SIMULATION.get() != null; }
    public static BlockEntity origin() { var scope = SIMULATION.get(); return scope == null ? null : scope.be; }
    public static void attached(Object value) {
        if (!(value instanceof BlockEntity be) || provider(be) == null || !(be.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        try {
            var state = data(be);
            if (state.bound || state.failed) return;
            IItemHandler inventory = inventory(be);
            if (inventory != null) ITEMS.put(inventory, new WeakReference<>(be));
            IndustrialFluids.bind(be);
            state.bound = true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { data(be).failed=true; fail("bind"); }
    }
    public static void loaded(Object value) {
        if (!handles(value)) return;
        var be = (BlockEntity)value;
        data(be).bound = false; attached(be); if(data(be).failed)return; baseline(be); data(be).established = true;
    }
    public static void baseline(BlockEntity be) {
        if (!handles(be)) return;
        if(!MachineAudit.policy(be,ActionType.MACHINE_STATE_CHANGE)){data(be).baseline=null;data(be).signature.clear();return;}
        try {
            var data = data(be);
            data.signature.clear();
            signature(be, data.signature);
            data.baseline = settings(be);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { data(be).baseline = null; data(be).failed=true; fail("baseline"); }
    }
    public static void dirty(Object value) {
        if (!handles(value)) return;
        var be = (BlockEntity)value;
        var level = (ServerLevel)be.getLevel();
        observeSettings(be, level, false);
    }

    private static void observeSettings(BlockEntity be, ServerLevel level, boolean simulation) {
        var data = data(be);
        if (!data.bound) attached(be);
        if (data.failed || data.itemActive || ModStateAudit.capturing(be, "industrial")) return;
        boolean enabled = simulation
                ? ModStateAudit.simulationEnabled(level.getServer(), ActionType.MACHINE_STATE_CHANGE)
                : ModStateAudit.enabled(level.getServer(), ActionType.MACHINE_STATE_CHANGE);
        if (!enabled) { data.baseline = null; return; }
        try {
            var current = data.observed;
            current.clear();
            signature(be, current);
            if (current.equals(data.signature)) {
                current.clear();
                return;
            }
            var previous = data.baseline;
            var next = settings(be);
            data.observed = data.signature;
            data.observed.clear();
            data.signature = current;
            data.baseline = next;
            if (previous != null && !previous.equals(next)) try (var cause = CauseContext.unattributed()) {
                ModStateAudit.receipt(level.getServer(), provider(be), "native-settings-observation",
                        ActionType.MACHINE_STATE_CHANGE, previous, next);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { data(be).failed=true; fail("dirty"); }
    }
    public static Action begin(Object value, String operation, boolean contents) {
        if (!handles(value)) return null;
        var be = (BlockEntity)value; attached(be);
        if(data(be).failed)return null;
        ActionType type = contents ? ActionType.MACHINE_CONTENT_CHANGE : ActionType.MACHINE_STATE_CHANGE;
        if (data(be).itemActive || !MachineAudit.policy(be, type) || ModStateAudit.capturing(be, "industrial")) return null;
        IndustrialFluids.flushOwner(be);
        var nativeAction = ModStateAudit.begin(((ServerLevel)be.getLevel()).getServer(), provider(be), operation, be, "industrial", type,
                () -> {
                    if(contents)return contents(be);
                    var tag=settings(be);
                    if(MachineAudit.policy(be,ActionType.MACHINE_CONTENT_CHANGE))tag.put("PhysicalContents",contents(be));
                    return tag;
                });
        return nativeAction == null ? null : new Action(be, nativeAction);
    }
    public static Action liveRead(Object value) {
        if(!(value instanceof IndustrialState tracked)||!tracked.avilixlogger$industrial().established||!handles(value))return null;
        return begin(value,"live-nbt-read",!MachineAudit.policy((BlockEntity)value,ActionType.MACHINE_STATE_CHANGE));
    }
    public static Action behaviour(Object value, String operation) {
        try { return begin(StateAccess.field(value, "blockEntity"), operation, false); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { fail("behaviour"); return null; }
    }
    /** Keep detached copies around the native call. Only a mutation goes through the codecs. */
    public static ItemAction item(Object handler, boolean simulate) {
        if (simulate) return null;
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return null;
        var ref = ITEMS.get(handler);
        var be = ref == null ? null : ref.get();
        if (be == null || !handles(be) || data(be).itemActive || ModStateAudit.capturing(be, "industrial")) return null;
        try {
            var receipt = ModStateAudit.prepareReceipt(server, provider(be), "physical-inventory", ActionType.MACHINE_CONTENT_CHANGE);
            if (receipt == null) return null;
            var before = PhysicalContents.read(be);
            data(be).itemActive = true;
            return new ItemAction(be, before, receipt);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            fail("item-before");
            return null;
        }
    }

    public static final class ItemAction {
        private final BlockEntity be;
        private final PhysicalContents before;
        private final ModStateAudit.Receipt receipt;
        private boolean done;

        private ItemAction(BlockEntity be, PhysicalContents before, ModStateAudit.Receipt receipt) {
            this.be = be;
            this.before = before;
            this.receipt = receipt;
        }

        public void finish(boolean threw) {
            if (done) return;
            done = true;
            try {
                var after = PhysicalContents.read(be);
                if (before.sameAs(after)) return;
                IndustrialFluids.flushOwner(be);
                receipt.finish(before.encode(be), after.encode(be), threw);
                baseline(be);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                fail("item-after");
            } finally {
                data(be).itemActive = false;
            }
        }
    }

    private record PhysicalContents(String dimension, BlockPos pos,
                                    net.minecraft.world.level.block.state.BlockState state,
                                    ItemStack[] slots, int exposed, ItemStack held, ItemStack processed,
                                    CompoundTag hints, List<IndustrialFluids.PhysicalTank> fluids) {
        static PhysicalContents read(BlockEntity be) throws ReflectiveOperationException {
            var inventory = inventory(be);
            ItemStack[] slots = null;
            int exposed = 0;
            if (inventory != null) {
                var stored = (List<?>) StateAccess.field(inventory, "stacks");
                if (stored.size() > 4096) throw new IllegalStateException("Stored item budget");
                slots = new ItemStack[stored.size()];
                for (int i = 0; i < slots.length; i++) slots[i] = ((ItemStack) stored.get(i)).copy();
                exposed = inventory.getSlots();
            }
            String type = be.getClass().getSimpleName();
            ItemStack held = null, processed = null;
            if (type.equals("BlazeEnchanterBlockEntity") || type.equals("ClassicBlazeEnchanterBlockEntity"))
                held = ((ItemStack) StateAccess.field(be, "heldItem")).copy();
            if (type.equals("GrindstoneDrainBlockEntity"))
                processed = ((ItemStack) StateAccess.field(be, "processedItem")).copy();
            CompoundTag hints = null;
            if (type.equals("BlazeForgerBlockEntity")) {
                hints = new CompoundTag();
                for (String key : new String[]{"cost", "mode", "conflicting", "overCap"})
                    hints.put(key, StateAccess.encode(StateAccess.field(inventory, key)));
            }
            return new PhysicalContents(be.getLevel().dimension().location().toString(), be.getBlockPos().immutable(),
                    be.getBlockState(), slots, exposed, held, processed, hints, IndustrialFluids.rawSnapshot(be));
        }

        boolean sameAs(PhysicalContents other) {
            if (!dimension.equals(other.dimension) || !pos.equals(other.pos) || state != other.state
                    || exposed != other.exposed || !sameStack(held, other.held) || !sameStack(processed, other.processed)
                    || !Objects.equals(hints, other.hints) || fluids.size() != other.fluids.size()) return false;
            if (slots != null || other.slots != null) {
                if (slots == null || other.slots == null || slots.length != other.slots.length) return false;
                for (int i = 0; i < slots.length; i++) if (!ItemStack.matches(slots[i], other.slots[i])) return false;
            }
            for (int i = 0; i < fluids.size(); i++) if (!fluids.get(i).sameAs(other.fluids.get(i))) return false;
            return true;
        }

        private static boolean sameStack(ItemStack first, ItemStack last) {
            return first == last || first != null && last != null && ItemStack.matches(first, last);
        }

        CompoundTag encode(BlockEntity be) {
            long started = HotPathMetrics.beginSnapshot();
            try {
                var location = new CompoundTag();
                location.putString("Dimension", dimension);
                location.putInt("X", pos.getX());
                location.putInt("Y", pos.getY());
                location.putInt("Z", pos.getZ());
                location.putString("Block", net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                location.put("BlockState", NbtUtils.writeBlockState(state));
                var tag = location.copy();
                var access = be.getLevel().registryAccess();
                if (slots != null) {
                    var stored = new ListTag();
                    for (int i = 0; i < slots.length; i++) {
                        var slot = new CompoundTag();
                        slot.putInt("Slot", i);
                        slot.put("Stack", slots[i].saveOptional(access));
                        stored.add(slot);
                    }
                    tag.put("StoredItems", stored);
                    tag.putInt("ExposedSlots", exposed);
                }
                if (held != null) tag.put("HeldItem", held.saveOptional(access));
                if (processed != null) tag.put("ProcessedItem", processed.saveOptional(access));
                if (hints != null) tag.put("NativeForgerPreview", hints.copy());
                var tanks = new ListTag();
                for (var tank : fluids) tanks.add(tank.encode(be, location));
                tag.put("PhysicalFluids", tanks);
                return tag;
            } finally {
                HotPathMetrics.endSnapshot(started);
            }
        }
    }

    public static Simulation simulate(Object value) {
        if (provider(value) == null || !(value instanceof BlockEntity be) || data(be).failed) return null;
        var level = MachineAudit.level(be);
        if (level == null) return null;
        var server = level.getServer();
        if (!ModStateAudit.simulationEnabled(server, ActionType.MACHINE_CONTENT_CHANGE)
                && !ModStateAudit.simulationEnabled(server, ActionType.MACHINE_STATE_CHANGE)) return null;
        return new Simulation(be, level, SIMULATION.get());
    }
    public static void removed(Object value) {
        if (value instanceof BlockEntity be && provider(be) != null && be.getLevel() instanceof ServerLevel level && level.getServer().isSameThread())
            IndustrialFluids.flushOwner(be);
    }
    private static IItemHandler inventory(BlockEntity be) throws ReflectiveOperationException {
        return switch (be.getClass().getSimpleName()) {
            case "GrindstoneDrainBlockEntity", "BlazeForgerBlockEntity", "BulkFermenterBlockEntity" -> (IItemHandler)StateAccess.field(be, "inventory");
            default -> null;
        };
    }
    private static String[] fields(String type) {
        return switch (type) {
            case "BlazeEnchanterBlockEntity" -> new String[]{"isCreative", "special", "cursed", "seed"};
            case "ClassicBlazeEnchanterBlockEntity", "BlazeForgerBlockEntity" -> new String[]{"isCreative", "special", "cursed"};
            case "ExperienceLanternBlockEntity" -> new String[]{"rate"};
            case "DieselEngineBlockEntity", "HugeDieselEngineBlockEntity" -> new String[]{"upgrade", "analogSignal"};
            case "ModularDieselEngineBlockEntity" -> new String[]{"upgrade", "analogSignal", "controller", "length"};
            case "OilBarrelBlockEntity" -> new String[]{"controller", "width", "height"};
            case "BulkFermenterBlockEntity" -> new String[]{"controller", "width", "height", "packagerMode"};
            case "DistillationTankBlockEntity" -> new String[]{"controller", "width", "height", "window"};
            case "CanisterBlockEntity" -> new String[]{"capacityEnchantLevel", "componentPatch"};
            case "PumpjackBearingBlockEntity" -> new String[]{"bearingBPos", "crankPos", "isLarge"};
            case "PumpjackCrankBlockEntity" -> new String[]{"bearingPos"};
            case "PumpjackHoleBlockEntity" -> new String[]{"pipeLength", "started", "valid"};
            case "ChemicalTurretBlockEntity" -> new String[]{"lighterUpgrade", "redstoneSignal", "controllingPlayer", "controllingEntity"};
            case "TurretBlockEntity" -> new String[]{"controllingPlayer", "controllingEntity"};
            case "BurnerBlockEntity" -> new String[]{"ignited", "redstonePower", "redstoneOutput"};
            case "BasinLidBlockEntity" -> new String[]{"steamInside", "running"};
            default -> new String[0];
        };
    }
    private static List<?> behaviours(BlockEntity be) throws ReflectiveOperationException {
        var selected = new ArrayList<>();
        for (Object behaviour : (Collection<?>)StateAccess.call(be, "getAllBehaviours")) {
            String type = behaviour.getClass().getSimpleName();
            if (SETTINGS_BEHAVIOURS.contains(type)) selected.add(behaviour);
        }
        if (selected.size() > 32) throw new IllegalStateException("Settings budget"); return selected;
    }
    private static void signature(BlockEntity be, List<Object> values) throws ReflectiveOperationException {
        values.add(be.getBlockState());
        for (String field : SETTINGS_FIELDS.get(be.getClass())) values.add(StateAccess.field(be, field));
        int selected = 0;
        for (Object behaviour : (Collection<?>)StateAccess.call(be, "getAllBehaviours")) {
            String type = behaviour.getClass().getSimpleName();
            if (!SETTINGS_BEHAVIOURS.contains(type)) continue;
            if (++selected > 32) throw new IllegalStateException("Settings budget");
            if (type.equals("EnchanterBehaviour")) {
                values.add(StateAccess.field(behaviour, "value")); values.add(StateAccess.field(behaviour, "template"));
            } else if (type.equals("ScrollOptionBehaviour") || type.equals("ScrollValueBehaviour")) values.add(StateAccess.field(behaviour, "value"));
            else { values.add(StateAccess.field(behaviour, "filter")); values.add(StateAccess.field(behaviour, "count")); }
        }
    }
    private static CompoundTag settings(BlockEntity be) throws ReflectiveOperationException {
        var tag = MachineAudit.location(be);
        for (String field : SETTINGS_FIELDS.get(be.getClass())) {
            Object value = StateAccess.field(be, field);
            tag.put(field, raw(be, value));
        }
        var behaviourTags = new ListTag();
        for (Object behaviour : behaviours(be)) {
            var selected = new CompoundTag();
            StateAccess.call(behaviour, "write", new Class<?>[]{CompoundTag.class, HolderLookup.Provider.class, boolean.class},
                    selected, be.getLevel().registryAccess(), false);
            var entry = new CompoundTag(); entry.putString("Class", behaviour.getClass().getName()); entry.put("Settings", selected); behaviourTags.add(entry);
        }
        tag.put("Behaviours", behaviourTags); return tag;
    }
    private static Tag raw(BlockEntity be, Object value) throws ReflectiveOperationException {
        if (value instanceof BlockPos pos) { var tag = new CompoundTag(); tag.putInt("X", pos.getX()); tag.putInt("Y", pos.getY()); tag.putInt("Z", pos.getZ()); return tag; }
        if (value instanceof Entity entity) { var tag = new CompoundTag(); tag.putUUID("UUID", entity.getUUID()); tag.putString("Type", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()); return tag; }
        if (value instanceof net.minecraft.core.component.DataComponentPatch patch)
            return net.minecraft.core.component.DataComponentPatch.CODEC.encodeStart(be.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE), patch).result().orElseThrow();
        if (value != null && value.getClass().getName().startsWith("com.jesz.createdieselgenerators.content.diesel_engine.") && value.getClass().getSimpleName().endsWith("Upgrade")) return StateAccess.encode(StateAccess.call(value, "getId"));
        return StateAccess.encode(value);
    }
    public static CompoundTag contents(BlockEntity be) throws ReflectiveOperationException {
        var tag = MachineAudit.location(be);
        IItemHandler inventory = inventory(be);
        if (inventory != null) {
            var stored=(List<?>)StateAccess.field(inventory,"stacks");
            if (stored.size() > 4096) throw new IllegalStateException("Stored item budget");
            var slots = new ListTag();
            for (int i=0; i<stored.size(); i++) { var slot = new CompoundTag(); slot.putInt("Slot",i); slot.put("Stack", ((ItemStack)stored.get(i)).saveOptional(be.getLevel().registryAccess())); slots.add(slot); }
            tag.put("StoredItems", slots);tag.putInt("ExposedSlots",inventory.getSlots());
        }
        switch (be.getClass().getSimpleName()) {
            case "BlazeEnchanterBlockEntity", "ClassicBlazeEnchanterBlockEntity" -> tag.put("HeldItem", ((ItemStack)StateAccess.field(be, "heldItem")).saveOptional(be.getLevel().registryAccess()));
            case "GrindstoneDrainBlockEntity" -> tag.put("ProcessedItem", ((ItemStack)StateAccess.field(be, "processedItem")).saveOptional(be.getLevel().registryAccess()));
        }
        if(be.getClass().getSimpleName().equals("BlazeForgerBlockEntity")){
            var hints=new CompoundTag();for(String key:new String[]{"cost","mode","conflicting","overCap"})hints.put(key,StateAccess.encode(StateAccess.field(inventory,key)));tag.put("NativeForgerPreview",hints);
        }
        tag.put("PhysicalFluids", IndustrialFluids.snapshot(be));
        return tag;
    }
    public static void clear() { ITEMS.clear(); SIMULATION.remove(); }
    public static void fail(String operation) { MachineAudit.failure("industrial-" + operation); }
    public static final class Data {
        public boolean bound, established, failed, itemActive; public CompoundTag baseline;
        private List<Object> signature = new ArrayList<>();
        private List<Object> observed = new ArrayList<>();
        public final List<IndustrialFluids.Binding> fluids = new ArrayList<>();
    }
    public static final class Action {
        private final BlockEntity be; private final ModStateAudit.Action delegate;
        private Action(BlockEntity be, ModStateAudit.Action delegate) { this.be=be; this.delegate=delegate; }
        public void finish(boolean threw) { try { delegate.finish(threw); } finally { baseline(be); } }
    }
    public static final class Simulation implements AutoCloseable {
        private final BlockEntity be;
        private final ServerLevel level;
        private final Simulation parent;
        private Simulation(BlockEntity be, ServerLevel level, Simulation parent) {
            this.be = be;
            this.level = level;
            this.parent = parent;
            SIMULATION.set(this);
            if (!data(be).bound) attached(be);
        }
        public void close() {
            try {
                if (!be.isRemoved() && be.getLevel() == level) observeSettings(be, level, true);
            } finally {
                if (parent == null) SIMULATION.remove();
                else SIMULATION.set(parent);
            }
        }
    }
}
