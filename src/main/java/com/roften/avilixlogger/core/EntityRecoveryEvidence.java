package com.roften.avilixlogger.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.*;
import java.util.*;

/** Producer-owned proof of vanilla creation that consumes no survival inventory resources. */
public final class EntityRecoveryEvidence {
    private static final ThreadLocal<UUID> SUMMON=new ThreadLocal<>();
    private static final Set<String> NO_TRANSFER=Set.of("minecraft:marker","minecraft:block_display","minecraft:text_display","minecraft:item_display");
    private static final Set<String> VANILLA_SPAWN=Set.of("minecraft:cow","minecraft:pig","minecraft:sheep","minecraft:chicken",
            "minecraft:armor_stand","minecraft:minecart","minecraft:boat");
    public record Scope(UUID previous) implements AutoCloseable {public void close(){if(previous==null)SUMMON.remove();else SUMMON.set(previous);}}
    private EntityRecoveryEvidence() {}
    public static boolean summoning(){return SUMMON.get()!=null;}
    public static Scope summon(){var previous=SUMMON.get();SUMMON.set(UUID.randomUUID());return new Scope(previous);}
    public static void capture(CompoundTag snapshot,Entity entity,ServerLevel level,CauseContext.Cause cause) {
        if(snapshot==null||!level.getServer().isSameThread())return;
        String method=null;UUID actor=null,group=SUMMON.get();
        if(group!=null)method="minecraft:summon";
        else if(cause!=null&&cause.actorUuid()!=null&&cause.itemKey()!=null) {
            var player=level.getServer().getPlayerList().getPlayer(cause.actorUuid());
            var key=net.minecraft.resources.ResourceLocation.tryParse(cause.itemKey());
            var item=key==null?null:BuiltInRegistries.ITEM.getOptional(key).orElse(null);
            boolean action=cause.kind()==CauseContext.Kind.USE_BLOCK||cause.kind()==CauseContext.Kind.USE_ITEM||cause.kind()==CauseContext.Kind.INTERACT_ENTITY;
            if(player!=null&&player.getAbilities().instabuild&&action&&(item instanceof SpawnEggItem||item instanceof ArmorStandItem||item instanceof MinecartItem||item instanceof BoatItem)) {
                method="creative_vanilla_item";actor=player.getUUID();
                group=UUID.nameUUIDFromBytes((actor+":"+cause.tsMs()+":"+cause.kind()+":"+cause.pos()+":"+cause.itemKey()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        if(method==null)return;
        var proof=new CompoundTag();proof.putInt("Version",1);proof.putString("Method",method);proof.putUUID("Group",group);
        proof.putUUID("Entity",entity.getUUID());if(actor!=null)proof.putUUID("Actor",actor);
        snapshot.put(EntityRecoverySnapshot.EVIDENCE,proof);
    }
    public static String policy(EntityRecoverySnapshot.Tree tree,boolean spawn,CompoundTag evidence,UUID root,UUID actor) {
        if(tree.nodes().size()>1&&(evidence==null||!evidence.hasUUID("Group")))return "entity_passenger_receipt_required";
        if(!spawn&&tree.nodes().size()>1)return "entity_passenger_removal_graph_required";
        if(tree.nodes().stream().allMatch(node->NO_TRANSFER.contains(node.type())))return null;
        if(!spawn)return "entity_resource_graph_required";
        if(tree.nodes().stream().anyMatch(node->!NO_TRANSFER.contains(node.type())&&!VANILLA_SPAWN.contains(node.type())))return "entity_codec_not_supported";
        if(evidence==null||evidence.getInt("Version")!=1||!evidence.hasUUID("Entity")||!evidence.getUUID("Entity").equals(root)||!evidence.hasUUID("Group"))return "entity_resource_proof_required";
        return switch(evidence.getString("Method")) {
            case "minecraft:summon" -> null;
            case "creative_vanilla_item" -> actor!=null&&evidence.hasUUID("Actor")&&actor.equals(evidence.getUUID("Actor"))?null:"entity_resource_proof_required";
            default -> "entity_resource_proof_required";
        };
    }
    public static String linkedState(CompoundTag tag) {
        if(tag==null)return null;
        if(!tag.getString("Team").isEmpty())return "entity_team_graph_required";
        if(tag.contains("Leash"))return "entity_leash_graph_required";
        if(tag.contains("NeoForgeData")&&(!(tag.get("NeoForgeData") instanceof CompoundTag data)||!data.isEmpty()))return "entity_persistent_data_graph_required";
        for(String key:List.of("ForgeCaps","NeoForgeCaps","neoforge:attachments"))if(tag.contains(key)) {
            if(!(tag.get(key) instanceof CompoundTag data))return "entity_attachments_graph_required";
            for(String entry:data.getAllKeys()) {
                // Create also attaches its immutable EMPTY sentinel to non-cart riding targets.
                // It has no cart, coupling or registry state. Preserve its exact serialized form.
                boolean emptyController=key.equals("neoforge:attachments")&&entry.equals("create:minecart_controller")
                        &&!tag.getString("id").equals("minecraft:minecart")&&data.get(entry) instanceof CompoundTag controller
                        &&controller.getAllKeys().equals(Set.of("Type"))&&controller.getString("Type").equals("EMPTY");
                if(!emptyController)return "entity_attachments_graph_required";
            }
        }
        for(var child:tag.getList("Passengers",net.minecraft.nbt.Tag.TAG_COMPOUND)){var reason=linkedState((CompoundTag)child);if(reason!=null)return reason;}
        return null;
    }
}
