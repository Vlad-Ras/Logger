package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import java.util.*;

public final class CartRecoveryPlanTest {
    private static final UUID ID=UUID.fromString("31000000-0000-0000-0000-000000000001");
    public static void run(){
        var envelope=snapshot();var original=envelope.copy();var plan=CartRecoveryPlan.fromSnapshot(ID,7,100,"after",envelope);
        check(envelope.equals(original),"recovery modified source history");
        check(plan.blocks()==1&&plan.item().getString("id").equals("create:minecart_contraption"),"native item/type absent");
        var data=plan.item().getCompound("components").getCompound("create:minecart_contraption_data");
        check(data.getList("items",10).equals(envelope.getCompound("Entity").getCompound("Contraption").getList("items",10)),"recovery lost cargo");
        check(data.getList("fluids",10).equals(envelope.getCompound("Entity").getCompound("Contraption").getList("fluids",10)),"recovery lost fluids");
        check(data.getString("InitialOrientation").equals("north"),"recovery lost orientation");
        reject(()->CartRecoveryPlan.fromSnapshot(UUID.randomUUID(),7,100,"after",envelope),"foreign ID accepted");
        var incomplete=envelope.copy();incomplete.getCompound("Entity").getCompound("Contraption").remove("Blocks");reject(()->CartRecoveryPlan.fromSnapshot(ID,7,100,"after",incomplete),"empty construction issued");
        var unsupported=envelope.copy();unsupported.getCompound("Cart").putString("id","minecraft:tnt_minecart");reject(()->CartRecoveryPlan.fromSnapshot(ID,7,100,"after",unsupported),"unsupported base cart converted");
        var nested=envelope.copy();var nestedList=new ListTag();nestedList.add(new CompoundTag());nested.getCompound("Entity").getCompound("Contraption").put("SubContraptions",nestedList);reject(()->CartRecoveryPlan.fromSnapshot(ID,7,100,"after",nested),"nested state silently lost");
        var pack=new CompoundTag();pack.putInt("Format",1);pack.putString("Form","item");pack.put("Item",plan.item());
        check(CartRecoveryPlan.fromSnapshot(ID,8,110,"after",pack).item().equals(plan.item()),"packed checkpoint changed components");
        var gone=new CompoundTag();gone.putInt("Format",1);gone.putString("Form","removed");
        var remove=row(20,ActionType.CART_REMOVE,envelope,gone);var move=row(21,ActionType.CART_MOVE,null,gone);
        var history=new Memory(List.of(row(7,ActionType.CART_ASSEMBLE,null,envelope),remove,move));
        var latest=CartRecoveryPlan.prepare(history,ID,0);check(latest.entry()==21&&latest.snapshotEntry()==20&&latest.side().equals("before"),"latest recovery did not use last full state before deletion");
        check(CartRecoveryPlan.prepare(history,ID,7).entry()==7,"explicit entry was replaced with latest");
        check(CartRecoveryPlan.prepare(history,ID,21).snapshotEntry()==20,"pose did not use prior full snapshot");
        reject(()->CartRecoveryPlan.prepare(history,ID,19),"missing entry selected neighbour");
        // Forward state reconstruction crosses a complete cursor page, bounded by capture IDs.
        var stateHistory=new ArrayList<LogEntry>();stateHistory.add(row(1,ActionType.CART_ASSEMBLE,null,envelope));
        for(int i=2;i<310;i++)stateHistory.add(row(i,ActionType.CART_MOVE,null,null));
        var before=new CompoundTag();before.putLong("LocalPos",0);before.put("State",envelope.getCompound("Entity").getCompound("Contraption").getCompound("Blocks").getList("Palette",10).getCompound(0).copy());
        var after=before.copy();var config=new CompoundTag();config.putString("Mode","saved-settings");after.put("Data",config);
        stateHistory.add(row(310,ActionType.CART_STRUCTURE_CHANGE,before,after));
        var nextBefore=after.copy();var nextAfter=after.copy();nextAfter.getCompound("Data").putString("Mode","later-settings");
        stateHistory.add(row(311,ActionType.CART_STRUCTURE_CHANGE,nextBefore,nextAfter));
        var store=new Memory(stateHistory);var selected=CartRecoveryPlan.prepare(store,ID,310);
        check(selected.snapshotEntry()==1&&selected.changes()==1&&selected.item().getCompound("components").getCompound("create:minecart_contraption_data").getCompound("Blocks").getList("BlockList",10).getCompound(0).getCompound("Data").getString("Mode").equals("saved-settings"),"selected state used future changes or lost block settings");
        check(CartRecoveryPlan.prepare(store,ID,0).changes()==2,"latest omitted final change");
        check(envelope.equals(original),"replay mutated stored NBT");
        var broken=new ArrayList<>(stateHistory);broken.set(broken.size()-1,row(311,ActionType.CART_STRUCTURE_CHANGE,before,nextAfter));reject(()->CartRecoveryPlan.prepare(new Memory(broken),ID,0),"broken state chain issued stale construction");
        var missing=new ArrayList<>(stateHistory);missing.add(row(312,ActionType.CART_PLACE,null,null));reject(()->CartRecoveryPlan.prepare(new Memory(missing),ID,0),"missing lifecycle checkpoint reused older state");
        var duplicate=envelope.copy();duplicate.getCompound("Entity").getCompound("Contraption").getCompound("Blocks").getList("BlockList",10).add(duplicate.getCompound("Entity").getCompound("Contraption").getCompound("Blocks").getList("BlockList",10).getCompound(0).copy());reject(()->CartRecoveryPlan.fromSnapshot(ID,1,1,"after",duplicate),"duplicate block positions accepted");
        System.out.println("CART_STATE_REPLAY_OK: selected/latest, 2-page history, block settings, source immutability, chain gaps, checkpoint and duplicate-position guards");
        System.out.println("CART_RECOVERY_PLAN_OK: exact/latest/before loss, source immutability, payload retention, ID/type/empty/nested guards; no period replay");
    }
    private static LogEntry row(long id,ActionType type,CompoundTag before,CompoundTag after){var row=new LogEntry();row.id=id;row.ts=id*100;row.type=type;row.source=CartAuditContext.prefix(ID)+"test:0";row.beBefore=before==null?null:before.toString();row.beAfter=after==null?null:after.toString();return row;}
    private record Memory(List<LogEntry> rows) implements LogStorage {
        public void append(LogEntry row){throw new UnsupportedOperationException();}
        public List<LogEntry> query(LogQuery q){return queryReverse(q);}
        public List<LogEntry> queryReverse(LogQuery q){return rows.stream().filter(e->q.afterId<=0||e.id>q.afterId).filter(e->q.beforeId<=0||e.id<q.beforeId).filter(e->q.types==null||q.types.contains(e.type)).sorted(Comparator.<LogEntry>comparingLong(e->e.id).reversed()).limit(q.limit).toList();}
        public void shutdown(){}
    }
    private static CompoundTag snapshot(){
        var n=new CompoundTag();n.putInt("Format",1);n.putString("Form","entity");var entity=new CompoundTag();var cart=new CompoundTag();cart.putString("id","minecraft:minecart");n.put("Cart",cart);entity.putString("InitialOrientation","north");
        var data=new CompoundTag();data.putString("Type","create:mounted");var audit=new CompoundTag();audit.putUUID("Id",ID);data.put(CartAuditState.KEY,audit);
        var blocks=new CompoundTag();var palette=new ListTag();var state=new CompoundTag();state.putString("Name","minecraft:stone");palette.add(state);blocks.put("Palette",palette);var blockList=new ListTag();var block=new CompoundTag();block.putLong("Pos",0);block.putInt("State",0);blockList.add(block);blocks.put("BlockList",blockList);data.put("Blocks",blocks);
        for(String key:List.of("items","fluids")){var values=new ListTag();var value=new CompoundTag();value.putString("OpaqueModState",key+"-preserved");values.add(value);data.put(key,values);}
        entity.put("Contraption",data);n.put("Entity",entity);return n;
    }
    private static void reject(Runnable task,String message){try{task.run();}catch(IllegalStateException|IllegalArgumentException expected){return;}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
