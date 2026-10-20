package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.compat.create.mixin.CartStorageAccessor;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.kinetics.deployer.*;
import com.simibubi.create.content.kinetics.drill.*;
import com.simibubi.create.content.trains.entity.*;
import com.simibubi.create.impl.contraption.storage.FallbackMountedStorage;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.apache.commons.lang3.tuple.MutablePair;
import java.util.*;

/** Real Create mechanics + detached capture/results. Does not authorize the production journal. */
public final class CreateMechanismActionsSmoke {
    private static final BlockPos LOCAL = new BlockPos(2, 0, 1);
    private static final UUID OWNER = UUID.randomUUID();
    public static final class Probe {
        UUID cancelAttack; int drillDrops; CreateContraptionKind expected;
        @SubscribeEvent public void attack(AttackEntityEvent event) {
            if (event.getTarget().getUUID().equals(cancelAttack)) event.setCanceled(true);
        }
        @SubscribeEvent public void drop(EntityJoinLevelEvent event) {
            if (!(event.getEntity() instanceof ItemEntity)) return;
            var source = CreateMechanismContext.decorate(MutationSourceResolver.resolveExternalSource());
            if (!Objects.equals(CreateMechanismContext.description(source), "create:mechanical_drill@2,0,1")) return;
            check(CreateContraptionKind.fromSource(source) == expected, "drill spill lost construction type"); drillDrops++;
        }
    }
    public static void run(ServerLevel world) throws Exception {
        var probe = new Probe(); NeoForge.EVENT_BUS.register(probe); int scenarios = 0;
        try {
            int index = 0;
            for (var kind : new CreateContraptionKind[]{CreateContraptionKind.MINECART, CreateContraptionKind.TRAIN, CreateContraptionKind.BEARING}) {
                var target = new BlockPos(8 + 8 * index++, 128, 16);
                world.getChunkAt(target); world.setBlock(target.below(), Blocks.STONE.defaultBlockState(), 3);
                var entity = entity(world, kind, new ItemStackHandler(1)); var contraption = entity.getContraption();
                var cargo = (net.neoforged.neoforge.items.IItemHandlerModifiable)contraption.getStorage().getAllItemStorages().get(BlockPos.ZERO);
                var data = new CompoundTag(); data.putUUID("Owner", OWNER); data.putString("Mode", "USE");
                var info = new StructureBlockInfo(LOCAL, AllBlocks.DEPLOYER.getDefaultState().setValue(DeployerBlock.FACING, Direction.EAST), data);
                var context = new MovementContext(world, info, contraption); context.position = Vec3.atCenterOf(target);
                var player = new DeployerFakePlayer(world, OWNER); context.temporaryData = player;
                var actor = (DeployerMovementBehaviour)com.simibubi.create.api.behaviour.movement.MovementBehaviour.REGISTRY.get(info.state());
                check(actor != null, "native deployer actor unavailable");
                world.setBlock(target, Blocks.AIR.defaultBlockState(), 3); player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 2));
                var placed = action(actor, context, target, player, "USE");
                check(world.getBlockState(target).is(Blocks.STONE), "native deployer did not place a block");
                check(placed != null && placed.count == 1 && metadata(placed).getString("Result").equals("item_use"), "placement result/consumption absent");
                check(placed.beBefore.contains("count:2") && placed.beAfter.contains("count:1"), "placement hand snapshots incorrect"); checkIdentity(placed, kind, entity); scenarios++;

                world.setBlock(target, Blocks.LEVER.defaultBlockState().setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR), 3);
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                var lever = action(actor, context, target, player, "USE");
                check(world.getBlockState(target).getValue(LeverBlock.POWERED), "native lever action did not happen");
                check(lever != null && metadata(lever).getString("Result").equals("block_use"), "empty-hand successful block use not recorded"); scenarios++;

                world.setBlock(target, Blocks.AIR.defaultBlockState(), 3); player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                check(action(actor, context, target, player, "USE") == null, "no-op activation produced a successful action"); scenarios++;

                var cow = new Cow(EntityType.COW, world); cow.setPos(Vec3.atCenterOf(target)); check(world.addFreshEntity(cow), "cow fixture rejected");
                cow.setCustomName(net.minecraft.network.chat.Component.literal("NamedCow"));
                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
                var milk = action(actor, context, target, player, "USE");
                check(milk != null && cow.getUUID().equals(milk.entityUuid) && metadata(milk).getString("Result").equals("entity_use"), "cow interaction/result target absent");
                check("NamedCow".equals(milk.entityName),"deployer entity-use target name absent");
                check(player.getMainHandItem().is(Items.MILK_BUCKET), "cow was not milked"); scenarios++;

