package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

/** A reserved item/package endpoint participates in the coordinator's compensation boundary. */
public final class CreateWorldItemRestore {
    private final MinecraftServer server;
    private final CreateWorldItemTransferPlan.Undo undo;
    private final ServerLevel level;
    private Entity entity;
    private final Entity staged;
    private boolean applied;
    public CreateWorldItemRestore(MinecraftServer server,CreateWorldItemTransferPlan.Undo undo){
        this.server=server;this.undo=undo;
        level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(undo.dimension())));
        if(level==null)throw new IllegalStateException("Измерение предмета недоступно");
        var pos=BlockPos.of(undo.before().getLong("Pos"));
        if(!level.hasChunkAt(pos))throw new IllegalStateException("Чанк исходного предмета не загружен");
        CartRestoreLocks.keepLoaded(level,pos);CartAuditContext.LOCKED_ENTITIES.add(undo.entity());
        for(var tag:new CompoundTag[]{undo.before(),undo.after()}){
            var nbt=tag.getCompound("EntityNBT");
            if(!nbt.getList("Passengers",10).isEmpty())throw new IllegalStateException("Предметная сущность содержит пассажиров");
            if(!tag.getCompound("Item").isEmpty())ItemStack.CODEC.parse(level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),tag.getCompound("Item"))
                    .result().orElseThrow(()->new IllegalStateException("Компоненты предметного endpoint не прочитаны полностью"));
        }
        verify(undo.after());entity=level.getEntity(undo.entity());
        staged=entity==null?EntityType.loadEntityRecursive(undo.before().getCompound("EntityNBT").copy(),level,e->e):null;
        if(entity==null&&(staged==null||!undo.entity().equals(staged.getUUID())))throw new IllegalStateException("Исходный предмет не восстановлен из снимка");
    }
    static CompoundTag stable(CompoundTag original){
        var tag=original.copy();for(String key:new String[]{"Pos","Motion","Rotation","FallDistance","Fire","Air","OnGround","PortalCooldown","TicksFrozen","Age","PickupDelay"})tag.remove(key);return tag;
    }
    private void verify(CompoundTag expected){
        var current=level.getEntity(undo.entity());var nbt=expected.getCompound("EntityNBT");
        for(var other:server.getAllLevels())if(other!=level&&other.getEntity(undo.entity())!=null)throw new IllegalStateException("Предметный endpoint перешёл в другое измерение");
        if(nbt.isEmpty()) {if(current!=null&&!current.isRemoved())throw new IllegalStateException("Всосанный предмет снова появился");return;}
        if(current==null||current.isRemoved())throw new IllegalStateException("Предметный остаток убран или выгружен");
        if(!level.hasChunkAt(current.blockPosition()))throw new IllegalStateException("Чанк предметного остатка не загружен");
        var actual=NbtSerde.snapshotEntity(level,current);
        if(actual==null||!stable(nbt).equals(stable(actual)))throw new IllegalStateException("Предметный остаток или его компоненты изменены");
        CartRestoreLocks.keepLoaded(level,current.blockPosition());
    }
    public void apply(){
        verify(undo.after());applied=true;
        if(entity==null){entity=staged;if(!level.addFreshEntity(entity))throw new IllegalStateException("Мир отклонил возврат всосанного предмета");}
        else entity.load(undo.before().getCompound("EntityNBT").copy());
        verifyBefore();
    }
    public void verifyAfter(){verify(undo.after());}
    public void verifyBefore(){verify(undo.before());}
    public UUID id(){return undo.entity();}
    public void compensate(){
        if(!applied)return;
        if(undo.after().getCompound("EntityNBT").isEmpty()){if(entity!=null&&!entity.isRemoved())entity.discard();entity=null;}
        else {entity.load(undo.after().getCompound("EntityNBT").copy());verify(undo.after());}
        applied=false;
    }
}
