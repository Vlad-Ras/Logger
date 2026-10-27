package com.roften.avilixlogger.compat.ae2;

import appeng.api.inventories.InternalInventory;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.menu.AEBaseMenu;
import appeng.parts.AEBasePart;
import com.roften.avilixlogger.core.AdaptiveLogDiagnostics;
import com.roften.avilixlogger.core.LogEntry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;

/** Synchronous action scope, observing only inventories/slots reached by native remote operations. */
public final class Ae2RemoteAudit {
    private Ae2RemoteAudit() {}
    public static Capture begin(AEBaseMenu menu, boolean liveReads) {
        return Ae2Audit.enabled() ? create(menu, liveReads, true) : null;
    }
    /** Detached evidence inspector; no production append and no authorization change. */
    public static Capture inspect(AEBaseMenu menu, boolean liveReads) { return create(menu, liveReads, false); }
    private static Capture create(AEBaseMenu menu, boolean liveReads, boolean production) {
        try {
            if (!Ae2Audit.onServerThread(menu)) return null;
            String address = Ae2Audit.menuAddress(menu);
            return address == null ? null : new Capture((ServerPlayer)menu.getPlayer(), address, liveReads, production);
        } catch (Throwable failure) { failure(); return null; }
    }
    private static void failure() { AdaptiveLogDiagnostics.adapterFailure("ae2-remote-pattern"); }
    public static final class Capture {
        private ServerPlayer player;
        private final String terminal;
        private boolean liveReads;
        private final boolean production;
        private IdentityHashMap<InternalInventory, Root> roots;
        private Capture(ServerPlayer player, String terminal, boolean liveReads, boolean production) {
            this.player = player; this.terminal = terminal; this.liveReads = liveReads; this.production = production;
        }
        public InternalInventory wrap(InternalInventory inventory, PatternContainer container, long id) {
            if (player == null || !player.server.isSameThread()) return inventory;
            try {
                if (roots != null && roots.containsKey(inventory)) return roots.get(inventory).observed;
                BlockEntity host = container instanceof BlockEntity block ? block : container instanceof AEBasePart part ? part.getBlockEntity()
                        : container instanceof PatternProviderLogicHost provider ? provider.getBlockEntity() : null;
                if (host == null || host.getLevel() == null || host.getLevel().getServer() != player.server) {
                    AdaptiveLogDiagnostics.adapterFailure("ae2-remote-origin"); return inventory;
                }
                var root = new Root(inventory, host, id, liveReads);
                if (roots == null) roots = new IdentityHashMap<>(); roots.put(inventory, root); return root.observed;
            } catch (Throwable failure) { failure(); return inventory; }
        }
        public void requireLiveReads() {
            if (liveReads) return;
            liveReads = true;
            if (roots != null) for (var root : roots.values()) root.liveReads = true;
        }
        /** Clears every live reference even after native or audit failure. Production streams each row. */
        public List<LogEntry> finish() {
            if (player == null) return List.of();
            List<LogEntry> rows = production ? null : new ArrayList<>();
            try {
                if (!player.server.isSameThread()) return List.of();
                if (roots != null) for (var root : roots.values()) {
                    if (root.before == null) continue;
                    for (var slot : root.before.entrySet()) {
                        if (slot.getValue() == null) continue;
                        try {
                            Ae2Audit.remoteChange(player, root.host, "ae2:PatternAccessTermMenu:terminal=" + terminal + ":provider=" + root.id
                                    + ":ENCODED_PATTERN:slot=" + slot.getKey(), slot.getValue(), root.inventory.getStackInSlot(slot.getKey()), rows);
                        } catch (Throwable failure) { failure(); }
                    }
                }
                return rows == null ? List.of() : List.copyOf(rows);
            } finally {
                if (roots != null) { for (var root : roots.values()) root.clear(); roots.clear(); roots = null; }
                player = null;
            }
        }
        public int retainedSlots() {
            if (roots == null) return 0;
            return roots.values().stream().mapToInt(r -> r.before == null ? 0 : r.before.size()).sum();
        }
    }
    private static final class Root {
        private InternalInventory inventory;
        private BlockEntity host;
        private final long id;
        private boolean liveReads;
        private final Observed observed;
        private Map<Integer, ItemStack> before;
        private Root(InternalInventory inventory, BlockEntity host, long id, boolean liveReads) {
            this.inventory = inventory; this.host = host; this.id = id; this.liveReads = liveReads; observed = new Observed(this, inventory, 0);
        }
        private boolean remember(int slot, ItemStack stack, boolean force) {
            if (inventory == null || before != null && before.containsKey(slot)) return false;
            if (!force && stack.isEmpty()) return false;
            if (before == null) before = new LinkedHashMap<>();
            try { before.put(slot, stack.copy()); } catch (Throwable failure) { before.put(slot, null); failure(); }
            return true;
        }
        private boolean writing(int slot) {
            if (inventory == null || before != null && before.containsKey(slot)) return false;
            try { return remember(slot, inventory.getStackInSlot(slot), true); }
            catch (Throwable failure) { if (before == null) before = new LinkedHashMap<>(); before.put(slot, null); failure(); return true; }
        }
        private void prune(int slot, boolean fresh) {
            if (!fresh || before == null || before.get(slot) == null || inventory == null) return;
            try { if (ItemStack.matches(before.get(slot), inventory.getStackInSlot(slot))) before.remove(slot); }
            catch (Throwable failure) { failure(); }
        }
        private void clear() { if (before != null) before.clear(); before = null; inventory = null; host = null; }
    }
    private static final class Observed implements InternalInventory {
        private final Root root;
        private final InternalInventory delegate;
        private final int offset;
        private Observed(Root root, InternalInventory delegate, int offset) { this.root = root; this.delegate = delegate; this.offset = offset; }
        public int size() { return delegate.size(); }
        public int getSlotLimit(int slot) { return delegate.getSlotLimit(slot); }
        public boolean isItemValid(int slot, ItemStack stack) { return delegate.isItemValid(slot, stack); }
        public ItemStack getStackInSlot(int slot) {
            var stack = delegate.getStackInSlot(slot);
            if (root.liveReads) { try { root.remember(offset + slot, stack, false); } catch (Throwable failure) { failure(); } }
            return stack;
        }
        public void setItemDirect(int slot, ItemStack stack) {
            boolean fresh = root.writing(offset + slot);
            try { delegate.setItemDirect(slot, stack); } finally { root.prune(offset + slot, fresh); }
        }
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            boolean fresh = !simulate && root.writing(offset + slot);
            try { return delegate.insertItem(slot, stack, simulate); } finally { root.prune(offset + slot, fresh); }
        }
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            boolean fresh = !simulate && root.writing(offset + slot);
            try { return delegate.extractItem(slot, amount, simulate); } finally { root.prune(offset + slot, fresh); }
        }
        public InternalInventory getSlotInv(int slot) { return new Observed(root, delegate.getSlotInv(slot), offset + slot); }
        public InternalInventory getSubInventory(int start, int end) { return new Observed(root, delegate.getSubInventory(start, end), offset + start); }
        public void sendChangeNotification(int slot) { delegate.sendChangeNotification(slot); }
    }
}
