package com.roften.avilixlogger.core;
import com.roften.avilixlogger.compat.create.*;
import net.minecraft.nbt.*;
import java.util.*;
public final class CreateCouplingPlanTest {
    public static void run(){
        var b=manifest(false);var a=manifest(true);var plan=CreateCouplingRollbackPlan.prepare(b,a);check(plan.carts().size()==3&&plan.constructions().size()==3,"coupling endpoints lost");
        for(String fault:List.of("partial","operation","old-format","duplicate","foreign-dimension","missing-backlink","length","identity","cycle")){
            var before=b.copy();var after=a.copy();var first=after.getList("Carts",10).getCompound(0);var second=after.getList("Carts",10).getCompound(1);
            switch(fault){
                case "partial"->after.putBoolean("Complete",false);
                case "operation"->after.putUUID("Operation",UUID.randomUUID());
                case "old-format"->{before.putInt("Format",1);after.putInt("Format",1);}
                case "duplicate"->{before.getList("Carts",10).add(before.getList("Carts",10).getCompound(0).copy());after.getList("Carts",10).add(first.copy());}
                case "foreign-dimension"->{before.getList("Carts",10).getCompound(1).putString("Dimension","minecraft:the_nether");second.putString("Dimension","minecraft:the_nether");}
                case "missing-backlink"->second.getCompound("Native").remove("ConnectedCoupling");
                case "length"->first.getCompound("Native").getCompound("MainCoupling").putFloat("Length",Float.NaN);
                case "identity"->first.putUUID("ConstructionEntity",UUID.randomUUID());
                case "cycle"->{var last=after.getList("Carts",10).getCompound(2);var edge=edge(last.getUUID("CartEntity"),first.getUUID("CartEntity"));last.getCompound("Native").put("MainCoupling",edge);first.getCompound("Native").put("ConnectedCoupling",edge.copy());}
            }
            reject(()->CreateCouplingRollbackPlan.prepare(before,after),"invalid coupling accepted: "+fault);
        }
        var frozen=plan.after().copy();a.getList("Carts",10).clear();check(plan.after().equals(frozen),"coupling snapshot is mutable");
        var same=new LogEntry();same.id=11;same.type=ActionType.CART_STATUS;same.source=CreateContraptionKind.MINECART.source(new UUID(57,10),"coupling");same.beAfter=plan.after().toString();verify(plan,List.of(same));
        for(var type:List.of(ActionType.CART_ROLLBACK,ActionType.CART_STATUS,ActionType.CART_CONTENT_CHANGE,ActionType.CART_REMOVE)){var row=new LogEntry();row.id=12;row.type=type;row.source=same.source;var tag=plan.after().copy();tag.putUUID("Operation",UUID.randomUUID());row.beAfter=tag.toString();reject(()->verify(plan,List.of(row,same)),"later coupling history accepted");}
        System.out.println("CREATE_COUPLING_PLAN_OK: complete bidirectional acyclic graphs, detached payloads, malformed endpoints, legacy rejection and all-participant history freshness");
    }
    private static CompoundTag manifest(boolean connected){var tag=NbtSerde.fromSnbt("{Format:2,Kind:'coupling',Complete:1b,Carts:[]}");tag.putUUID("Operation",new UUID(57,1));
        for(int i=0;i<3;i++){var cart=NbtSerde.fromSnbt("{Dimension:'minecraft:overworld',CartType:'minecraft:minecart',Native:{}}");cart.putUUID("CartEntity",new UUID(57,2+i));cart.putUUID("Construction",new UUID(57,10+i));cart.putUUID("ConstructionEntity",new UUID(57,20+i));tag.getList("Carts",10).add(cart);}
        if(connected)for(int i=0;i<2;i++){var main=tag.getList("Carts",10).getCompound(i);var peer=tag.getList("Carts",10).getCompound(i+1);var link=edge(main.getUUID("CartEntity"),peer.getUUID("CartEntity"));main.getCompound("Native").put("MainCoupling",link);peer.getCompound("Native").put("ConnectedCoupling",link.copy());}return tag;
    }
    private static CompoundTag edge(UUID main,UUID connected){var tag=NbtSerde.fromSnbt("{Length:4f,Contraption:0b}");tag.putUUID("Main",main);tag.putUUID("Connected",connected);return tag;}
    private static void verify(CreateCouplingRollbackPlan plan,List<LogEntry> rows){LogStorage storage=new LogStorage(){public void append(LogEntry row){throw new UnsupportedOperationException();}public List<LogEntry> query(LogQuery q){return queryReverse(q);}public List<LogEntry> queryReverse(LogQuery q){return rows.stream().filter(row->q.constructionId.equals(CreateContraptionKind.id(row.source))).toList();}public void shutdown(){}};
        try{var method=CreateCouplingRollbackCoordinator.class.getDeclaredMethod("verifyLatest",LogStorage.class,CreateCouplingRollbackPlan.class,long.class);method.setAccessible(true);method.invoke(null,storage,plan,10L);}catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException cause)throw cause;throw new AssertionError(failure.getCause());}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}}
    private static void reject(Runnable action,String message){try{action.run();}catch(IllegalStateException|IllegalArgumentException expected){return;}throw new AssertionError(message);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
