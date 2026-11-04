package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.compat.create.mixin.CartStorageAccessor;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.impl.contraption.storage.FallbackMountedStorage;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.neoforge.items.ItemStackHandler;
import java.util.List;

/** Native data/callback policy and recovery; never authorizes production publication. */
public final class CreateMechanismSwitchesSmoke {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void run(ServerLevel world) {
        long previous = CaptureTypePolicy.mask();
        try {
            String[][] families = {{"deployer"}, {"mechanical_drill"}, {"mechanical_saw"},
                    {"mechanical_harvester"}, {"mechanical_plough"}, {"mechanical_roller"},
                    {"chute", "smart_chute"}, {"andesite_funnel", "brass_funnel", "andesite_belt_funnel", "brass_belt_funnel"},
                    {"portable_storage_interface", "portable_fluid_interface"}};
            for (int i = 0; i < families.length; i++) for (String block : families[i]) {
                var id = ResourceLocation.fromNamespaceAndPath("create", block);
                check(BuiltInRegistries.BLOCK.getOptional(id).isPresent(), "missing native block " + id);
                check(CreateCapturePolicy.mechanismGroup(id) == CaptureTypePolicy.CREATE_DEPLOYER + i, "wrong native mechanism family " + id);
            }
            check(CreateCapturePolicy.mechanismGroup(ResourceLocation.parse("minecraft:dispenser")) == CaptureTypePolicy.CREATE_DISPENSER, "dispenser group");
            check(CreateCapturePolicy.mechanismGroup(ResourceLocation.parse("minecraft:dropper")) == CaptureTypePolicy.CREATE_DISPENSER, "dropper group");
            var body = new MountedContraption(); body.anchor = BlockPos.ZERO;
            body.bounds = new net.minecraft.world.phys.AABB(BlockPos.ZERO);
            body.getBlocks().put(BlockPos.ZERO, new StructureBlockInfo(BlockPos.ZERO, AllBlocks.DEPLOYER.getDefaultState(), new CompoundTag()));
            var inventory = new ItemStackHandler(1); inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 17));
            ((CartStorageAccessor)body.getStorage()).avilixlogger$addItem(new FallbackMountedStorage(inventory), BlockPos.ZERO);
            var fluid = (CompoundTag)new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, 1250).save(world.registryAccess());
            fluid.putInt("Capacity", 4000);
            ((CartStorageAccessor)body.getStorage()).avilixlogger$addFluid(com.simibubi.create.content.fluids.tank.storage.FluidTankMountedStorage.fromLegacy(world.registryAccess(), fluid), BlockPos.ZERO);
            var entity = OrientedContraptionEntity.create(world, body, Direction.NORTH);
            entity.startRiding(new net.minecraft.world.entity.vehicle.Minecart(world, 0, 128, 0), true);
            CartStorageAudit.bind(entity);
            var context = new MovementContext(world, body.getBlocks().get(BlockPos.ZERO), body);
            CaptureTypePolicy.install(CaptureTypePolicy.changed(0, CaptureTypePolicy.CREATE_DEPLOYER, false));
            check(!CreateCapturePolicy.actor(context) && CreateCapturePolicy.lifecycle(entity), "deployer switch muted lifecycle");
            try (var rows = CreateCargoAudit.captureChanges(entity)) {
                for (int i = 0; i < 1000; i++) {
                    boolean prior = CreateCapturePolicy.enterActor(context);
                    try {
                        check(CreateDeployerAudit.begin(context, BlockPos.ZERO, null, "USE") == null, "disabled deployer read technical player");
                        check(!CreateCargoAudit.active(entity), "disabled callback still copies cargo");
                    } finally { CaptureTypePolicy.restoreSuppression(prior); }
                }
                check(rows.rows().size() == 1, "capture gap barrier spam or missing barrier");
                var gap = rows.rows().getFirst(); gap.materializeSnapshots();
                check(NbtSerde.fromSnbt(gap.beAfter).getBoolean("CaptureDisabled"), "missing capture gap metadata");
                var snapshot = CreateCartAudit.snapshot(entity, null);
                var recovery = CartRecoveryPlan.fromSnapshot(CreateContraptionSources.id(entity), 1, 0, "before", snapshot);
                var restored = new StagedCartRestore(snapshot); int steps = 0;
                while (!restored.step(world)) check(++steps < 100, "muted snapshot restore did not finish");
                check(restored.entity.getContraption().getBlocks().get(BlockPos.ZERO).state().is(AllBlocks.DEPLOYER.get()), "restore lost disabled deployer");
                check(restored.entity.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO).getStackInSlot(0).getCount() == 17, "restore lost cargo");
                check(restored.entity.getContraption().getStorage().getFluids().storages.get(BlockPos.ZERO).getFluidInTank(0).getAmount() == 1250, "restore lost fluid");
                check(recovery.blocks() == 1, "snapshot recovery rejected muted mechanism");
                // A new checkpoint may start another period under the same disabled policy.
                CreateCartAudit.state(entity).sequence++;
                CreateCaptureGaps.remember(entity);
                check(rows.rows().size() == 2, "new lifecycle period missing safety barrier");
                // Real tickActors wrapper must mute capture without stopping a native drill.
                CaptureTypePolicy.install(CaptureTypePolicy.changed(0, CaptureTypePolicy.CREATE_DRILL, false));
                var target = world.getSharedSpawnPos().offset(700, 96, 700);
                world.getChunkAt(target); world.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
                var liveCargo = (net.neoforged.neoforge.items.IItemHandlerModifiable)body.getStorage().getAllItemStorages().get(BlockPos.ZERO);
                liveCargo.setStackInSlot(0, ItemStack.EMPTY);
                int beforeDrill = rows.rows().size();
                var drillInfo = new StructureBlockInfo(new BlockPos(2, 0, 1), AllBlocks.MECHANICAL_DRILL.getDefaultState()
                        .setValue(com.simibubi.create.content.kinetics.drill.DrillBlock.FACING, Direction.EAST), null);
                body.getBlocks().put(drillInfo.pos(), drillInfo);
                var drill = new MovementContext(world, drillInfo, body);
                drill.motion = new net.minecraft.world.phys.Vec3(1, 0, 0); drill.relativeMotion = drill.motion;
                drill.stall = true; drill.data.put("BreakingPos", net.minecraft.nbt.NbtUtils.writeBlockPos(target)); drill.data.putInt("Progress", 9);
                body.getActors().add(org.apache.commons.lang3.tuple.MutablePair.of(drillInfo, drill));
                entity.setPos(target.getX() - 3, target.getY(), target.getZ() - 1);
                HotPathMetrics.start();
                entity.tickActors();
                check(HotPathMetrics.stop().contains("NBT snapshot calls: 0,"), "muted native drill serialized NBT");
                check(world.getBlockState(target).isAir() && liveCargo.getStackInSlot(0).is(Items.COBBLESTONE), "capture switch stopped native drill");
                check(rows.rows().size() == beforeDrill + 1, "muted native drill emitted detailed cargo/world rows");
                CaptureTypePolicy.install(0);
                check(CreateCapturePolicy.actor(context), "live re-enable failed");
                check(!CaptureTypePolicy.suppressed(), "muted callback scope leaked");
            }
            // Durable mask roundtrip retains old IDs and all newly appended switches.
            var data = LoggerServerData.load(new CompoundTag(), world.registryAccess()); data.setCaptureMask(CaptureTypePolicy.ALL_DISABLED);
            var saved = data.save(new CompoundTag(), world.registryAccess());
            var loaded = LoggerServerData.load(saved, world.registryAccess());
            check(loaded.captureMask() == CaptureTypePolicy.ALL_DISABLED, "extended settings did not survive SavedData");
        } finally { HotPathMetrics.clear(); CaptureTypePolicy.restoreSuppression(false); CaptureTypePolicy.install(previous); CreateCaptureGaps.clear(); }
        System.out.println("CREATE_MECHANISM_SWITCHES_NATIVE_OK: exact families, 1000 muted callbacks/one barrier, lifecycle+cargo recovery, live reenable and durable mask");
    }
}
