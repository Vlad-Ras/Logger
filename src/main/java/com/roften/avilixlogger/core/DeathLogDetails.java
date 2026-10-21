package com.roften.avilixlogger.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import java.util.ArrayList;
import java.util.List;

/** Detached death context in the existing extra column; old death_message rows remain readable. */
public final class DeathLogDetails {
    private DeathLogDetails(){}
    public static String capture(LivingEntity victim,DamageSource source,String message){
        var context=new JsonObject();context.addProperty("Version",1);context.addProperty("Message",message);
        context.addProperty("DamageType",source.typeHolder().unwrapKey().map(key->key.location().toString()).orElse("unknown"));
        context.addProperty("MessageId",source.getMsgId());context.addProperty("DirectDamage",source.isDirect());
        context.add("Victim",identity(victim));context.add("Direct",identity(source.getDirectEntity()));context.add("Responsible",identity(source.getEntity()));
        var position=source.getSourcePosition();if(position!=null){var p=new JsonObject();p.addProperty("X",position.x);p.addProperty("Y",position.y);p.addProperty("Z",position.z);context.add("SourcePosition",p);}
        var envelope=new JsonObject();envelope.add("AvilixDeath",context);return envelope.toString();
    }
    private static JsonObject identity(Entity entity){
        var result=new JsonObject();if(entity==null)return result;
        result.addProperty("UUID",entity.getUUID().toString());result.addProperty("Type",net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString());result.addProperty("Name",EntityLogIdentity.name(entity));return result;
    }
    private static JsonObject context(LogEntry row){
        if(row.extra==null||!row.extra.startsWith("{\"AvilixDeath\":"))return null;
        try{
            var value=JsonParser.parseString(row.extra).getAsJsonObject().getAsJsonObject("AvilixDeath");if(value.get("Version").getAsInt()!=1)return null;
            for(String key:List.of("Message","DamageType","MessageId"))text(value,key);
            for(String key:List.of("Victim","Direct","Responsible"))if(value.has(key)){var identity=value.getAsJsonObject(key);for(String field:List.of("Name","Type","UUID"))text(identity,field);}
            if(value.has("SourcePosition")){var position=value.getAsJsonObject("SourcePosition");for(String key:List.of("X","Y","Z"))text(position,key);}
            return value;
        }catch(RuntimeException invalid){return null;}
    }
    public static String message(LogEntry row){
        if(row.extra!=null&&row.extra.startsWith("death_message:"))return row.extra.substring("death_message:".length()).trim();
        var context=context(row);return context==null?null:text(context,"Message");
    }
    public static List<Component> describe(LogEntry row){
        var context=context(row);if(context==null)return List.of();var lines=new ArrayList<Component>();
        lines.add(Component.literal("Причина смерти: "+text(context,"DamageType")+" ("+text(context,"MessageId")+")"));
        String message=text(context,"Message");if(!message.isBlank())lines.add(Component.literal(message));
        for(String key:List.of("Victim","Direct","Responsible")){
            var identity=context.getAsJsonObject(key);if(identity==null||identity.size()==0)continue;
            String label=key.equals("Victim")?"Погибший":key.equals("Direct")?"Прямой источник":"Ответственный источник";
            lines.add(Component.literal(label+": "+text(identity,"Name")+" · "+text(identity,"Type")+" · "+text(identity,"UUID")));
        }
        if(context.has("SourcePosition")){var p=context.getAsJsonObject("SourcePosition");lines.add(Component.literal("Место источника: "+text(p,"X")+" "+text(p,"Y")+" "+text(p,"Z")));}
        return List.copyOf(lines);
    }
    private static String text(JsonObject object,String key){return object.has(key)&&!object.get(key).isJsonNull()?object.get(key).getAsString():"";}
}
