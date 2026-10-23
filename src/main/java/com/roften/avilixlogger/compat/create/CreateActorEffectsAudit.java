package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.world.entity.item.ItemEntity;

/** Sparse actor operations: source, direct actor NBT and emitted drops share a construction. */
public final class CreateActorEffectsAudit {
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private CreateActorEffectsAudit() {}
    public static Scope begin(MovementContext context,String reason,boolean internal,boolean structure){
        var entity=context.contraption.entity;
        return entity==null||!CreateCapturePolicy.actors(entity)||!CreateCargoAudit.active(entity)?null:new Scope(context,reason,internal,structure);
    }
    public static void spawning(ItemEntity drop){
        var scope=CURRENT.get();if(scope==null)return;
        var cart=CreateCartAudit.state(scope.entity);
        if(cart!=null)drop.getPersistentData().putUUID("AvilixCartDrop",cart.id);
        else drop.getPersistentData().putString("AvilixCreateDropSource",CreateMechanismContext.decorate(
                CreateContraptionSources.kind(scope.entity).source(CreateContraptionSources.id(scope.entity),"actor_drop")));
    }
    public static void spawned(ItemEntity drop){
        var scope=CURRENT.get();if(scope==null)return;
        // The existing cart join handler publishes this same drop under its active cart context.
        if(CreateCartAudit.enabled())return;
        var row=CreateCargoAudit.row(scope.entity,ActionType.ENTITY_SPAWN,"actor_drop",null,null,"actor world item drop");
        EntityLogIdentity.capture(row,drop);row.x=drop.blockPosition().getX();row.y=drop.blockPosition().getY();row.z=drop.blockPosition().getZ();row.count=drop.getItem().getCount();
        row.deferSnapshot(LogEntry.SnapshotField.ITEM,NbtSerde.snapshotItemStack(drop.getItem(),drop.registryAccess()));
        row.deferSnapshot(LogEntry.SnapshotField.ENTITY,NbtSerde.snapshotEntity((net.minecraft.server.level.ServerLevel)drop.level(),drop));
        CreateCargoAudit.deliver(scope.entity,row);
    }
    public static final class Scope implements AutoCloseable {
        final AbstractContraptionEntity entity;final Scope previous;
        final CreateCargoAudit.OperationScope operation;final CreateStructureAudit.Interaction structure;
        private boolean successful;
        private boolean closed;
        Scope(MovementContext context,String reason,boolean internal,boolean track){
            entity=context.contraption.entity;previous=CURRENT.get();operation=CreateCargoAudit.operation(context,reason,internal);
            try{structure=track?CreateStructureAudit.interaction(entity,context.localPos):null;CURRENT.set(this);}
            catch(RuntimeException|Error failure){if(operation!=null)operation.close();throw failure;}
        }
        public void success(){successful=true;}
        public void close(){if(closed)return;closed=true;
            try{
                if(structure!=null)structure.close();
                if(!successful){var failed=new net.minecraft.nbt.CompoundTag();failed.putBoolean("Complete",false);failed.putString("Kind","actor_result");
                    CreateCargoAudit.deliver(entity,CreateCargoAudit.row(entity,ActionType.CART_TRANSFER,"actor_result",failed,failed,"actor callback failed; joint undo blocked"));}
            }
            finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);if(operation!=null)operation.close();}
        }
    }
}
