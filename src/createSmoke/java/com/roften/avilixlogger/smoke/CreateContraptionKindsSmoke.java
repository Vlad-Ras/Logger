package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.MutablePair;
import java.util.UUID;

/** Dev-only checks of the native registry, actual actor callbacks and server GUI filters. */
public final class CreateContraptionKindsSmoke {
    private static final class CountingBearing extends BearingContraption {
        int writes;
        CountingBearing() { super(false, Direction.UP); }
        @Override public net.minecraft.nbt.CompoundTag writeNBT(net.minecraft.core.HolderLookup.Provider registries, boolean packet) {
            writes++;return super.writeNBT(registries,packet);
        }
    }
    private static void singleSnapshot(ServerLevel world) throws Exception {
        var entity = new ControlledContraptionEntity(AllEntityTypes.CONTROLLED_CONTRAPTION.get(),world);
        var contraption = new CountingBearing();contraption.anchor=BlockPos.ZERO;
        contraption.bounds=new net.minecraft.world.phys.AABB(BlockPos.ZERO);
        var set=ControlledContraptionEntity.class.getDeclaredMethod("setContraption",Contraption.class);
        set.setAccessible(true);set.invoke(entity,contraption);
        contraption.getBlocks().put(BlockPos.ZERO,new StructureBlockInfo(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),null));
        entity.setRotationAxis(Direction.Axis.Y);
        var controller=ControlledContraptionEntity.class.getDeclaredField("controllerPos");controller.setAccessible(true);controller.set(entity,BlockPos.ZERO.below());
        var tag=NbtSerde.snapshotEntity(world,entity);
        check(CreateContraptionSnapshotStore.hasCompleteContraptionSnapshot(tag),"bearing snapshot lost native payload");
        check(contraption.writes==1,"bearing snapshot serialized full construction "+contraption.writes+" times");
        check(tag.getCompound("Contraption").getCompound("Blocks").getList("BlockList",10).size()==1,"bearing snapshot lost blocks");
        contraption.getBlocks().clear();
        check(tag.getCompound("Contraption").getCompound("Blocks").getList("BlockList",10).size()==1,"bearing snapshot retained mutable block map");
        System.out.println("CREATE_BEARING_SINGLE_SERIALIZATION_OK");
    }
    public static void run(ServerLevel world) throws Exception {
        singleSnapshot(world);
        CreateCaptureSuppressionSmoke.run(world);
        var mountedContraption = new MountedContraption(); mountedContraption.anchor = BlockPos.ZERO;
        mountedContraption.bounds = new net.minecraft.world.phys.AABB(BlockPos.ZERO);
        var mounted = OrientedContraptionEntity.create(world, mountedContraption, Direction.NORTH);
        check(CreateContraptionSources.kind(mounted) == CreateContraptionKind.MINECART, "native mounted type");
        check(CreateContraptionSources.id(mounted).equals(CreateCartAudit.state(mounted).id), "native cart identity");
        var train = new CarriageContraptionEntity(AllEntityTypes.CARRIAGE_CONTRAPTION.get(), world);
        var carriage = new CarriageContraption(); carriage.anchor = BlockPos.ZERO;
        carriage.bounds = new net.minecraft.world.phys.AABB(BlockPos.ZERO);
        var baseSet = AbstractContraptionEntity.class.getDeclaredMethod("setContraption", Contraption.class);
        baseSet.setAccessible(true); baseSet.invoke(train, carriage);
        train.trainId = UUID.randomUUID();
        check(CreateContraptionSources.kind(train) == CreateContraptionKind.TRAIN, "native carriage type");
        check(CreateContraptionSources.id(train).equals(train.trainId), "native train identity");
        var bearing = new ControlledContraptionEntity(AllEntityTypes.CONTROLLED_CONTRAPTION.get(), world);
        var contraption = new BearingContraption(false, Direction.UP); contraption.anchor = BlockPos.ZERO;
        contraption.bounds = new net.minecraft.world.phys.AABB(BlockPos.ZERO);
        var set = ControlledContraptionEntity.class.getDeclaredMethod("setContraption", Contraption.class);
        set.setAccessible(true); set.invoke(bearing, contraption);
        check(CreateContraptionSources.kind(bearing) == CreateContraptionKind.BEARING, "native bearing type");
        check(CreateContraptionSources.id(bearing).equals(bearing.getUUID()), "bearing entity identity");
        var visited = new int[1];
        MovementBehaviour.REGISTRY.register(Blocks.DIAMOND_BLOCK, new MovementBehaviour() {
            private void checkStamp() {
                var stamp = CreateMechanismContext.current();
                check(stamp != null && stamp.blockId().equals("minecraft:diamond_block")
                        && stamp.x() == 2 && stamp.y() == -1 && stamp.z() == 3, "actor identity missing in native callback");
                visited[0]++;
            }
            public void tick(MovementContext context) { checkStamp(); }
            public void visitNewPosition(MovementContext context, BlockPos pos) { checkStamp(); }
            public void onSpeedChanged(MovementContext context, Vec3 before, Vec3 after) { checkStamp(); }
        });
        var info = new StructureBlockInfo(new BlockPos(2, -1, 3), Blocks.DIAMOND_BLOCK.defaultBlockState(), null);
        var context = new MovementContext(world, info, contraption);
        context.position = new Vec3(-10, 0, 0); context.firstMovement = false;
        contraption.getActors().add(MutablePair.of(info, context));
        var source = CreateContraptionKind.BEARING.source(bearing.getUUID(), "work");
        var previous = MutationSourceResolver.enter(source);
        try {
            bearing.tickActors();
            check(visited[0] == 3, "native visit/speed/tick callbacks were not wrapped");
            var cached = CreateMechanismAudit.enter(context);
            var first = CreateMechanismContext.current(); CreateMechanismContext.restore(cached);
            cached = CreateMechanismAudit.enter(context);
            check(first == CreateMechanismContext.current(), "actor stamp allocated in unchanged callback");
            CreateMechanismContext.restore(cached);
        } finally { MutationSourceResolver.restore(previous); }
        check(CreateMechanismContext.current() == null, "native actor context leaked");
        var flags = com.roften.avilixlogger.LoggerConfig.VALUES;
        boolean bearingEnabled = flags.logCreateBearings.get();
        boolean actorEnabled = flags.logCreateActorWork.get();
        boolean cargoEnabled = flags.logCreateCargoTransfers.get();
        boolean lifecycleEnabled = flags.logCreateLifecycle.get();
        boolean movementEnabled = flags.logCreateMovement.get();
        try {
            flags.logCreateBearings.set(false);
            check(CreateCapturePolicy.mutedEntity(bearing), "bearing tick must be muted");
            check(!CreateCapturePolicy.allows(bearing), "bearing must be excluded");
            check(CreateCapturePolicy.allows(train) && CreateCapturePolicy.allows(mounted), "other construction types were disabled");
            check(CreateMechanismAudit.enter(context) == null && CreateMechanismContext.current() == null,
                    "disabled bearing still allocated actor stamp");
            flags.logCreateBearings.set(true);
            flags.logCreateActorWork.set(false);
            check(!CreateCapturePolicy.actors(bearing) && CreateCapturePolicy.cargo(bearing),
                    "actor flag unexpectedly switched cargo category");
            flags.logCreateActorWork.set(true);
            flags.logCreateCargoTransfers.set(false);
            check(!CreateCapturePolicy.cargo(bearing) && CreateCapturePolicy.lifecycle(bearing),
                    "cargo flag unexpectedly disabled lifecycle");
            flags.logCreateCargoTransfers.set(true);
            flags.logCreateLifecycle.set(false);
            check(!CreateCapturePolicy.lifecycle(bearing) && CreateCapturePolicy.cargo(bearing),
                    "lifecycle flag unexpectedly disabled cargo");
            flags.logCreateLifecycle.set(true);
            flags.logCreateMovement.set(false);
            check(!CreateCapturePolicy.movement(bearing) && CreateCapturePolicy.cargo(bearing),
                    "movement flag unexpectedly disabled cargo");
        } finally {
            flags.logCreateBearings.set(bearingEnabled);
            flags.logCreateActorWork.set(actorEnabled);
            flags.logCreateCargoTransfers.set(cargoEnabled);
            flags.logCreateLifecycle.set(lifecycleEnabled);
            flags.logCreateMovement.set(movementEnabled);
        }
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), "type_filter_fixture");
        var player = new net.minecraft.server.level.ServerPlayer(world.getServer(), world, profile, net.minecraft.server.level.ClientInformation.createDefault());
        var build = com.roften.avilixlogger.net.LoggerNetwork.class.getDeclaredMethod("buildBaseGuiQuery", net.minecraft.server.level.ServerPlayer.class, com.roften.avilixlogger.net.GuiFilters.class, int.class);
        build.setAccessible(true);
        var row = new LogEntry(); row.source = CreateMechanismContext.decorate(source); row.type = ActionType.BLOCK_BREAK;
        var unrelated = new LogEntry(); unrelated.source = CreateContraptionKind.MINECART.source(UUID.randomUUID(), "work"); unrelated.type = ActionType.BLOCK_BREAK;
        var gf = new com.roften.avilixlogger.net.GuiFilters(1, 3, 11, -1, -1, "", bearing.getUUID().toString(), "", "");
        var query = (LogQuery)build.invoke(null, player, gf, 25);
        check(query.types == null && query.sourcePrefix.equals(CreateContraptionKind.BEARING.prefix() + bearing.getUUID() + ":"), "bearing SQL filter discarded work events");
        check(query.copy().sourcePrefix.equals(query.sourcePrefix), "query copy lost construction filter");
        var filtered = java.util.List.of(row, unrelated).stream().filter(e->LogSearchPredicate.matches(e,query)).toList();
        check(filtered.size() == 1 && filtered.getFirst() == row, "GUI leaked a different kind or discarded a world event");
        System.out.println("CREATE_CONTRAPTION_KINDS_NATIVE_OK registry=cart+train+bearing actors=visit+speed+tick cache=reused gui=type+id+world-event");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