                player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
                float health = cow.getHealth(); var attack = action(actor, context, target, player, "PUNCH");
                check(cow.getHealth() < health && attack != null && metadata(attack).getString("Result").equals("attack"), "native entity attack result missing"); scenarios++;
                check("NamedCow".equals(attack.entityName)&&CreateDeployerDetails.describe(world,attack).stream().anyMatch(line->line.getString().contains("NamedCow")),"deployer attack target name absent from details");
                cow.invulnerableTime = 0; cow.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_ABSORPTION).setBaseValue(100); cow.setAbsorptionAmount(100); health = cow.getHealth();
                var absorbed = action(actor, context, target, player, "PUNCH");
                check(absorbed != null && cow.getHealth() == health && cow.getAbsorptionAmount() < 100 && metadata(absorbed).getFloat("Damage") > 0, "absorbed damage missed when health did not change"); scenarios++;
                cow.invulnerableTime = 0; probe.cancelAttack = cow.getUUID(); health = cow.getHealth();
                check(action(actor, context, target, player, "PUNCH") == null && cow.getHealth() == health, "cancelled entity attack reported as success");
                probe.cancelAttack = null; cow.discard(); scenarios++;

                data.putString("Mode", "PUNCH"); player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
                world.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
                var mined = action(actor, context, target, player, "PUNCH");
                check(world.getBlockState(target).isAir() && mined != null && metadata(mined).getString("Result").equals("harvest"), "native deployer harvest result missing");
                check(metadata(mined).getCompound("HeldItem").getCompound("components").contains("minecraft:damage"), "tool wear/components lost"); scenarios++;

                // Actor.tick is invoked through the real tickActors wrapper; stall preserves the prepared break target.
                contraption.getActors().clear(); cargo.setStackInSlot(0, ItemStack.EMPTY);
                var drillInfo = new StructureBlockInfo(LOCAL, AllBlocks.MECHANICAL_DRILL.getDefaultState().setValue(DrillBlock.FACING, Direction.EAST), null);
                var drill = new MovementContext(world, drillInfo, contraption); drill.motion = new Vec3(1, 0, 0); drill.relativeMotion = drill.motion;
                drill.stall = true; drill.data.put("BreakingPos", NbtUtils.writeBlockPos(target)); drill.data.putInt("Progress", 9);
                contraption.getActors().add(MutablePair.of(drillInfo, drill)); entity.setPos(target.getX() - 3, target.getY(), target.getZ() - 1);
                world.setBlock(target, Blocks.STONE.defaultBlockState(), 3); String old = MutationSourceResolver.enter(kind.source(CreateContraptionSources.id(entity), "work"));
                try { entity.tickActors(); } finally { MutationSourceResolver.restore(old); }
                check(world.getBlockState(target).isAir() && cargo.getStackInSlot(0).is(Items.COBBLESTONE), "native drill did not break and collect cargo"); scenarios++;
                cargo.setStackInSlot(0, new ItemStack(Items.DIAMOND, 64)); drill.stall = true;
                drill.data.put("BreakingPos", NbtUtils.writeBlockPos(target)); drill.data.putInt("Progress", 9); world.setBlock(target, Blocks.STONE.defaultBlockState(), 3);
                probe.expected = kind; int drops = probe.drillDrops; old = MutationSourceResolver.enter(kind.source(CreateContraptionSources.id(entity), "work"));
                try { entity.tickActors(); } finally { MutationSourceResolver.restore(old); }
                check(probe.drillDrops > drops, "full drill cargo did not spill with exact mechanism attribution"); scenarios++;
                data.remove("Owner"); data.putString("Mode", "USE"); context.stall = false;
                var anonymous = new DeployerFakePlayer(world, null); context.temporaryData = anonymous;
                world.setBlock(target, Blocks.LEVER.defaultBlockState().setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR), 3);
                try (var capture = CreateDeployerAudit.capture(context, target, anonymous, "USE")) {
                    check(CauseContext.peek() == null, "fallback FakePlayer UUID used as an owner");
                    actor.visitNewPosition(context, target); var row = capture.finish(true); row.materializeSnapshots();
                    check(row.actorUuid == null && !metadata(row).hasUUID("Owner"), "unknown mechanism owner was guessed");
                }
                scenarios++;
            }
            check(CreateActorContext.current() == null && CreateMechanismContext.current() == null && CauseContext.peek() == null, "actor/cause scope leaked");
            System.out.println("CREATE_MECHANISM_ACTIONS_NATIVE_OK: " + scenarios + " scenarios; real deployer place+lever+milk+attack+cancel+harvest+tool-wear and drill cargo+spill, cart/train/bearing, detached hand states+owner+mechanism");
        } finally { NeoForge.EVENT_BUS.unregister(probe); }
    }
    private static LogEntry action(DeployerMovementBehaviour actor, MovementContext context, BlockPos target, DeployerFakePlayer player, String mode) {
        context.blockEntityData.putString("Mode", mode); context.stall = false;
        try (var capture = CreateDeployerAudit.capture(context, target, player, mode)) {
            check(capture != null && CauseContext.peek() == null, "technical player replaced the known owner");
            var other = new net.minecraft.server.level.ServerPlayer(context.world.getServer(), (ServerLevel)context.world,
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "bystander"), net.minecraft.server.level.ClientInformation.createDefault());
            try (var unrelated = CauseContext.push(other, CauseContext.Kind.USE_BLOCK, target, ItemStack.EMPTY)) {
                check(other.getUUID().equals(CauseContext.peek().actorUuid()), "mechanism context replaced a real player's actor");
            }
            check(CauseContext.peek() == null, "nested cause did not restore mechanism owner");
            actor.visitNewPosition(context, target);
            for (int ticks = 0; context.stall && ticks < 100; ticks++) actor.tick(context);
            check(!context.stall, "native deployer did not finish its multi-tick action");
            var row = capture.finish(true); if (row != null) row.materializeSnapshots(); return row;
        }
    }
    private static void checkIdentity(LogEntry row, CreateContraptionKind kind, AbstractContraptionEntity entity) {
        check(row.type == ActionType.CREATE_DEPLOYER_ACTION && CreateContraptionKind.fromSource(row.source) == kind && CreateContraptionSources.id(entity).equals(CreateContraptionKind.id(row.source)), "deployer construction identity incorrect");
        check(row.actorUuid == null && "SYSTEM".equals(row.actorName) && OWNER.equals(metadata(row).getUUID("Owner")), "deployer fake actor persisted");
        check("create:deployer@2,0,1".equals(CreateMechanismContext.description(row.source)), "deployer local mechanism identity absent");
    }
    private static CompoundTag metadata(LogEntry row) { return NbtSerde.fromSnbt(row.beAfter); }
    private static AbstractContraptionEntity entity(ServerLevel world, CreateContraptionKind kind, ItemStackHandler cargo) throws Exception {
        Contraption c = switch (kind) { case MINECART -> new MountedContraption(); case TRAIN -> new CarriageContraption(); default -> new BearingContraption(false, Direction.UP); };
        c.anchor = BlockPos.ZERO; c.bounds = new AABB(BlockPos.ZERO);
        if (c instanceof CarriageContraption) {
            var nativeStorage = Contraption.class.getDeclaredField("storage"); nativeStorage.setAccessible(true);
            var proxy = CarriageContraption.class.getDeclaredField("storageProxy"); proxy.setAccessible(true); proxy.set(c, nativeStorage.get(c));
        }
        ((CartStorageAccessor)c.getStorage()).avilixlogger$addItem(new FallbackMountedStorage(cargo), BlockPos.ZERO);
        AbstractContraptionEntity entity;
        if (kind == CreateContraptionKind.MINECART) entity = OrientedContraptionEntity.create(world, c, Direction.NORTH);
        else {
            entity = kind == CreateContraptionKind.TRAIN ? new CarriageContraptionEntity(AllEntityTypes.CARRIAGE_CONTRAPTION.get(), world) : new ControlledContraptionEntity(AllEntityTypes.CONTROLLED_CONTRAPTION.get(), world);
            var set = AbstractContraptionEntity.class.getDeclaredMethod("setContraption", Contraption.class); set.setAccessible(true); set.invoke(entity, c);
            if (entity instanceof CarriageContraptionEntity train) train.trainId = UUID.randomUUID();
        }
        return entity;
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
