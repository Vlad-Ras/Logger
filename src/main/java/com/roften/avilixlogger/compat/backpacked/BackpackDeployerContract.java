package com.roften.avilixlogger.compat.backpacked;

import com.mrcrayfish.backpacked.core.ModSyncedDataKeys;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.mrcrayfish.framework.api.sync.SyncedDataKey;
import com.roften.avilixlogger.core.NbtSerde;
import com.roften.avilixlogger.compat.create.CreateExternalPlayerState;
import java.util.*;

/** Materialize known lazy defaults before taking a snapshot, never during an undo.
 * A new empty Framework holder is not a gameplay change. Audited Framework data is restored as sparse deltas; unknown attachments
 * still invalidate the joint operation.
 * Loaded only behind the optional Backpacked guard.
 */
public final class BackpackDeployerContract {
    private BackpackDeployerContract() {}
    public static void prepare(Player player){
        var mods=ModList.get();
        if(!mods.getModContainerById("backpacked").orElseThrow().getModInfo().getVersion().toString().equals("3.0.5")
                ||!mods.getModContainerById("framework").orElseThrow().getModInfo().getVersion().toString().equals("0.13.10"))
            throw new IllegalStateException("Неизвестный контракт lazy defaults Backpacked/Framework");
        ModSyncedDataKeys.UNLOCK_TRACKER.getValue(player);
        ModSyncedDataKeys.BACKPACK.getValue(player);
        var backpacks=ModSyncedDataKeys.BACKPACKS.getValue(player);
        int capacity=com.mrcrayfish.backpacked.inventory.ManagementInventory.getMaxEquipable();
        // The supplier starts with an empty list; the native getter expands it to
        // the configured empty slots. Never invoke its resizing/drop path on items.
        if(backpacks.size()!=capacity&&backpacks.stream().anyMatch(stack->!stack.isEmpty()))
            throw new IllegalStateException("Размер существующего инвентаря Backpacked требует миграции до снимка");
        com.mrcrayfish.backpacked.BackpackHelper.getBackpacks(player);
        var slots=ModSyncedDataKeys.UNLOCKABLE_BACKPACK_SLOTS.getValue(player);
        var defaults=ModSyncedDataKeys.UNLOCKABLE_BACKPACK_SLOTS.defaultValueSupplier().get();
        boolean initial=slots.getMaxSlots()==defaults.getMaxSlots()&&slots.getUnlockCount()==defaults.getUnlockCount();
        if(initial)for(int slot=0;slot<slots.getMaxSlots();slot++)if(slots.isUnlocked(slot)!=defaults.isUnlocked(slot)){initial=false;break;}
        if(!initial&&slots.getMaxSlots()!=capacity)
            throw new IllegalStateException("Существующие слоты Backpacked требуют миграции до снимка");
        com.mrcrayfish.backpacked.BackpackHelper.getBackpackUnlockableSlots(player);
        ModSyncedDataKeys.COSMETIC_PROPERTIES.getValue(player);
        ModSyncedDataKeys.SELECTED_BACKPACK.getValue(player);
        ModSyncedDataKeys.IMMORTAL_COOLDOWN.getValue(player);
        ModSyncedDataKeys.BACKPACK_SCALE.getValue(player);
    }

