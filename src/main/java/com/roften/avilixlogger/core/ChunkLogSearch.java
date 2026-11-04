package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;

/** A claim addresses an entire vertical chunk, rather than a block at its synthetic y=0. */
public final class ChunkLogSearch {
    private ChunkLogSearch() {}
    public static void radius(LogQuery query, BlockPos player, int custom, int preset) {
        if(custom<0 && preset==3){query.dim="*";query.minPos=null;query.maxPos=null;return;}
        int radius=custom>=0?Math.min(1000000,custom):new int[]{0,1,3,0}[Math.max(0,Math.min(3,preset))];
        int cx=Math.floorDiv(player.getX(),16),cz=Math.floorDiv(player.getZ(),16);
        query.minPos=new BlockPos((cx-radius)*16,Integer.MIN_VALUE,(cz-radius)*16);
        query.maxPos=new BlockPos((cx+radius)*16+15,Integer.MAX_VALUE,(cz+radius)*16+15);
    }
    static void appendBounds(StringBuilder sql, LogQuery query, BlockPos min, BlockPos max) {
        int minX=Math.min(min.getX(),max.getX()),maxX=Math.max(min.getX(),max.getX());
        int minY=Math.min(min.getY(),max.getY()),maxY=Math.max(min.getY(),max.getY());
        int minZ=Math.min(min.getZ(),max.getZ()),maxZ=Math.max(min.getZ(),max.getZ());
        sql.append(" AND (");
        sql.append("x BETWEEN ").append(minX).append(" AND ").append(maxX)
            .append(" AND y BETWEEN ").append(minY).append(" AND ").append(maxY)
            .append(" AND z BETWEEN ").append(minZ).append(" AND ").append(maxZ);
        if(query.includeIntersectingClaims)sql.append(" OR (action = ").append(ActionType.CLAIM_ACTION.ordinal())
            .append(" AND x BETWEEN ").append(Math.floorDiv(minX,16)*16).append(" AND ").append(Math.floorDiv(maxX,16)*16)
            .append(" AND z BETWEEN ").append(Math.floorDiv(minZ,16)*16).append(" AND ").append(Math.floorDiv(maxZ,16)*16).append(')');
        sql.append(')');
    }
}
