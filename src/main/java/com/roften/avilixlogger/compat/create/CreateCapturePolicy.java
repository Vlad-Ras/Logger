package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.CreateContraptionKind;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

/** Fast server-side capture policy. Never changes the actual Create machinery. */
public final class CreateCapturePolicy {
    private CreateCapturePolicy() {}

    public static boolean kindEnabled(CreateContraptionKind kind) {
        return switch (kind) {
            case MINECART -> LoggerConfig.VALUES.logCreateMinecarts.get();
            case TRAIN -> LoggerConfig.VALUES.logCreateTrains.get();
            case BEARING -> LoggerConfig.VALUES.logCreateBearings.get();
            case OTHER -> LoggerConfig.VALUES.logCreateOtherContraptions.get();
            case UNKNOWN -> false;
        };
    }

    public static boolean allows(AbstractContraptionEntity entity) {
        return entity != null && entity.getContraption() != null
                && kindEnabled(CreateContraptionSources.kind(entity));
    }

    /** Treat excluded Create entity ticks as simulation so generic world hooks do not
     * rebuild block-entity NBT under an unattributed mod:create source. */
    public static boolean mutedEntity(Object entity) {
        return entity instanceof AbstractContraptionEntity contraption
                && contraption.getContraption() != null
                && !kindEnabled(CreateContraptionSources.kind(contraption));
    }

    public static boolean actors(AbstractContraptionEntity entity) {
        return LoggerConfig.VALUES.logCreateActorWork.get() && allows(entity);
    }

    public static boolean cargo(AbstractContraptionEntity entity) {
        return LoggerConfig.VALUES.logCreateCargoTransfers.get() && allows(entity);
    }

    public static boolean lifecycle(AbstractContraptionEntity entity) {
        return LoggerConfig.VALUES.logCreateLifecycle.get() && allows(entity);
    }

    public static boolean movement(AbstractContraptionEntity entity) {
        return LoggerConfig.VALUES.logCreateMovement.get() && allows(entity);
    }

    public static boolean action(AbstractContraptionEntity entity, ActionType action) {
        if (!allows(entity)) return false;
        return switch (action) {
            case CART_MOVE, CART_STATUS -> LoggerConfig.VALUES.logCreateMovement.get();
            case CART_ASSEMBLE, CART_DISASSEMBLE, CART_LOAD, CART_UNLOAD, CART_REMOVE,
                    CART_PACK, CART_PLACE, TRAIN_ASSEMBLE, TRAIN_DISASSEMBLE ->
                    LoggerConfig.VALUES.logCreateLifecycle.get();
            case CREATE_DEPLOYER_ACTION, CART_STRUCTURE_CHANGE ->
                    LoggerConfig.VALUES.logCreateActorWork.get();
            case CART_ROLLBACK -> true; // Audit of committed rollback must never be silenced.
            default -> LoggerConfig.VALUES.logCreateCargoTransfers.get();
        };
    }
}
