package com.roften.avilixlogger.compat.industry;

import com.roften.avilixlogger.compat.teams.ModStateAudit;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import java.lang.ref.WeakReference;
import java.util.*;

/** Native continuous sprayer use: detached hand evidence, without rescanning player inventories. */
public final class IndustrialTools {
    private static final Map<ItemStack,Pending> PENDING=new WeakHashMap<>();
    private static final ThreadLocal<Integer> ACTIVE=ThreadLocal.withInitial(()->0);
    public static boolean capturing(){return ACTIVE.get()>0;}
    private IndustrialTools(){}
    public static Focus focus(LivingEntity entity,ItemStack stack){return focus(entity,stack,"sprayer-use-interval",true);}
    public static Focus automaticLighter(Entity entity,ItemStack stack){
        if(!(entity.level() instanceof ServerLevel level)||!ModStateAudit.enabled(level.getServer(),ActionType.ITEM_TRANSFORM))return null;
        var key=net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("createdieselgenerators","lighter_state"));
        Object state=key==null?null:stack.get(key);
        return state instanceof Enum<?> value&&value.name().equals("OPEN_IGNITED")?focus(entity,stack,"lighter-automatic-state",false):null;
    }
    private static Focus focus(Entity entity,ItemStack stack,String operation,boolean batch){
        if(!(entity.level() instanceof ServerLevel level)||!ModStateAudit.enabled(level.getServer(),ActionType.ITEM_TRANSFORM))return null;
        var before=stack.copy();ACTIVE.set(ACTIVE.get()+1);return new Focus(entity,stack,before,level.getServer().getTickCount(),operation,batch);
    }
    public static void release(ItemStack stack){
        var server=net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if(server==null||!server.isSameThread())return;
        var pending=PENDING.remove(stack);if(pending!=null)emit(pending);
    }
    public static void player(LivingEntity entity){
        if(!(entity.level() instanceof ServerLevel level)||!level.getServer().isSameThread())return;
        var entries=new ArrayList<>(PENDING.entrySet());
        for(var entry:entries)if(entry.getValue().owner.get()==entity){PENDING.remove(entry.getKey());emit(entry.getValue());}
    }
    public static void stop(MinecraftServer server){
        if(!server.isSameThread())return;
        for(var pending:new ArrayList<>(PENDING.values()))emit(pending);PENDING.clear();
    }
    private static CompoundTag tag(Pending pending,ItemStack stack){
        var tag=pending.location.copy();tag.put("FocusStack",stack.saveOptional(pending.level.registryAccess()));return tag;
    }
    private static boolean sameActor(CauseContext.Cause first,CauseContext.Cause last){
        if(first==null||last==null)return first==last;
        return Objects.equals(first.actorUuid(),last.actorUuid())&&first.kind()==last.kind()&&Objects.equals(first.itemKey(),last.itemKey());
    }
    private static void emit(Pending pending){
        if(!ModStateAudit.enabled(pending.level.getServer(),ActionType.ITEM_TRANSFORM))return;
        try(var cause=CauseContext.push(pending.cause)){
            var before=tag(pending,pending.before);var after=tag(pending,pending.after);
            var owner=pending.owner.get();if(owner!=null){after.putInt("X",owner.blockPosition().getX());after.putInt("Y",owner.blockPosition().getY());after.putInt("Z",owner.blockPosition().getZ());}
            after.putInt("NativeUseMutations",pending.operations);after.putInt("FromServerTick",pending.start);after.putInt("ToServerTick",pending.end);
            after.putBoolean("NativeThrew",pending.threw);
            ModStateAudit.receipt(pending.level.getServer(),"createdieselgenerators",pending.operation,ActionType.ITEM_TRANSFORM,before,after);
        }catch(RuntimeException|LinkageError failure){IndustrialAudit.fail("tool-flush");}
    }
    private static final class Pending {
        private final WeakReference<Entity> owner;private final ServerLevel level;private final CauseContext.Cause cause;
        private final ItemStack before;private ItemStack after;private final CompoundTag location;private final int start;private final String operation;private int end,operations;private boolean threw;
        private Pending(Entity entity,ItemStack before,ItemStack after,int tick,String operation){
            this.operation=operation;
            owner=new WeakReference<>(entity);level=(ServerLevel)entity.level();cause=CauseContext.peek();this.before=before;this.after=after;start=end=tick;operations=1;
            location=new CompoundTag();location.putString("Dimension",level.dimension().location().toString());
            location.putInt("X",entity.blockPosition().getX());location.putInt("Y",entity.blockPosition().getY());location.putInt("Z",entity.blockPosition().getZ());
            location.putUUID("EntityUUID",entity.getUUID());location.putString("EntityType",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            location.putString("EntityName",entity.getName().getString());
        }
    }
    public static final class Focus {
        private final Entity owner;private final ItemStack live,before;private final int tick;private final String operation;private final boolean batch;
        private Focus(Entity owner,ItemStack live,ItemStack before,int tick,String operation,boolean batch){this.owner=owner;this.live=live;this.before=before;this.tick=tick;this.operation=operation;this.batch=batch;}
        public void finish(boolean threw){
            try{
                var pending=PENDING.get(live);var cause=CauseContext.peek();
                if(pending!=null&&(pending.owner.get()!=owner||!pending.operation.equals(operation)||!sameActor(pending.cause,cause)||!ItemStack.matches(pending.after,before))){release(live);pending=null;}
                if(!ItemStack.matches(before,live)){
                    if(pending==null){
                        pending=new Pending(owner,before,live.copy(),tick,operation);
                        if(PENDING.size()>=1024){pending.threw=threw;emit(pending);return;}
                        PENDING.put(live,pending);
                    }else{pending.after=live.copy();pending.end=tick;pending.operations++;}
                    pending.threw|=threw;
                }
                if(pending!=null&&(!batch||threw||tick-pending.start>=20))release(live);
            }catch(RuntimeException|LinkageError failure){IndustrialAudit.fail("tool-after");}
            finally{int active=ACTIVE.get()-1;if(active==0)ACTIVE.remove();else ACTIVE.set(active);}
        }
    }
}
