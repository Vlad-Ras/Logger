package com.roften.avilixlogger.compat.create;
import net.minecraft.nbt.*;
import java.util.*;

/** Entire affected native coupling graph; historical partial format 1 is read-only. */
public record CreateCouplingRollbackPlan(UUID operation,List<Cart> carts,Set<UUID> constructions,CompoundTag before,CompoundTag after) {
    public record Cart(CompoundTag before,CompoundTag after){}
    public static CreateCouplingRollbackPlan prepare(CompoundTag before,CompoundTag after){
        if(before==null||after==null||before.sizeInBytes()+after.sizeInBytes()>4*1024*1024)throw new IllegalStateException("Нет полного снимка сцепки");
        for(var tag:List.of(before,after))if(tag.getInt("Format")!=2||!tag.getString("Kind").equals("coupling")||!tag.getBoolean("Complete")||!tag.hasUUID("Operation")||!tag.contains("Carts",9)||tag.getList("Carts",10).isEmpty()||tag.getList("Carts",10).size()>256)throw new IllegalStateException("Сцепка не захвачена полностью");
        if(!before.getUUID("Operation").equals(after.getUUID("Operation"))||before.getList("Carts",10).size()!=after.getList("Carts",10).size())throw new IllegalStateException("Снимки сцепки не связаны");
        var carts=new ArrayList<Cart>();var roots=new HashSet<UUID>();
        for(int i=0;i<before.getList("Carts",10).size();i++){
            var b=before.getList("Carts",10).getCompound(i);var a=after.getList("Carts",10).getCompound(i);
            for(var tag:List.of(b,a))if(!tag.hasUUID("CartEntity")||tag.getString("Dimension").isBlank()||tag.getString("CartType").isBlank()||!tag.contains("Native",10)||tag.hasUUID("Construction")!=tag.hasUUID("ConstructionEntity"))throw new IllegalStateException("Нет адреса или NBT контроллера сцепки");
            for(var field:List.of("CartEntity","Dimension","CartType","Construction","ConstructionEntity"))if(!Objects.equals(b.get(field),a.get(field)))throw new IllegalStateException("Участник сцепки заменён или разобран");
            if(b.hasUUID("Construction"))roots.add(b.getUUID("Construction"));carts.add(new Cart(b.copy(),a.copy()));
        }
        for(boolean old:List.of(true,false)){
            var graph=new HashMap<UUID,CompoundTag>();
            for(var cart:carts){var tag=old?cart.before:cart.after;if(graph.put(tag.getUUID("CartEntity"),tag)!=null)throw new IllegalStateException("Повторный участник сцепки");}
            var forward=new HashMap<UUID,UUID>();
            for(var tag:graph.values())for(String edge:List.of("MainCoupling","ConnectedCoupling")){
                var nativeTag=tag.getCompound("Native");if(!nativeTag.contains(edge))continue;var link=nativeTag.getCompound(edge);
                if(!link.hasUUID("Main")||!link.hasUUID("Connected")||!link.contains("Length",5)||!link.contains("Contraption",1)||!Float.isFinite(link.getFloat("Length"))||link.getFloat("Length")<=0)throw new IllegalStateException("Повреждён endpoint сцепки");
                var main=link.getUUID("Main");var connected=link.getUUID("Connected");var own=tag.getUUID("CartEntity");
                if(main.equals(connected)||!own.equals(edge.equals("MainCoupling")?main:connected))throw new IllegalStateException("Неверная ориентация сцепки");
                var peer=graph.get(edge.equals("MainCoupling")?connected:main);
                if(peer==null||!peer.getString("Dimension").equals(tag.getString("Dimension"))||!peer.getCompound("Native").getCompound(edge.equals("MainCoupling")?"ConnectedCoupling":"MainCoupling").equals(link))throw new IllegalStateException("Обратная сторона сцепки отсутствует");
                if(edge.equals("MainCoupling"))forward.put(main,connected);
            }
            for(var id:graph.keySet()){var seen=new HashSet<UUID>();for(var current=id;current!=null;current=forward.get(current))if(!seen.add(current))throw new IllegalStateException("Цикл сцепленных вагонеток");}
        }
        if(roots.isEmpty())throw new IllegalStateException("У сцепки нет учтённой конструкции");
        return new CreateCouplingRollbackPlan(before.getUUID("Operation"),List.copyOf(carts),Set.copyOf(roots),before.copy(),after.copy());
    }
}
