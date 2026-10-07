package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.CartAuditContext;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

/** A logical ID survives entity UUID changes, item packing and server restarts. */
public final class CartAuditState {
    public static final String KEY = "AvilixCartAudit";
    public UUID id = UUID.randomUUID();
    public UUID owner;
    public String ownerName = "";
    public long sequence;
    public transient String status;
    public transient int chunkX = Integer.MIN_VALUE, chunkZ = Integer.MIN_VALUE;
    public transient long lastPositionTick;
    public transient boolean removing;
    public transient boolean locked;
    public transient boolean joined;
    private transient CartAuditContext.Stamp workStamp;
    public CartAuditContext.Stamp stamp(String phase) {
        if (!phase.equals("work")) return new CartAuditContext.Stamp(id, owner, ownerName, phase, sequence);
        if (workStamp == null || workStamp.operation() != sequence || !java.util.Objects.equals(workStamp.owner(),owner)) workStamp = new CartAuditContext.Stamp(id,owner,ownerName,phase,sequence);
        return workStamp;
    }
    public CompoundTag write() {
        CompoundTag n = new CompoundTag(); n.putInt("Version", 1); n.putUUID("Id", id);
        if (owner != null) n.putUUID("Owner", owner);
        n.putString("OwnerName", ownerName == null ? "" : ownerName); n.putLong("Sequence", sequence); return n;
    }
    public void read(CompoundTag n) {
        if (n.hasUUID("Id")) id = n.getUUID("Id");
        owner = n.hasUUID("Owner") ? n.getUUID("Owner") : null;
        ownerName = n.getString("OwnerName"); sequence = Math.max(0, n.getLong("Sequence"));
    }
}
