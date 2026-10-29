package com.roften.avilixlogger.compat.ae2;

import appeng.menu.AEBaseMenu;

/** Native call scope only. The caller restores the previous source in finally. */
public interface Ae2CpuCancellationSource {
    AEBaseMenu avilixlogger$swapCancellationMenu(AEBaseMenu menu);
    AEBaseMenu avilixlogger$cancellationMenu();
}
