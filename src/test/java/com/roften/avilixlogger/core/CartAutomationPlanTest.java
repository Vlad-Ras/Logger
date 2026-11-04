package com.roften.avilixlogger.core;

import com.roften.avilixlogger.compat.create.*;
import net.minecraft.nbt.*;
import java.util.*;

/** Adversarial histories: two-sided conservation, alias sides, gaps, components and paging. */
public final class CartAutomationPlanTest {
    private static final UUID CART=UUID.randomUUID();
    public static void run(){
        for(String kind:List.of("item","fluid")){
            UUID id=UUID.randomUUID();var b=manifest(id,kind,0);var a=manifest(id,kind,12);var cb=cargo(id,kind,12);var ca=cargo(id,kind,0);
            var p=plan(b,a,cb,ca);var undo=p.finish(CART).getFirst();check(undo.before().isEmpty()&&quantity(undo.after(),kind)==12,"resource/recipient amount lost");
            var incomplete=a.copy();incomplete.putBoolean("Complete",false);reject(()->plan(b,incomplete,cb,ca).finish(CART),"incomplete operation accepted");
            var different=a.copy();different.getList("Slots",10).getCompound(0).getCompound(field(kind)).put("components",custom("changed"));reject(()->plan(b,different,cb,ca).finish(CART),"recipient component change accepted");
            var missing=new CartAutomationTransferPlan();missing.cargo(cb,ca);reject(()->missing.finish(CART),"missing external snapshot accepted");
            var imbalance=manifest(id,kind,11);reject(()->plan(b,imbalance,cb,ca).finish(CART),"imbalance accepted");
            var count=a.copy();count.putInt("Changes",2);reject(()->plan(b,count,cb,ca).finish(CART),"missing cargo row accepted");
            var newest=UUID.randomUUID();var nb=manifest(newest,kind,12);var na=manifest(newest,kind,16);na.getList("Slots",10).getCompound(0).putString("Side","up");nb.getList("Slots",10).getCompound(0).putString("Side","up");
            var chain=new CartAutomationTransferPlan();chain.manifest(nb,na);chain.cargo(cargo(newest,kind,4),cargo(newest,kind,0));chain.manifest(b,a);chain.cargo(cb,ca);
            var joined=chain.finish(CART);check(joined.size()==1&&quantity(joined.getFirst().after(),kind)==16,"same storage through different sides duplicated undo");
            var gap=manifest(newest,kind,0);var gapAfter=manifest(newest,kind,4);var broken=new CartAutomationTransferPlan();broken.manifest(gap,gapAfter);broken.cargo(cargo(newest,kind,4),cargo(newest,kind,0));broken.manifest(b,a);broken.cargo(cb,ca);reject(()->broken.finish(CART),"recipient move between operations accepted");
            var malformed=a.copy();malformed.getList("Slots",10).getCompound(0).putInt("Slot",-1);reject(()->plan(b,malformed,cb,ca).finish(CART),"invalid external slot accepted");
        }
        doubleChestHistory();
        worldItems();
        worldCreative();
        CreateMixedDropTransferPlanTest.run();
        UUID id=UUID.randomUUID();var b=manifest(id,"item",0);var a=manifest(id,"item",1);var cb=cargo(id,"item",1);var ca=cargo(id,"item",0);a.getList("Slots",10).getCompound(0).getCompound("Item").remove("count");cb.getCompound("Item").remove("count");check(plan(b,a,cb,ca).finish(CART).size()==1,"native implicit item count rejected");
        var rows=new ArrayList<LogEntry>();var point=row(1,ActionType.CART_DISASSEMBLE);var target=new CompoundTag();target.putInt("Format",1);target.putString("Form","blocks");target.putString("Dimension","minecraft:overworld");var cart=new CompoundTag();cart.putUUID("UUID",UUID.randomUUID());cart.putString("id","minecraft:minecart");target.put("Cart",cart);point.beAfter=target.toString();rows.add(point);
        var change=row(2,ActionType.CART_CONTENT_CHANGE);change.beBefore=cb.toString();change.beAfter=ca.toString();rows.add(change);for(int i=3;i<601;i++)rows.add(row(i,ActionType.CART_MOVE));var receipt=row(601,ActionType.CART_TRANSFER);receipt.source=CartAuditContext.prefix(CART)+"automation_transfer:1";receipt.beBefore=b.toString();receipt.beAfter=a.toString();rows.add(receipt);
        var storage=new LogStorage(){public void append(LogEntry r){}public List<LogEntry> query(LogQuery q){return rows.stream().filter(r->r.id>q.afterId).limit(q.limit).toList();}public List<LogEntry> queryReverse(LogQuery q){return rows.stream().filter(r->r.id<q.beforeId).sorted(Comparator.<LogEntry>comparingLong(r->r.id).reversed()).limit(q.limit).toList();}public void shutdown(){}};
        check(CartRollbackPlan.prepare(storage,CART,1).automation().size()==1,"automatic recipient lost across history pages");
        System.out.println("Create automation plan checks passed: items/fluids, components, gaps, alias sides, incomplete operations and reverse paging.");
    }
    private static void doubleChestHistory(){
        for(int slot:List.of(0,26,27,53)){
            var older=UUID.randomUUID();var newer=UUID.randomUUID();
            var b=doubleManifest(older,slot,0);var a=doubleManifest(older,slot,12);
            var nb=doubleManifest(newer,slot,12);var na=doubleManifest(newer,slot,16);
            nb.getList("Slots",10).getCompound(0).putString("Side","up");na.getList("Slots",10).getCompound(0).putString("Side","up");
            var joined=new CartAutomationTransferPlan();joined.manifest(nb,na);joined.cargo(cargo(newer,"item",4),cargo(newer,"item",0));joined.manifest(b,a);joined.cargo(cargo(older,"item",12),cargo(older,"item",0));
            var undo=joined.finish(CART);check(undo.size()==1&&undo.getFirst().address().getInt("Slot")==slot&&undo.getFirst().address().contains("DoubleChest",10)&&quantity(undo.getFirst().after(),"item")==16,"double chest alias/history lost physical slot or partner");
            var changed=na.copy();changed.getList("Slots",10).getCompound(0).getCompound("DoubleChest").putLong("Pos",12);
            reject(()->plan(nb,changed,cargo(newer,"item",4),cargo(newer,"item",0)).finish(CART),"changed partner inside operation accepted");
            var splitBefore=nb.copy();var splitAfter=na.copy();for(var n:List.of(splitBefore,splitAfter))n.getList("Slots",10).getCompound(0).getCompound("DoubleChest").putLong("Pos",12);
            var broken=new CartAutomationTransferPlan();broken.manifest(splitBefore,splitAfter);broken.cargo(cargo(newer,"item",4),cargo(newer,"item",0));broken.manifest(b,a);broken.cargo(cargo(older,"item",12),cargo(older,"item",0));
            reject(()->broken.finish(CART),"changed partner between operations accepted");
        }
    }
    private static void worldItems(){
        UUID entity=UUID.randomUUID(),transfer=UUID.randomUUID();
        var b=worldManifest(transfer,entity,5);var a=worldManifest(transfer,entity,2);
        var cb=cargo(transfer,"item",0);var ca=cargo(transfer,"item",3);
        var p=plan(b,a,cb,ca);check(p.finish(CART).isEmpty()&&p.worldItems().size()==1,"funnel item endpoint became a block slot");
        check(p.worldItems().getFirst().before().getCompound("Item").getInt("count")==5,"funnel original stack missing");
        var incomplete=a.copy();incomplete.remove("Entities");reject(()->plan(b,incomplete,cb,ca).finish(CART),"missing world endpoint accepted");
        var id=a.copy();id.getList("Entities",10).getCompound(0).putUUID("WorldEntity",UUID.randomUUID());reject(()->plan(b,id,cb,ca).finish(CART),"changed entity identity accepted");
        var payload=a.copy();payload.getList("Entities",10).getCompound(0).getCompound("EntityNBT").getCompound("Item").putInt("count",12);reject(()->plan(b,payload,cb,ca).finish(CART),"NBT/item divergence accepted");
        var unbalanced=worldManifest(transfer,entity,1);reject(()->plan(b,unbalanced,cb,ca).finish(CART),"world item imbalance accepted");
        var newer=UUID.randomUUID();var latest=new CartAutomationTransferPlan();latest.manifest(worldManifest(newer,entity,2),worldManifest(newer,entity,0));latest.cargo(cargo(newer,"item",0),cargo(newer,"item",2));latest.manifest(b,a);latest.cargo(cb,ca);
        check(latest.finish(CART).isEmpty()&&latest.worldItems().getFirst().after().getCompound("EntityNBT").isEmpty(),"partial-to-full absorption chain lost");
        var gap=new CartAutomationTransferPlan();gap.manifest(worldManifest(newer,entity,1),worldManifest(newer,entity,0));gap.cargo(cargo(newer,"item",0),cargo(newer,"item",1));gap.manifest(b,a);gap.cargo(cb,ca);reject(()->gap.finish(CART),"external entity move between exchanges accepted");
        var single=worldManifest(transfer,entity,1);for(String field:List.of("Item","EntityNBT")){
            var endpoint=single.getList("Entities",10).getCompound(0);
            (field.equals("Item")?endpoint.getCompound("Item"):endpoint.getCompound("EntityNBT").getCompound("Item")).remove("count");
        }
        check(plan(single,worldManifest(transfer,entity,0),cargo(transfer,"item",0),cargo(transfer,"item",1)).finish(CART).isEmpty(),"implicit world item quantity rejected");
        System.out.println("Create world item plan checks passed: conservation, identity, exact entity payload, missing endpoints, reverse partial/full chain and gaps.");
    }
    private static void worldCreative(){
        for(String kind:List.of("item","fluid"))for(boolean inbound:List.of(false,true)){
            UUID id=UUID.randomUUID();var b=manifest(id,kind,0);var a=manifest(id,kind,0);
            b.put("Slots",new ListTag());a.put("Slots",new ListTag());
            var io=new CompoundTag();io.putString("Kind",kind);io.putString("Direction",inbound?"in":"out");
            io.putString("Dimension","minecraft:overworld");io.putLong("Pos",10);io.putString("Side","none");
            io.putString("Handler",kind.equals("item")?"com.simibubi.create.content.logistics.crate.BottomlessItemHandler"
                    :"com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity$CreativeSmartFluidTank");
            var block=new CompoundTag();block.putString("Name",kind.equals("item")?"create:creative_crate":"create:creative_fluid_tank");
            io.put("Block",block);io.putString("BlockEntity",kind.equals("item")?"create:creative_crate":"create:creative_fluid_tank");
            io.putInt("Size",kind.equals("item")?2:1);io.putInt("Limit",1000);io.put("Template",stack(kind,64));io.put("Payload",stack(kind,12));
            var list=new ListTag();list.add(io);b.put("WorldCreativeIO",list.copy());a.put("WorldCreativeIO",list.copy());
            var p=plan(b,a,cargo(id,kind,inbound?12:0),cargo(id,kind,inbound?0:12));
            check(p.finish(CART).isEmpty()&&p.worldCreative().size()==1,"world creative transfer lost");
            var single=singleCartCreative(b,a,cargo(id,kind,inbound?12:0),cargo(id,kind,inbound?0:12));
            check(single.worldCreative().size()==1&&single.automation().isEmpty(),"single-cart checkpoint lost its immutable creative endpoint");
            reject(()->singleCartCreative(b,a,cargo(id,kind,inbound?12:0),cargo(id,kind,inbound?0:12),true),
                    "creative source also changed as a world block during the checkpoint");
            var tampered=a.copy();tampered.getList("WorldCreativeIO",10).getCompound(0).getCompound("Payload").putInt(kind.equals("item")?"count":"amount",11);
            reject(()->plan(b,tampered,cargo(id,kind,inbound?12:0),cargo(id,kind,inbound?0:12)).finish(CART),"creative payload tampering accepted");
            var missing=a.copy();missing.remove("WorldCreativeIO");
            reject(()->plan(b,missing,cargo(id,kind,inbound?12:0),cargo(id,kind,inbound?0:12)).finish(CART),"missing creative endpoint accepted");
            if(!inbound){
                var badBefore=b.copy();var badAfter=a.copy();
                for(var snapshot:List.of(badBefore,badAfter))snapshot.getList("WorldCreativeIO",10).getCompound(0).getCompound("Template").putString("id","minecraft:stone");
                reject(()->plan(badBefore,badAfter,cargo(id,kind,0),cargo(id,kind,12)).finish(CART),"creative supply with unrelated template accepted");
            }
            var unbalanced=b.copy();unbalanced.getList("WorldCreativeIO",10).getCompound(0).getCompound("Payload").putInt(kind.equals("item")?"count":"amount",11);
            a.getList("WorldCreativeIO",10).getCompound(0).getCompound("Payload").putInt(kind.equals("item")?"count":"amount",11);
            reject(()->plan(unbalanced,a,cargo(id,kind,inbound?12:0),cargo(id,kind,inbound?0:12)).finish(CART),"unbalanced creative IO accepted");
        }
    }
    private static CartRollbackPlan singleCartCreative(CompoundTag b,CompoundTag a,CompoundTag cb,CompoundTag ca){
        return singleCartCreative(b,a,cb,ca,false);
    }
    private static CartRollbackPlan singleCartCreative(CompoundTag b,CompoundTag a,CompoundTag cb,CompoundTag ca,boolean changedBlock){
        var checkpoint=row(1,ActionType.CART_DISASSEMBLE);var target=new CompoundTag();target.putInt("Format",1);
        target.putString("Form","blocks");target.putString("Dimension","minecraft:overworld");
        var cart=new CompoundTag();cart.putUUID("UUID",UUID.randomUUID());cart.putString("id","minecraft:minecart");target.put("Cart",cart);
        checkpoint.beAfter=target.toString();var cargo=row(2,ActionType.CART_CONTENT_CHANGE);
        cargo.beBefore=cb.toString();cargo.beAfter=ca.toString();
        var manifest=row(3,ActionType.CART_TRANSFER);manifest.source=CartAuditContext.prefix(CART)+"automation_transfer:0";
        manifest.beBefore=b.toString();manifest.beAfter=a.toString();
        var rows=new ArrayList<>(List.of(checkpoint,cargo,manifest));
        if(changedBlock){
            var block=row(4,ActionType.BLOCK_INTERACT);var pos=net.minecraft.core.BlockPos.of(10);
            block.x=pos.getX();block.y=pos.getY();block.z=pos.getZ();block.dim="minecraft:overworld";
            var old=new CompoundTag();old.putString("Name","create:creative_crate");
            var changed=new CompoundTag();changed.putString("Name","minecraft:stone");
            block.blockBefore=old.toString();block.blockAfter=changed.toString();rows.add(block);
        }
        var storage=new LogStorage(){public void append(LogEntry r){}
            public List<LogEntry> query(LogQuery q){return rows.stream().filter(r->r.id>q.afterId).limit(q.limit).toList();}
            public List<LogEntry> queryReverse(LogQuery q){return rows.stream().filter(r->r.id<q.beforeId)
                    .sorted(Comparator.<LogEntry>comparingLong(r->r.id).reversed()).limit(q.limit).toList();}
            public void shutdown(){}};
        return CartRollbackPlan.prepare(storage,CART,1);
    }
    private static CompoundTag worldManifest(UUID transfer,UUID entity,int count){
        var n=manifest(transfer,"item",0);n.put("Slots",new ListTag());var list=new ListTag();var endpoint=new CompoundTag();endpoint.putUUID("WorldEntity",entity);endpoint.putString("Dimension","minecraft:overworld");endpoint.putString("EntityType","minecraft:item");endpoint.putLong("Pos",10);
        if(count>0){var item=stack("item",count);endpoint.put("Item",item);var nbt=new CompoundTag();nbt.putUUID("UUID",entity);nbt.putString("id","minecraft:item");nbt.put("Item",item.copy());var pos=new ListTag();for(int i=0;i<3;i++)pos.add(DoubleTag.valueOf(i));nbt.put("Pos",pos);endpoint.put("EntityNBT",nbt);}
        list.add(endpoint);n.put("Entities",list);return n;
    }
    private static CompoundTag doubleManifest(UUID id,int slot,int count){
        var n=manifest(id,"item",count);var address=n.getList("Slots",10).getCompound(0);address.putInt("Slot",slot);address.putInt("Size",54);address.putInt("Limit",64);
        var other=new CompoundTag();other.putLong("Pos",11);other.putString("BlockEntity","minecraft:chest");other.put("Block",address.getCompound("Block").copy());address.put("DoubleChest",other);return n;
    }
    private static CartAutomationTransferPlan plan(CompoundTag b,CompoundTag a,CompoundTag cb,CompoundTag ca){var p=new CartAutomationTransferPlan();p.manifest(b,a);p.cargo(cb,ca);return p;}
    private static CompoundTag manifest(UUID id,String kind,int count){var n=new CompoundTag();n.putInt("Format",1);n.putString("Kind","automation");n.putUUID("Transfer",id);n.putUUID("Cart",CART);n.putInt("Changes",1);n.putBoolean("Complete",true);var slots=new ListTag();var s=new CompoundTag();s.putString("Kind",kind);s.putString("Dimension","minecraft:overworld");s.putLong("Pos",10);s.putString("Side","none");s.putInt("Slot",0);s.putInt("Size",1);s.putInt("Limit",10000);s.putString("Handler","native");s.putString("BlockEntity","minecraft:chest");var block=new CompoundTag();block.putString("Name","minecraft:chest");s.put("Block",block);if(count>0)s.put(field(kind),stack(kind,count));slots.add(s);n.put("Slots",slots);return n;}
    private static CompoundTag cargo(UUID id,String kind,int count){var n=new CompoundTag();n.putString("Kind",kind);n.putString("TransferKind","automation");n.putUUID("Transfer",id);n.putInt("TransferIndex",0);n.putBoolean("External",true);n.putLong("LocalPos",0);n.putInt(kind.equals("item")?"Slot":"Tank",0);if(count>0)n.put(field(kind),stack(kind,count));return n;}
    private static CompoundTag stack(String kind,int count){var n=new CompoundTag();n.putString("id",kind.equals("item")?"minecraft:diamond":"minecraft:water");n.putInt(kind.equals("item")?"count":"amount",count);return n;}
    private static int quantity(CompoundTag stack,String kind){return stack.isEmpty()?0:kind.equals("item")&&!stack.contains("count")?1:stack.getInt(kind.equals("item")?"count":"amount");}
    private static String field(String kind){return kind.equals("item")?"Item":"Fluid";}
    private static CompoundTag custom(String value){var n=new CompoundTag();n.putString("minecraft:custom_name",value);return n;}
    private static LogEntry row(long id,ActionType type){var r=new LogEntry();r.id=id;r.ts=1000+id;r.type=type;r.source=CartAuditContext.prefix(CART)+"work:1";return r;}
    private static void reject(Runnable action,String message){try{action.run();}catch(IllegalStateException|IllegalArgumentException expected){return;}throw new AssertionError(message);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
