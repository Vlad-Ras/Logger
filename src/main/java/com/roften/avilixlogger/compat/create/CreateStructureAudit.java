package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.util.*;

/** Snapshot only addressed blocks. A player interaction coalesces setBlock and in-place NBT changes. */
public final class CreateStructureAudit {
    private static final ThreadLocal<Interaction> CURRENT=new ThreadLocal<>();
    private CreateStructureAudit() {}
    public static CompoundTag beforeChange(AbstractContraptionEntity entity,BlockPos pos){
        if (!CreateCapturePolicy.actors(entity) || !CreateCapturePolicy.cargo(entity)) return null;
        var scope=CURRENT.get();if(scope!=null&&scope.entity==entity&&scope.track(pos))return null;
        return snapshot(entity,pos);
    }
    public static Interaction interaction(AbstractContraptionEntity entity,BlockPos pos){
        if(!CreateCapturePolicy.actors(entity) || !CreateCargoAudit.active(entity))return null;
        var current=CURRENT.get();if(current!=null&&current.entity==entity){current.track(pos);return null;}
        return new Interaction(entity,pos);
    }
    private static CompoundTag snapshot(AbstractContraptionEntity entity,BlockPos pos){
        var tag=new CompoundTag();tag.putString("Kind","structure");tag.putLong("LocalPos",pos.asLong());
        var block=entity.getContraption().getBlocks().get(pos);
        if(block!=null){tag.put("State",NbtUtils.writeBlockState(block.state()));if(block.nbt()!=null)tag.put("Data",block.nbt().copy());}
        return tag;
    }
    public static void changed(AbstractContraptionEntity entity,BlockPos pos,CompoundTag before){
        if(before==null || !CreateCapturePolicy.actors(entity) || !CreateCapturePolicy.cargo(entity))return;var after=snapshot(entity,pos);if(before.equals(after))return;
        if(CreateCargoAudit.capture(entity,ActionType.CART_STRUCTURE_CHANGE,"structure",before,after,"local="+pos.toShortString()))return;
        CreateCargoAudit.emit(entity,ActionType.CART_STRUCTURE_CHANGE,"structure",before,after,"local="+pos.toShortString());
    }
    public static final class Interaction implements AutoCloseable {
        private final AbstractContraptionEntity entity;
        private final Interaction previous;
        private final Map<BlockPos,CompoundTag> before=new LinkedHashMap<>();
        private boolean closed;
        private Interaction(AbstractContraptionEntity entity,BlockPos pos){this.entity=entity;previous=CURRENT.get();CURRENT.set(this);track(pos);}
        private boolean track(BlockPos pos){
            if(before.containsKey(pos))return true;
            if(before.size()>=512)return false;
            before.put(pos.immutable(),snapshot(entity,pos));return true;
        }
        @Override public void close(){
            if(closed)return;closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);
            for(var entry:before.entrySet())changed(entity,entry.getKey(),entry.getValue());
        }
    }
    public static void clear(){CURRENT.remove();}
}
