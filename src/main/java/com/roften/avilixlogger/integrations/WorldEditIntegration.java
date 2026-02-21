package com.roften.avilixlogger.integrations;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * Optional WorldEdit integration (reflection-based).
 *
 * Tries to obtain the player's current selection as a cuboid (min/max).
 */
public final class WorldEditIntegration {

    private static final List<String> ADAPTERS = List.of(
            // NeoForge
            "com.sk89q.worldedit.neoforge.ForgeAdapter",
            "com.sk89q.worldedit.neoforge.NeoForgeAdapter",
            // Legacy Forge
            "com.sk89q.worldedit.forge.ForgeAdapter",
            // Fabric
            "com.sk89q.worldedit.fabric.FabricAdapter"
    );

    private WorldEditIntegration() {}

    /** @return {min,max} or null if WE missing or no selection */
    public static BlockPos[] getSelection(ServerPlayer player) {
        try {
            Object actor = adaptPlayer(player);
            if (actor == null) return null;

            Class<?> worldEditClz = Class.forName("com.sk89q.worldedit.WorldEdit");
            Object we = worldEditClz.getMethod("getInstance").invoke(null);
            Object sessionManager = worldEditClz.getMethod("getSessionManager").invoke(we);

            // WorldEdit 7.3+ uses SessionOwner (not Actor) for sessions in API docs. Some older builds used Actor.
            Object session;
            try {
                Class<?> ownerClz = Class.forName("com.sk89q.worldedit.session.SessionOwner");
                session = sessionManager.getClass().getMethod("get", ownerClz).invoke(sessionManager, actor);
            } catch (NoSuchMethodException noOwnerGet) {
                // Fallback for older signatures
                Class<?> actorClz = Class.forName("com.sk89q.worldedit.extension.platform.Actor");
                session = sessionManager.getClass().getMethod("get", actorClz).invoke(sessionManager, actor);
            }

            // LocalSession#getSelection(World)
            Object weWorld = adaptWorld(player);
            if (weWorld == null) return null;
            Object region = session.getClass().getMethod("getSelection", Class.forName("com.sk89q.worldedit.world.World")).invoke(session, weWorld);
            if (region == null) return null;

            // Region#getMinimumPoint / getMaximumPoint => BlockVector3
            Object min = region.getClass().getMethod("getMinimumPoint").invoke(region);
            Object max = region.getClass().getMethod("getMaximumPoint").invoke(region);
            int minX = (int) min.getClass().getMethod("getBlockX").invoke(min);
            int minY = (int) min.getClass().getMethod("getBlockY").invoke(min);
            int minZ = (int) min.getClass().getMethod("getBlockZ").invoke(min);
            int maxX = (int) max.getClass().getMethod("getBlockX").invoke(max);
            int maxY = (int) max.getClass().getMethod("getBlockY").invoke(max);
            int maxZ = (int) max.getClass().getMethod("getBlockZ").invoke(max);

            BlockPos a = new BlockPos(Math.min(minX, maxX), Math.min(minY, maxY), Math.min(minZ, maxZ));
            BlockPos b = new BlockPos(Math.max(minX, maxX), Math.max(minY, maxY), Math.max(minZ, maxZ));
            return new BlockPos[]{a, b};
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object adaptPlayer(ServerPlayer player) {
        for (String cn : ADAPTERS) {
            try {
                Class<?> adapter = Class.forName(cn);

                // WorldEdit adapters vary by platform/version. Instead of hardcoding the param type,
                // search for a compatible static method.
                for (String mn : new String[]{"adapt", "adaptPlayer"}) {
                    for (Method m : adapter.getMethods()) {
                        if (!m.getName().equals(mn)) continue;
                        if (!Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getParameterCount() != 1) continue;
                        Class<?> p0 = m.getParameterTypes()[0];
                        if (!p0.isAssignableFrom(player.getClass())) continue;
                        Object r = m.invoke(null, player);
                        if (r != null) return r;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Object adaptWorld(ServerPlayer player) {
        Object level = player.level();
        Object serverLevel = player.serverLevel();
        for (String cn : ADAPTERS) {
            try {
                Class<?> adapter = Class.forName(cn);

                for (String mn : new String[]{"adapt", "adaptWorld"}) {
                    for (Method m : adapter.getMethods()) {
                        if (!m.getName().equals(mn)) continue;
                        if (!Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getParameterCount() != 1) continue;
                        Class<?> p0 = m.getParameterTypes()[0];

                        // Prefer ServerLevel if possible
                        if (serverLevel != null && p0.isAssignableFrom(serverLevel.getClass())) {
                            Object r = m.invoke(null, serverLevel);
                            if (r != null) return r;
                        }
                        if (level != null && p0.isAssignableFrom(level.getClass())) {
                            Object r = m.invoke(null, level);
                            if (r != null) return r;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
