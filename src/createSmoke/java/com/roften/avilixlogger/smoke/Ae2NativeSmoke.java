package com.roften.avilixlogger.smoke;

import appeng.api.config.*;
import appeng.api.stacks.*;
import appeng.api.util.IConfigurableObject;
import appeng.core.definitions.AEBlocks;
import appeng.core.network.serverbound.ConfigButtonPacket;
import appeng.helpers.InterfaceLogicHost;
import appeng.menu.*;
import appeng.menu.implementations.SetStockAmountMenu;
import appeng.menu.slot.*;
import appeng.util.ConfigManager;
import appeng.util.inv.AppEngInternalInventory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.*;
import com.roften.avilixlogger.core.CaptureTypePolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Supplied AE2, real menu/packet operations and isolated wrapper faults; no AuthCore bypass. */
@EventBusSubscriber(modid="avilixlogger")
public final class Ae2NativeSmoke {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke");
    private static final RuntimeException FAULT = new RuntimeException("intentional native AE2 action fault");
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }

    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("avilixlogger.ae2Smoke") || Boolean.getBoolean("avilixlogger.ae2Absent")) return;
        try { run(event.getServer().overworld()); LOG.info("AE2_NATIVE_OK: selected menu/packet/scope contracts; production authenticated emit remains open"); }
        catch (Throwable failure) { LOG.error("AE2_NATIVE_FAILED", failure); }
        finally { event.getServer().halt(false); }
    }

    private static final class Contents extends AppEngInternalInventory {
        int reads;
        boolean fail;
        Contents() { super(6); }
        @Override public ItemStack getStackInSlot(int slot) { reads++; if (fail) throw FAULT; return super.getStackInSlot(slot); }
        @Override public void setItemDirect(int slot, ItemStack stack) { if (fail) throw FAULT; super.setItemDirect(slot, stack); }
    }

    private static final class Target extends net.minecraft.world.level.block.entity.BlockEntity implements IConfigurableObject {
        int reads;
        Runnable update = () -> {};
        final ConfigManager settings = new ConfigManager((Runnable) () -> update.run());
        Target(ServerLevel level) {
            super(net.minecraft.world.level.block.entity.BlockEntityType.CHEST, new BlockPos(40, 100, 40), net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
            setLevel(level); settings.registerSetting(Settings.SORT_DIRECTION, SortDir.ASCENDING);
        }
        @Override public ConfigManager getConfigManager() { reads++; return settings; }
    }

    private static class Menu extends AEBaseMenu {
        final Contents items = new Contents();
        int callbacks;
        Menu(ServerPlayer player, Target target) {
            super(null, 47, player.getInventory(), target);
            addSlot(new FakeSlot(items, 0), SlotSemantics.CONFIG);
            addSlot(new AppEngSlot(items, 1), SlotSemantics.UPGRADE);
            addSlot(new AppEngSlot(items, 2), SlotSemantics.STORAGE_CELL);
            addSlot(new AppEngSlot(items, 3), SlotSemantics.STORAGE);
            addSlot(new AppEngSlot(items, 4), SlotSemantics.MACHINE_OUTPUT);
            addSlot(new AppEngSlot(items, 5), SlotSemantics.CRAFTING_GRID);
            addSlot(new Slot(player.getInventory(), 0, 0, 0), SlotSemantics.PLAYER_INVENTORY);
            registerClientAction("native-fault", () -> { callbacks++; setFilter(0, new ItemStack(Items.STONE, 3)); throw FAULT; });
            registerClientAction("native-noop", () -> callbacks++);
        }
        @Override public boolean stillValid(Player player) { return true; }
    }

    private static void run(ServerLevel level) throws Throwable {
        var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), "Ae2NativeEditor");
        var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        var target = new Target(level);
        var menu = new Menu(player, target);
        player.containerMenu = menu;
        check(menu instanceof Ae2MenuCapture, "AE2 audit mixins did not apply");
        check(!Ae2Audit.enabled(), "fixture must not bypass production authorization");
        var capture = (Ae2MenuCapture) menu;
        menu.items.setItemDirect(0, new ItemStack(Items.DIRT, 7));
        menu.items.setItemDirect(1, new ItemStack(Items.DIAMOND, 2));
        menu.items.setItemDirect(5, new ItemStack(Items.GOLD_INGOT, 9));
        var before = Ae2Audit.snapshot(menu);
        check(before != null && before.slots().size() == 5 && !before.slots().containsKey(5) && !before.slots().containsKey(6), "snapshot included crafting/player slots");
        menu.items.getStackInSlot(0).setCount(1);
        target.settings.putSetting(Settings.SORT_DIRECTION, SortDir.DESCENDING);
        check(before.slots().get(0).stack().getCount() == 7 && !before.settings().equals(target.settings.exportSettings()), "snapshot retained mutable native contents/settings");
        menu.items.reads = 0; target.reads = 0;
        int mask = CaptureTypePolicy.mask();
        try {
            CaptureTypePolicy.install(CaptureTypePolicy.ALL_DISABLED);
            check(Ae2Audit.capture(menu) == null, "disabled capture returned snapshot");
            menu.receiveClientAction("native-noop", "null");
            check(menu.items.reads == 0 && target.reads == 0, "disabled action read native slots/settings");
        } finally { CaptureTypePolicy.install(mask); }
        var detached = new AtomicReference<Ae2Audit.Snapshot>();
        var failure = new AtomicReference<Throwable>();
        var worker = new Thread(() -> { try { detached.set(Ae2Audit.snapshot(menu)); } catch (Throwable t) { failure.set(t); } }, "ae2-snapshot-wrong-thread");
        worker.start(); worker.join(5000);
        check(!worker.isAlive() && failure.get() == null && detached.get() == null && menu.items.reads == 0 && target.reads == 0, "off-thread snapshot touched native state");
        expectFault(() -> menu.receiveClientAction("native-fault", "null"));
        check(menu.callbacks == 2 && menu.items.getStackInSlot(0).getCount() == 3, "native nested callback repeated or lost its mutation");
        cleared(menu);
        menu.items.fail = true;
        try { expectFault(() -> menu.setFilter(0, new ItemStack(Items.DIRT))); }
        finally { menu.items.fail = false; }
        cleared(menu);
        wrapperFaults(menu, player);
        var other = new Menu(player, new Target(level));
        target.update = () -> player.containerMenu = other;
        new ConfigButtonPacket(Settings.SORT_DIRECTION, true).handleOnServer(player);
        check(player.containerMenu == other, "native setting callback did not switch menus");
        cleared(menu); cleared(other);
        player.containerMenu = menu;
        target.update = () -> { throw FAULT; };
        expectFault(() -> new ConfigButtonPacket(Settings.SORT_DIRECTION, true).handleOnServer(player));
        cleared(menu);
        target.update = () -> {};
        stock(level, player);
        Ae2TransferNativeSmoke.run(level, player);
        Ae2DeviceSettingsNativeSmoke.run(level, player);
        Ae2PatternNativeSmoke.run(level, player);
        LOG.info("AE2_MENU_SCOPE_NATIVE_OK: native filter/client action/setting packets, nested exceptions, original-menu close, detached snapshots, disabled/off-thread no reads; five wrapper fault contracts");
    }

    private static void wrapperFaults(Menu menu, ServerPlayer player) throws Throwable {
        var capture = (Ae2MenuCapture) menu;
        int tested = 0;
        for (Method method : AEBaseMenu.class.getDeclaredMethods()) {
            var types = method.getParameterTypes();
            if (!method.getName().contains("avilixlogger$") || types.length == 0 || types[types.length - 1] != Operation.class) continue;
            method.setAccessible(true);
            Object[] args = new Object[types.length];
            for (int i = 0; i < types.length - 1; i++) args[i] = types[i] == int.class ? 0 : types[i] == long.class ? 0L : types[i] == ItemStack.class ? ItemStack.EMPTY : types[i] == String.class ? "" : types[i].isInstance(player) ? player : types[i].isEnum() ? types[i].getEnumConstants()[0] : null;
            int[] calls = {0};
            args[args.length - 1] = (Operation<Object>) supplied -> { calls[0]++; throw FAULT; };
            capture.avilixlogger$beforeAction();
            try {
                expectFault(() -> { try { method.invoke(menu, args); } catch (InvocationTargetException e) { throw e.getCause(); } });
                check(depth(menu) == 1 && calls[0] == 1, "nested wrapper lost outer depth or repeated native operation: " + method);
            } finally { capture.avilixlogger$afterAction(); }
            cleared(menu); tested++;
        }
        check(tested == 5, "expected five transformed action wrappers, got " + tested);
    }

    private static void stock(ServerLevel level, ServerPlayer player) throws Throwable {
        var pos = new BlockPos(60, 100, 60);
        level.setBlockAndUpdate(pos, AEBlocks.INTERFACE.block().defaultBlockState());
        try {
            var host = (InterfaceLogicHost) Objects.requireNonNull(level.getBlockEntity(pos));
            var previousMenu = new AEBaseMenu(null, 49, player.getInventory(), host) {};
            Method address = Ae2Audit.class.getDeclaredMethod("targetPos", AEBaseMenu.class, ServerPlayer.class);
            address.setAccessible(true);
            check(address.invoke(null, previousMenu, player).equals(pos), "address followed replacement menu instead of captured target");
            var key = AEItemKey.of(Items.STONE);
            host.getConfig().setStack(0, new GenericStack(key, 8));
            int[] reads = {0}, configReads = {0}, returns = {0}; boolean[] fail = {false};
            var proxy = (InterfaceLogicHost) Proxy.newProxyInstance(InterfaceLogicHost.class.getClassLoader(), new Class[]{InterfaceLogicHost.class}, (p, method, args) -> {
                if (method.getName().equals("getInterfaceLogic")) reads[0]++;
                if (method.getName().equals("returnToMainMenu")) { returns[0]++; return null; }
                if (method.getName().equals("getConfig")) { configReads[0]++; if (fail[0]) throw FAULT; }
                try { return method.invoke(host, args); } catch (InvocationTargetException e) { throw e.getCause(); }
            });
            var stock = new SetStockAmountMenu(48, player.getInventory(), host);
            field(SetStockAmountMenu.class, "host").set(stock, proxy);
            field(SetStockAmountMenu.class, "slot").setInt(stock, 0);
            field(SetStockAmountMenu.class, "whatToStock").set(stock, key);
            ItemStack oldStock = GenericStack.wrapInItemStack(host.getConfig().getStack(0));
            stock.confirm(32);
            Ae2DeviceSettingsNativeSmoke.checkStock(stock, oldStock, GenericStack.wrapInItemStack(host.getConfig().getStack(0)));
            check(host.getConfig().getStack(0).amount() == 32 && returns[0] == 1 && reads[0] == 0 && configReads[0] == 1, "disabled stock audit read extra interface state or changed native confirm");
            fail[0] = true; expectFault(() -> stock.confirm(64));
            check(reads[0] == 0 && configReads[0] == 2 && host.getConfig().getStack(0).amount() == 32, "failed native stock action repeated or serialized with capture disabled");
            Method snapshot = SetStockAmountMenu.class.getDeclaredMethod("avilixlogger$snapshot");
            snapshot.setAccessible(true);
            check(snapshot.invoke(stock) == null, "failed stock snapshot became a false empty filter");
            fail[0] = false;
            check(GenericStack.fromItemStack((ItemStack) snapshot.invoke(stock)).amount() == 32, "stock snapshot bypassed the native host config accessor");
            LOG.info("AE2_STOCK_NATIVE_OK: real interface config/confirm, unchanged native amounts and exception, zero disabled audit interface reads; navigation callback isolated");
        } finally { level.removeBlock(pos, false); }
    }

    private static Field field(Class<?> type, String name) throws Exception { var f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static int depth(AEBaseMenu menu) throws Exception { return field(AEBaseMenu.class, "avilixlogger$depth").getInt(menu); }
    private static void cleared(AEBaseMenu menu) throws Exception { check(depth(menu) == 0 && field(AEBaseMenu.class, "avilixlogger$before").get(menu) == null, "AE2 action retained depth/snapshot"); }
    @FunctionalInterface private interface Checked { void run() throws Throwable; }
    private static void expectFault(Checked action) throws Throwable {
        try { action.run(); } catch (Throwable t) { for (Throwable cause = t; cause != null; cause = cause.getCause()) if (cause == FAULT) return; throw t; }
        throw new AssertionError("native fault did not propagate");
    }
}
