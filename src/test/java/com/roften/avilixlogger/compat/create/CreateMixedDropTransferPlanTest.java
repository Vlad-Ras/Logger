package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.CartAuditContext;
import net.minecraft.nbt.*;
import java.util.*;

/** Real reverse ordering: pickup, funnel suction, then merge of both UUIDs. */
public final class CreateMixedDropTransferPlanTest {
    private static final UUID CART=UUID.fromString("11000000-0000-0000-0000-000000000001");
    private static final UUID FIRST=UUID.fromString("11000000-0000-0000-0000-000000000002");
    private static final UUID SECOND=UUID.fromString("11000000-0000-0000-0000-000000000003");
    private static final UUID PLAYER=UUID.fromString("11000000-0000-0000-0000-000000000004");
    private CreateMixedDropTransferPlanTest(){}
    public static void run(){
        var world=new CreateWorldItemTransferPlan();var slots=new CartMenuTransferPlan();
        var plan=new CreateMixedDropTransferPlan(CART,world,slots);
        // Actual order: two spawned stacks (2+3), merge (5), suction (1), pickup (4).
        var pickupBefore=pickup(4);var pickupAfter=pickup(0);pickupAfter.put("Item",new CompoundTag());pickupAfter.remove("EntityNBT");
        pickupBefore.put("Slots",slots(0));pickupAfter.put("Slots",slots(4));
        plan.add(pickupBefore,pickupAfter,"minecraft:overworld",10);
        // An automation manifest validated between pickup and merge contributes the 5 -> 4 endpoint.
        world.add(entity(FIRST,5,false),entity(FIRST,4,false));
        var mergedBefore=merge(false);var mergedAfter=merge(true);
        plan.add(mergedBefore,mergedAfter,"minecraft:overworld",10);
        var undo=world.finish();
        check(undo.size()==2&&undo.stream().anyMatch(e->e.entity().equals(FIRST)&&e.before().getCompound("Item").getInt("count")==2&&e.after().getCompound("EntityNBT").isEmpty()),"merge/suction/pickup order lost");
        var received=slots.finish();check(received.size()==1&&received.getFirst().after().getInt("count")==4,"recipient pickup missing");
        var foreign=mergedBefore.copy();foreign.getList("Entities",10).getCompound(1).getList("Origins",8).set(0,StringTag.valueOf(CartAuditContext.prefix(UUID.randomUUID())+"actor_result:0"));
        reject(()->new CreateMixedDropTransferPlan(CART,new CreateWorldItemTransferPlan(),new CartMenuTransferPlan()).add(foreign,mergedAfter,"minecraft:overworld",10),"foreign drop accepted");
        var corrupt=mergedAfter.copy();corrupt.getList("Entities",10).getCompound(0).getCompound("Item").putInt("count",6);
        reject(()->new CreateMixedDropTransferPlan(CART,new CreateWorldItemTransferPlan(),new CartMenuTransferPlan()).add(mergedBefore,corrupt,"minecraft:overworld",10),"non-conserving merge accepted");
        System.out.println("CREATE_MIXED_DROP_PLAN_OK: merge, suction, pickup, both UUIDs, recipient and foreign/corrupt rejection");
    }
    private static CompoundTag pickup(int count){
        var tag=new CompoundTag();tag.putInt("Format",1);tag.putString("Kind","actor_result");tag.putString("Operation","mixed_world_item_pickup");tag.putBoolean("Complete",true);
        tag.putUUID("Drop",FIRST);tag.putUUID("Player",PLAYER);tag.putUUID("Pickup",UUID.fromString("11000000-0000-0000-0000-000000000005"));tag.putInt("InventorySize",41);
        if(count>0){tag.put("Item",item(count));tag.put("EntityNBT",nbt(FIRST,count));}return tag;
    }
    private static ListTag slots(int count){var list=new ListTag();var entry=new CompoundTag();entry.putInt("Slot",0);if(count>0)entry.put("Item",item(count));list.add(entry);return list;}
    private static CompoundTag merge(boolean after){
        var tag=new CompoundTag();tag.putInt("Format",1);tag.putString("Kind","actor_result");tag.putString("Operation","world_item_merge");tag.putBoolean("Complete",true);
        tag.putUUID("Merge",UUID.fromString("11000000-0000-0000-0000-000000000006"));tag.putBoolean("Conserved",true);
        var list=new ListTag();list.add(entity(FIRST,after?5:2,true));list.add(entity(SECOND,after?0:3,true));tag.put("Entities",list);return tag;
    }
    private static CompoundTag entity(UUID id,int count,boolean origins){
        var endpoint=new CompoundTag();endpoint.putUUID("WorldEntity",id);endpoint.putString("Dimension","minecraft:overworld");endpoint.putString("EntityType","minecraft:item");endpoint.putLong("Pos",10);
        if(count>0){endpoint.put("Item",item(count));endpoint.put("EntityNBT",nbt(id,count));}
        if(origins){var values=new ListTag();values.add(StringTag.valueOf(CartAuditContext.prefix(CART)+"actor_result:0"));endpoint.put("Origins",values);}
        return endpoint;
    }
    private static CompoundTag item(int count){var tag=new CompoundTag();tag.putString("id","minecraft:diamond");tag.putInt("count",count);return tag;}
    private static CompoundTag nbt(UUID id,int count){var tag=new CompoundTag();tag.putUUID("UUID",id);tag.putString("id","minecraft:item");tag.put("Item",item(count));var pos=new ListTag();for(int i=0;i<3;i++)pos.add(DoubleTag.valueOf(i));tag.put("Pos",pos);return tag;}
    private static void reject(Runnable action,String message){try{action.run();}catch(IllegalStateException expected){return;}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
