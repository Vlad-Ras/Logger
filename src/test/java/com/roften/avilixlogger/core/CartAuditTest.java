package com.roften.avilixlogger.core;

import com.roften.avilixlogger.compat.create.CartAuditState;
import com.roften.avilixlogger.compat.create.CartRollbackPlan;
import com.roften.avilixlogger.compat.create.CartMenuTransferPlan;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Detached history invariants: no Create runtime or live world is touched. */
public final class CartAuditTest {
    private static final UUID ID=UUID.fromString("59c687ca-4e6c-4054-ac29-a741e65a7481");
    public static void run() {
        identityRoundTrip(); nestedScopes(); pagedHistory(); conflictRejection(); itemIdentity(); spawnedEffects(); monotonicCapture(); deployerGuard(); menuTransfers(); lootTransfers();
        CartAutomationPlanTest.run();
        com.roften.avilixlogger.compat.create.CreateDeployerRuntimeTest.run();
        com.roften.avilixlogger.compat.create.CartRecoveryPlanTest.run();
        System.out.println("Cart audit checks passed: persistent identity, nested attribution, 600-row reverse paging, foreign rows/external cargo/incomplete checkpoints rejected.");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void identityRoundTrip(){
        CartAuditState a=new CartAuditState();a.id=ID;a.owner=UUID.randomUUID();a.ownerName="owner";a.sequence=37;
        CartAuditState b=new CartAuditState();b.read(a.write());
        check(b.id.equals(ID)&&a.owner.equals(b.owner)&&b.sequence==37,"identity did not survive serialization");
        check(CartAuditContext.id(b.stamp("pack").source()).equals(ID),"packed identity not searchable");
        check(CartAuditContext.phase(b.stamp("pack").source()).equals("pack"),"phase parser failed");
        check(CartAuditContext.id("create:cart:"+ID+"Xwork:1")==null,"malformed identity delimiter accepted");
        check(b.stamp("work")==b.stamp("work"),"idle work stamp allocation");
    }
    private static void nestedScopes(){
        LogQuery query=new LogQuery();query.cartId=ID;query.requireDetails=true;check(ID.equals(query.copy().cartId)&&query.copy().requireDetails,"query fallback lost mandatory cart filter");
        var outer=new CartAuditContext.Stamp(ID,UUID.randomUUID(),"owner","work",1);
        var old=CartAuditContext.enter(outer);
        try {
            var inner=new CartAuditContext.Stamp(UUID.randomUUID(),null,"","pack",2);
            var previous=CartAuditContext.enter(inner);
            try {LogEntry row=new LogEntry();CartAuditContext.enrich(row);check(CartAuditContext.id(row.source).equals(inner.id()),"nested attribution lost");}
            finally{CartAuditContext.restore(previous);}
            check(CartAuditContext.current()==outer,"outer cause not restored");
        }finally{CartAuditContext.restore(old);}
        check(CartAuditContext.current()==null,"cart cause leaked");
        UUID previous=CartAuditContext.loot(ID);try{check(CartAuditContext.collectingLoot(ID)&&!CartAuditContext.collectingLoot(UUID.randomUUID()),"loot scope crossed cart identities");}finally{CartAuditContext.loot(previous);}
        check(!CartAuditContext.collectingLoot(ID),"loot scope leaked");
    }
    private static void pagedHistory(){
        Memory history=new Memory();history.rows.add(checkpoint());
        for(int i=2;i<=602;i++)history.rows.add(event(i,ActionType.CART_MOVE));
        var newest=event(602,ActionType.BLOCK_PLACE);newest.blockBefore="{Name:\"minecraft:air\"}";newest.blockAfter="{Name:\"minecraft:stone\"}";newest.dim="minecraft:overworld";
        history.rows.set(history.rows.size()-1,newest);
        var plan=CartRollbackPlan.prepare(history,ID,1);
        check(plan.cutoffId()==602&&history.reverseCalls==3,"reverse cursor skipped or duplicated rows");
        check(plan.blocks().size()==1&&plan.blocks().getFirst().beforeTag().getString("Name").equals("minecraft:air"),"block undo missing");
        check(plan.target().getString("Form").equals("blocks"),"target checkpoint changed");
    }
    private static void conflictRejection(){
        Memory h=new Memory();h.rows.add(checkpoint());
        var e=event(2,ActionType.CART_CONTENT_CHANGE);e.beAfter="{External:1b}";h.rows.add(e);
        reject(()->CartRollbackPlan.prepare(h,ID,1),"external cargo accepted");
        h.rows.set(1,event(2,ActionType.CART_ROLLBACK));reject(()->CartRollbackPlan.prepare(h,ID,1),"overlapping rollback accepted");
        e=event(2,ActionType.CART_MOVE);e.source=CartAuditContext.prefix(UUID.randomUUID())+"work:0";h.rows.set(1,e);
        reject(()->CartRollbackPlan.prepare(h,ID,1),"foreign cart history accepted");
        h.rows.clear();var incomplete=checkpoint();incomplete.beAfter=null;h.rows.add(incomplete);
        reject(()->CartRollbackPlan.prepare(h,ID,1),"incomplete checkpoint accepted");
    }
    private static void itemIdentity(){
        CompoundTag root=new CompoundTag(),components=new CompoundTag(),payload=new CompoundTag(),audit=new CompoundTag();audit.putUUID("Id",ID);payload.put("AvilixCartAudit",audit);components.put("create:minecart_contraption_data",payload);root.put("components",components);
        LogEntry e=event(1,ActionType.ITEM_DROP);e.source="player:drop";e.deferSnapshot(LogEntry.SnapshotField.ITEM,root);e.attachCartItemIdentity();check(ID.equals(CartAuditContext.id(e.source)),"packed item lost logical ID");
        for (var kind : CreateContraptionKind.values()) {
            if (kind == CreateContraptionKind.UNKNOWN) continue;
            String source=kind.source(UUID.randomUUID(),"deployer:mechanism=create:deployer@0,1,2");
            LogEntry action=event(2,ActionType.CREATE_DEPLOYER_ACTION);action.source=source;
            action.deferSnapshot(LogEntry.SnapshotField.ITEM,root.copy());action.attachCartItemIdentity();action.materializeSnapshots();
            check(source.equals(action.source),"packed item replaced acting construction");
            var packedTarget=NbtSerde.fromSnbt(action.itemStackNbt).getCompound("components").getCompound("create:minecart_contraption_data").getCompound("AvilixCartAudit");
            check(ID.equals(packedTarget.getUUID("Id")),"packed target identity lost");
            action.itemStackNbt=root.toString();action.materializeSnapshots();
            check(source.equals(action.source),"encoded packed item replaced acting construction");
        }
    }
    private static void spawnedEffects(){
        Memory h=new Memory();h.rows.add(checkpoint());var e=event(2,ActionType.ENTITY_SPAWN);e.entityUuid=UUID.randomUUID();e.entityType="minecraft:item";e.dim="minecraft:overworld";e.entityNbt="{id:\"minecraft:item\"}";e.itemStackNbt="{id:\"minecraft:cobblestone\",count:1}";h.rows.add(e);
        check(CartRollbackPlan.prepare(h,ID,1).spawned().size()==1,"drill output not included in undo");
        var pickup=event(3,ActionType.ITEM_PICKUP);pickup.source=CartAuditContext.prefix(ID)+"loot_pickup:0";h.rows.add(pickup);reject(()->CartRollbackPlan.prepare(h,ID,1),"transferred drill output accepted");
        h.rows.removeLast();e.entityType="minecraft:cow";reject(()->CartRollbackPlan.prepare(h,ID,1),"unsupported external entity accepted");
        h.rows.clear();var point=checkpoint();point.type=ActionType.CART_ROLLBACK;h.rows.add(point);check(CartRollbackPlan.prepare(h,ID,1).target()!=null,"rollback receipt cannot be a new checkpoint");
    }
    private static void monotonicCapture(){
        long first=LogIdGenerator.next(System.currentTimeMillis()), second=LogIdGenerator.next(1);check(second>first,"clock rollback reversed event order");
        Memory h=new Memory();h.rows.add(checkpoint());var e=event(2,ActionType.CART_MOVE);e.ts=1;h.rows.add(e);check(CartRollbackPlan.prepare(h,ID,1).cutoffId()==2,"backward clock skipped captured history");
    }
    private static void deployerGuard(){
        Memory h=new Memory();h.rows.add(checkpoint());h.rows.add(event(2,ActionType.CREATE_DEPLOYER_ACTION));
        reject(()->CartRollbackPlan.prepare(h,ID,1),"deployer action ignored by single-cart rollback");
        h.rows.removeLast();var io=event(2,ActionType.CART_TRANSFER);io.source=CartAuditContext.prefix(ID)+"storage_io:0";h.rows.add(io);
        reject(()->CartRollbackPlan.prepare(h,ID,1),"creative storage operation ignored by single-cart rollback");
        io.source=CartAuditContext.prefix(ID)+"automation_transfer:0";io.beAfter="{Kind:'storage_io'}";
        reject(()->CartRollbackPlan.prepare(h,ID,1),"creative storage guard depended only on phase");
    }
    private static void menuTransfers(){
        UUID player=UUID.randomUUID(),transfer=UUID.randomUUID();
        var before=transferManifest(transfer,player,0,0);var after=transferManifest(transfer,player,12,0);
        var cargoBefore=cargo(transfer,0,12);var cargoAfter=cargo(transfer,0,0);
        var plan=new CartMenuTransferPlan();plan.manifest(before,after);plan.cargo(cargoBefore,cargoAfter);
        var slots=plan.finish();check(slots.size()==1&&slots.getFirst().slot()==0&&slots.getFirst().before().isEmpty()&&slots.getFirst().after().getInt("count")==12,"linked transfer did not restore recipient");
        var missing=new CartMenuTransferPlan();missing.cargo(cargoBefore,cargoAfter);reject(missing::finish,"missing recipient manifest accepted");
        var incomplete=new CartMenuTransferPlan();incomplete.manifest(before,after);reject(incomplete::finish,"missing cargo accepted");
        var altered=new CartMenuTransferPlan();altered.manifest(before,transferManifest(transfer,player,11,0));altered.cargo(cargoBefore,cargoAfter);reject(altered::finish,"non-conserving transfer accepted");
        var components=new CartMenuTransferPlan();var other=transferManifest(transfer,player,12,0);other.getList("Slots",10).getCompound(0).getCompound("Item").putString("id","minecraft:coal");components.manifest(before,other);components.cargo(cargoBefore,cargoAfter);reject(components::finish,"different recipient item accepted");
        var duplicate=new CartMenuTransferPlan();duplicate.manifest(before,after);duplicate.cargo(cargoBefore,cargoAfter);reject(()->duplicate.cargo(cargoBefore,cargoAfter),"duplicate cargo accepted");
        var failed=new CartMenuTransferPlan();var interrupted=after.copy();interrupted.putBoolean("Complete",false);failed.manifest(before,interrupted);failed.cargo(cargoBefore,cargoAfter);reject(failed::finish,"interrupted click accepted");
        var cursor=new CartMenuTransferPlan();cursor.manifest(transferManifest(transfer,player,0,-1),transferManifest(transfer,player,12,-1));cursor.cargo(cargoBefore,cargoAfter);check(cursor.finish().getFirst().slot()==-1,"cursor transfer not represented");
        var single=new CartMenuTransferPlan();var oneAfter=transferManifest(transfer,player,1,0);oneAfter.getList("Slots",10).getCompound(0).getCompound("Item").remove("count");var oneBefore=cargo(transfer,0,1);oneBefore.getCompound("Item").remove("count");single.manifest(before,oneAfter);single.cargo(oneBefore,cargoAfter);check(single.finish().size()==1,"default codec count=1 rejected");
        UUID close=UUID.randomUUID();var closeBefore=transferManifest(close,player,12,-1);var closeAfter=transferManifest(close,player,0,-1);closeBefore.putInt("Changes",0);closeAfter.putInt("Changes",0);
        CompoundTag inventoryBefore=new CompoundTag(),inventoryAfter=new CompoundTag();inventoryBefore.putInt("Slot",0);inventoryAfter.putInt("Slot",0);inventoryAfter.put("Item",item(12));closeBefore.getList("Slots",10).add(inventoryBefore);closeAfter.getList("Slots",10).add(inventoryAfter);
        var closed=new CartMenuTransferPlan();closed.manifest(closeBefore,closeAfter);closed.manifest(transferManifest(transfer,player,0,-1),transferManifest(transfer,player,12,-1));closed.cargo(cargoBefore,cargoAfter);var closedSlots=closed.finish();check(closedSlots.size()==1&&closedSlots.getFirst().slot()==0&&closedSlots.getFirst().after().getInt("count")==12,"menu closure lost cursor-to-inventory transfer");
        UUID latest=UUID.randomUUID();var chain=new CartMenuTransferPlan();chain.manifest(transferManifest(latest,player,12,0),transferManifest(latest,player,16,0));chain.cargo(cargo(latest,0,4),cargo(latest,0,0));chain.manifest(before,after);chain.cargo(cargoBefore,cargoAfter);
        var combined=chain.finish().getFirst();check(combined.before().isEmpty()&&combined.after().getInt("count")==16,"reverse transfer chain incorrectly merged");
        var broken=new CartMenuTransferPlan();broken.manifest(transferManifest(latest,player,0,0),transferManifest(latest,player,4,0));broken.cargo(cargo(latest,0,4),cargo(latest,0,0));broken.manifest(before,after);broken.cargo(cargoBefore,cargoAfter);reject(broken::finish,"moved recipient cargo accepted");
        // Exercise the real history builder, including manifest/cargo on opposite pages.
        Memory history=new Memory();history.rows.add(checkpoint());
        var cargoRow=event(2,ActionType.CART_CONTENT_CHANGE);cargoRow.beBefore=cargoBefore.toString();cargoRow.beAfter=cargoAfter.toString();history.rows.add(cargoRow);
        for(int i=3;i<260;i++)history.rows.add(event(i,ActionType.CART_MOVE));
        var receipt=event(260,ActionType.CART_TRANSFER);receipt.source=CartAuditContext.prefix(ID)+"menu_transfer:1";receipt.beBefore=before.toString();receipt.beAfter=after.toString();history.rows.add(receipt);
        check(CartRollbackPlan.prepare(history,ID,1).recipients().size()==1,"linked transfer lost across history pages");
        receipt.source=CartAuditContext.prefix(ID)+"depot_swap:1";cargoRow.source=CartAuditContext.prefix(ID)+"depot_swap:1";
        check(CartRollbackPlan.prepare(history,ID,1).recipients().size()==1,"native depot phase omitted linked recipient manifest");
        var failedDepot=after.copy();failedDepot.putBoolean("Complete",false);receipt.beAfter=failedDepot.toString();
        reject(()->CartRollbackPlan.prepare(history,ID,1),"failed depot interaction allowed joint rollback");
    }
    private static CompoundTag transferManifest(UUID id,UUID player,int count,int slot){
        CompoundTag n=new CompoundTag();n.putInt("Format",1);n.putString("Kind","menu_items");n.putUUID("Transfer",id);n.putUUID("Player",player);n.putInt("Changes",1);n.putBoolean("Complete",true);
        net.minecraft.nbt.ListTag slots=new net.minecraft.nbt.ListTag();CompoundTag value=new CompoundTag();value.putInt("Slot",slot);if(count>0)value.put("Item",item(count));slots.add(value);n.put("Slots",slots);return n;
    }
    private static CompoundTag cargo(UUID transfer,int index,int count){CompoundTag n=new CompoundTag();n.putString("Kind","item");n.putBoolean("External",true);n.putUUID("Transfer",transfer);n.putInt("TransferIndex",index);n.putLong("LocalPos",0);n.putInt("Slot",3);if(count>0)n.put("Item",item(count));return n;}
    private static void lootTransfers(){
        UUID drop=UUID.randomUUID(),player=UUID.randomUUID();Memory history=new Memory();history.rows.add(checkpoint());
        var spawn=event(2,ActionType.ENTITY_SPAWN);spawn.entityUuid=drop;spawn.entityType="minecraft:item";spawn.dim="minecraft:overworld";spawn.entityNbt="{id:\"minecraft:item\"}";spawn.itemStackNbt=item(12).toString();history.rows.add(spawn);
        var partial=pickup(3,drop,player,12,7,0,5);history.rows.add(partial);
        var plan=CartRollbackPlan.prepare(history,ID,1);check(plan.spawned().getFirst().item().getInt("count")==7&&plan.recipients().getFirst().after().getInt("count")==5,"partial pickup lost world remainder or recipient");
        partial.entityUuid=null;check(CartRollbackPlan.prepare(history,ID,1).recipients().size()==1,"split ITEMS drop UUID was not recovered from envelope");
        history.rows.add(pickup(4,drop,player,7,0,5,12));plan=CartRollbackPlan.prepare(history,ID,1);
        check(plan.spawned().getFirst().item().isEmpty()&&plan.recipients().size()==1&&plan.recipients().getFirst().after().getInt("count")==12,"multiple pickups did not merge into a full pickup");
        var damaged=pickup(4,drop,player,8,0,4,12);history.rows.set(3,damaged);reject(()->CartRollbackPlan.prepare(history,ID,1),"interrupted pickup history accepted");
        history.rows.set(3,pickup(4,drop,UUID.randomUUID(),7,0,0,7));plan=CartRollbackPlan.prepare(history,ID,1);check(plan.recipients().size()==2,"different pickup recipients not retained");
        history.rows.removeLast();var invalid=pickup(3,drop,player,12,7,0,4);history.rows.set(2,invalid);reject(()->CartRollbackPlan.prepare(history,ID,1),"pickup item imbalance accepted");
        history.rows.set(2,pickup(3,drop,player,12,0,0,12));spawn.itemStackNbt=item(13).toString();reject(()->CartRollbackPlan.prepare(history,ID,1),"drop modified between spawn and pickup accepted");spawn.itemStackNbt=item(12).toString();
        history.rows.remove(1);reject(()->CartRollbackPlan.prepare(history,ID,1),"pickup created before checkpoint accepted");history.rows.add(1,spawn);
        UUID menuId=UUID.randomUUID();var cb=event(4,ActionType.CART_CONTENT_CHANGE);cb.beBefore=cargo(menuId,0,0).toString();cb.beAfter=cargo(menuId,0,12).toString();history.rows.add(cb);
        var menu=event(5,ActionType.CART_TRANSFER);menu.source=CartAuditContext.prefix(ID)+"menu_transfer:0";menu.beBefore=transferManifest(menuId,player,12,0).toString();menu.beAfter=transferManifest(menuId,player,0,0).toString();history.rows.add(menu);
        plan=CartRollbackPlan.prepare(history,ID,1);check(plan.recipients().isEmpty()&&plan.spawned().getFirst().item().isEmpty(),"pickup followed by cargo deposit did not conserve the combined chain");
    }
    private static LogEntry pickup(long sequence,UUID drop,UUID player,int original,int remaining,int inventoryBefore,int inventoryAfter){
        UUID transfer=UUID.randomUUID();var b=lootManifest(transfer,drop,player,original,inventoryBefore);var a=lootManifest(transfer,drop,player,remaining,inventoryAfter);
        var row=event(sequence,ActionType.ITEM_PICKUP);row.source=CartAuditContext.prefix(ID)+"loot_pickup:0";row.entityUuid=drop;row.actorUuid=player;row.playerInvBefore=b.toString();row.playerInvAfter=a.toString();return row;
    }
    private static CompoundTag lootManifest(UUID transfer,UUID drop,UUID player,int count,int inventory){var n=transferManifest(transfer,player,inventory,0);n.putString("Kind","loot_items");n.putInt("Changes",0);n.putUUID("Drop",drop);n.put("Item",count==0?new CompoundTag():item(count));return n;}
    private static CompoundTag item(int count){CompoundTag n=new CompoundTag();n.putString("id","minecraft:diamond");n.putInt("count",count);return n;}
    private static void reject(Runnable task,String message){try{task.run();}catch(IllegalStateException|IllegalArgumentException expected){return;}throw new AssertionError(message);}
    private static LogEntry checkpoint(){var e=event(1,ActionType.CART_DISASSEMBLE);CompoundTag tag=new CompoundTag();tag.putInt("Format",1);tag.putString("Form","blocks");tag.putString("Dimension","minecraft:overworld");CompoundTag base=new CompoundTag();base.putUUID("UUID",UUID.randomUUID());base.putString("id","minecraft:minecart");tag.put("Cart",base);e.beAfter=tag.toString();return e;}
    private static LogEntry event(long id,ActionType type){LogEntry e=new LogEntry();e.id=id;e.ts=1000+id;e.type=type;e.source=CartAuditContext.prefix(ID)+"work:1";return e;}
    private static final class Memory implements LogStorage {
        final List<LogEntry> rows=new ArrayList<>();int reverseCalls;
        public void append(LogEntry e){rows.add(e);}
        public List<LogEntry> query(LogQuery q){check(q.requireDetails&&ID.equals(q.cartId),"required detail/cart filter absent");return rows.stream().filter(e->e.id>q.afterId).sorted(Comparator.comparingLong(e->e.id)).limit(q.limit).toList();}
        public List<LogEntry> queryReverse(LogQuery q){reverseCalls++;return rows.stream().filter(e->e.id<q.beforeId&&e.ts>=q.sinceTs).sorted(Comparator.<LogEntry>comparingLong(e->e.id).reversed()).limit(q.limit).toList();}
        public void shutdown(){}
    }
}
