package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.contraptions.mounted.MinecartContraptionItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Development-only source set; never included in the shipped mod. No AuthCore bypass. */
@EventBusSubscriber(modid="avilixlogger")
public final class CreateCartSmoke {
    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if(!Boolean.getBoolean("avilixlogger.createSmoke"))return;
        try {
            var world=event.getServer().overworld();
            MountedContraption c=new MountedContraption();c.bounds=new net.minecraft.world.phys.AABB(BlockPos.ZERO);
            c.getBlocks().put(BlockPos.ZERO,new StructureBlockInfo(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),null));
            var entity=OrientedContraptionEntity.create(world,c,Direction.NORTH);
            var cart=new Minecart(world,0,128,0);entity.setPos(0,128,0);entity.startRiding(cart,true);
            CartAuditState state=((CartAuditAccess)c).avilixlogger$cartState();state.owner=java.util.UUID.randomUUID();state.ownerName="smoke";
            var item=MinecartContraptionItem.create(AbstractMinecart.Type.RIDEABLE,entity);
            check(state.id.equals(CreateCartAudit.itemId(item)),"packed ID");
            var roundTrip=Contraption.fromNBT(world,c.writeNBT(world.registryAccess(),false),false);
            check(state.id.equals(((CartAuditAccess)roundTrip).avilixlogger$cartState().id),"save/load ID");
            var snapshot=CreateCartAudit.snapshot(entity,null);
            var staged=new StagedCartRestore(snapshot);int steps=0;
            while(!staged.step(world))check(++steps<100,"restore did not terminate");
            check(staged.entity.getContraption().getBlocks().size()==1,"restored block count");
            check(staged.entity.getContraption().getBlocks().get(BlockPos.ZERO).state().is(Blocks.STONE),"restored block state");
            check(state.id.equals(CreateCartAudit.state(staged.entity).id),"restored persistent ID");
            check(world.getEntity(staged.entity.getUUID())!=staged.entity,"staging published entity prematurely");
            System.out.println("AVILIX_CREATE_SMOKE_OK identity=pack+save+load restore=detached+incremental");
        }catch(Throwable failure){
            failure.printStackTrace();System.out.println("AVILIX_CREATE_SMOKE_FAILED");
            event.getServer().halt(false);
        }
    }
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}
