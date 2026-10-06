package com.roften.avilixlogger.core;

/**
 * Log entry type.
 */
public enum ActionType {
    // Blocks
    BLOCK_BREAK,
    BLOCK_PLACE,
    BLOCK_INTERACT,

    /** Inventory-like interaction (chest/barrel/modded storages). */
    CONTAINER_OPEN,
    /** BlockEntity NBT state changed (used internally for some container-like diffs and BE state tracking). */
    BLOCK_ENTITY_NBT_CHANGE,

    // Entities
    ENTITY_DEATH,
    ENTITY_SPAWN,
    ENTITY_MOUNT,
    ENTITY_DISMOUNT,
    ENTITY_CONTAINER_OPEN,

    // Items
    ITEM_DROP,
    ITEM_PICKUP,
    ITEM_CRAFT,
    ITEM_SMELT,

    /** Player death (victim is the player). */
    PLAYER_DEATH,
    /** Player joined the server. */
    PLAYER_JOIN,
    /** Player left the server. */
    PLAYER_LEAVE,

    // Container inventory diffs
    CONTAINER_PUT,
    CONTAINER_TAKE,

    // Ownership / admin operations
    ENTITY_OWNER_SET,

    // Planes (Immersive Aircraft / Man of Many Planes)
    PLANE_PLACE,
    PLANE_REMOVE,
    PLANE_MOUNT,
    PLANE_PICKUP,

    // Chat (AvilixChat / server chat)
    CHAT_MESSAGE,

    // --- Create trains (APPEND ONLY) ---
    TRAIN_ASSEMBLE,
    TRAIN_DISASSEMBLE,
    TRAIN_SCHEDULE_TAKE,
    TRAIN_CONTROL_START,
    TRAIN_CONTROL_STOP,
    TRAIN_SCHEDULE_PUT,

    // Append-only: generic entity interaction (villagers / NPCs / storekeepers / etc.)
    ENTITY_INTERACT,

    // Append-only: expanded player/world/item coverage
    BLOCK_USE,
    ITEM_USE,
    ITEM_CONSUME,
    ENTITY_ATTACK,
    PLAYER_DIMENSION_CHANGE,
    PLAYER_RESPAWN,

    // Append-only: detailed item/projectile/menu audit coverage
    ITEM_USE_START,
    ITEM_USE_STOP,
    PROJECTILE_SHOOT,
    PROJECTILE_HIT,
    GUI_OPEN,

    // Append-only: Applied Energistics 2 audits (not automatically reversible).
    ME_PUT,
    ME_TAKE,
    ME_UPGRADE_CHANGE,
    ME_FILTER_CHANGE,
    ME_SETTING_CHANGE,
    ME_CELL_CHANGE,

    // Append-only: logical Create minecart contraption history.
    CART_ASSEMBLE, CART_DISASSEMBLE, CART_PACK, CART_PLACE, CART_REMOVE,
    CART_STATUS, CART_MOVE, CART_LOAD, CART_UNLOAD, CART_CONTENT_CHANGE,
    CART_STRUCTURE_CHANGE, CART_TRANSFER, CART_ROLLBACK
}
