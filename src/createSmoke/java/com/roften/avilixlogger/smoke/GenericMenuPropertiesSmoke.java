package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

final class GenericMenuPropertiesSmoke {
    private static final class Menu extends AbstractContainerMenu {
        final DataSlot value=DataSlot.standalone();
        Menu() { super(MenuType.GENERIC_9x1,31);addDataSlot(value); }
        @Override public ItemStack quickMoveStack(Player player,int slot){return ItemStack.EMPTY;}
        @Override public boolean stillValid(Player player){return true;}
        @Override public boolean clickMenuButton(Player player,int button){value.set(button);return true;}
    }
    static void run(ServerLevel world) {
        var menu=new Menu();int[] before=GenericMenuSnapshot.properties(menu);
        if(before==null||before.length!=1||before[0]!=0)throw new AssertionError("Vanilla menu property accessor missing");
        menu.clickMenuButton(null,73);int[] after=GenericMenuSnapshot.properties(menu);
        if(before[0]!=0||after[0]!=73)throw new AssertionError("Menu property capture is live or misses button changes");
        int old=CaptureTypePolicy.mask();try{
            CaptureTypePolicy.install(CaptureTypePolicy.changed(old,2,false));
            if(CaptureTypePolicy.allows(ActionType.GUI_CHANGE,"player:menu_button",null))throw new AssertionError("Menu change bypasses disabled containers");
        }finally{CaptureTypePolicy.install(old);}
        System.out.println("GENERIC_MENU_PROPERTIES_NATIVE_OK: vanilla button, detached data slots, bounded event capture, category gate");
    }
}
