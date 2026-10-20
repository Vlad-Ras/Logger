package com.roften.avilixlogger.compat.backpacked;

import com.roften.avilixlogger.core.BackpackIds;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Server-thread identity/location index; history remains in the asynchronous audit backend. */
public final class BackpackIndex extends SavedData {
    public static final String FILE="avilixlogger_backpacks";
    private static volatile MinecraftServer server;
    private static volatile BackpackIndex instance;
    private final Map<UUID,Record> records=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,UUID> shortIds=new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String,Set<UUID>> owners=new HashMap<>();
    private long next=1;
    public static final class Record {
        public UUID id, owner, holder;
        public String shortId="", ownerName="", holderName="", dim="", state="unknown", kind="", reason="";
        public int x,y,z,slot=-1;
        public UUID entity;
        public long updated, lossId, lastEventId;
        public CompoundTag snapshot;
        transient ItemStack lastStack;
        public boolean lost(){return state.equals("destroyed");}
    }
    public static void start(MinecraftServer value) {
        server=value;instance=value.overworld().getDataStorage().computeIfAbsent(new Factory<>(BackpackIndex::new,(n,r)->load(n)),FILE);
    }
    public static void stop(){server=null;instance=null;}
    public static BackpackIndex get(MinecraftServer value){
        // AuthCore can accept a corrected key after server startup without restarting Minecraft.
        if(value!=null&&(server!=value||instance==null)&&value.isSameThread()&&com.roften.avilixlogger.LoggerConfig.isEnabled())start(value);
        return server==value?instance:null;
    }
    public Record record(UUID id){return records.get(id);}
    public Collection<Record> owned(String player) {
        Set<UUID> ids=owners.getOrDefault(player.toLowerCase(Locale.ROOT),Set.of());
        return ids.stream().map(records::get).sorted(Comparator.comparingLong((Record r)->r.updated).reversed()).toList();
    }
    public UUID resolve(String value) {
        if(value==null)return null;
        try{UUID id=UUID.fromString(value);return records.containsKey(id)?id:null;}catch(IllegalArgumentException ignored){}
        return shortIds.get(value.toUpperCase(Locale.ROOT));
    }
    public Record identify(ItemStack stack,UUID owner,String name) {
        CompoundTag audit=BackpackIds.metadata(stack);UUID id=audit.hasUUID("Id")?audit.getUUID("Id"):UUID.randomUUID();
        Record r=records.get(id);
        if(r==null){
            r=new Record();r.id=id;
            String token=id.toString().replace("-", "").toUpperCase(Locale.ROOT);
            String preferred=audit.getString("Short");
            if(preferred.matches("BP-[0-9A-Z]{1,32}")&&!shortIds.containsKey(preferred))r.shortId=preferred;
            else { int length=10; r.shortId="BP-"+token.substring(0,length);
                while(shortIds.containsKey(r.shortId)){r.shortId="BP-"+token.substring(0,++length);} }
            next++;
            r.owner=audit.hasUUID("Owner")?audit.getUUID("Owner"):owner;
            r.ownerName=audit.contains("OwnerName")?audit.getString("OwnerName"):Objects.toString(name,"");
            records.put(id,r);shortIds.put(r.shortId,id);
            if(!r.ownerName.isBlank())owners.computeIfAbsent(r.ownerName.toLowerCase(Locale.ROOT),v->new HashSet<>()).add(id);
            setDirty();
        } else if(r.owner==null&&owner!=null) {
            r.owner=owner;r.ownerName=Objects.toString(name,"");
            if(!r.ownerName.isBlank())owners.computeIfAbsent(r.ownerName.toLowerCase(Locale.ROOT),v->new HashSet<>()).add(id);
            setDirty();
        }
        if(!id.equals(BackpackIds.id(stack))||!r.shortId.equals(audit.getString("Short"))||(!audit.hasUUID("Owner")&&r.owner!=null)){
            audit.putUUID("Id",id);audit.putString("Short",r.shortId);
            if(r.owner!=null)audit.putUUID("Owner",r.owner);audit.putString("OwnerName",r.ownerName);
            BackpackIds.write(stack,audit);
        }
        return r;
    }
    public boolean snapshot(Record r,ItemStack value,HolderLookup.Provider registries) {
        if(value.isEmpty())return false;
        if(r.lastStack!=null&&ItemStack.matches(r.lastStack,value))return false;
        r.snapshot=(CompoundTag)value.save(registries);r.lastStack=value.copy();setDirty();return true;
    }
    public void touched(Record r){r.updated=System.currentTimeMillis();setDirty();}
    @Override public CompoundTag save(CompoundTag n,HolderLookup.Provider registries) {
        n.putLong("Next",next);ListTag list=new ListTag();
        for(Record r:records.values()){
            CompoundTag t=new CompoundTag();t.putUUID("Id",r.id);t.putString("Short",r.shortId);
            if(r.owner!=null)t.putUUID("Owner",r.owner);t.putString("OwnerName",r.ownerName);
            if(r.holder!=null)t.putUUID("Holder",r.holder);t.putString("HolderName",r.holderName);
            t.putString("Dim",r.dim);t.putString("State",r.state);t.putString("Kind",r.kind);t.putString("Reason",r.reason);
            t.putInt("X",r.x);t.putInt("Y",r.y);t.putInt("Z",r.z);t.putInt("Slot",r.slot);
            if(r.entity!=null)t.putUUID("Entity",r.entity);t.putLong("Updated",r.updated);t.putLong("Loss",r.lossId);t.putLong("LastEvent",r.lastEventId);
            if(r.snapshot!=null)t.put("Snapshot",r.snapshot.copy());list.add(t);
        }
        n.put("Backpacks",list);return n;
    }
    public static BackpackIndex load(CompoundTag n){
        BackpackIndex index=new BackpackIndex();index.next=Math.max(1,n.getLong("Next"));
        for(Tag item:n.getList("Backpacks",Tag.TAG_COMPOUND)){
            CompoundTag t=(CompoundTag)item;if(!t.hasUUID("Id"))continue;
            Record r=new Record();r.id=t.getUUID("Id");r.shortId=t.getString("Short");r.owner=t.hasUUID("Owner")?t.getUUID("Owner"):null;r.ownerName=t.getString("OwnerName");
            r.holder=t.hasUUID("Holder")?t.getUUID("Holder"):null;r.holderName=t.getString("HolderName");r.dim=t.getString("Dim");
            r.state=t.getString("State");r.kind=t.getString("Kind");r.reason=t.getString("Reason");r.x=t.getInt("X");r.y=t.getInt("Y");r.z=t.getInt("Z");r.slot=t.getInt("Slot");
            r.entity=t.hasUUID("Entity")?t.getUUID("Entity"):null;r.updated=t.getLong("Updated");r.lossId=t.getLong("Loss");r.lastEventId=t.getLong("LastEvent");
            if(t.contains("Snapshot",Tag.TAG_COMPOUND))r.snapshot=t.getCompound("Snapshot").copy();
            if(index.shortIds.containsKey(r.shortId))throw new IllegalArgumentException("Duplicate backpack short ID "+r.shortId);
            index.records.put(r.id,r);index.shortIds.put(r.shortId,r.id);
            if(!r.ownerName.isBlank())index.owners.computeIfAbsent(r.ownerName.toLowerCase(Locale.ROOT),v->new HashSet<>()).add(r.id);
        }
        return index;
    }
}
