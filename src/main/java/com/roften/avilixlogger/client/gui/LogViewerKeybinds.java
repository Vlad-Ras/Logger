package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.net.C2SOpenGuiPayload;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/** Keybinds for opening the GUI. Client-only. */
@OnlyIn(Dist.CLIENT)
public final class LogViewerKeybinds {
    private LogViewerKeybinds() {}

    public static final String CATEGORY = "key.categories." + AvilixLoggerMod.MOD_ID;

    public static KeyMapping OPEN_GUI;

    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        OPEN_GUI = new KeyMapping(
                "key." + AvilixLoggerMod.MOD_ID + ".open_gui",
                GLFW.GLFW_KEY_L,
                CATEGORY
        );
        event.register(OPEN_GUI);
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        if (OPEN_GUI == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        while (OPEN_GUI.consumeClick()) {
            // Ask the server to open the GUI (permission checked server-side).
            PacketDistributor.sendToServer(new C2SOpenGuiPayload());
        }
    }
}
