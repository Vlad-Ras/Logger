package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.logistics.funnel.FunnelMovementBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import java.lang.reflect.*;
import java.util.*;

/** Native funnel plus the same multi-endpoint Job for every supported construction kind. */
public final class CreateTransferWorldSmoke {
    public static void run(ServerLevel world)throws Exception{
        var factory=method(CreateCargoSmoke.class,"entity",ServerLevel.class,CreateContraptionKind.class);
        var attach=method(CreateLinkedTransferSmoke.class,"attachCarriage",AbstractContraptionEntity.class);
        var fill=method(CreateFunnelSmoke.class,"fill",AbstractContraptionEntity.class,int.class);
        var stackFactory=method(CreateFunnelSmoke.class,"packageStack",String.class);
        var spawn=method(CreateFunnelSmoke.class,"spawn",ServerLevel.class,BlockPos.class,ItemStack.class,String.class);
        var contextFactory=method(CreateFunnelSmoke.class,"context",AbstractContraptionEntity.class,BlockPos.class,boolean.class);
        var pos=world.getSharedSpawnPos().offset(12,38,12);world.getChunk(pos);
        method(CreateFunnelSmoke.class,"visible",ServerLevel.class,BlockPos.class).invoke(null,world,pos);
        Class<?> type=Class.forName("com.roften.avilixlogger.compat.create.CreateTransferRollbackCoordinator$Job");
        var constructor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CreateTransferRollbackPlan.class,long.class);constructor.setAccessible(true);
        var step=method(type,"step",MinecraftServer.class);var cancel=method(type,"cancel",String.class);
        var applied=field(type,"applied");int tested=0;
        for(var kind:List.of(CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER))
            for(String mode:List.of("full","partial","package","full-cancel","partial-cancel","package-cancel","full-cancel-all","partial-cancel-all","package-cancel-all","conflict","reappear","lock")){
                var entity=(AbstractContraptionEntity)factory.invoke(null,world,kind);attach.invoke(null,entity);
                entity.setPos(pos.getX(),pos.getY()+3,pos.getZ());check(world.addFreshEntity(entity),"world transfer construction spawn");
                Entity item=null;
                try{
                    entity.getContraption().getStorage().initialize();CartStorageAudit.bind(entity);
                    boolean partial=mode.startsWith("partial")||mode.equals("conflict");
                    var stack=(ItemStack)stackFactory.invoke(null,mode);fill.invoke(null,entity,partial?64:0);
                    var chest=entity.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO);
                    chest.setStackInSlot(0,partial?stack.copyWithCount(63):ItemStack.EMPTY);
                    item=(Entity)spawn.invoke(null,world,pos,stack,mode);
                    List<LogEntry> rows;
                    try(var capture=CreateCargoAudit.captureChanges(entity)){
                        FunnelMovementBehaviour.andesite().visitNewPosition((MovementContext)contextFactory.invoke(null,entity,pos,false),pos);rows=capture.rows();
                    }
                    rows.forEach(LogEntry::materializeSnapshots);check(rows.size()==2,"world transfer history missing");
                    var manifest=rows.getLast();var plan=CreateTransferRollbackPlan.prepare(NbtSerde.fromSnbt(manifest.beBefore),NbtSerde.fromSnbt(manifest.beAfter));
                    check(plan.world().size()==1&&plan.cargo().size()==1,"world transfer plan missing endpoint");
                    if(mode.equals("conflict")){var changed=((ItemEntity)item).getItem().copy();changed.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("changed"));((ItemEntity)item).setItem(changed);}
                    if(mode.equals("reappear")){var duplicate=EntityType.loadEntityRecursive(plan.world().getFirst().before().getCompound("EntityNBT").copy(),world,e->e);check(duplicate!=null&&world.addFreshEntity(duplicate),"source UUID reappearance");}
                    var job=constructor.newInstance(world.getServer().createCommandSourceStack(),plan,1L);
                    boolean canceled=false,rejected=false,locked=false;boolean previous=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
                    try{
                        for(int calls=0;;calls++){
                            check(calls<100,"world transfer did not terminate");boolean done;
                            try{done=(Boolean)step.invoke(job,world.getServer());}
                            catch(InvocationTargetException failure){if(!Set.of("conflict","reappear").contains(mode))throw failure;check(applied.getInt(job)==0,"world conflict changed cargo before preflight");rejected=true;break;}
                            if(mode.contains("cancel")&&!canceled&&applied.getInt(job)>=(mode.endsWith("-all")?2:1)){cancel.invoke(job,"world transfer cancellation");canceled=true;}
                            if(mode.equals("lock")&&!locked&&applied.getInt(job)==2){
                                CartAuditContext.restoring(false);
                                try{var restored=(ItemEntity)world.getEntity(item.getUUID());check(restored!=null,"restored source missing");
                                    var inventory=new net.minecraft.world.SimpleContainer(5);check(!net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(inventory,restored),"hopper consumed reserved source");
                                    var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(world,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"transfer_lock"));restored.setNoPickUpDelay();restored.playerTouch(player);check(!restored.isRemoved(),"player picked up reserved source");
                                    check(chest.extractItem(0,1,false).isEmpty(),"mounted cargo escaped reservation");}
                                finally{CartAuditContext.restoring(true);}locked=true;
                            }
                            if(done)break;
                        }
                        var remaining=world.getEntity(item.getUUID());
                        if(Set.of("conflict","reappear").contains(mode)){check(rejected&&chest.getStackInSlot(0).getCount()==(partial?64:stack.getCount()),"world conflict mutated cargo");}
                        else if(mode.contains("cancel")){check(canceled&&chest.getStackInSlot(0).getCount()==(partial?64:stack.getCount()),"cancel lost mounted endpoint");check(partial?remaining instanceof ItemEntity drop&&drop.getItem().getCount()==stack.getCount()-1:remaining==null,"cancel lost world remainder");}
                        else{check(ItemStack.matches(partial?stack.copyWithCount(63):ItemStack.EMPTY,chest.getStackInSlot(0)),"joint undo duplicated cargo");check(remaining!=null&&ItemStack.matches(stack,com.simibubi.create.foundation.item.ItemHelper.fromItemEntity(remaining)),"joint undo lost world UUID/components");if(mode.equals("lock"))check(locked,"world locks not exercised");}
                    }finally{CartAuditContext.restoring(previous);CartAuditContext.rollbackActive=false;}
                    tested++;
                }finally{
                    CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();CartAutomationAudit.clear();
                    boolean previous=CartAuditContext.restoring(true);
                    try{if(item!=null){var current=world.getEntity(item.getUUID());if(current!=null)current.discard();}((CartEntityAccess)entity).avilixlogger$discardForRollback();}
                    finally{CartAuditContext.restoring(previous);}
                }
            }
        System.out.println("CREATE_TRANSFER_WORLD_NATIVE_OK: "+tested+" scenarios; four construction kinds, native full/partial/package suction, joint commit, early/late cancellation, conflicts, reappearance, hopper/player/mounted locks");
    }
    private static Method method(Class<?> type,String name,Class<?>...parameters)throws Exception{var method=type.getDeclaredMethod(name,parameters);method.setAccessible(true);return method;}
    private static Field field(Class<?> type,String name)throws Exception{var field=type.getDeclaredField(name);field.setAccessible(true);return field;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
