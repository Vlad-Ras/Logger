package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Detached, bounded entity-tree validation. No entity construction or world/registry access. */
public final class EntityRecoverySnapshot {
    public static final int MAX_NODES=256, MAX_DEPTH=32;
    public static final String EVIDENCE="AvilixRecoveryEvidence";
    public record Node(UUID uuid,String type,BlockPos pos) {}
    public record Tree(List<Node> nodes) {
        public Set<UUID> ids(){var result=new LinkedHashSet<UUID>();for(var node:nodes)result.add(node.uuid());return Set.copyOf(result);}
    }
    private EntityRecoverySnapshot() {}
    public static Tree require(CompoundTag tag,String type,UUID uuid) {
        var nodes=new ArrayList<Node>();var ids=new HashSet<UUID>();
        visit(tag,type,uuid,0,nodes,ids);return new Tree(List.copyOf(nodes));
    }
    public static String validate(CompoundTag tag,String type,UUID uuid) {
        try {require(tag,type,uuid);return null;}catch(IllegalArgumentException invalid){return invalid.getMessage();}
    }
    private static void visit(CompoundTag tag,String expectedType,UUID expectedId,int depth,List<Node> nodes,Set<UUID> ids) {
        if(depth>MAX_DEPTH||nodes.size()>=MAX_NODES)fail("entity_tree_limit");
        if(tag==null||tag.isEmpty())fail("invalid_entity_nbt");
        String type=tag.getString("id");
        if(type.isBlank()&&expectedType!=null)type=expectedType;
        if(ResourceLocation.tryParse(type)==null)fail("unknown_entity_type");
        if(type.equals("minecraft:player"))fail("player_entity_snapshot_forbidden");
        if(expectedType!=null&&!expectedType.equals(type))fail("entity_type_mismatch");
        if(!tag.hasUUID("UUID"))fail("entity_missing_uuid");
        UUID id=tag.getUUID("UUID");
        if(expectedId!=null&&!expectedId.equals(id))fail("entity_uuid_mismatch");
        if(!ids.add(id))fail("entity_duplicate_uuid_in_tree");
        var pos=tag.getList("Pos",Tag.TAG_DOUBLE);var rot=tag.getList("Rotation",Tag.TAG_FLOAT);
        if(pos.size()!=3)fail("entity_missing_position");
        for(int i=0;i<3;i++)if(!Double.isFinite(pos.getDouble(i))||Math.abs(pos.getDouble(i))>30_000_000)fail("entity_invalid_position");
        if(rot.size()!=2||!Float.isFinite(rot.getFloat(0))||!Float.isFinite(rot.getFloat(1)))fail("entity_missing_rotation");
        if(tag.contains("Motion")) {
            var motion=tag.getList("Motion",Tag.TAG_DOUBLE);
            if(motion.size()!=3)fail("entity_invalid_motion");
            for(int i=0;i<3;i++)if(!Double.isFinite(motion.getDouble(i)))fail("entity_invalid_motion");
        }
        nodes.add(new Node(id,type,BlockPos.containing(pos.getDouble(0),pos.getDouble(1),pos.getDouble(2))));
        if(tag.contains("Passengers")) {
            if(!(tag.get("Passengers") instanceof ListTag children)||!children.isEmpty()&&children.getElementType()!=Tag.TAG_COMPOUND)
                fail("entity_invalid_passengers");
            for(var child:tag.getList("Passengers",Tag.TAG_COMPOUND))visit((CompoundTag)child,null,null,depth+1,nodes,ids);
        }
    }
    public static CompoundTag payload(CompoundTag tag) {
        if(tag==null)return null;
        var result=tag.copy();strip(result);return result;
    }
    private static void strip(CompoundTag tag) {
        tag.remove(EVIDENCE);
        for(var child:tag.getList("Passengers",Tag.TAG_COMPOUND))strip((CompoundTag)child);
    }
    private static void fail(String reason){throw new IllegalArgumentException(reason);}
}
