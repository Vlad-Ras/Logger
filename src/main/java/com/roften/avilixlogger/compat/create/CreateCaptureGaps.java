package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.nbt.CompoundTag;
import java.util.Map;
import java.util.WeakHashMap;

/** One small history barrier per mounted lifecycle/setting generation, never an actor snapshot. */
public final class CreateCaptureGaps {
    private record Seen(long generation, long sequence) {}
    private static final Map<AbstractContraptionEntity, Seen> SEEN = new WeakHashMap<>();
    private CreateCaptureGaps() {}
    public static void remember(AbstractContraptionEntity entity) {
        // Minecart period rollback needs contiguous world/cargo history. Recovery from a
        // later full pack/remove snapshot remains valid and does not cross this barrier.
        var state = CreateCartAudit.state(entity);
        if (state == null || !CreateCapturePolicy.lifecycle(entity)) return;
        long generation = CaptureTypePolicy.generation();
        var seen = SEEN.get(entity);
        if (seen != null && seen.generation == generation && seen.sequence == state.sequence) return;
        if (!CreateCargoAudit.lifecycleActive(entity)) return;
        var tag = new CompoundTag(); tag.putBoolean("Complete", false);
        tag.putBoolean("CaptureDisabled", true); tag.putString("Kind", "actor_result");
        var row = CreateCargoAudit.row(entity, ActionType.CART_TRANSFER, "actor_result", tag, tag,
                "capture disabled; period undo cannot cross unrecorded mechanism work");
        // A muted callback still needs its safety barrier, even when cargo capture is off.
        boolean previous = CaptureTypePolicy.suppressed();
        CaptureTypePolicy.restoreSuppression(false);
        try { CreateCargoAudit.safetyBarrier(entity, row); }
        finally { CaptureTypePolicy.suppress(previous); }
        SEEN.put(entity, new Seen(generation, state.sequence));
    }
    public static void clear() { SEEN.clear(); }
}