    private static List<SyncedDataKey<Player,?>> keys(){return List.of(ModSyncedDataKeys.UNLOCK_TRACKER,ModSyncedDataKeys.BACKPACK,ModSyncedDataKeys.BACKPACKS,ModSyncedDataKeys.UNLOCKABLE_BACKPACK_SLOTS,ModSyncedDataKeys.COSMETIC_PROPERTIES,ModSyncedDataKeys.SELECTED_BACKPACK,ModSyncedDataKeys.IMMORTAL_COOLDOWN,ModSyncedDataKeys.BACKPACK_SCALE);}
    private static final Set<String> TRACKERS=Set.of("CountProgressTracker","CraftingProgressTracker","UniqueCraftingProgressTracker");
    private static final Set<String> CHALLENGES=Set.of("BreedAnimalChallenge","CraftItemChallenge","ExploreBiomeChallenge","FeedAnimalChallenge","InteractWithBlockChallenge","InteractWithEntityChallenge","KillMobChallenge","MerchantTradeChallenge","MineBlockChallenge","TravelDistanceChallenge");
    private static boolean supportedTracker(String type){return type.startsWith("com.mrcrayfish.backpacked.common.tracker.impl.")&&TRACKERS.contains(type.substring(type.lastIndexOf('.')+1))||CHALLENGES.stream().anyMatch(name->type.equals("com.mrcrayfish.backpacked.common.challenge.impl."+name+"$Tracker"));}
    private static <T> Tag write(Player player,SyncedDataKey<Player,T> key,T value){return key.serializer().getTagWriter().apply(value,player.registryAccess()).copy();}
    private static <T> T read(Player player,SyncedDataKey<Player,T> key,Tag value){return key.serializer().getTagReader().apply(value.copy(),player.registryAccess());}
    private static CompoundTag item(Player player,ItemStack stack){return Objects.requireNonNullElseGet(NbtSerde.snapshotItemStack(stack,player.registryAccess()),CompoundTag::new);}
    private static ItemStack item(Player player,CompoundTag tag){
        if(tag.isEmpty())return ItemStack.EMPTY;
        var stack=NbtSerde.readItemStack(tag,player.registryAccess());
        if(stack.isEmpty()||!item(player,stack).equals(tag))throw new IllegalStateException("Предмет Backpacked не прочитан полностью");return stack;
    }
    private static CompoundTag state(Player player,Map<SyncedDataKey<Player,?>,Object> values){
        var out=new CompoundTag();
        for(var key:keys()){
            Object value=values.get(key);
            if(key==ModSyncedDataKeys.UNLOCK_TRACKER){
                var tracker=(com.mrcrayfish.backpacked.data.unlock.UnlockTracker)value;
                for(var id:tracker.getUnlockedBackpacks())out.putBoolean("unlock:"+id,true);
                tracker.getProgressTrackerMap().forEach((id,progress)->{
                    String type=progress.getClass().getName();
                    if(!supportedTracker(type))throw new IllegalStateException("Неизвестный tracker Backpacked: "+type);
                    var data=new CompoundTag();progress.write(data);out.put("tracker:"+id,data);out.putString("type:"+id,type);
                });
            }else if(key==ModSyncedDataKeys.BACKPACK)out.put("Backpack",item(player,(ItemStack)value));
            else if(key==ModSyncedDataKeys.BACKPACKS){
                var bags=(List<?>)value;if(bags.size()>64)throw new IllegalStateException("Слишком много рюкзаков");out.putInt("BagSize",bags.size());
                for(int i=0;i<bags.size();i++)out.put("bag:"+i,item(player,(ItemStack)bags.get(i)));
            }else out.put(key.id().toString(),writeUnchecked(player,key,value));
        }return out;
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static Tag writeUnchecked(Player player,SyncedDataKey key,Object value){return write(player,key,value);}
    private static Map<SyncedDataKey<Player,?>,Object> values(Player player){var values=new LinkedHashMap<SyncedDataKey<Player,?>,Object>();for(var key:keys())values.put(key,key.getValue(player));return values;}
    public static CompoundTag capture(Player player,CompoundTag unknown){
        prepare(player);var out=new CompoundTag();out.putInt("Format",1);out.putString("Version","3.0.5");out.putString("Framework","0.13.10");out.put("State",state(player,values(player)));out.putInt("CellCount",out.getCompound("State").size());out.putInt("TrackerCount",(int)out.getCompound("State").getAllKeys().stream().filter(k->k.startsWith("tracker:")).count());
        // Strip only the audited Framework rows, preserving all other attachments/keys.
        var rows=unknown.getList("framework:data_holder",10);
        rows.removeIf(raw->{var row=(CompoundTag)raw;return keys().stream().anyMatch(key->key.id().toString().equals(row.getString("DataKey"))&&key.classKey().id().toString().equals(row.getString("ClassKey")));});
        if(rows.isEmpty())unknown.remove("framework:data_holder");
        return out;
    }
    private static Map<SyncedDataKey<Player,?>,Object> decode(Player player,CompoundTag state){
        var out=new LinkedHashMap<SyncedDataKey<Player,?>,Object>();var progress=new CompoundTag();var unlocked=new ListTag();var trackers=new ListTag();
        for(String field:new TreeSet<>(state.getAllKeys())){
            if(field.startsWith("unlock:")){
                String id=field.substring(7);if(ResourceLocation.tryParse(id)==null||!state.contains(field,1)||!state.getBoolean(field)||com.mrcrayfish.backpacked.common.backpack.BackpackManager.instance().getBackpack(ResourceLocation.parse(id))==null)throw new IllegalStateException("Неизвестный unlock Backpacked");unlocked.add(StringTag.valueOf(id));
            }
            if(field.startsWith("tracker:")){var row=new CompoundTag();row.putString("Id",field.substring(8));row.put("Data",state.getCompound(field).copy());trackers.add(row);}
        }
        progress.put("UnlockedBackpacks",unlocked);progress.put("ProgressTrackers",trackers);
        out.put(ModSyncedDataKeys.UNLOCK_TRACKER,read(player,ModSyncedDataKeys.UNLOCK_TRACKER,progress));
        out.put(ModSyncedDataKeys.BACKPACK,item(player,state.getCompound("Backpack")));
        if(!state.contains("BagSize",3)||state.getInt("BagSize")<0||state.getInt("BagSize")>64)throw new IllegalStateException("Неполный размер Backpacked");
        var bags=net.minecraft.core.NonNullList.withSize(state.getInt("BagSize"),ItemStack.EMPTY);
        for(int i=0;i<bags.size();i++){if(!state.contains("bag:"+i,10))throw new IllegalStateException("Потерян слот Backpacked");var stack=item(player,state.getCompound("bag:"+i));if(!stack.isEmpty()&&stack.getItem().getClass()!=com.mrcrayfish.backpacked.item.BackpackItem.class)throw new IllegalStateException("Неподдержанный предмет в экипировке Backpacked");bags.set(i,stack);}out.put(ModSyncedDataKeys.BACKPACKS,bags);
        for(var key:keys())if(!out.containsKey(key)){var value=state.get(key.id().toString());if(value==null)throw new IllegalStateException("Потерян ключ Backpacked");out.put(key,read(player,key,value));}
        if(!state(player,out).equals(state))throw new IllegalStateException("Данные/определения Backpacked не прочитаны полностью");return out;
    }
    public static void preflight(Player player,CompoundTag before,CompoundTag after){
        prepare(player);if(before.getCompound("State").equals(after.getCompound("State")))return;decode(player,before.getCompound("State"));decode(player,after.getCompound("State"));
        for(var key:keys())if(!((Object)key instanceof BackpackDeployerGuard))throw new IllegalStateException("Нет защиты записей Framework");
        var tracker=ModSyncedDataKeys.UNLOCK_TRACKER.getValue(player);
        if(!(tracker instanceof BackpackDeployerGuard))throw new IllegalStateException("Нет защиты прогресса Backpacked");
        com.roften.avilixlogger.core.CartRestoreLocks.handler(tracker);
        // Schema/configuration witnesses may never change inside a reversible action.
        var b=before.getCompound("State");var a=after.getCompound("State");
        if(player.containerMenu!=player.inventoryMenu&&(!stateForKey(b,ModSyncedDataKeys.BACKPACKS).equals(stateForKey(a,ModSyncedDataKeys.BACKPACKS))||!stateForKey(b,ModSyncedDataKeys.BACKPACK).equals(stateForKey(a,ModSyncedDataKeys.BACKPACK))))throw new IllegalStateException("Закройте меню перед возвратом экипировки Backpacked");
        if(b.getInt("BagSize")!=a.getInt("BagSize"))throw new IllegalStateException("Изменена конфигурация слотов Backpacked");
        for(String key:b.getAllKeys())if(key.startsWith("type:")&&!Objects.equals(b.get(key),a.get(key)))throw new IllegalStateException("Изменён tracker Backpacked");
    }
    public static void check(Player player,CompoundTag from,CompoundTag to,boolean apply){
        var live=state(player,values(player));var merged=CreateExternalPlayerState.merge(live,from.getCompound("State"),to.getCompound("State"));
        if(!apply||live.equals(merged))return;var decoded=decode(player,merged);
        boolean bagsChanged=!stateForKey(live,ModSyncedDataKeys.BACKPACKS).equals(stateForKey(merged,ModSyncedDataKeys.BACKPACKS));
        for(var key:keys())if(!stateForKey(live,key).equals(stateForKey(merged,key)))set(player,key,decoded.get(key));
        if(bagsChanged){var access=(com.mrcrayfish.backpacked.inventory.BackpackedInventoryAccess)player;for(int slot=0;slot<merged.getInt("BagSize");slot++){var inventory=access.backpacked$GetBackpackInventory(slot);if(inventory!=null)BackpackAudit.rebase(inventory);}}
    }
    private static CompoundTag stateForKey(CompoundTag state,SyncedDataKey<Player,?> key){
        var out=new CompoundTag();for(String name:state.getAllKeys())if(key==ModSyncedDataKeys.UNLOCK_TRACKER?name.startsWith("unlock:")||name.startsWith("tracker:")||name.startsWith("type:"):key==ModSyncedDataKeys.BACKPACKS?name.equals("BagSize")||name.startsWith("bag:"):name.equals(key==ModSyncedDataKeys.BACKPACK?"Backpack":key.id().toString()))out.put(name,state.get(name).copy());return out;
    }
    @SuppressWarnings({"unchecked","rawtypes"}) private static void set(Player player,SyncedDataKey key,Object value){key.setValue(player,value);if(key==ModSyncedDataKeys.UNLOCK_TRACKER)com.roften.avilixlogger.core.CartRestoreLocks.handler(value);}
}
