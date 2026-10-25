package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.MenuDataAccess;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import java.util.List;

@Mixin(AbstractContainerMenu.class)
public interface MenuDataAccessor extends MenuDataAccess {
    @Accessor("dataSlots") List<DataSlot> avilixlogger$dataSlots();
}
