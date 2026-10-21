package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import java.util.*;

/** Full payloads at lifecycle boundaries only; relations never require a nearby-world search. */
public final class CreateLifecycleAudit {
    private static final ThreadLocal<Disassembly> CURRENT=new ThreadLocal<>();
    private CreateLifecycleAudit() {}
    public static boolean handles(Entity entity){return entity instanceof AbstractContraptionEntity ce&&ce.getContraption()!=null&&CreateContraptionSources.kind(ce)!=CreateContraptionKind.UNKNOWN;}
    public static void join(EntityJoinLevelEvent event){
        if(event.isCanceled()||!CreateCartAudit.enabled()||!(event.getEntity() instanceof AbstractContraptionEntity entity)
                ||CreateCartAudit.state(entity)!=null||entity.getContraption()==null||CreateContraptionSources.kind(entity)==CreateContraptionKind.UNKNOWN)return;
        try{record(entity,event.loadedFromDisk()?ActionType.CART_LOAD:ActionType.CART_ASSEMBLE,event.loadedFromDisk()?"load":"assemble",null,snapshot(entity,"entity"),"entity representation joined");}
        catch(RuntimeException failure){CreateCartAudit.failed("contraption lifecycle join",failure);}
    }
    public static CompoundTag snapshot(AbstractContraptionEntity entity,String form){
        if(!(entity.level() instanceof ServerLevel level)||!level.getServer().isSameThread()||entity.getContraption()==null)throw new IllegalStateException("Снимок конструкции требует серверный поток");
        var tag=new CompoundTag();tag.putInt("Format",2);tag.putString("Kind","lifecycle");tag.putString("Form",form);
        tag.putUUID("Construction",CreateContraptionSources.id(entity));tag.putString("ConstructionKind",CreateContraptionSources.kind(entity).name());
        tag.putUUID("StorageEntity",entity.getUUID());tag.putString("Dimension",entity.level().dimension().location().toString());
        if(form.equals("entity"))tag.put("Entity",CreateCartAudit.save(entity));
        tag.putLong("Anchor",entity.getContraption().anchor.asLong());
        tag.put("Links",links(entity));
        if(entity instanceof CarriageContraptionEntity carriage&&carriage.getCarriage() instanceof ContraptionIdentity identity){
            tag.putUUID("Carriage",identity.avilixlogger$identity());var dimensions=new ListTag();var status=new ListTag();
            var present=new ArrayList<>(carriage.getCarriage().getPresentDimensions());present.sort(Comparator.comparing(d->d.location().toString()));
            for(var dimension:present){
                dimensions.add(StringTag.valueOf(dimension.location().toString()));
                var entry=new CompoundTag();entry.putString("Dimension",dimension.location().toString());
                var representation=carriage.getCarriage().getDimensionalIfPresent(dimension);
                var actual=representation==null||representation.entity==null?null:representation.entity.get();
                boolean leaving=dimension.equals(entity.level().dimension())&&(form.equals("unloaded")||form.equals("removed")||form.equals("blocks"));
                boolean loaded=actual!=null&&!actual.isRemoved()&&!leaving;
                entry.putBoolean("Loaded",loaded);if(loaded)entry.putUUID("Entity",actual.getUUID());
                status.add(entry);
            }
            tag.put("Representations",dimensions);tag.put("RepresentationStatus",status);
            tag.putBoolean("Portal",carriage.getCarriage().presentInMultipleDimensions());
            var nativeCarriage=carriage.getCarriage();
            boolean addressed=!entity.isRemoved()&&(form.equals("entity")||form.equals("unloaded"))&&nativeCarriage.train!=null&&nativeCarriage.train.carriages!=null&&nativeCarriage.train.carriages.indexOf(nativeCarriage)==carriage.carriageIndex;
            tag.putBoolean("TrainRepresentationsComplete",addressed);
            if(addressed)tag.put("TrainRepresentations",CreateTrainRepresentationState.capture(carriage));
            else tag.putString("TrainRepresentationsFailure","У представления нет активного payload или связи с составом");
        }
        if(tag.sizeInBytes()>128L*1024*1024)throw new IllegalStateException("Снимок конструкции превышает 128 MiB");
        return tag; // Every child was captured into fresh NBT above; do not duplicate the full carriage again.
    }
    public static ListTag links(AbstractContraptionEntity entity){
        var links=new ListTag();
        if(entity.getVehicle()!=null)links.add(link(entity.getVehicle(),"vehicle"));
        for(var passenger:entity.getPassengers()){if(links.size()>=512)throw new IllegalStateException("Больше 512 связей конструкции");links.add(link(passenger,"passenger"));}
        for(var entry:((com.roften.avilixlogger.compat.create.mixin.CartContraptionAccessor)entity.getContraption()).avilixlogger$subContraptions().entrySet()){
            if(links.size()>=512)throw new IllegalStateException("Больше 512 связей конструкции");
            var link=new CompoundTag();link.putString("Relation","stabilized");link.putUUID("Entity",entry.getKey());link.putLong("LocalPos",entry.getValue().getConnectedPos().asLong());links.add(link);
        }
        if(entity.getVehicle() instanceof AbstractMinecart cart){
            var controller=CapabilityMinecartController.getIfPresent(cart.level(),cart.getUUID());
            if(controller!=null)for(boolean main:new boolean[]{true,false}){
                UUID peer=controller.getCoupledCart(main);if(peer==null)continue;
                var link=new CompoundTag();link.putString("Relation",main?"coupled_main":"coupled_connected");link.putUUID("Entity",peer);
                link.putFloat("Length",controller.getCouplingLength(main));link.putBoolean("ThroughContraption",controller.hasContraptionCoupling(main));links.add(link);
            }
        }
        return links;
    }
    private static CompoundTag link(Entity entity,String relation){
        var tag=new CompoundTag();tag.putString("Relation",relation);tag.putUUID("Entity",entity.getUUID());tag.putString("Dimension",entity.level().dimension().location().toString());
        tag.putString("EntityType",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
        if(entity instanceof AbstractContraptionEntity peer&&peer.getContraption()!=null){tag.putUUID("Construction",CreateContraptionSources.id(peer));tag.putString("ConstructionKind",CreateContraptionSources.kind(peer).name());}
        return tag;
    }
    public static Disassembly disassembly(AbstractContraptionEntity entity){
        try{return !CreateStructuralAudit.covered(entity)&&CreateCartAudit.state(entity)==null&&CreateCargoAudit.active(entity)&&entity.isAlive()?new Disassembly(entity):null;}
        catch(RuntimeException failure){CreateCartAudit.failed("lifecycle before disassembly",failure);return null;}
    }
    public static void removed(AbstractContraptionEntity entity,Entity.RemovalReason reason){
        if(CreateCartAudit.state(entity)!=null||!CreateCargoAudit.active(entity)||entity.isRemoved())return;
        if(CreateStructuralAudit.covered(entity))return;
        var scope=CURRENT.get();if(scope!=null&&scope.entity==entity)return;
        boolean unload=reason==Entity.RemovalReason.UNLOADED_TO_CHUNK||reason==Entity.RemovalReason.UNLOADED_WITH_PLAYER||reason==Entity.RemovalReason.CHANGED_DIMENSION;
        record(entity,unload?ActionType.CART_UNLOAD:ActionType.CART_REMOVE,unload?"unload":"remove",snapshot(entity,"entity"),snapshot(entity,unload?"unloaded":"removed"),"reason="+reason);
    }
    private static void record(AbstractContraptionEntity entity,ActionType type,String phase,CompoundTag before,CompoundTag after,String detail){
        if(CreateCargoAudit.capture(entity,type,phase,before,after,detail))return;
        CreateCargoAudit.emit(entity,type,phase,before,after,detail);
    }
    public static final class Disassembly implements AutoCloseable {
        final AbstractContraptionEntity entity;final CompoundTag before;final Disassembly previous;final String previousSource;
        boolean success,closed;
        Disassembly(AbstractContraptionEntity entity){
            this.entity=entity;before=snapshot(entity,"entity");previous=CURRENT.get();
            previousSource=MutationSourceResolver.enter(CreateContraptionSources.kind(entity).source(CreateContraptionSources.id(entity),"disassemble"));CURRENT.set(this);
        }
        public void success(){success=true;}
        public void close(){if(closed)return;closed=true;
            try{var after=snapshot(entity,entity.isRemoved()?"blocks":"entity");after.putBoolean("Complete",success);
                if(entity.isRemoved()||!success)record(entity,success?ActionType.CART_DISASSEMBLE:ActionType.CART_TRANSFER,success?"disassemble":"actor_result",before,after,success?"disassembled":"incomplete disassembly");}
            catch(RuntimeException failure){CreateCartAudit.failed("lifecycle after disassembly",failure);}
            finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);MutationSourceResolver.restore(previousSource);}
        }
    }
    public static void clear(){CURRENT.remove();}
}
