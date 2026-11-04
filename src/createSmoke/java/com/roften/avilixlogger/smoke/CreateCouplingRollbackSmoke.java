package com.roften.avilixlogger.smoke;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllAttachmentTypes;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.minecart.capability.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.Minecart;
import java.lang.reflect.*;
import java.util.*;

/** Genuine native coupling, decoupling and reversal with the production transaction. */
public final class CreateCouplingRollbackSmoke {
    public static void run(ServerLevel world)throws Exception{
        int tested=0;var pos=world.getSharedSpawnPos().offset(32,42,32);world.getChunkAt(pos);
        var managerField=ServerLevel.class.getDeclaredField("entityManager");managerField.setAccessible(true);
        @SuppressWarnings("unchecked")var manager=(net.minecraft.world.level.entity.PersistentEntitySectionManager<net.minecraft.world.entity.Entity>)managerField.get(world);
        manager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(pos),net.minecraft.world.level.entity.Visibility.TRACKED);
        var factory=CreateCargoSmoke.class.getDeclaredMethod("entity",ServerLevel.class,CreateContraptionKind.class);factory.setAccessible(true);
        for(String operation:List.of("couple","decouple","reverse"))for(String mode:List.of("commit","cancel-first","cancel-last","locks","conflict","registry","missing","apply-failure")){
            var carts=new ArrayList<Minecart>();var bodies=new ArrayList<AbstractContraptionEntity>();var controllers=new ArrayList<MinecartController>();
            for(int i=0;i<3;i++){
                var cart=new Minecart(world,pos.getX()+i*4,pos.getY(),pos.getZ());var body=(AbstractContraptionEntity)factory.invoke(null,world,CreateContraptionKind.MINECART);body.setPos(cart.position());
                check(world.addFreshEntity(cart)&&world.addFreshEntity(body)&&body.startRiding(cart,true),"coupling fixture spawn");carts.add(cart);bodies.add(body);controllers.add(cart.getData(AllAttachmentTypes.MINECART_CONTROLLER));
            }
            try{
                refresh(world,controllers);
                if(!operation.equals("couple")){connect(controllers,carts,0,1);connect(controllers,carts,1,2);}
                refresh(world,controllers);
                try(var capture=CreateCargoAudit.captureChanges(bodies.getFirst())){
                    try(var scope=CreateCouplingAudit.begin()){
                        if(operation.equals("couple")){connect(controllers,carts,0,1);connect(controllers,carts,1,2);}
                        if(operation.equals("decouple"))controllers.get(1).decouple();
                        if(operation.equals("reverse"))controllers.getFirst().prepareForCoupling(true);
                        scope.success();
                    }
                    refresh(world,controllers);
                    check(capture.rows().size()==1,"coupling history missing/duplicated");var row=capture.rows().getFirst();row.materializeSnapshots();
                    var before=NbtSerde.fromSnbt(row.beBefore);var after=NbtSerde.fromSnbt(row.beAfter);var plan=CreateCouplingRollbackPlan.prepare(before,after);
                    check(plan.carts().size()==3&&plan.constructions().size()==3,"whole affected chain missing");
                    var incomplete=before.copy();incomplete.getList("Carts",10).remove(2);reject(()->CreateCouplingRollbackPlan.prepare(incomplete,after),"partial graph accepted");
                    if(mode.equals("conflict"))controllers.getFirst().setStalledExternally(true);
                    if(mode.equals("registry"))CapabilityMinecartController.loadedMinecartsByUUID.get(world).remove(carts.getFirst().getUUID());
                    if(mode.equals("missing"))carts.getLast().discard();
                    if(mode.equals("apply-failure")){var fault=before.getList("Carts",10).getCompound(1).getCompound("Native");fault.putInt("RoundTripFault",1);plan=CreateCouplingRollbackPlan.prepare(before,after);}
                    boolean old=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
                    try{
                        var type=Class.forName("com.roften.avilixlogger.compat.create.CreateCouplingRollbackCoordinator$Job");var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CreateCouplingRollbackPlan.class,long.class);ctor.setAccessible(true);var job=ctor.newInstance(world.getServer().createCommandSourceStack(),plan,1L);
                        var step=type.getDeclaredMethod("step",net.minecraft.server.MinecraftServer.class);step.setAccessible(true);var cancel=type.getDeclaredMethod("cancel",String.class);cancel.setAccessible(true);var applied=field(type,"applied");boolean canceled=false,rejected=false,lockChecked=false;int calls=0;
                        while(true){boolean done;
                            try{done=(Boolean)step.invoke(job,world.getServer());}
                            catch(InvocationTargetException failure){if(mode.equals("apply-failure")){check(applied.getInt(job)>0,"failure did not follow mutation");cancel.invoke(job,failure.getCause().getMessage());canceled=true;continue;}
                                if(!List.of("conflict","registry","missing").contains(mode))throw new IllegalStateException(operation+"/"+mode+" carts="+carts.stream().map(c->c.getUUID()+":"+c.isRemoved()).toList()+" captured="+plan.carts().stream().map(c->c.after().getUUID("CartEntity")).toList(),failure);check(applied.getInt(job)==0,"preflight conflict modified controllers");rejected=true;break;}
                            if(mode.equals("locks")&&!lockChecked&&field(type,"phase").getInt(job)==1){
                                var saved=controllers.stream().map(c->c.serializeNBT(world.registryAccess()).copy()).toList();CartAuditContext.restoring(false);
                                try{controllers.getFirst().deserializeNBT(world.registryAccess(),new CompoundTag());controllers.getFirst().prepareForCoupling(true);controllers.get(1).decouple();controllers.getLast().coupleWith(true,carts.getFirst().getUUID(),20,false);
                                    for(int i=0;i<3;i++)check(saved.get(i).equals(controllers.get(i).serializeNBT(world.registryAccess())),"reserved chain mutated");}
                                finally{CartAuditContext.restoring(true);}lockChecked=true;
                            }
                            if(!canceled&&((mode.equals("cancel-first")&&applied.getInt(job)>0)||(mode.equals("cancel-last")&&applied.getInt(job)==3))){cancel.invoke(job,"native chain cancellation");canceled=true;}
                            if(done)break;check(++calls<500,"coupling Job did not terminate");
                        }
                        if(List.of("conflict","registry","missing").contains(mode))check(rejected,"conflict accepted");
                        else{
                            check(field(type,"committed").getBoolean(job)==!canceled,"coupling commit flag wrong");if(mode.equals("locks"))check(lockChecked,"chain locks not exercised");
                            for(var endpoint:plan.carts()){var expected=canceled?endpoint.after():endpoint.before();var cart=(Minecart)world.getEntity(expected.getUUID("CartEntity"));check(expected.equals(CreateCouplingAudit.data(cart.getData(AllAttachmentTypes.MINECART_CONTROLLER))),"controller compensation/commit differs");}
                            for(var controller:controllers)check(CapabilityMinecartController.loadedMinecartsWithCoupling.get(world).contains(controller.cart().getUUID())==controller.isLeadingCoupling(),"native leading registry incorrect");
                        }
                    }finally{CartAuditContext.restoring(old);CartAuditContext.rollbackActive=false;CartAuditContext.LOCKED_ENTITIES.clear();CartRestoreLocks.clear();}
                }tested++;
            }finally{boolean old=CartAuditContext.restoring(true);try{for(var body:bodies)if(!body.isRemoved())((CartEntityAccess)body).avilixlogger$discardForRollback();for(var cart:carts){cart.discard();CapabilityMinecartController.loadedMinecartsByUUID.get(world).remove(cart.getUUID());CapabilityMinecartController.loadedMinecartsWithCoupling.get(world).remove(cart.getUUID());}}
                finally{CartAuditContext.restoring(old);CreateCouplingAudit.clear();}}
        }
        // Native through-contraption decoupling destroys the body and must retain incomplete history.
        var cartA=new Minecart(world,pos.getX(),pos.getY(),pos.getZ());var cartB=new Minecart(world,pos.getX()+4,pos.getY(),pos.getZ());
        var bodyA=(AbstractContraptionEntity)factory.invoke(null,world,CreateContraptionKind.MINECART);var bodyB=(AbstractContraptionEntity)factory.invoke(null,world,CreateContraptionKind.MINECART);
        world.addFreshEntity(cartA);world.addFreshEntity(cartB);world.addFreshEntity(bodyA);world.addFreshEntity(bodyB);bodyA.startRiding(cartA,true);bodyB.startRiding(cartB,true);
        var ca=cartA.getData(AllAttachmentTypes.MINECART_CONTROLLER);var cb=cartB.getData(AllAttachmentTypes.MINECART_CONTROLLER);refresh(world,List.of(ca,cb));ca.coupleWith(true,cartB.getUUID(),4,true);cb.coupleWith(false,cartA.getUUID(),4,true);refresh(world,List.of(ca,cb));
        try(var capture=CreateCargoAudit.captureChanges(bodyA)){
            try(var scope=CreateCouplingAudit.begin()){ca.decouple();scope.success();}
            check(bodyA.isRemoved(),"native through-contraption decoupling did not destroy body");boolean found=false;
            for(var row:capture.rows()){row.materializeSnapshots();var before=NbtSerde.fromSnbt(row.beBefore);var after=NbtSerde.fromSnbt(row.beAfter);if(after!=null&&after.getString("Kind").equals("coupling")){check(!after.getBoolean("Complete"),"destructive decoupling marked complete");reject(()->CreateCouplingRollbackPlan.prepare(before,after),"destructive decoupling accepted without structure");found=true;}}
            check(found,"removed participant lost coupling history");tested++;
        }finally{boolean old=CartAuditContext.restoring(true);try{for(var body:List.of(bodyA,bodyB))if(!body.isRemoved())((CartEntityAccess)body).avilixlogger$discardForRollback();for(var cart:List.of(cartA,cartB)){cart.discard();CapabilityMinecartController.loadedMinecartsByUUID.get(world).remove(cart.getUUID());CapabilityMinecartController.loadedMinecartsWithCoupling.get(world).remove(cart.getUUID());}}finally{CartAuditContext.restoring(old);}}
        System.out.println("CREATE_COUPLING_ROLLBACK_NATIVE_OK: "+tested+" scenarios; native three-cart coupling/decoupling/reversal, full controller graph and leading registry, commit, early/late cancellation, conflicts, partial graphs, locks and failure after mutation");
    }
    private static void connect(List<MinecartController> controllers,List<Minecart> carts,int a,int b){controllers.get(a).coupleWith(true,carts.get(b).getUUID(),4,false);controllers.get(b).coupleWith(false,carts.get(a).getUUID(),4,false);}
    private static void refresh(ServerLevel world,List<MinecartController> controllers){for(var c:controllers)c.tick();CapabilityMinecartController.tick(world);var leading=CapabilityMinecartController.loadedMinecartsWithCoupling.get(world);for(var c:controllers)if(c.isLeadingCoupling())leading.add(c.cart().getUUID());else leading.remove(c.cart().getUUID());}
    private static Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void reject(Runnable action,String message){try{action.run();}catch(IllegalStateException|IllegalArgumentException expected){return;}throw new AssertionError(message);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
