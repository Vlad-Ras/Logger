package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.CreateContraptionKind;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

/** Fast server-side capture policy. Never changes the actual Create machinery. */
public final class CreateCapturePolicy {
    private CreateCapturePolicy() {}

    public static int mechanismGroup(net.minecraft.resources.ResourceLocation block) {
        if (block == null) return 0;
        if ("minecraft".equals(block.getNamespace()))
            return block.getPath().equals("dispenser") || block.getPath().equals("dropper") ? com.roften.avilixlogger.core.CaptureTypePolicy.CREATE_DISPENSER : 0;
        if (!"create".equals(block.getNamespace())) return 0;
        return switch (block.getPath()) {
            case "deployer" -> com.roften.avilixlogger.core.CaptureTypePolicy.CREATE_DEPLOYER;
            case "mechanical_drill" -> com.roften.avilixlogger.core.CaptureTypePolicy.CREATE_DRILL;
            case "mechanical_saw" -> com.roften.avilixlogger.core.CaptureTypePolicy.CREATE_SAW;
            case "mechanical_harvester" -> com.roften.avilixlogger.core.CaptureTypePolicy.CREATE_HARVESTER;
            case "mechanical_plough" -> com.roften.avilixlogger.core.CaptureTypePolicy.CREATE_PLOUGH;
            case "mechanical_roller" -> com.roften.avilixlogger.core.CaptureTypePolicy.CREATE_ROLLER;
            case "chute", "smart_chute" -> com.roften.avilixlogger.core.CaptureTypePolicy.CREATE_CHUTE;
            case "andesite_funnel", "brass_funnel", "andesite_belt_funnel", "brass_belt_funnel" -> com.roften.avilixlogger.core.CaptureTypePolicy.CREATE_FUNNEL;
            case "portable_storage_interface", "portable_fluid_interface" -> com.roften.avilixlogger.core.CaptureTypePolicy.CREATE_PORTABLE_STORAGE;
            default -> 0;
        };
    }
    public static boolean mechanismEnabled(net.minecraft.world.level.block.state.BlockState state) {
        if (state == null) return true;
        int group = mechanismGroup(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        return group == 0 || com.roften.avilixlogger.core.CaptureTypePolicy.enabled(group);
    }
    public static boolean actor(com.simibubi.create.content.contraptions.behaviour.MovementContext context) {
        return context != null && context.contraption != null && actors(context.contraption.entity)
                && mechanismEnabled(context.state);
    }
    /** Called around native callbacks; Create itself always continues executing. */
    public static boolean enterActor(com.simibubi.create.content.contraptions.behaviour.MovementContext context) {
        boolean muted = context != null && !context.world.isClientSide && !actor(context);
        if (muted && context.contraption != null && context.contraption.entity != null
                && lifecycle(context.contraption.entity)) CreateCaptureGaps.remember(context.contraption.entity);
        return com.roften.avilixlogger.core.CaptureTypePolicy.suppress(muted);
    }

    public static boolean kindEnabled(CreateContraptionKind kind) {
        if (!com.roften.avilixlogger.core.CaptureTypePolicy.allowsCreateKind(kind)) return false;
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

    public static CreateContraptionKind controllerKind(net.minecraft.resources.ResourceLocation type) {
        if(type==null||!"create".equals(type.getNamespace()))return CreateContraptionKind.UNKNOWN;
        return switch(type.getPath()) {
            case "mechanical_bearing", "windmill_bearing", "clockwork_bearing" -> CreateContraptionKind.BEARING;
            case "mechanical_piston", "rope_pulley", "elevator_pulley", "gantry_pinion" -> CreateContraptionKind.OTHER;
            default -> CreateContraptionKind.UNKNOWN;
        };
    }
    public static boolean mutedController(net.minecraft.world.level.block.entity.BlockEntity controller) {
        if(controller==null)return false;
        var kind=controllerKind(net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(controller.getType()));
        return kind!=CreateContraptionKind.UNKNOWN&&!kindEnabled(kind);
    }
    /** Treat excluded Create entity ticks as simulation so generic world hooks do not
     * rebuild block-entity NBT under an unattributed mod:create source. */
    public static boolean mutedEntity(Object entity) {
        return entity instanceof AbstractContraptionEntity contraption
                && contraption.getContraption() != null
                && !kindEnabled(CreateContraptionSources.kind(contraption));
    }

    public static boolean actors(AbstractContraptionEntity entity) {
        return !com.roften.avilixlogger.core.CaptureTypePolicy.suppressed()
                && LoggerConfig.VALUES.logCreateActorWork.get() && allows(entity);
    }

    public static boolean cargo(AbstractContraptionEntity entity) {
        return !com.roften.avilixlogger.core.CaptureTypePolicy.suppressed()
                && LoggerConfig.VALUES.logCreateCargoTransfers.get() && allows(entity);
    }

    public static boolean lifecycle(AbstractContraptionEntity entity) {
        return LoggerConfig.VALUES.logCreateLifecycle.get() && allows(entity);
    }

    public static boolean movement(AbstractContraptionEntity entity) {
        return LoggerConfig.VALUES.logCreateMovement.get() && allows(entity);
    }

    public static boolean action(AbstractContraptionEntity entity, ActionType action) {
        if (com.roften.avilixlogger.core.CaptureTypePolicy.suppressed()) return false;
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
