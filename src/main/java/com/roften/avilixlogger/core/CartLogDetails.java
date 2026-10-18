package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.fluids.FluidStack;
import java.util.*;

/** Readable native cargo and external endpoint diffs; no dependency on Create classes. */
public final class CartLogDetails {
    private CartLogDetails(){}
    public static List<Component> describe(ServerLevel level,LogEntry row){
        if(CartAuditContext.id(row.source)==null)return List.of();
        var before=NbtSerde.fromSnbt(row.beBefore);var after=NbtSerde.fromSnbt(row.beAfter);if(before==null||after==null)return List.of();
        List<Component> lines=new ArrayList<>();String kind=after.getString("Kind");
        if(kind.equals("item")||kind.equals("fluid")){
            lines.add(Component.literal("Груз конструкции: "+(after.getBoolean("BaseCart")?"вагонетка":BlockPos.of(after.getLong("LocalPos")).toShortString())+", "+(kind.equals("item")?"слот "+after.getInt("Slot"):"бак "+after.getInt("Tank"))));
            delta(lines,level,kind,before,after);
            if(after.hasUUID("Transfer"))lines.add(Component.literal("Операция: "+after.getUUID("Transfer")));
        }else if(kind.equals("automation")){
            lines.add(Component.literal("Автоматика: "+after.getString("Machine")+" · "+after.getString("MachineDim")+" "+BlockPos.of(after.getLong("MachinePos")).toShortString()));
            lines.add(Component.literal("Операция: "+after.getUUID("Transfer")+" · "+(after.getBoolean("Complete")?"обе стороны захвачены":"неполный снимок — откат запрещён")));
            var b=before.getList("Slots",10);var a=after.getList("Slots",10);int count=Math.min(200,Math.min(b.size(),a.size()));
            for(int i=0;i<count;i++){var old=b.getCompound(i);var now=a.getCompound(i);String k=now.getString("Kind");
                lines.add(Component.literal("Хранилище: "+now.getString("Dimension")+" "+BlockPos.of(now.getLong("Pos")).toShortString()+" · "+(k.equals("item")?"слот ":"бак ")+now.getInt("Slot")));
                if(now.contains("DoubleChest",10))lines.add(Component.literal("Двойной сундук: вторая половина "+BlockPos.of(now.getCompound("DoubleChest").getLong("Pos")).toShortString()+" · физический слот "+(now.getInt("Slot")%27)+" по адресу "+BlockPos.of(now.getInt("Slot")<27?now.getLong("Pos"):now.getCompound("DoubleChest").getLong("Pos")).toShortString()));
                delta(lines,level,k,old,now);
            }
            if(a.size()>count)lines.add(Component.literal("… ещё "+(a.size()-count)+" изменений; полный снимок доступен в JSON"));
        }
        return List.copyOf(lines);
    }
    private static void delta(List<Component> lines,ServerLevel level,String kind,CompoundTag before,CompoundTag after){
        String field=kind.equals("item")?"Item":"Fluid";var b=before.getCompound(field);var a=after.getCompound(field);
        lines.add(Component.literal("До: ").append(resource(level,kind,b)).append(" → После: ").append(resource(level,kind,a)));
        if(!b.getCompound("components").equals(a.getCompound("components"))){String old=b.getCompound("components").toString(),now=a.getCompound("components").toString();lines.add(Component.literal("Компоненты: "+clip(old)+" → "+clip(now)));}
    }
    private static Component resource(ServerLevel level,String kind,CompoundTag tag){
        if(tag.isEmpty())return Component.literal("пусто");
        try{
            if(kind.equals("item")){var item=NbtSerde.readItemStack(tag,level.registryAccess());if(!item.isEmpty())return item.getHoverName().copy().append(" ×"+item.getCount());}
            else{var fluid=FluidStack.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),tag).result().orElse(FluidStack.EMPTY);if(!fluid.isEmpty())return fluid.getHoverName().copy().append(" · "+fluid.getAmount()+" мБ");}
        }catch(RuntimeException ignored){}
        return Component.literal(tag.getString("id")+" · "+(kind.equals("item")?(tag.contains("count")?tag.getInt("count"):1):tag.getInt("amount")));
    }
    private static String clip(String value){return value.length()>512?value.substring(0,512)+"…":value;}
}
