package com.roften.avilixlogger.compat.curios;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import top.theillusivec4.curios.api.event.CurioChangeEvent;

/** Observe Curios' own change event; never poll capabilities or force equipment updates. */
public final class CuriosAudit {
    private CuriosAudit() {}
    public static void install() { NeoForge.EVENT_BUS.addListener(CuriosAudit::changed); }
    private static void changed(CurioChangeEvent event) {
        if (!CaptureTypePolicy.enabled(3) || !LoggerConfig.VALUES.logEntities.get() || !LoggerConfig.isEnabled() || CartAuditContext.restoring()) return;
        if (!(event.getEntity().level() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        try {
            var row = capture(event);
            if (row != null) LoggerRuntime.storage(level).append(row);
        } catch (RuntimeException failure) { AdaptiveLogDiagnostics.adapterFailure("curios-change"); }
    }
    /** Native event payload kernel; callers retain live access on the server thread. */
    public static LogEntry capture(CurioChangeEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level) || !level.getServer().isSameThread()
                || ItemStack.matches(event.getFrom(),event.getTo())) return null;
        var before = state(event,event.getFrom(),level);
        var after = state(event,event.getTo(),level);
        var entity=event.getEntity();var row=new LogEntry();row.ts=System.currentTimeMillis();
        row.type=ActionType.ENTITY_EQUIPMENT_CHANGE;row.actorName="SYSTEM";
        // Curios detects changes on its entity tick, including login/clone. The wearer is a target,
        // not proof of the actor who changed the slot during an earlier callback.
        row.entityUuid=entity.getUUID();row.entityType=EntityType.getKey(entity.getType()).toString();
        row.entityName=EntityLogIdentity.name(entity);row.dim=level.dimension().location().toString();
        row.x=entity.blockPosition().getX();row.y=entity.blockPosition().getY();row.z=entity.blockPosition().getZ();
        row.source="mod:curios:equipment";row.extra="provider=curios; slot="+event.getIdentifier()+"["+event.getSlotIndex()+"]; observation=change_event";
        row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,before);row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,after);
        return row;
    }
    private static CompoundTag state(CurioChangeEvent event,ItemStack stack,ServerLevel level) {
        var tag=new CompoundTag();tag.putString("Kind","equipment");tag.putString("Provider","curios");
        tag.putString("SlotType",event.getIdentifier());tag.putInt("Slot",event.getSlotIndex());
        var item=stack.isEmpty()?new CompoundTag():NbtSerde.snapshotItemStack(stack,level.registryAccess());
        if(item==null)throw new IllegalStateException("Equipment item codec failed");tag.put("Item",item);return tag;
    }
}
