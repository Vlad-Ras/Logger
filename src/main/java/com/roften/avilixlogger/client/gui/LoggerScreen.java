package com.roften.avilixlogger.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Common input for the logger screens, including Escape and mouse Back. */
abstract class LoggerScreen extends Screen {
    static final int MOUSE_BACK = 3;
    static final int MOUSE_FORWARD = 4;

    LoggerScreen(Component title) {
        super(title);
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == MOUSE_BACK) {
            onClose();
            return true;
        }
        return super.mouseClicked(x, y, button);
    }
}
