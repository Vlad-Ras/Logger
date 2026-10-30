package com.roften.avilixlogger.compat.opac;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.lang.reflect.Proxy;
import java.util.UUID;

/** Successful native action receipts; never grants/denies access or claims authorship from an owner UUID. */
public final class OpenPacAudit {
    private OpenPacAudit() {}

    public static void started(ServerStartedEvent event) {
        var mod = ModList.get().getModContainerById("openpartiesandclaims");
        if (mod.isEmpty()) return;
        String version = mod.get().getModInfo().getVersion().toString();
        if (!version.equals("0.33.0")) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] OPAC audit disabled: unsupported version {}", version);
            return;
        }
        try {
            var loader = OpenPacAudit.class.getClassLoader();
            Class<?> api = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI", false, loader);
            Class<?> claims = Class.forName("xaero.pac.common.server.claims.api.IServerClaimsManagerAPI", false, loader);
            Class<?> listeners = Class.forName("xaero.pac.common.server.claims.action.listener.api.IClaimActionListenerManagerAPI", false, loader);
            Class<?> listener = Class.forName("xaero.pac.common.server.claims.action.listener.api.IClaimActionListenerAPI", false, loader);
            Class<?> action = Class.forName("xaero.pac.common.claims.action.api.ClaimingAction", false, loader);
            Class<?> permission = Class.forName("xaero.pac.common.server.claims.action.listener.override.api.ClaimActionPermissionOverride", false, loader);
            var success = listener.getMethod("handleSuccessfulClaimingAction", UUID.class, ResourceLocation.class,
                    int.class, int.class, action, claims, MinecraftServer.class);
            var override = listener.getMethod("overrideClaimingActionPermission", UUID.class, ResourceLocation.class,
                    int.class, int.class, action, claims, permission, MinecraftServer.class);
            var getName = listener.getMethod("getName");
            var get = api.getMethod("get", MinecraftServer.class);
            var getClaims = api.getMethod("getServerClaimsManager");
            var getListeners = claims.getMethod("getActionListenerManager");
            var register = listeners.getMethod("register", listener);
            if (!listener.isInterface() || !action.isEnum() || success.getReturnType() != void.class
                    || override.getReturnType() != permission || getName.getReturnType() != String.class
                    || get.getReturnType() != api || getClaims.getReturnType() != claims
                    || getListeners.getReturnType() != listeners || register.getReturnType() != void.class)
                throw new IllegalStateException("Incompatible OPAC action API");
            MinecraftServer server = event.getServer();
            Object observer = Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "toString" -> "AvilixLogger OPAC observer";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
                if (method.equals(getName)) return "AvilixLogger audit";
                if (method.equals(override)) return args[6]; // Preserve the exact upstream candidate, always.
                if (method.equals(success) && args[6] == server) {
                    try {
                        receipt(server, (UUID) args[0], (ResourceLocation) args[1], (int) args[2], (int) args[3],
                                ((Enum<?>) args[4]).name());
                    } catch (RuntimeException | LinkageError failure) {
                        AdaptiveLogDiagnostics.adapterFailure("opac-action");
                    }
                }
                return null;
            });
            Object instance = get.invoke(null, server);
            Object manager = getClaims.invoke(instance);
            register.invoke(getListeners.invoke(manager), observer);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] OPAC audit disabled: incompatible optional API", failure);
        }
    }

    private static void receipt(MinecraftServer server, UUID performedAs, ResourceLocation dimension,
                                int chunkX, int chunkZ, String action) {
        if (!server.isSameThread() || !CaptureTypePolicy.enabled(1) || !LoggerConfig.isEnabled()
                || !LoggerConfig.VALUES.logBlocks.get() || CartAuditContext.restoring()) return;
        var tag = new CompoundTag(); tag.putString("Provider", "openpartiesandclaims");
        tag.putUUID("PerformedAsUUID", performedAs); tag.putString("Dimension", dimension.toString());
        tag.putInt("ChunkX", chunkX); tag.putInt("ChunkZ", chunkZ); tag.putString("Action", action);
        // The success callback for UNANCHOR is inside the native asynchronous removal completion.
        tag.putString("Phase", "successful-action");
        tag.putString("ActorEvidence", "performed-as identity only; initiating actor unavailable");
        var row = new LogEntry(); row.ts = System.currentTimeMillis(); row.type = ActionType.CLAIM_ACTION;
        row.actorName = "SYSTEM"; row.dim = dimension.toString();
        long blockX = (long) chunkX * 16, blockZ = (long) chunkZ * 16;
        // Keep exact chunk coordinates in evidence rather than wrap invalid block coordinates.
        if (blockX < Integer.MIN_VALUE || blockX > Integer.MAX_VALUE || blockZ < Integer.MIN_VALUE || blockZ > Integer.MAX_VALUE) return;
        row.x = (int) blockX; row.z = (int) blockZ; row.y = 0;
        row.source = "mod:openpartiesandclaims:claim-action";
        row.extra = "OPAC " + action + "; chunk=" + chunkX + "," + chunkZ + "; performedAs=" + performedAs
                + "; successful action; audit only";
        row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, tag);
        LoggerRuntime.storage(server.overworld()).append(row);
    }
}
