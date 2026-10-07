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
    public record Ref(WeakReference<OrientedContraptionEntity> entity, BlockPos pos,boolean base) {}
    private static final Map<Object, Ref> OWNERS = new WeakHashMap<>();
    private static final Map<net.minecraft.world.inventory.AbstractContainerMenu,Ref> MENUS=new WeakHashMap<>();
    private CartStorageAudit() {}
    public static void menu(net.minecraft.world.inventory.AbstractContainerMenu menu,Ref ref){MENUS.put(menu,ref);}
    public static void lockMenus(java.util.UUID id,net.minecraft.server.MinecraftServer server){
        for(var player:server.getPlayerList().getPlayers()) {
            var ref=MENUS.get(player.containerMenu);var entity=ref==null?null:ref.entity.get();
            boolean baseMenu=false;
            if(!baseMenu)for(var slot:player.containerMenu.slots) {
                if(slot.container instanceof net.minecraft.world.entity.Entity base && base.getPersistentData().getCompound(CartAuditState.KEY).hasUUID("Id") && id.equals(base.getPersistentData().getCompound(CartAuditState.KEY).getUUID("Id"))){baseMenu=true;break;}
            }
            if(baseMenu || entity!=null && id.equals(CreateCartAudit.state(entity).id)) {
                com.roften.avilixlogger.core.CartRestoreLocks.menu(player.containerMenu);player.closeContainer();
            }
        }
    }
    public static void bind(OrientedContraptionEntity entity) {
        if(entity.getVehicle() instanceof net.minecraft.world.Container)OWNERS.put(entity.getVehicle(),new Ref(new WeakReference<>(entity),BlockPos.ZERO,true));
        var storage = entity.getContraption().getStorage();
        storage.getAllItemStorages().forEach((pos, handler) -> OWNERS.put(handler, new Ref(new WeakReference<>(entity), pos,false)));
        storage.getFluids().storages.forEach((pos, handler) -> OWNERS.put(handler, new Ref(new WeakReference<>(entity), pos,false)));
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
        CompoundTag b = item(e, ref.pos, slot, before), a = item(e, ref.pos, slot, after);b.putBoolean("BaseCart",ref.base);a.putBoolean("BaseCart",ref.base);
        CreateCartAudit.emit(e, ActionType.CART_CONTENT_CHANGE, "cargo", b, a, "item; local=" + ref.pos.toShortString() + "; slot=" + slot);
    }
    public static CompoundTag item(OrientedContraptionEntity e, BlockPos pos, int slot, ItemStack stack) {
        CompoundTag n = new CompoundTag(); n.putString("Kind", "item"); n.putLong("LocalPos", pos.asLong()); n.putInt("Slot", slot); n.putBoolean("External", !CartAuditContext.collectingLoot(CreateCartAudit.state(e).id));
        if (!stack.isEmpty()) n.put("Item", NbtSerde.snapshotItemStack(stack, e.registryAccess())); return n;
    }
    public static net.neoforged.neoforge.fluids.FluidStack[] fluids(net.neoforged.neoforge.fluids.capability.IFluidHandler handler) {
        var result=new net.neoforged.neoforge.fluids.FluidStack[handler.getTanks()];
        for(int i=0;i<result.length;i++)result[i]=handler.getFluidInTank(i).copy();
        return result;
    }
    public static void changedFluids(Ref ref,net.neoforged.neoforge.fluids.FluidStack[] before,net.neoforged.neoforge.fluids.capability.IFluidHandler handler) {
        var e=ref==null?null:ref.entity.get();if(e==null || !CreateCartAudit.enabled())return;
        for(int i=0;i<before.length;i++) {
            var after=handler.getFluidInTank(i);
            if(net.neoforged.neoforge.fluids.FluidStack.matches(before[i],after))continue;
            CompoundTag b=fluid(e,ref.pos,i,before[i]),a=fluid(e,ref.pos,i,after);
            CreateCartAudit.emit(e,ActionType.CART_CONTENT_CHANGE,"cargo",b,a,"fluid; local="+ref.pos.toShortString()+"; tank="+i);
        }
    }
    private static CompoundTag fluid(OrientedContraptionEntity e,BlockPos pos,int tank,net.neoforged.neoforge.fluids.FluidStack stack) {
        CompoundTag n=new CompoundTag();n.putString("Kind","fluid");n.putLong("LocalPos",pos.asLong());n.putInt("Tank",tank);n.putBoolean("External",true);
        if(!stack.isEmpty())n.put("Fluid",stack.save(e.registryAccess()));return n;
    }
    public static void clear() { MENUS.clear();OWNERS.clear(); }
}
