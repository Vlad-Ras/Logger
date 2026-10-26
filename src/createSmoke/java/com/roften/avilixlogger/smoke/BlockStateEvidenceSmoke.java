package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import java.util.UUID;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;

/** Detached production row/journal/details kernels; never authorizes or publishes capture. */
final class BlockStateEvidenceSmoke {
    static void run(ServerLevel level) {
        var pos=new BlockPos(-37,103,61);
        var cause=new CauseContext.Cause(UUID.randomUUID(),"StateActor",CauseContext.Kind.USE_ITEM,BlockPos.ZERO,"minecraft:stick",1);
        var before=Blocks.OAK_STAIRS.defaultBlockState();var after=before.cycle(BlockStateProperties.HORIZONTAL_FACING);
        var row=changed(level,pos,before,after,cause);
        check(row.type==ActionType.BLOCK_INTERACT&&row.actorUuid.equals(cause.actorUuid())&&row.actorName.equals(cause.actorName()),"setting actor/type lost");
        check(row.x==pos.getX()&&row.y==pos.getY()&&row.z==pos.getZ(),"setting used actor/click position instead of changed block");
        check(BlockChangeDetails.changes(row).stream().anyMatch(s->s.contains("facing")),"setting property absent from details");
        check(changed(level,pos,before,after,null).actorUuid==null,"autonomous setting invented a player");
        org.slf4j.LoggerFactory.getLogger("AvilixLogger/StateEvidenceSmoke").info("BLOCK_STATE_EVIDENCE_NATIVE_OK: exact mutation position, actor/system separation, journal and property details");
    }
    static LogEntry changed(ServerLevel level,BlockPos target,BlockState before,BlockState after,CauseContext.Cause cause) {
        check(!before.equals(after),"fixture attempted a no-op state row");
        String source=cause==null?"system:unattributed":"player:"+cause.kind().name().toLowerCase(java.util.Locale.ROOT);
        var cap=new SetBlockCapture(target,level.dimension().location().toString(),before,null,null,source,cause==null?null:cause.kind(),cause==null?null:cause.actorUuid(),cause==null?"SYSTEM["+source+"]":cause.actorName());
        var row=cap.entry(after,net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString(),System.currentTimeMillis());row.materializeSnapshots();
        var restored=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row),LogEntry.class);
        check(row.blockBefore.equals(restored.blockBefore)&&row.blockAfter.equals(restored.blockAfter)&&row.source.equals(restored.source),"state journal lost before/after/source");
        check(NbtSerde.fromSnbt(restored.blockBefore).getCompound("Properties").equals(net.minecraft.nbt.NbtUtils.writeBlockState(before).getCompound("Properties")),"journal lost original properties");
        check(NbtSerde.fromSnbt(restored.blockAfter).getCompound("Properties").equals(net.minecraft.nbt.NbtUtils.writeBlockState(after).getCompound("Properties")),"journal lost actual properties");
        check(!BlockChangeDetails.changes(restored).isEmpty(),"state difference invisible in details");return restored;
    }
}
