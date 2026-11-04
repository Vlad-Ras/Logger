package com.roften.avilixlogger.core;

import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import java.util.ArrayList;
import java.util.List;

public final class SignLogDetails {
    private SignLogDetails(){}
    public static List<Component> describe(ServerLevel level,LogEntry row){
        if(row.type!=ActionType.BLOCK_ENTITY_NBT_CHANGE)return List.of();
        var before=NbtSerde.fromSnbt(row.beBefore);var after=NbtSerde.fromSnbt(row.beAfter);
        if(before==null||after==null||!before.getCompound("front_text").contains("messages",9)||!after.getCompound("front_text").contains("messages",9))return List.of();
        var result=new ArrayList<Component>();
        for(String side:List.of("front_text","back_text")){
            result.add(Component.literal(side.equals("front_text")?"Табличка: лицевая сторона":"Табличка: обратная сторона"));
            messages(result,level,"До",before.getCompound(side),"messages");messages(result,level,"После",after.getCompound(side),"messages");
            if(!java.util.Objects.equals(before.getCompound(side).get("filtered_messages"),after.getCompound(side).get("filtered_messages"))){
                messages(result,level,"До (фильтр)",before.getCompound(side),"filtered_messages");messages(result,level,"После (фильтр)",after.getCompound(side),"filtered_messages");
            }
        }
        return List.copyOf(result);
    }
    private static void messages(List<Component> result,ServerLevel level,String label,CompoundTag side,String key){
        var messages=side.getList(key,8);
        for(int i=0;i<Math.min(4,messages.size());i++){
            String raw=messages.getString(i),value=raw;
            try{var text=Component.Serializer.fromJson(raw,level.registryAccess());if(text!=null)value=text.getString();}catch(RuntimeException ignored){}
            result.add(Component.literal(label+" ["+(i+1)+"]: "+value));
        }
    }
}
