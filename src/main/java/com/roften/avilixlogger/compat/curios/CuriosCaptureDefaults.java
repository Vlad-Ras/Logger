package com.roften.avilixlogger.compat.curios;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.common.CuriosRegistry;
import top.theillusivec4.curios.common.capability.CurioInventory;

/** Prepare only the versioned, genuinely empty lazy holder before an action's first snapshot. */
public final class CuriosCaptureDefaults {
    private CuriosCaptureDefaults() {}
    private static final boolean SUPPORTED=ModList.get().getModContainerById("curios").map(c->c.getModInfo().getVersion().toString().equals("9.5.1+1.21.1")).orElse(false);
    private static final VarHandle PENDING=pendingField();
    private static VarHandle pendingField() {
        try { return MethodHandles.privateLookupIn(CurioInventory.class,MethodHandles.lookup()).findVarHandle(CurioInventory.class,"markDeserialized",boolean.class); }
        catch (ReflectiveOperationException | RuntimeException failure) { return null; }
    }
    public static void prepare(Player player) {
        if(!SUPPORTED||PENDING==null)throw new IllegalStateException("Unknown Curios lazy attachment contract");
        if(!player.hasData(CuriosRegistry.INVENTORY.get()))CuriosApi.getCuriosInventory(player);
        if(!player.hasData(CuriosRegistry.INVENTORY.get()))return;
        var inventory=player.getData(CuriosRegistry.INVENTORY.get());
        if(!(boolean)PENDING.get(inventory))return;
        // A pending nonempty inventory may migrate/drop items during native reset. Logger
        // must not initiate that operation. Let the normal Curios lifecycle complete first.
        var raw=inventory.serializeNBT(player.registryAccess());
        if(!inventory.asMap().isEmpty()||!raw.getAllKeys().equals(java.util.Set.of("Curios"))
                ||!(raw.get("Curios") instanceof ListTag list)||!list.isEmpty())
            throw new IllegalStateException("Curios inventory requires native initialization before capture");
        CuriosApi.getCuriosInventory(player);
    }
}
