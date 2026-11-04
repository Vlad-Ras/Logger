package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.CreateContraptionKind;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import java.util.UUID;

/** Uses the registry of the contraption, since several kinds share one entity type. */
public final class CreateContraptionSources {
    private CreateContraptionSources() {}
    public static CreateContraptionKind kind(AbstractContraptionEntity entity) {
        var contraption = entity.getContraption();
        return contraption == null ? CreateContraptionKind.UNKNOWN
                : CreateContraptionKind.fromLocation(contraption.getType().holder.key().location());
    }
    public static UUID id(AbstractContraptionEntity entity) {
        if (entity instanceof CartEntityAccess access && access.avilixlogger$mountedState() != null)
            return access.avilixlogger$mountedState().id;
        if (entity instanceof CarriageContraptionEntity carriage && carriage.trainId != null)
            return carriage.trainId;
        if(entity.getContraption() instanceof ContraptionIdentity identity && identity.avilixlogger$identity()!=null)
            return identity.avilixlogger$identity();
        return entity.getUUID();
    }
}
