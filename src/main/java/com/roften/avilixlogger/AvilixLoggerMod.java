package com.roften.avilixlogger;

import com.mojang.logging.LogUtils;
import com.roften.avilixlogger.command.LoggerCommands;
import com.roften.avilixlogger.core.LoggerEventHandlers;
import com.roften.avilixlogger.core.LoggerRuntime;
import com.roften.avilixlogger.net.LoggerNetwork;
import com.roften.avilixlogger.net.LoggerNetworkHooks;
import org.slf4j.Logger;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;

/**
 * Avilix Logger: server-side audit log + rollback for blocks/entities/inventories.
 *
 * Scope (MVP):
 * - block place/break + blockentity state snapshot
 * - entity death/spawn snapshot (best-effort)
 * - container changes (blockentity NBT diff) for vanilla and most modded storages
 * - chat commands for lookup and rollback
 */
@Mod(AvilixLoggerMod.MOD_ID)
public final class AvilixLoggerMod {
    public static final String MOD_ID = "avilixlogger";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AvilixLoggerMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, LoggerConfig.SPEC);

        // Mod bus listeners
        modEventBus.addListener(LoggerNetwork::registerPayloads);

        // Optional client bootstrap (GUI hooks, keybinds, etc.)
        // NeoForge 21.1.x: DistExecutor may not be present depending on the toolchain.
        // We keep this mod single-jar and avoid hard-linking client classes on dedicated servers.
        tryInitClient(modEventBus);

        // Server/game events
        NeoForge.EVENT_BUS.register(new LoggerEventHandlers());
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        NeoForge.EVENT_BUS.addListener(this::onServerStarting);
        NeoForge.EVENT_BUS.addListener(this::onServerStopping);
        NeoForge.EVENT_BUS.addListener(LoggerNetworkHooks::onLogout);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        LoggerCommands.register(event.getDispatcher());
    }

    private void onServerStarting(ServerStartingEvent event) {
        // Warm up DB storage off-thread so the first player join does not block the server tick.
        LoggerRuntime.warmupAsync();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        // Ensure we flush writers and stop GUI/database helper executors.
        LoggerEventHandlers.shutdownBackground();
        LoggerNetwork.shutdown();
        LoggerRuntime.shutdown();
    }

    /**
     * Runs client-only bootstrap if we're on the client distribution.
     * Uses reflection to avoid dedicated server classloading issues.
     */
    private static void tryInitClient(IEventBus modEventBus) {
        try {
            // net.neoforged.fml.loading.FMLEnvironment#dist is present on both sides
            Class<?> env = Class.forName("net.neoforged.fml.loading.FMLEnvironment");
            Object dist = env.getField("dist").get(null);
            if (dist != null && dist.toString().equalsIgnoreCase("CLIENT")) {
                Class<?> bootstrap = Class.forName("com.roften.avilixlogger.client.ClientBootstrap");
                bootstrap.getMethod("init", IEventBus.class).invoke(null, modEventBus);
            }
        } catch (Throwable ignored) {
            // Not a client environment or classes not present; ignore.
        }
    }
}
