package com.roften.avilixlogger.mixin;

import net.minecraft.world.level.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import java.util.function.Consumer;

@Mixin(PersistentEntitySectionManager.class)
public interface RecoveryEntityManagerAccessor<T extends EntityAccess> {
    @Invoker("ensureChunkQueuedForLoad") void avilixlogger$queueEntityChunk(long chunk);
    @Accessor("permanentStorage") EntityPersistentStorage<T> avilixlogger$entityStorage();
    @Accessor("sectionStorage") EntitySectionStorage<T> avilixlogger$entitySections();
    @Invoker("storeChunkSections") boolean avilixlogger$storeReservedChunk(long chunk,Consumer<T> action);
}
