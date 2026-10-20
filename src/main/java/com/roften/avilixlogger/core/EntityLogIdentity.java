package com.roften.avilixlogger.core;

import com.google.gson.*;
import net.minecraft.network.chat.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import java.util.UUID;

/** Capture strings on the server; history never guesses a victim from the actor or an online player. */
public final class EntityLogIdentity {
    private static final String KEY="AvilixEntityTarget";
    private EntityLogIdentity() {}
    public static String name(Entity entity){
        try{return clean(entity instanceof Player player?player.getGameProfile().getName():entity.getDisplayName().getString());}
        catch(RuntimeException ignored){return null;}
    }
    public static void capture(LogEntry row,Entity entity){
        row.entityType=EntityType.getKey(entity.getType()).toString();row.entityUuid=entity.getUUID();row.entityName=clean(name(entity));
    }
    private static String clean(String value){
        if(value==null)return null;var text=value.replace('\n',' ').replace('\r',' ').trim();return text.isEmpty()?null:text.substring(0,Math.min(256,text.length()));
    }
    /** Existing extra columns carry the identity in split/feed schemas, including installations without DDL rights. */
    static String storageExtra(LogEntry row,String text){
        if(row.entityName==null||row.entityName.isBlank())return text;
        var meta=new JsonObject();meta.addProperty("version",1);meta.addProperty("name",row.entityName);
        if(row.entityUuid!=null)meta.addProperty("uuid",row.entityUuid.toString());
        if(row.entityType!=null)meta.addProperty("type",row.entityType);
        var envelope=new JsonObject();envelope.add(KEY,meta);envelope.addProperty("text",text);return envelope.toString();
    }
    static void restoreExtra(LogEntry row){
        if(row.extra==null||!row.extra.contains('"'+KEY+'"'))return;
        try{
            var envelope=JsonParser.parseString(row.extra).getAsJsonObject();var meta=envelope.getAsJsonObject(KEY);
            if(meta==null||!meta.has("version")||meta.get("version").getAsInt()!=1||!meta.has("name")||!envelope.has("text"))return;
            var name=clean(meta.get("name").getAsString());if(name==null)return;
            UUID id=meta.has("uuid")?UUID.fromString(meta.get("uuid").getAsString()):null;
            if(row.entityUuid!=null&&id!=null&&!row.entityUuid.equals(id))return;
            if(row.entityType!=null&&meta.has("type")&&!row.entityType.equals(meta.get("type").getAsString()))return;
            row.entityName=name;if(row.entityUuid==null)row.entityUuid=id;
            if(row.entityType==null&&meta.has("type"))row.entityType=meta.get("type").getAsString();
            row.extra=envelope.get("text").isJsonNull()?null:envelope.get("text").getAsString();
        }catch(RuntimeException ignored){}
    }
    public static MutableComponent label(LogEntry row){
        var result=Component.literal(displayName(row));
        var hover=Component.literal("Тип: "+(row.entityType==null?"неизвестен":row.entityType)+(row.entityUuid==null?"":"\nUUID цели: "+row.entityUuid));
        result.withStyle(s->s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,hover)));
        return result;
    }
    public static String displayName(LogEntry row){
        String name=clean(row.entityName);
        if(name==null&&row.entityNbt!=null){
            var tag=NbtSerde.fromSnbt(row.entityNbt);
            if(tag!=null&&tag.contains("CustomName",8)&&tag.getString("CustomName").length()<=4096)try{name=clean(plain(JsonParser.parseString(tag.getString("CustomName"))));}catch(RuntimeException ignored){}
        }
        return name!=null?name:"minecraft:player".equals(row.entityType)&&row.entityUuid!=null?"игрок "+row.entityUuid:row.entityType==null?"сущность":row.entityType;
    }
    private static String plain(JsonElement value){return plain(value,0);}
    private static String plain(JsonElement value,int depth){
        if(depth>16)return "";
        if(value.isJsonPrimitive())return value.getAsString();
        if(value.isJsonArray()){var text=new StringBuilder();for(var child:value.getAsJsonArray()){if(text.length()>=256)break;text.append(plain(child,depth+1));}return text.toString();}
        if(!value.isJsonObject())return "";var object=value.getAsJsonObject();
        String text=object.has("text")?object.get("text").getAsString():"";
        return text+(object.has("extra")?plain(object.get("extra"),depth+1):"");
    }
}
