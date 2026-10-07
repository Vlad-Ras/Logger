package com.roften.avilixlogger.core;

import com.roften.avilixlogger.compat.create.CartAuditState;
import com.roften.avilixlogger.compat.create.CartRollbackPlan;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Detached history invariants: no Create runtime or live world is touched. */
public final class CartAuditTest {
    private static final UUID ID=UUID.fromString("59c687ca-4e6c-4054-ac29-a741e65a7481");
    public static void run() {
        identityRoundTrip(); nestedScopes(); pagedHistory(); conflictRejection(); itemIdentity();
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
    }
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
