package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.logistics.box.*;
import com.simibubi.create.content.logistics.funnel.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.Vec3;
import java.lang.reflect.*;
import java.util.*;

/** Native funnel operations; captures are detached and never authorize the logger. */
public final class CreateFunnelSmoke {
    public static void run(ServerLevel world)throws Exception{
        var factory=CreateCargoSmoke.class.getDeclaredMethod("entity",ServerLevel.class,CreateContraptionKind.class);factory.setAccessible(true);
        int tested=0;
        for(var kind:new CreateContraptionKind[]{CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER})
            for(String mode:List.of("full","partial","package","extract","lock")){
                var entity=(AbstractContraptionEntity)factory.invoke(null,world,kind);
                entity.getContraption().getStorage().initialize();CartStorageAudit.bind(entity);
                var pos=world.getSharedSpawnPos().offset(8,32,8);visible(world,pos);
                boolean extract=mode.equals("extract"),partial=mode.equals("partial");
                var stack=packageStack(mode);fill(entity,partial?64:0);
                var chest=entity.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO);
                chest.setStackInSlot(0,partial?stack.copyWithCount(63):extract?stack.copyWithCount(5):ItemStack.EMPTY);
                Entity item=extract?null:spawn(world,pos,stack,mode);
                var context=context(entity,pos,extract);
                try(var capture=CreateCargoAudit.captureChanges(entity)){
                    if(mode.equals("lock")){CartAuditContext.rollbackActive=true;CartAuditContext.LOCKED_ENTITIES.add(item.getUUID());}
                    FunnelMovementBehaviour.andesite().visitNewPosition(context,pos);
                    var rows=capture.rows();rows.forEach(LogEntry::materializeSnapshots);
                    if(mode.equals("lock")){check(rows.isEmpty()&&!item.isRemoved(),"reserved world item was absorbed");}
                    else if(extract){check(rows.stream().filter(r->r.type==ActionType.ENTITY_SPAWN).count()==1&&chest.getStackInSlot(0).getCount()==4,"funnel output missing");
                        var born=world.getEntity(rows.stream().filter(r->r.type==ActionType.ENTITY_SPAWN).findFirst().orElseThrow().entityUuid);check(born!=null,"native funnel output absent");born.discard();}
                    else {
                        check(rows.size()==2,"funnel transfer missing/duplicated: "+mode+" "+rows.size());
                        var before=NbtSerde.fromSnbt(rows.getLast().beBefore);var after=NbtSerde.fromSnbt(rows.getLast().beAfter);
                        check(after.getBoolean("Complete")&&after.getList("Entities",10).size()==1,"funnel world endpoint missing");
                        check(item.isRemoved()!=partial,"funnel native remainder state");
                        check(CartLogDetails.describe(world,rows.getLast()).stream().anyMatch(c->c.getString().startsWith("Предмет в мире:")),"GUI world endpoint missing");
                        var plan=new CreateWorldItemTransferPlan();plan.add(before.getList("Entities",10).getCompound(0),after.getList("Entities",10).getCompound(0));
                        var old=CartAuditContext.restoring(true);
                        try {var restore=new CreateWorldItemRestore(world.getServer(),plan.finish().getFirst());restore.apply();
                            var restored=world.getEntity(item.getUUID());check(restored!=null&&ItemStack.matches(stack,com.simibubi.create.foundation.item.ItemHelper.fromItemEntity(restored)),"world endpoint restore lost item/components");
                            restore.compensate();check(partial?((ItemEntity)world.getEntity(item.getUUID())).getItem().getCount()==stack.getCount()-1:world.getEntity(item.getUUID())==null,"world endpoint compensation lost actual remainder");}
                        finally{CartAuditContext.restoring(old);}
                    }
                    tested++;
                }finally{CartAuditContext.rollbackActive=false;CartAuditContext.LOCKED_ENTITIES.clear();CartRestoreLocks.clear();if(item!=null){var current=world.getEntity(item.getUUID());if(current!=null)current.discard();}CartAutomationAudit.clear();}
            }
        System.out.println("CREATE_FUNNEL_WORLD_ITEMS_NATIVE_OK: "+tested+" scenarios; native full/partial/package suction, extraction, UUID/components, endpoint compensation and locks for four kinds");
    }
    public static void fullRollback(MinecraftServer server,CompoundTag checkpoint)throws Exception{
        int tested=0;
        for(String mode:List.of("full","partial","package","cancel","partial-cancel","package-cancel","conflict","reappear","lock")){
            var world=server.overworld();var pos=world.getSharedSpawnPos().offset(8,34,8);visible(world,pos);
            var built=new StagedCartRestore(checkpoint);while(!built.step(world)){}
            var current=built.entity;var cart=built.cart;StagedCartRestore replacement=null;
            Entity item=null;
            try{
                current.setUUID(UUID.randomUUID());cart.setUUID(UUID.randomUUID());cart.setPos(Vec3.atCenterOf(pos.above(3)));current.setPos(cart.position());current.startRiding(cart,true);
                check(world.addFreshEntity(cart)&&world.addFreshEntity(current),"funnel source spawn");CreateCartAudit.attach(current);CreateCartAudit.state(current).locked=false;CartStorageAudit.bind(current);
                boolean partial=mode.startsWith("partial")||mode.equals("conflict");var stack=packageStack(mode);
                fill(current,partial?64:0);var chest=current.getContraption().getStorage().getAllItemStorages().values().iterator().next();chest.setStackInSlot(0,partial?stack.copyWithCount(63):ItemStack.EMPTY);
                var target=CreateCartAudit.snapshot(current,null);var id=CreateCartAudit.state(current).id;
                item=spawn(world,pos,stack,mode);
                List<LogEntry> captured;
                try(var capture=CreateCargoAudit.captureChanges(current)){FunnelMovementBehaviour.andesite().visitNewPosition(context(current,pos,false),pos);captured=capture.rows();}
                check(captured.size()==2,"full rollback source capture");
                var point=new LogEntry();point.id=1;point.type=ActionType.CART_PLACE;point.source=CartAuditContext.prefix(id)+"place:1";point.beAfter=target.toString();
                var history=new ArrayList<LogEntry>();history.add(point);long sequence=2;for(var row:captured){row.materializeSnapshots();row.id=sequence++;history.add(row);}
                var plan=CartRollbackPlan.prepare(new History(history),id,1);check(plan.worldItems().size()==1,"full rollback omitted world endpoint");
                if(mode.equals("conflict")){var remaining=((ItemEntity)item).getItem().copy();remaining.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("changed"));((ItemEntity)item).setItem(remaining);}
                if(mode.equals("reappear")){var duplicate=spawn(world,pos,stack,"full");duplicate.discard();duplicate=EntityType.loadEntityRecursive(plan.worldItems().getFirst().before().getCompound("EntityNBT").copy(),world,e->e);check(world.addFreshEntity(duplicate),"reappearing source");}
                replacement=new StagedCartRestore(target);
                Class<?> type=Class.forName("com.roften.avilixlogger.compat.create.CartRollbackCoordinator$Job");var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CartRollbackPlan.class,StagedCartRestore.class,Entity.class);ctor.setAccessible(true);var job=ctor.newInstance(server.createCommandSourceStack(),plan,replacement,current);
                var step=type.getDeclaredMethod("step",MinecraftServer.class);step.setAccessible(true);var phase=field(type,"phase");var applied=field(type,"applied");var worldApplied=field(type,"worldApplied");var index=field(type,"index");var failure=field(type,"failure");
                boolean cancelled=false,rejected=false,checked=false;var old=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
                try{
                    for(int calls=0;;calls++){
                        check(calls<400,"funnel rollback did not terminate");boolean done;
                        try{done=(Boolean)step.invoke(job,server);}catch(InvocationTargetException error){if(!Set.of("conflict","reappear").contains(mode))throw error;check(applied.getInt(job)==0&&worldApplied.getInt(job)==0,"funnel conflict mutated an endpoint");rejected=true;break;}
                        if(mode.contains("cancel")&&!cancelled&&worldApplied.getInt(job)>0){phase.setInt(job,4);index.setInt(job,applied.getInt(job)-1);failure.set(job,"funnel fixture cancel");cancelled=true;}
                        if(mode.equals("lock")&&!checked&&phase.getInt(job)==9){
                            CartAuditContext.restoring(false);
                            try{var restored=(ItemEntity)world.getEntity(item.getUUID());var inventory=new net.minecraft.world.SimpleContainer(5);check(!net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(inventory,restored),"native hopper consumed reserved restored item");
                                var player=net.neoforged.neoforge.common.util.FakePlayerFactory.get(world,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"funnel_lock"));restored.setNoPickUpDelay();restored.playerTouch(player);check(!restored.isRemoved(),"player picked reserved restored item");}
                            finally{CartAuditContext.restoring(true);}checked=true;
                        }
                        if(done)break;
                    }
                    var remaining=world.getEntity(item.getUUID());
                    if(Set.of("conflict","reappear").contains(mode))check(rejected&&!current.isRemoved(),"unsafe funnel rollback committed");
                    else if(mode.contains("cancel"))check(!current.isRemoved()&&(partial?remaining instanceof ItemEntity drop&&drop.getItem().getCount()==stack.getCount()-1:remaining==null),"cancel failed to restore actual funnel endpoints");
                    else {check(current.isRemoved()&&remaining!=null&&ItemStack.matches(stack,com.simibubi.create.foundation.item.ItemHelper.fromItemEntity(remaining)),"full job did not restore world source");
                        var restoredCargo=replacement.entity.getContraption().getStorage().getAllItemStorages().values().iterator().next().getStackInSlot(0);check(ItemStack.matches(partial?stack.copyWithCount(63):ItemStack.EMPTY,restoredCargo),"full job duplicated funnel cargo");if(mode.equals("lock"))check(checked,"funnel locks not exercised");}
                }finally{CartAuditContext.restoring(old);CartAuditContext.rollbackActive=false;}
                tested++;
            }finally{
                CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();CartAutomationAudit.clear();
                if(item!=null){var remaining=world.getEntity(item.getUUID());if(remaining!=null)remaining.discard();}
                if(!current.isRemoved())((CartEntityAccess)current).avilixlogger$discardForRollback();if(!cart.isRemoved())cart.discard();
                if(replacement!=null){if(replacement.entity!=null&&!replacement.entity.isRemoved())((CartEntityAccess)replacement.entity).avilixlogger$discardForRollback();if(replacement.cart!=null&&!replacement.cart.isRemoved())replacement.cart.discard();}
            }
        }
        System.out.println("CREATE_FUNNEL_WORLD_ROLLBACK_NATIVE_OK: "+tested+" scenarios; complete cart Job for item/partial/package, cancellation compensation, components/reappearance conflicts and native hopper/player locks");
    }
    private static Field field(Class<?> type,String name)throws Exception{var field=type.getDeclaredField(name);field.setAccessible(true);return field;}
    private static ItemStack packageStack(String mode){var stack=mode.startsWith("package")?PackageItem.containing(List.of(new ItemStack(Items.EMERALD,5))):new ItemStack(Items.DIAMOND,5);stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Funnel source"));return stack;}
    private static Entity spawn(ServerLevel world,BlockPos pos,ItemStack stack,String mode){var point=Vec3.atCenterOf(pos);Entity item=mode.startsWith("package")?PackageEntity.fromItemStack(world,point,stack.copy()):new ItemEntity(world,point.x,point.y,point.z,stack.copy());check(world.addFreshEntity(item),"funnel world source spawn");return item;}
    private static MovementContext context(AbstractContraptionEntity entity,BlockPos pos,boolean extracting){var state=AllBlocks.ANDESITE_FUNNEL.getDefaultState().setValue(FunnelBlock.FACING,Direction.DOWN).setValue(FunnelBlock.EXTRACTING,extracting);var info=new StructureTemplate.StructureBlockInfo(new BlockPos(10,0,0),state,new CompoundTag());var context=new MovementContext(entity.level(),info,entity.getContraption());context.position=Vec3.atCenterOf(pos);return context;}
    private static void fill(AbstractContraptionEntity entity,int amount){for(var storage:entity.getContraption().getStorage().getAllItemStorages().values())for(int i=0;i<storage.getSlots();i++)storage.setStackInSlot(i,amount==0?ItemStack.EMPTY:new ItemStack(Items.STONE,amount));}
    private static void visible(ServerLevel world,BlockPos pos)throws Exception{var field=ServerLevel.class.getDeclaredField("entityManager");field.setAccessible(true);@SuppressWarnings("unchecked")var manager=(net.minecraft.world.level.entity.PersistentEntitySectionManager<Entity>)field.get(world);manager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(pos),net.minecraft.world.level.entity.Visibility.TRACKED);}
    private record History(List<LogEntry> rows)implements LogStorage{public void append(LogEntry r){}public List<LogEntry> query(LogQuery q){return rows.stream().filter(r->r.id>q.afterId).limit(q.limit).toList();}public List<LogEntry> queryReverse(LogQuery q){return rows.stream().filter(r->r.id<q.beforeId).sorted(Comparator.<LogEntry>comparingLong(r->r.id).reversed()).limit(q.limit).toList();}public void shutdown(){}}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
