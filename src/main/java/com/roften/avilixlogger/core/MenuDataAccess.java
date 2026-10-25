package com.roften.avilixlogger.core;

import net.minecraft.world.inventory.DataSlot;
import java.util.List;

/** Common vanilla synchronization properties; never links a mod's private menu implementation. */
public interface MenuDataAccess {
    List<DataSlot> avilixlogger$dataSlots();
}
