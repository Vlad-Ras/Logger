package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.minecart.capability.*;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import java.util.*;

public final class CreateLifecycleSmoke {
    public static void run(ServerLevel world)throws Exception{
        var factory=CreateCargoSmoke.class.getDeclaredMethod("entity",ServerLevel.class,CreateContraptionKind.class);factory.setAccessible(true);
        var pos=world.getSharedSpawnPos().offset(26,40,26);world.getChunk(pos);int tested=0;
        for(var kind:List.of(CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER))for(String mode:List.of("snapshot","disassemble","remove","unload","nested","failure")){
            var entity=(AbstractContraptionEntity)factory.invoke(null,world,kind);entity.setPos(pos.getX(),pos.getY(),pos.getZ());
            prepare(entity,pos);entity.getContraption().getBlocks().put(BlockPos.ZERO,new StructureBlockInfo(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),null));
            check(world.addFreshEntity(entity),"lifecycle spawn");
            try(var capture=CreateCargoAudit.captureChanges(entity)){
                var snapshot=CreateLifecycleAudit.snapshot(entity,"entity");check(snapshot.getInt("Format")==2&&snapshot.getCompound("Entity").getCompound("Contraption").contains("Blocks"),"incomplete lifecycle checkpoint");
                check(snapshot.getUUID("Construction").equals(CreateContraptionSources.id(entity)),"lifecycle ID lost");
                var actor=kind.source(CreateContraptionSources.id(entity),"work");
                if(mode.equals("disassemble")){
                    entity.disassemble();check(entity.isRemoved(),"native disassembly did not remove entity");
                    var rows=capture.rows();check(rows.size()==1&&rows.getFirst().type==ActionType.CART_DISASSEMBLE,"generic disassembly missing/duplicated");rows.getFirst().materializeSnapshots();
                    check(NbtSerde.fromSnbt(rows.getFirst().beAfter).getString("Form").equals("blocks"),"world form missing");
                    check(CartLogDetails.describe(world,rows.getFirst()).stream().anyMatch(c->c.getString().startsWith("Форма конструкции:")),"lifecycle GUI details missing");
                }else if(mode.equals("remove")||mode.equals("unload")){
                    entity.remove(mode.equals("remove")?Entity.RemovalReason.DISCARDED:Entity.RemovalReason.UNLOADED_TO_CHUNK);
                    check(capture.rows().size()==1&&capture.rows().getFirst().type==(mode.equals("remove")?ActionType.CART_REMOVE:ActionType.CART_UNLOAD),"typed removal/unload missing");
                }else if(mode.equals("failure")){
                    try(var scope=CreateLifecycleAudit.disassembly(entity)){check(scope!=null,"missing disassembly scope");}
                    check(capture.rows().size()==1&&capture.rows().getFirst().type==ActionType.CART_TRANSFER,"partial disassembly missing");capture.rows().getFirst().materializeSnapshots();check(!NbtSerde.fromSnbt(capture.rows().getFirst().beAfter).getBoolean("Complete"),"failed disassembly marked complete");
                }else if(mode.equals("nested")){
                    var child=(AbstractContraptionEntity)factory.invoke(null,world,CreateContraptionKind.MINECART);check(child.startRiding(entity,true),"nested child mount");
                    try{var relations=CreateLifecycleAudit.links(entity);check(relations.stream().map(t->(CompoundTag)t).anyMatch(t->t.getUUID("Entity").equals(child.getUUID())&&t.hasUUID("Construction")),"nested logical relationship lost");}
                    finally{child.stopRiding();}
                }else{
                    var saved=snapshot.copy();entity.getContraption().getBlocks().clear();check(!saved.getCompound("Entity").getCompound("Contraption").getCompound("Blocks").getList("BlockList",10).isEmpty(),"checkpoint references live block map");
                }
                check(MutationSourceResolver.resolveExternalSource()==null,"lifecycle source leaked");tested++;
            }finally{boolean old=CartAuditContext.restoring(true);try{if(!entity.isRemoved())((CartEntityAccess)entity).avilixlogger$discardForRollback();}finally{CartAuditContext.restoring(old);}world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);}
        }
        var first=(OrientedContraptionEntity)factory.invoke(null,world,CreateContraptionKind.MINECART);var second=(OrientedContraptionEntity)factory.invoke(null,world,CreateContraptionKind.MINECART);
        var cart1=new Minecart(world,pos.getX(),pos.getY(),pos.getZ());var cart2=new Minecart(world,pos.getX()+4,pos.getY(),pos.getZ());
        first.startRiding(cart1,true);second.startRiding(cart2,true);world.addFreshEntity(cart1);world.addFreshEntity(cart2);world.addFreshEntity(first);world.addFreshEntity(second);
        var controller1=cart1.getData(com.simibubi.create.AllAttachmentTypes.MINECART_CONTROLLER);var controller2=cart2.getData(com.simibubi.create.AllAttachmentTypes.MINECART_CONTROLLER);
        try(var one=CreateCargoAudit.captureChanges(first);var two=CreateCargoAudit.captureChanges(second)){
            try(var scope=CreateCouplingAudit.begin()){
                controller1.coupleWith(true,cart2.getUUID(),4,false);controller2.coupleWith(false,cart1.getUUID(),4,false);scope.success();
            }
            check(one.rows().size()==1&&two.rows().size()==1,"coupling missing from either construction");
            var a=one.rows().getFirst();var b=two.rows().getFirst();a.materializeSnapshots();b.materializeSnapshots();
            var after=NbtSerde.fromSnbt(a.beAfter);check(after.getList("Carts",10).size()==2&&after.getBoolean("Complete"),"coupling did not preserve both native controller endpoints");
            check(after.getUUID("Operation").equals(NbtSerde.fromSnbt(b.beAfter).getUUID("Operation")),"coupling operation differs between participants");
            int count=one.rows().size();controller1.coupleWith(true,cart2.getUUID(),4,false);check(one.rows().size()==count,"unchanged coupling emitted a row");
            var nativeBefore1=controller1.serializeNBT(world.registryAccess()).copy();var nativeBefore2=controller2.serializeNBT(world.registryAccess()).copy();
            boolean previous=CartAuditContext.restoring(false);CartAuditContext.rollbackActive=true;CartAuditContext.LOCKED_ENTITIES.add(cart1.getUUID());
            try{
                controller2.prepareForCoupling(false);controller2.removeConnection(false);controller2.decouple();controller2.coupleWith(false,cart1.getUUID(),8,false);
                check(nativeBefore1.equals(controller1.serializeNBT(world.registryAccess()))&&nativeBefore2.equals(controller2.serializeNBT(world.registryAccess())),"reserved peer changed or reversed coupling chain");
                var position1=cart1.position();var position2=cart2.position();var motion1=cart1.getDeltaMovement();var motion2=cart2.getDeltaMovement();
                com.simibubi.create.content.contraptions.minecart.CouplingPhysics.tickCoupling(world,net.createmod.catnip.data.Couple.create(controller1,controller2));
                check(position1.equals(cart1.position())&&position2.equals(cart2.position())&&motion1.equals(cart1.getDeltaMovement())&&motion2.equals(cart2.getDeltaMovement()),"coupling physics moved reserved endpoint");
                controller1.tick();check(nativeBefore1.equals(controller1.serializeNBT(world.registryAccess())),"controller tick changed reserved endpoint");
                check(!com.simibubi.create.content.contraptions.minecart.CouplingHandler.tryToCoupleCarts(null,world,cart1.getId(),cart2.getId()),"native coupling ignored reservation without AuthCore");
                check(one.rows().size()==count&&two.rows().size()==1,"blocked coupling emitted mutations");tested+=6;
            }finally{CartAuditContext.rollbackActive=false;CartAuditContext.LOCKED_ENTITIES.clear();CartRestoreLocks.clear();CartAuditContext.restoring(previous);}
            controller1.removeConnection(true);check(one.rows().size()==count+1,"decoupling missing");
            check(CartLogDetails.describe(world,a).stream().anyMatch(c->c.getString().startsWith("Изменение сцепки")),"coupling details missing");tested+=4;
        }finally{boolean old=CartAuditContext.restoring(true);try{((CartEntityAccess)first).avilixlogger$discardForRollback();((CartEntityAccess)second).avilixlogger$discardForRollback();cart1.discard();cart2.discard();}finally{CartAuditContext.restoring(old);}}
        System.out.println("CREATE_LIFECYCLE_NATIVE_OK: "+tested+" scenarios; full typed snapshots, native disassembly/removal/unload, detached payloads, nested IDs, failure markers and shared coupling/decoupling history");
    }
    private static void prepare(AbstractContraptionEntity entity,BlockPos pos)throws Exception{
        if(entity instanceof ControlledContraptionEntity controlled){controlled.setRotationAxis(net.minecraft.core.Direction.Axis.Y);var controller=ControlledContraptionEntity.class.getDeclaredField("controllerPos");controller.setAccessible(true);controller.set(entity,pos.below());}
        if(entity instanceof CarriageContraptionEntity train){var carriage=new Carriage(new CarriageBogey(AllBlocks.SMALL_BOGEY.get(),false,new CompoundTag()),null,0);var field=CarriageContraptionEntity.class.getDeclaredField("carriage");field.setAccessible(true);field.set(train,carriage);}
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
