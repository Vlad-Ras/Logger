package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Map;
import java.util.WeakHashMap;

/** Server-thread cache of existing actors; never overwrites Create's temporaryData. */
public final class CreateMechanismAudit {
    private record Cached(BlockState state, CreateMechanismContext.Stamp stamp) {}
    private static final Map<MovementContext, Cached> ACTORS = new WeakHashMap<>();
    private CreateMechanismAudit() {}
    public static CreateMechanismContext.Stamp enter(MovementContext context) {
        var previous = CreateMechanismContext.current();
        if (context.world.isClientSide || context.state == null || context.localPos == null
                || CartAuditContext.current() == null
                && CreateContraptionKind.fromSource(MutationSourceResolver.resolveExternalSource()) == CreateContraptionKind.UNKNOWN)
            return previous;
        var cached = ACTORS.get(context);
        var pos = context.localPos;
        if (cached == null || cached.state() != context.state || cached.stamp().x() != pos.getX()
                || cached.stamp().y() != pos.getY() || cached.stamp().z() != pos.getZ()) {
            cached = new Cached(context.state, new CreateMechanismContext.Stamp(
                    BuiltInRegistries.BLOCK.getKey(context.state.getBlock()).toString(), pos.getX(), pos.getY(), pos.getZ()));
            ACTORS.put(context, cached);
        }
        return CreateMechanismContext.enter(cached.stamp());
    }
    public static void clear() { ACTORS.clear(); CreateMechanismContext.restore(null); }
}
