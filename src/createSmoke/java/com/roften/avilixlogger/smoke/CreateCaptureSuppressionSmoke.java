package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.compat.create.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import java.lang.ref.WeakReference;

/** Same server SavedData path as GUI, including repair of a stale volatile policy. */
public final class CreateCaptureSuppressionSmoke {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void run(ServerLevel world)throws Exception{
        var entity=new ControlledContraptionEntity(AllEntityTypes.CONTROLLED_CONTRAPTION.get(),world);
        var body=new BearingContraption(false,Direction.UP);body.anchor=BlockPos.ZERO;body.bounds=new net.minecraft.world.phys.AABB(BlockPos.ZERO);
        var setter=ControlledContraptionEntity.class.getDeclaredMethod("setContraption",Contraption.class);setter.setAccessible(true);setter.invoke(entity,body);
        var info=new StructureBlockInfo(BlockPos.ZERO,AllBlocks.DEPLOYER.getDefaultState(),new CompoundTag());
        var context=new MovementContext(world,info,body);var data=LoggerServerData.get(world.getServer().overworld());int previous=data.captureMask();
        try{
            int disabled=CaptureTypePolicy.changed(previous,11,false);data.setCaptureMask(disabled);
            check(!CreateCapturePolicy.actors(entity)&&!CreateCapturePolicy.cargo(entity),"disabled bearing still captures");
            check(CreateCapturePolicy.mutedEntity(entity),"muted bearing re-enters generic tick audit");
            HotPathMetrics.start();
            for(int i=0;i<1000;i++)check(CreateDeployerAudit.begin(context,BlockPos.ZERO,null,"USE")==null,"disabled actor dereferenced technical player");
            check(HotPathMetrics.stop().contains("NBT snapshot calls: 0,"),"disabled actor created NBT");
            check(!CartStorageAudit.capturing(new CartStorageAudit.Ref(new WeakReference<>(entity),BlockPos.ZERO,false)),"disabled storage still copies contents");
            CaptureTypePolicy.install(0);data.setCaptureMask(disabled);check(CaptureTypePolicy.mask()==disabled,"unchanged SavedData failed to repair policy");
            data.setCaptureMask(CaptureTypePolicy.changed(disabled,11,true));check(CreateCapturePolicy.actors(entity)&&CreateCapturePolicy.cargo(entity),"re-enable failed");
            for(String name:new String[]{"mechanical_bearing","windmill_bearing","clockwork_bearing"})controller(name,CreateContraptionKind.BEARING);
            for(String name:new String[]{"mechanical_piston","rope_pulley","elevator_pulley","gantry_pinion"})controller(name,CreateContraptionKind.OTHER);
        }finally{HotPathMetrics.clear();data.setCaptureMask(previous);}
        System.out.println("CREATE_GUI_CAPTURE_SUPPRESSION_OK: live mask, no NBT/cargo copies, re-enable and native controller IDs");
    }
    private static void controller(String name,CreateContraptionKind kind){
        var id=ResourceLocation.fromNamespaceAndPath("create",name);
        check(BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).isPresent(),"wrong native controller ID: "+id);
        check(CreateCapturePolicy.controllerKind(id)==kind,"wrong capture family: "+id);
    }
}
