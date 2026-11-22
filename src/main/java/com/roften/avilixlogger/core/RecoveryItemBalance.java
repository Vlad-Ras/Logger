package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Detached item accounting, not a codec for fluids, energy or opaque mod state.
 * A local history checkpoint does not prove that its missing items were lost in a crash.
 * Until external resource receipts are available, only a scene preserving its current
 * complete item multiset can change inventories. SlotSources identify physical inventories
 * so a double chest is never counted twice or written outside the backed-up scene.
 */
public final class RecoveryItemBalance {
    public record Input(String dimension, BlockPos pos, RollbackEngine.ReplayCheckpoint before,
                        RollbackEngine.ReplayCheckpoint after) {}
    public record Change(CompoundTag before,CompoundTag after) {}
    private record Address(String dimension, BlockPos pos) {}
    private record Stack(CompoundTag identity, long count) {}
    private record Inventory(int size, Map<Integer, Stack> items) {}
    private record Segment(Address address, int first, int size) {}
    private static final Map<String,Integer> VANILLA_SIZES=Map.ofEntries(
            Map.entry("minecraft:chest",27),Map.entry("minecraft:trapped_chest",27),
            Map.entry("minecraft:barrel",27),Map.entry("minecraft:shulker_box",27),
            Map.entry("minecraft:hopper",5),Map.entry("minecraft:dispenser",9),Map.entry("minecraft:dropper",9),
            Map.entry("minecraft:furnace",3),Map.entry("minecraft:blast_furnace",3),Map.entry("minecraft:smoker",3),
            Map.entry("minecraft:brewing_stand",5));
    private final Map<Address,Inventory> before=new HashMap<>(), after=new HashMap<>();
    private final Set<Address> participants=new HashSet<>();
    private final Map<CompoundTag,Long> balance=new HashMap<>();
    private final long limit;
    private final BooleanSupplier cancelled;
    private long bytes;

