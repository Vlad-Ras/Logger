package com.roften.avilixlogger.core;
import com.roften.avilixlogger.compat.create.CreateTransferRollbackPlan;
import net.minecraft.nbt.*;
import java.util.*;

public final class CreateLinkedTransferPlanTest {
    public static void run(){
        for(var kind:List.of(CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER)){
            var before=manifest(kind,false);var after=manifest(kind,true);
            var plan=CreateTransferRollbackPlan.prepare(before,after);check(plan.cargo().size()==2&&plan.constructions().size()==2,"peer missing");
            var missing=after.copy();missing.getList("Cargo",10).remove(1);reject(()->CreateTransferRollbackPlan.prepare(before,missing),"missing endpoint accepted");
            var corrupt=after.copy();corrupt.getList("Cargo",10).getCompound(1).getCompound("Item").putInt("count",9);reject(()->CreateTransferRollbackPlan.prepare(before,corrupt),"unbalanced transfer accepted");
            var wrongId=after.copy();wrongId.getList("Cargo",10).getCompound(1).putUUID("StorageEntity",UUID.randomUUID());reject(()->CreateTransferRollbackPlan.prepare(before,wrongId),"storage replacement accepted");
            var components=after.copy();components.getList("Cargo",10).getCompound(1).getCompound("Item").put("components",NbtSerde.fromSnbt("{'minecraft:damage':3}"));reject(()->CreateTransferRollbackPlan.prepare(before,components),"component loss accepted");
            var incomplete=after.copy();incomplete.putBoolean("Complete",false);reject(()->CreateTransferRollbackPlan.prepare(before,incomplete),"partial operation accepted");
            var aliasBefore=before.copy();var aliasAfter=after.copy();
            aliasBefore.getList("Cargo",10).getCompound(1).putUUID("StorageEntity",aliasBefore.getList("Cargo",10).getCompound(0).getUUID("StorageEntity"));
            aliasAfter.getList("Cargo",10).getCompound(1).putUUID("StorageEntity",aliasAfter.getList("Cargo",10).getCompound(0).getUUID("StorageEntity"));
            reject(()->CreateTransferRollbackPlan.prepare(aliasBefore,aliasAfter),"one physical storage assigned to different participants");
            historyGuard(plan,kind);
            var frozen=plan.cargo().getFirst().before().copy();before.getList("Cargo",10).getCompound(0).getCompound("Item").putInt("count",111);check(plan.cargo().getFirst().before().equals(frozen),"worker plan shares input snapshots");
            check(CreateContraptionKind.phase(kind.source(UUID.randomUUID(),"automation_transfer")).equals("automation_transfer"),"phase classification differs by type");
        }
        System.out.println("CREATE_LINKED_TRANSFER_PLAN_OK: paired balance, missing peers, ID/components, partial operations, detached snapshots and all-kind phases");
    }
    private static void historyGuard(CreateTransferRollbackPlan plan,CreateContraptionKind kind){
        var id=plan.constructions().iterator().next();
        var sameTransfer=new LogEntry();sameTransfer.id=11;sameTransfer.type=ActionType.CART_TRANSFER;sameTransfer.source=kind.source(id,"automation_transfer");sameTransfer.beAfter=plan.after().toString();
        verifyHistory(plan,List.of(sameTransfer));
        for(var action:List.of(ActionType.CART_ROLLBACK,ActionType.CART_DISASSEMBLE,ActionType.CART_STATUS,ActionType.CART_CONTENT_CHANGE)){
            var later=new LogEntry();later.id=12;later.type=action;later.source=kind.source(id,action==ActionType.CART_STATUS?"coupling":"transfer_rollback");
            later.beAfter=(action==ActionType.CART_CONTENT_CHANGE?new CompoundTag():plan.after()).toString();
            reject(()->verifyHistory(plan,List.of(later,sameTransfer)),"later mutation/rollback accepted: "+action);
        }
    }
    private static void verifyHistory(CreateTransferRollbackPlan plan,List<LogEntry> rows){
        LogStorage history=new LogStorage(){
            public void append(LogEntry row){throw new UnsupportedOperationException();}
            public List<LogEntry> query(LogQuery q){return queryReverse(q);}
            public List<LogEntry> queryReverse(LogQuery q){return rows.stream().filter(r->q.constructionId.equals(CreateContraptionKind.id(r.source))).sorted(Comparator.<LogEntry>comparingLong(r->r.id).reversed()).toList();}
            public void shutdown(){}
        };
        try{
            var coordinator=Class.forName("com.roften.avilixlogger.compat.create.CreateTransferRollbackCoordinator");
            var method=coordinator.getDeclaredMethod("verifyLatest",LogStorage.class,CreateTransferRollbackPlan.class,long.class);method.setAccessible(true);method.invoke(null,history,plan,10L);
        }catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException cause)throw cause;throw new AssertionError(failure.getCause());}
        catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    public static CompoundTag manifest(CreateContraptionKind kind,boolean after){
        UUID first=UUID.fromString("42000000-0000-0000-0000-000000000001"),second=UUID.fromString("42000000-0000-0000-0000-000000000002"),transfer=UUID.fromString("42000000-0000-0000-0000-000000000003");
        var tag=new CompoundTag();tag.putInt("Format",1);tag.putString("Kind","automation");tag.putBoolean("Complete",true);tag.putInt("Changes",2);tag.putUUID("Transfer",transfer);tag.putUUID("Construction",first);tag.put("Slots",new ListTag());tag.putString("Machine","create:andesite_funnel");tag.putString("MachineDim","minecraft:overworld");
        var cargo=new ListTag();
        for(int i=0;i<2;i++){
            var cell=new CompoundTag();cell.putString("Kind","item");cell.putString("ConstructionKind",kind.name());cell.putUUID("Construction",i==0?first:second);cell.putUUID("StorageEntity",i==0?first:second);cell.putString("Dimension","minecraft:overworld");cell.putBoolean("External",true);cell.putInt("TransferIndex",i);cell.putUUID("Transfer",transfer);cell.putString("TransferKind","automation");cell.putLong("LocalPos",0);cell.putInt("Slot",i);
            int count=i==0?(after?3:8):(after?5:0);if(count>0){var item=new CompoundTag();item.putString("id","minecraft:diamond");item.putInt("count",count);cell.put("Item",item);}cargo.add(cell);
        }tag.put("Cargo",cargo);return tag;
    }
    private static void reject(Runnable action,String message){try{action.run();}catch(IllegalStateException|IllegalArgumentException expected){return;}throw new AssertionError(message);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
