package com.roften.avilixlogger.smoke;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.lang.reflect.*;
import java.util.*;
/** Real Carriage serializers and the same staged recovery kernel used by commands. */
public final class CreateTrainStateSmoke {
    public static void run(ServerLevel world,Train train,String mode)throws Exception{
        var carriage=train.carriages.getFirst();
        if(mode.contains("portal")){
            var original=carriage.getDimensional(world);var second=carriage.getDimensional(Level.NETHER);
            second.read(original.write(world.registryAccess()),world.registryAccess());second.cutoff=-.5f;original.cutoff=.5f;
            if(mode.contains("both")){var other=world.getServer().getLevel(Level.NETHER);check(other!=null,"nether missing");
                var visible=CreateFunnelSmoke.class.getDeclaredMethod("visible",ServerLevel.class,net.minecraft.core.BlockPos.class);visible.setAccessible(true);visible.invoke(null,other,net.minecraft.core.BlockPos.containing(second.positionAnchor));
                invoke(second,"createEntity",new Class<?>[]{Level.class,boolean.class},other,false);check(second.entity.get()!=null,"second native projection not created");}
        }
        if(mode.contains("unloaded"))for(var car:train.carriages)for(var dimension:car.getPresentDimensions()){
            var projection=car.getDimensionalIfPresent(dimension);var entity=projection.entity.get();
            if(entity!=null)invoke(projection,"removeAndSaveEntity",new Class<?>[]{CarriageContraptionEntity.class,boolean.class},entity,false);
        }
        var checkpoint=CreateTrainLifecycleAudit.capture(train,world,"fixture_"+mode);
        check(checkpoint.getBoolean("Complete"),"native checkpoint incomplete: "+checkpoint.getString("Failure"));
        var saved=checkpoint.copy();var plan=CreateTrainLifecyclePlan.prepare(checkpoint);check(checkpoint.equals(saved),"worker mutated checkpoint");
        check(plan.carriages()==train.carriages.size()&&plan.projections()==train.carriages.stream().mapToInt(c->c.getPresentDimensions().size()).sum(),"projection count lost");
        var prepared=new CreateTrainRegistry.Prepared(plan.registry(),true);
        if(mode.equals("state-duplicate")){reject(()->new CreateTrainRecovery.Restore(world.getServer(),plan,prepared),"existing train duplicated");return;}
        if(mode.equals("state-loaded-duplicate")){
            ((CreateRailwayAccess)Create.RAILWAYS).avilixlogger$remove(train.id);
            reject(()->new CreateTrainRecovery.Restore(world.getServer(),plan,prepared),"loaded orphan train duplicated");return;
        }
        boolean old=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
        try{
            for(var car:train.carriages)car.forEachPresentEntity(e->{if(!e.isRemoved())((CartEntityAccess)e).avilixlogger$discardForRollback();});
            ((CreateRailwayAccess)Create.RAILWAYS).avilixlogger$remove(train.id);
            var station=train.getCurrentStation();if(station!=null)station.nearestTrain=new java.lang.ref.WeakReference<>(null);
            for(var group:Create.RAILWAYS.signalEdgeGroups.values())group.trains.removeIf(t->t.id.equals(train.id));
            if(mode.equals("state-graph-conflict")){
                var data=plan.registry().getCompound("Graph").getCompound("Native");
                train.graph.id=UUID.randomUUID();
                try{reject(()->new CreateTrainRecovery.Restore(world.getServer(),plan,prepared),"changed graph accepted");}
                finally{train.graph.id=data.getUUID("Id");}return;
            }
            var restore=new CreateTrainRecovery.Restore(world.getServer(),plan,prepared);int steps=0;
            while(!restore.stage())check(++steps<100,"native staging did not terminate");
            check(!Create.RAILWAYS.trains.containsKey(plan.id()),"staging published a train early");
            restore.ready();restore.install();restore.verify();
            var result=Create.RAILWAYS.trains.get(plan.id());check(result!=null&&result!=train,"registry not replaced");
            check(CreateTrainRegistry.snapshot(result,world).equals(plan.registry()),"serialized train round trip differs");
            for(int i=0;i<result.carriages.size();i++){var car=result.carriages.get(i);check(car.anyAvailableEntity()==null,"restore forced a representation/chunk load");
                check(((ContraptionIdentity)car).avilixlogger$identity().equals(((ContraptionIdentity)train.carriages.get(i)).avilixlogger$identity()),"carriage ID lost");}
            if(mode.equals("state-cancel")){restore.compensate();check(!Create.RAILWAYS.trains.containsKey(plan.id()),"cancellation retained restored train");}
        }finally{CartAuditContext.restoring(old);CartAuditContext.rollbackActive=false;CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();}
        System.out.println("CREATE_TRAIN_STATE_CASE_OK "+mode);
    }
    private static Object invoke(Object target,String name,Class<?>[] args,Object...values)throws Exception{var m=target.getClass().getDeclaredMethod(name,args);m.setAccessible(true);return m.invoke(target,values);}
    private static void reject(Runnable action,String text){try{action.run();}catch(IllegalStateException expected){return;}throw new AssertionError(text);}
    private static void check(boolean condition,String text){if(!condition)throw new AssertionError(text);}
}
