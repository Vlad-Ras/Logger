package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.*;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import java.util.*;

/** Explicit optional contracts; unknown attachments are witnesses, never blindly deserialized. */
public final class CreateExternalPlayerState {
    private CreateExternalPlayerState() {}
    public static CompoundTag capture(Player player) {
        var out=new CompoundTag();out.putInt("Format",1);
        var raw=player.serializeAttachments(player.registryAccess());
        var unknown=raw==null?new CompoundTag():raw.copy();
        if(ModList.get().isLoaded("backpacked"))out.put("Backpacked",com.roften.avilixlogger.compat.backpacked.BackpackDeployerContract.capture(player,unknown));
        out.put("Unknown",unknown);return out;
    }
    public static void validate(CompoundTag tag) {
        if(tag.getInt("Format")!=1||!tag.contains("Unknown",10)||tag.sizeInBytes()>4L*1024*1024
                ||!Set.of("Format","Unknown","Backpacked").containsAll(tag.getAllKeys()))throw new IllegalStateException("Неполный контракт внешних данных игрока");
        if(tag.contains("Backpacked")){
            var bp=tag.getCompound("Backpacked");
            if(!bp.getString("Version").equals("3.0.5")||!bp.getString("Framework").equals("0.13.10")||bp.getInt("Format")!=1||!bp.contains("State",10)||bp.getAllKeys().size()!=6||!bp.contains("CellCount",3)||!bp.contains("TrackerCount",3)||bp.getInt("CellCount")!=bp.getCompound("State").size())
                throw new IllegalStateException("Неизвестный контракт внешних данных Backpacked");
            var state=bp.getCompound("State");
            if(!state.contains("BagSize",3)||state.getInt("BagSize")<0||state.getInt("BagSize")>64||!state.contains("Backpack",10))throw new IllegalStateException("Потеряны слоты Backpacked");
            for(int slot=0;slot<state.getInt("BagSize");slot++)if(!state.contains("bag:"+slot,10))throw new IllegalStateException("Потерян рюкзак");
            for(String key:List.of("selected_backpack","immortal_cooldown","backpack_scale","cosmetic_backpack","unlockable_backpack_slots"))if(!state.contains("backpacked:"+key))throw new IllegalStateException("Потерян ключ Backpacked");
            int trackers=0;for(String key:state.getAllKeys())if(key.startsWith("tracker:")){trackers++;if(!state.contains(key,10)||!state.contains("type:"+key.substring(8),8))throw new IllegalStateException("Потерян тип tracker");}
            if(trackers!=bp.getInt("TrackerCount")||state.getAllKeys().stream().filter(k->k.startsWith("type:")).count()!=trackers)throw new IllegalStateException("Потерян tracker Backpacked");
        }
    }
    public static void validatePair(CompoundTag before,CompoundTag after) {
        validate(before);validate(after);
        if(!before.getCompound("Unknown").equals(after.getCompound("Unknown"))||before.contains("Backpacked")!=after.contains("Backpacked"))
            throw new IllegalStateException("Изменены внешние данные без контракта");
    }
    /** Changed cells are atomic; unrelated progress, backpack slots and other keys survive. */
    public static CompoundTag merge(CompoundTag live,CompoundTag from,CompoundTag to) {
        var out=live.copy();var keys=new HashSet<>(from.getAllKeys());keys.addAll(to.getAllKeys());
        for(String key:keys){var f=from.get(key);var t=to.get(key);if(Objects.equals(f,t))continue;
            if(!Objects.equals(live.get(key),f))throw new IllegalStateException("Изменены внешние данные игрока: "+key);
            if(t==null)out.remove(key);else out.put(key,t.copy());
        }return out;
    }
    public static final class Session {
        final Player player;final CompoundTag before,after;
        public Session(Player player,CompoundTag before,CompoundTag after) {
            this.player=player;this.before=before;this.after=after;validatePair(before,after);
            if(before.contains("Backpacked")){
                if(!ModList.get().isLoaded("backpacked"))throw new IllegalStateException("Backpacked удалён после действия");
                com.roften.avilixlogger.compat.backpacked.BackpackDeployerContract.preflight(player,before.getCompound("Backpacked"),after.getCompound("Backpacked"));
            }
        }
        public void verify(boolean original) {
            if(before.contains("Backpacked"))com.roften.avilixlogger.compat.backpacked.BackpackDeployerContract.check(player,(original?before:after).getCompound("Backpacked"),(original?after:before).getCompound("Backpacked"),false);
        }
        public void set(boolean original) {
            if(before.contains("Backpacked"))com.roften.avilixlogger.compat.backpacked.BackpackDeployerContract.check(player,(original?after:before).getCompound("Backpacked"),(original?before:after).getCompound("Backpacked"),true);
        }
    }
}
