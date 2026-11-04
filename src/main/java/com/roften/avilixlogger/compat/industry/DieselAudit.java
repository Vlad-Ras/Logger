package com.roften.avilixlogger.compat.industry;

import com.mojang.serialization.Codec;
import com.roften.avilixlogger.compat.teams.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import java.util.*;

/** Raw SavedData override and native filter editor; never calls oil generation or opens a chunk. */
public final class DieselAudit {
    private DieselAudit() {}
    public static ModStateAudit.Action oil(Object data,ChunkPos chunk) {
        try{
            var level=(ServerLevel)StateAccess.field(data,"level");
            if(!level.getServer().isSameThread())return null;
            return ModStateAudit.begin(level.getServer(),"createdieselgenerators","oil-override",data,"oil:"+chunk.x+":"+chunk.z,
                    ActionType.MACHINE_STATE_CHANGE,()->{
                        var values=(Map<?,?>)StateAccess.field(data,"chunks");var tag=new CompoundTag();
                        tag.putString("Dimension",level.dimension().location().toString());tag.putInt("ChunkX",chunk.x);tag.putInt("ChunkZ",chunk.z);
                        tag.putBoolean("ExplicitOverride",values.containsKey(chunk));
                        if(values.containsKey(chunk))tag.putInt("RawAmountMB",(int)values.get(chunk));
                        tag.putBoolean("BaseGenerationEvaluatedByLogger",false);return tag;
                    });
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){IndustrialAudit.fail("oil");return null;}
    }
    public static boolean opened(ServerPlayer player,Object menu){
        if(!IndustrialAudit.supportsDiesel()||menu==null||!menu.getClass().getName().equals("com.jesz.createdieselgenerators.content.entity_filter.EntityFilterMenu"))return false;
        var first=new CompoundTag();first.putString("Dimension",player.level().dimension().location().toString());first.putInt("X",player.blockPosition().getX());first.putInt("Y",player.blockPosition().getY());first.putInt("Z",player.blockPosition().getZ());
        var after=first.copy();after.putString("Menu",menu.getClass().getName());
        if(com.roften.avilixlogger.LoggerConfig.VALUES.logGuiOpen.get())ModStateAudit.receipt(player.server,"createdieselgenerators","entity-filter-open",ActionType.GUI_OPEN,first,after);
        return true;
    }
    public static ModStateAudit.Action filterMenu(Object menu){
        try{Object player=StateAccess.field(menu,"player");return player instanceof ServerPlayer serverPlayer?filter(serverPlayer,menu):null;}
        catch(ReflectiveOperationException|RuntimeException|LinkageError failure){IndustrialAudit.fail("filter-menu");return null;}
    }
    public static ModStateAudit.Action filter(ServerPlayer player) { return player==null?null:filter(player,player.containerMenu); }
    private static ModStateAudit.Action filter(ServerPlayer player,Object menu) {
        if(!IndustrialAudit.supportsDiesel()||player==null||!player.server.isSameThread()||!(menu instanceof net.minecraft.world.inventory.AbstractContainerMenu nativeMenu)||!menu.getClass().getName().equals("com.jesz.createdieselgenerators.content.entity_filter.EntityFilterMenu"))return null;
        return ModStateAudit.begin(player.server,"createdieselgenerators","entity-filter-editor",menu,"filter",ActionType.GUI_CHANGE,()->{
            var tag=new CompoundTag();tag.putString("Dimension",player.level().dimension().location().toString());
            tag.putInt("X",player.blockPosition().getX());tag.putInt("Y",player.blockPosition().getY());tag.putInt("Z",player.blockPosition().getZ());
            tag.put("WhitelistMode",StateAccess.encode(StateAccess.field(menu,"whitelistMode")));
            var selected=(Collection<?>)StateAccess.field(menu,"selectedAttributes");
            if(selected.size()>4096)throw new IllegalStateException("Filter editor budget");
            var attributes=new ListTag();
            for(Object entry:selected)attributes.add(encode(player,entry,"com.jesz.createdieselgenerators.content.entity_filter.EntityAttribute$EntityAttributeEntry"));
            tag.put("SelectedAttributes",attributes);
            Object ghost=StateAccess.field(menu,"ghostInventory");
            tag.put("GhostSlots",(CompoundTag)StateAccess.call(ghost,"serializeNBT",new Class<?>[]{net.minecraft.core.HolderLookup.Provider.class},player.registryAccess()));
            var item=StateAccess.field(menu,"contentHolder");if(item instanceof net.minecraft.world.item.ItemStack stack)tag.put("FocusStack",stack.saveOptional(player.registryAccess()));
            tag.put("PlayerInventory",EntityContainerSerde.snapshot(player.getInventory(),player.registryAccess()));
            tag.put("Cursor",nativeMenu.getCarried().saveOptional(player.registryAccess()));return tag;
        });
    }
    @SuppressWarnings("unchecked")
    private static Tag encode(ServerPlayer player,Object value,String name)throws ReflectiveOperationException{
        var codec=(Codec<Object>)Class.forName(name).getField("CODEC").get(null);
        return codec.encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE),value).result().orElseThrow();
    }
}
