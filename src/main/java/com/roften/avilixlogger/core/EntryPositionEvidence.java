package com.roften.avilixlogger.core;

/** Detached worker-side position validation. Global settings must never become a teleport to zero. */
public final class EntryPositionEvidence {
    public record Position(String dimension,long x,int y,long z,boolean chunk) {}
    private EntryPositionEvidence() {}
    public static Position read(LogEntry row) {
        if(row==null||row.dim==null||row.dim.isBlank()||row.dim.equals("*"))return null;
        var evidence=row.beAfter==null?null:NbtSerde.fromSnbt(row.beAfter);
        if(evidence!=null&&evidence.contains("PositionKnown")&&!evidence.getBoolean("PositionKnown"))return null;
        if(row.type==ActionType.CLAIM_ACTION) {
            if(evidence==null||!evidence.contains("ChunkX")||!evidence.contains("ChunkZ"))return null;
            return new Position(row.dim,(long)evidence.getInt("ChunkX")*16+8,0,(long)evidence.getInt("ChunkZ")*16+8,true);
        }
        if(row.type==ActionType.PARTY_CHANGE||row.type==ActionType.PROTECTION_CHANGE||row.type==ActionType.MACHINE_STATE_CHANGE||row.type==ActionType.MACHINE_CONTENT_CHANGE) {
            if(evidence==null||!evidence.contains("X")||!evidence.contains("Y")||!evidence.contains("Z"))return null;
        }
        return new Position(row.dim,row.x,row.y,row.z,false);
    }
}
