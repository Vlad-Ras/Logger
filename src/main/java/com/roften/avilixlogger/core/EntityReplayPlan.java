package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Lifecycle replay is forward-only. It never repurposes a death before-image as a spawn after-image. */
public final class EntityReplayPlan {
    public record Prepared(UUID uuid,String type,CompoundTag before,CompoundTag after,List<Long> eventIds,long firstTs,long lastTs,
                           ActionType action,String policyIssue) {
        public EntityRecoverySnapshot.Tree tree(){var value=after!=null?after:before;return EntityRecoverySnapshot.require(value,type,uuid);}
        public BlockPos pos(){return tree().nodes().getFirst().pos();}
    }
    public record Issue(String dimension,BlockPos pos,UUID uuid,long recordId,String reason,boolean conditional) {
        public Issue(String dimension,BlockPos pos,UUID uuid,long recordId,String reason){this(dimension,pos,uuid,recordId,reason,false);}
    }
    private static final class Chain {
        UUID uuid,actor;String type,dimension;CompoundTag before,after,evidence;long firstTs,lastTs;ActionType action;
        final List<Long> ids=new ArrayList<>();boolean selected,rejected;
    }
    private final Map<UUID,Chain> chains=new LinkedHashMap<>();
    private final List<Issue> issues=new ArrayList<>();
    private long bytes;
    private List<Prepared> finished;
    public static boolean handles(ActionType type){return type==ActionType.ENTITY_SPAWN||type==ActionType.ENTITY_DEATH||type==ActionType.PLANE_PLACE||type==ActionType.PLANE_REMOVE;}
    public void accept(LogEntry row,boolean matches) {
        if(finished!=null)throw new IllegalStateException("План сущностей уже подготовлен");
        if(row.entityUuid==null){if(matches)issue(row,"entity_missing_uuid");return;}
        var chain=chains.computeIfAbsent(row.entityUuid,ignored->new Chain());chain.selected|=matches;
        if(chain.rejected)return;
        if(!matches){reject(row,chain,"replay_filtered_entity_chain");return;}
        if(row.id<=0){reject(row,chain,"entity_missing_event_id");return;}
        var raw=NbtSerde.fromSnbt(row.entityNbt);
        String invalid=EntityRecoverySnapshot.validate(raw,row.entityType,row.entityUuid);
        if(invalid!=null){reject(row,chain,invalid);return;}
        String type=row.entityType==null||row.entityType.isBlank()?raw.getString("id"):row.entityType;
        var snapshot=EntityRecoverySnapshot.payload(raw);snapshot.putString("id",type);
        boolean spawn=row.type==ActionType.ENTITY_SPAWN||row.type==ActionType.PLANE_PLACE;
        if(CreateContraptionKind.fromSource(row.source)!=CreateContraptionKind.UNKNOWN||type.startsWith("create:")) {
            reject(row,chain,"replay_linked_create_entity");return;
        }
        if(!chain.ids.isEmpty()&&(!Objects.equals(chain.type,type)||(chain.after!=null)==spawn)) {
            reject(row,chain,"entity_lifecycle_gap");return;
        }
        if(chain.ids.isEmpty()) {chain.uuid=row.entityUuid;chain.dimension=row.dim;chain.type=type;chain.firstTs=row.ts;chain.before=spawn?null:snapshot;bytes+=160+PayloadSizeEstimator.estimateTags(chain.before);}
        chain.after=spawn?snapshot:null;chain.evidence=spawn?raw.getCompound(EntityRecoverySnapshot.EVIDENCE):null;
        chain.actor=row.actorUuid;chain.ids.add(row.id);chain.lastTs=row.ts;chain.action=row.type;
        bytes+=48+PayloadSizeEstimator.estimateTags(snapshot);
    }
    public List<Prepared> finish() {
        if(finished!=null)return finished;
        var candidates=new LinkedHashMap<UUID,Chain>();
        for(var chain:chains.values())if(!chain.rejected&&chain.selected&&!Objects.equals(chain.before,chain.after))candidates.put(chain.uuid,chain);
        var consumed=new HashSet<UUID>();
        for(var parent:candidates.values()) {
            if(parent.before!=null||parent.after==null)continue;
            mergeChildren(parent,parent.after,candidates,consumed);
        }
        var result=new ArrayList<Prepared>();var owners=new HashMap<UUID,UUID>();
        for(var chain:candidates.values()) {
            if(consumed.contains(chain.uuid))continue;
            var tree=EntityRecoverySnapshot.require(chain.after!=null?chain.after:chain.before,chain.type,chain.uuid);
            String policy=EntityRecoveryEvidence.policy(tree,chain.after!=null,chain.evidence,chain.uuid,chain.actor);
            var linked=EntityRecoveryEvidence.linkedState(chain.after!=null?chain.after:chain.before);if(linked!=null)policy=linked;
            for(var node:tree.nodes())if(owners.putIfAbsent(node.uuid(),chain.uuid)!=null)
                throw new IllegalStateException("entity_overlapping_roots: "+node.uuid()+"; выберите полное связанное событие");
            if(policy!=null)issues.add(new Issue(chain.dimension,tree.nodes().getFirst().pos(),chain.uuid,chain.ids.getLast(),policy,true));
            var ids=chain.ids.stream().distinct().sorted().toList();
            result.add(new Prepared(chain.uuid,chain.type,chain.before,chain.after,ids,chain.firstTs,chain.lastTs,chain.action,policy));
        }
        finished=List.copyOf(result);return finished;
    }
    private static void mergeChildren(Chain parent,CompoundTag node,Map<UUID,Chain> candidates,Set<UUID> consumed) {
        for(var raw:node.getList("Passengers",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
            var child=(CompoundTag)raw;var chain=candidates.get(child.getUUID("UUID"));
            if(chain!=null&&chain!=parent) {
                boolean group=parent.evidence!=null&&chain.evidence!=null&&parent.evidence.hasUUID("Group")&&chain.evidence.hasUUID("Group")
                        &&parent.evidence.getUUID("Group").equals(chain.evidence.getUUID("Group"));
                if(!group||chain.before!=null||chain.after==null||!child.equals(chain.after)||!Objects.equals(parent.dimension,chain.dimension))
                    throw new IllegalStateException("entity_ambiguous_passenger_history: "+chain.uuid);
                if(consumed.add(chain.uuid)) {
                    parent.ids.addAll(chain.ids);parent.firstTs=Math.min(parent.firstTs,chain.firstTs);parent.lastTs=Math.max(parent.lastTs,chain.lastTs);
                }
            }
            mergeChildren(parent,child,candidates,consumed);
        }
    }
    public List<Issue> issues(){return issues.stream().filter(issue->issue.uuid()==null||chains.get(issue.uuid()).selected).toList();}
    public long estimatedBytes(){return bytes;}
    public static void verifyHistory(LogStorage storage,List<Prepared> entities) {
        if(entities.isEmpty())return;
        try {if(!storage.awaitVisible(System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30)))throw new IllegalStateException("Журнал сущностей ещё не сохранён полностью");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}
        var wanted=new HashSet<UUID>();var allowed=new HashSet<Long>();var seen=new HashSet<Long>();
        long since=Long.MAX_VALUE;
        for(var entity:entities){wanted.addAll(entity.tree().ids());allowed.addAll(entity.eventIds());since=Math.min(since,entity.firstTs());}
        var query=new LogQuery();query.dim="*";query.sinceTs=since;query.untilTs=Long.MAX_VALUE;query.requireDetails=true;query.chronological=true;query.limit=2000;
        query.types=java.util.EnumSet.of(ActionType.ENTITY_SPAWN,ActionType.ENTITY_DEATH,ActionType.PLANE_PLACE,ActionType.PLANE_REMOVE);query.debugSource="entity_replay_freshness";
        int examined=0;
        while(true) {
            var rows=storage.query(query);if(rows.isEmpty())break;
            for(var row:rows) {
                if(++examined>100_000)throw new IllegalStateException("Слишком много событий для надёжной проверки свежести сущностей");
                if(wanted.contains(row.entityUuid)) {
                    if(!allowed.contains(row.id))throw new IllegalStateException("entity_later_or_unselected_history: UUID="+row.entityUuid+" запись="+row.id);
                    seen.add(row.id);
                }
            }
            if(rows.size()<query.limit)break;
            var last=rows.getLast();
            if(last.ts<query.cursorTs||last.ts==query.cursorTs&&last.id<=query.afterId)throw new IllegalStateException("История сущностей не продвинула курсор");
            query.afterId=last.id;query.cursorTs=last.ts;
        }
        if(!seen.containsAll(allowed))throw new IllegalStateException("entity_history_no_longer_available");
    }
    private void reject(LogEntry row,Chain chain,String reason){chain.rejected=true;issue(row,reason);}
    private void issue(LogEntry row,String reason){issues.add(new Issue(row.dim,new BlockPos(row.x,row.y,row.z),row.entityUuid,row.id,reason));bytes+=192;}
}
