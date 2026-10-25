package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Failure-inducing serializers and unloaded chunks exercise the real common snapshot boundary. */
final class ModpackBoundarySmoke {
    private static final class CountingBlock extends BlockEntity {
        int calls; boolean fail; final CompoundTag mutable=new CompoundTag();
        CountingBlock(BlockPos pos) { super(BlockEntityType.CHEST,pos,Blocks.CHEST.defaultBlockState()); }
        @Override protected void saveAdditional(CompoundTag tag,HolderLookup.Provider registries) {
            calls++; if(fail)throw new IllegalStateException("expected mod serializer failure");
            mutable.putInt("value",calls);tag.put("ModPayload",mutable);
        }
    }
    static void run(ServerLevel level) {
        BlockPos pos=level.getSharedSpawnPos();level.getChunkAt(pos);
        var block=new CountingBlock(pos);block.setLevel(level);
        var saved=NbtSerde.snapshotBlockEntity(level,block);
        check(saved!=null&&block.calls==1,"successful mod serializer executed more than once");
        block.mutable.putInt("value",999);
        check(saved.getCompound("ModPayload").getInt("value")==1,"mod reused a live payload in history");
        block.calls=0;block.fail=true;
        check(NbtSerde.snapshotBlockEntity(level,block)==null&&block.calls==1,"failed mod serializer retried or fabricated a complete snapshot");
        var offThread=java.util.concurrent.CompletableFuture.supplyAsync(()->NbtSerde.snapshotBlockEntity(level,block)).join();
        check(offThread==null&&block.calls==1,"mod serializer ran off the world thread");
        var unloaded=new BlockPos(20_000_000,80,20_000_000);check(!level.hasChunkAt(unloaded),"unloaded fixture unexpectedly loaded");
        var absent=new CountingBlock(unloaded);absent.setLevel(level);
        check(NbtSerde.snapshotBlockEntity(level,absent)==null&&absent.calls==0,"unloaded mod serializer invoked");
        check(ContainerSlotSnapshot.snapshotTag(level,unloaded)==null&&!level.hasChunkAt(unloaded),"audit forced a chunk load");
        int old=CaptureTypePolicy.mask();
        try {
            CaptureTypePolicy.install(CaptureTypePolicy.changed(old,2,false));
            check(LoggerEventHandlers.beginMenuAction(null)==null,"disabled container capture dereferenced player/menu");
        } finally { CaptureTypePolicy.install(old); }
        if(net.neoforged.fml.ModList.get().isLoaded("curios"))CuriosAuditSmoke.run(level);
        System.out.println("MODPACK_SNAPSHOT_BOUNDARIES_OK: one serializer call, failure refusal, detached payload, no off-thread world reads, no forced chunks, disabled menus");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
