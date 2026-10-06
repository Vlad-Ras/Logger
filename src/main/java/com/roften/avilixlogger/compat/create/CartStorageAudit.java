package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.CartAuditContext;
import com.roften.avilixlogger.core.NbtSerde;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** O(1) storage attribution; no per-tick inventory polling. Main-thread only. */
public final class CartStorageAudit {
    public record Ref(WeakReference<OrientedContraptionEntity> entity, BlockPos pos) {}
    private static final Map<Object, Ref> OWNERS = new WeakHashMap<>();
    private CartStorageAudit() {}
    public static void bind(OrientedContraptionEntity entity) {
        var storage = entity.getContraption().getStorage();
        storage.getAllItemStorages().forEach((pos, handler) -> OWNERS.put(handler, new Ref(new WeakReference<>(entity), pos)));
        storage.getFluids().storages.forEach((pos, handler) -> OWNERS.put(handler, new Ref(new WeakReference<>(entity), pos)));
    }
    public static Ref ref(Object handler) {
        if (CartAuditContext.restoring()) return null;
        Ref ref = OWNERS.get(handler);
        return ref != null && ref.entity.get() != null && !ref.entity.get().isRemoved() ? ref : null;
    }
    public static boolean locked(Ref ref) { var e = ref == null ? null : ref.entity.get(); return e != null && CreateCartAudit.state(e).locked; }
    public static void changed(Ref ref, int slot, ItemStack before, ItemStack after) {
        var e = ref == null ? null : ref.entity.get();
        if (e == null || ItemStack.matches(before, after) || !CreateCartAudit.enabled()) return;
        CompoundTag b = item(e, ref.pos, slot, before), a = item(e, ref.pos, slot, after);
        CreateCartAudit.emit(e, ActionType.CART_CONTENT_CHANGE, "cargo", b, a, "item; local=" + ref.pos.toShortString() + "; slot=" + slot);
    }
    public static CompoundTag item(OrientedContraptionEntity e, BlockPos pos, int slot, ItemStack stack) {
        CompoundTag n = new CompoundTag(); n.putString("Kind", "item"); n.putLong("LocalPos", pos.asLong()); n.putInt("Slot", slot); n.putBoolean("External", CartAuditContext.current() == null);
        if (!stack.isEmpty()) n.put("Item", NbtSerde.snapshotItemStack(stack, e.registryAccess())); return n;
    }
    public static void clear() { OWNERS.clear(); }
}
