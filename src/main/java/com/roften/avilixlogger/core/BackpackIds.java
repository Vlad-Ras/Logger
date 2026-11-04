package com.roften.avilixlogger.core;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.util.UUID;

/** Vanilla components only: safe to load even when Backpacked is absent. */
public final class BackpackIds {
    public static final String KEY = "AvilixBackpackAudit";
    private BackpackIds() {}
    public static CompoundTag metadata(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag().getCompound(KEY);
    }
    public static UUID id(ItemStack stack) { var n=metadata(stack); return n.hasUUID("Id")?n.getUUID("Id"):null; }
    public static String shortId(ItemStack stack) { return metadata(stack).getString("Short"); }
    public static void write(ItemStack stack, CompoundTag audit) {
        CustomData.update(DataComponents.CUSTOM_DATA,stack,n->n.put(KEY,audit.copy()));
    }
    public static String prefix(UUID id) { return "backpacked:bag:"+id+":"; }
    public static UUID fromSource(String source) {
        String prefix="backpacked:bag:";
        if(source==null||!source.startsWith(prefix)||source.length()<prefix.length()+37||source.charAt(prefix.length()+36)!=':')return null;
        try{return UUID.fromString(source.substring(prefix.length(),prefix.length()+36));}catch(IllegalArgumentException e){return null;}
    }
    public static java.util.EnumSet<ActionType> types() {
        return java.util.EnumSet.range(ActionType.BACKPACK_CREATE,ActionType.BACKPACK_RETURN);
    }
}