    private RecoveryItemBalance(List<Input> inputs,long limit,BooleanSupplier cancelled) {
        this.limit=limit;this.cancelled=cancelled;
        for(var input:inputs)participants.add(new Address(input.dimension(),input.pos()));
        charge(inputs.size()*80L);
    }
    public static void verify(List<Input> inputs,long limit,BooleanSupplier cancelled) {
        verify(inputs,List.of(),limit,cancelled);
    }
    public static void verify(List<Input> inputs,List<Change> provenExternalChanges,long limit,BooleanSupplier cancelled) {
        var check=new RecoveryItemBalance(inputs,limit,cancelled);
        for(var input:inputs) {
            check.checkCancelled();
            check.add(input,input.before(),check.before,-1);
            check.add(input,input.after(),check.after,1);
        }
        // These are complete, producer-owned transfer deltas, never extra world inventories.
        for(var change:provenExternalChanges) {
            check.external(change.before(),-1);check.external(change.after(),1);
        }
        for(var entry:check.balance.entrySet())if(entry.getValue()!=0)
            throw new IllegalStateException("replay_item_resource_graph_required: "+entry.getKey().getString("id")
                    +"; изменение количества в сцене="+entry.getValue()
                    +"; нужны связанные снимки внешних получателей/источников. Мир не изменён.");
    }
    private void external(CompoundTag slots,int sign) {
        if(!ContainerSlotSnapshot.isValid(slots)||slots.contains("SlotSources"))throw new IllegalStateException("replay_player_transfer_incomplete");
        // Reuse the same strict stack codec and memory ceiling with an isolated logical address.
        var input=new Input("avilixlogger:transfer",BlockPos.ZERO,null,null);
        var scratch=new HashMap<Address,Inventory>();
        add(input,new RollbackEngine.ReplayCheckpoint(new CompoundTag(),null,slots),scratch,sign);
    }
    private void add(Input input,RollbackEngine.ReplayCheckpoint checkpoint,Map<Address,Inventory> inventories,int sign) {
        CompoundTag slots=checkpoint.slots(), be=checkpoint.be();
        if(be!=null&&be.contains("LootTable"))
            throw problem("replay_unresolved_loot",input);
        if(slots==null&&be!=null) {
            var size=VANILLA_SIZES.get(be.getString("id"));
            if(size!=null) {
                slots=new CompoundTag();slots.putInt("Size",size);
                slots.put("Items",be.contains("Items")?be.get("Items"):new ListTag());
            } else if(be.contains("Items"))throw problem("replay_item_snapshot_required",input);
        }
        if(slots==null)return;
        if(!ContainerSlotSnapshot.isValid(slots))throw problem("invalid_container_snapshot",input);
        int size=slots.getInt("Size");
        var segments=segments(input,slots,size);
        var items=slots.getList("Items",Tag.TAG_COMPOUND);
        for(var segment:segments) {
            checkCancelled();
            var values=new HashMap<Integer,Stack>();
            for(var raw:items) {
                checkCancelled();
                var item=(CompoundTag)raw;int slot=ContainerSlotSnapshot.readSlot(item);
                if(slot<segment.first()||(long)slot-segment.first()>=segment.size())continue;
                String id=item.getString("id");
                boolean modern=item.contains("count",Tag.TAG_ANY_NUMERIC),legacy=item.contains("Count",Tag.TAG_ANY_NUMERIC);
                if(ResourceLocation.tryParse(id)==null||modern==legacy)throw problem("replay_invalid_resource_stack",input);
                byte countType=item.get(modern?"count":"Count").getId();
                if(countType<Tag.TAG_BYTE||countType>Tag.TAG_LONG)throw problem("replay_invalid_resource_stack",input);
                long count=item.getLong(modern?"count":"Count");
                if(count<=0||count>Integer.MAX_VALUE)throw problem("replay_invalid_resource_stack",input);
                charge(96L+item.sizeInBytes());
                var identity=item.copy();identity.remove("Slot");identity.remove("count");identity.remove("Count");
                values.put(slot-segment.first(),new Stack(identity,count));
            }
            var inventory=new Inventory(segment.size(),Map.copyOf(values));
            var previous=inventories.putIfAbsent(segment.address(),inventory);
            if(previous!=null) {
                if(!previous.equals(inventory))throw problem("replay_resource_alias_conflict",input);
                continue;
            }
            charge(192);
            for(var stack:values.values()) {
                long delta=Math.multiplyExact(stack.count(),sign);
                balance.merge(stack.identity(),delta,Math::addExact);
            }
        }
    }
    private List<Segment> segments(Input input,CompoundTag slots,int size) {
        if(!slots.contains("SlotSources"))return List.of(new Segment(new Address(input.dimension(),input.pos()),0,size));
        if(!(slots.get("SlotSources") instanceof ListTag sources)||sources.isEmpty()
                ||sources.getElementType()!=Tag.TAG_COMPOUND||sources.size()>64)
            throw problem("replay_resource_alias_conflict",input);
        var result=new ArrayList<Segment>();
        for(var raw:sources) {
            var source=(CompoundTag)raw;
            for(String key:List.of("FirstSlot","SlotCount","X","Y","Z"))
                if(!source.contains(key,Tag.TAG_INT))throw problem("replay_resource_alias_conflict",input);
            var address=new Address(source.getString("Dimension"),new BlockPos(source.getInt("X"),source.getInt("Y"),source.getInt("Z")));
            if(!participants.contains(address))throw problem("replay_resource_participant_outside_scene",input);
            result.add(new Segment(address,source.getInt("FirstSlot"),source.getInt("SlotCount")));
        }
        result.sort(Comparator.comparingInt(Segment::first));
        long next=0;
        for(var segment:result) {
            if(segment.first()!=next||segment.size()<=0)throw problem("replay_resource_alias_conflict",input);
            next+=segment.size();if(next>size)throw problem("replay_resource_alias_conflict",input);
        }
        if(next!=size)throw problem("replay_resource_alias_conflict",input);
        return result;
    }
    private void charge(long amount) {
        bytes=Math.addExact(bytes,amount);
        if(bytes>limit)throw new IllegalStateException("Проверка ресурсов превышает оставшийся лимит памяти; мир не изменён");
    }
    private void checkCancelled(){if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();}
    private static IllegalStateException problem(String reason,Input input) {
        return new IllegalStateException(reason+": "+input.dimension()+" "+input.pos()+"; мир не изменён");
    }
}
