package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.CartAuditContext;
import com.roften.avilixlogger.core.NbtSerde;
import com.roften.avilixlogger.core.CreateContraptionKind;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/** O(1) storage attribution; no per-tick inventory polling. Main-thread only. */
public final class CartStorageAudit {
    public record Ref(WeakReference<AbstractContraptionEntity> entity, BlockPos pos,boolean base) {}
    private static final Map<Object, Ref> OWNERS = new WeakHashMap<>();
    private record Alias(WeakReference<AbstractContraptionEntity> entity, WeakReference<Object> manager, boolean aggregate) {}
    private static final Map<Object, Alias> ALIASES=new WeakHashMap<>();
    private static final Map<net.minecraft.world.inventory.AbstractContainerMenu,Ref> MENUS=new WeakHashMap<>();
    private record Bound(WeakReference<Object> contraption, WeakReference<Object> manager, WeakReference<Object> items, WeakReference<Object> fluids, WeakReference<Object> vehicle) {}
    private static final Map<AbstractContraptionEntity, Bound> BOUND = new WeakHashMap<>();
    private static volatile net.minecraft.server.MinecraftServer activeServer;
    private CartStorageAudit() {}
    /** Constant-time identity checks; rebinding scans addresses only when wrappers change. */
    public static void ensureBound(AbstractContraptionEntity entity) {
        if (!(entity.level() instanceof net.minecraft.server.level.ServerLevel level) || !level.getServer().isSameThread() || entity.getContraption() == null) return;
        var previous = BOUND.get(entity);
        if (previous == null || previous.contraption.get() != entity.getContraption()) { bind(entity); return; }
        var storage = entity.getContraption().getStorage();
        if (previous.manager.get() == storage && previous.items.get() == storage.getAllItems()
                && previous.fluids.get() == storage.getFluids() && previous.vehicle.get() == entity.getVehicle()) return;
        bind(entity);
    }
    public static void menu(net.minecraft.world.inventory.AbstractContainerMenu menu,Ref ref){MENUS.put(menu,ref);}
    public static Ref menuRef(net.minecraft.world.inventory.AbstractContainerMenu menu) {
        Ref ref=MENUS.get(menu);if(ref!=null)return ref;
        for(var slot:menu.slots){ref=ref(slot.container);if(ref!=null)return ref;}
        return null;
    }
    public static Ref closingMenuRef(net.minecraft.world.inventory.AbstractContainerMenu menu){
        Ref ref=MENUS.get(menu);if(ref!=null)return ref;
        for(var slot:menu.slots){ref=OWNERS.get(slot.container);if(ref!=null && ref.entity.get()!=null)return ref;}
        return null;
    }
    public static void lockMenus(java.util.UUID id,net.minecraft.server.MinecraftServer server){
        for(var player:server.getPlayerList().getPlayers()) {
            var ref=MENUS.get(player.containerMenu);var entity=ref==null?null:ref.entity.get();
            boolean baseMenu=false;
            if(!baseMenu)for(var slot:player.containerMenu.slots) {
                if(slot.container instanceof net.minecraft.world.entity.Entity base && base.getPersistentData().getCompound(CartAuditState.KEY).hasUUID("Id") && id.equals(base.getPersistentData().getCompound(CartAuditState.KEY).getUUID("Id"))){baseMenu=true;break;}
            }
            if(baseMenu || entity!=null && id.equals(CreateContraptionSources.id(entity))) {
                com.roften.avilixlogger.core.CartRestoreLocks.menu(player.containerMenu);
            }
        }
    }
    public static void closeLockedMenus(net.minecraft.server.MinecraftServer server) {
        for(var player:server.getPlayerList().getPlayers())if(com.roften.avilixlogger.core.CartRestoreLocks.menuLocked(player.containerMenu) && menuRef(player.containerMenu)!=null)player.closeContainer();
    }
    public static void bind(AbstractContraptionEntity entity) {
        if (!(entity.level() instanceof net.minecraft.server.level.ServerLevel level) || !level.getServer().isSameThread() || entity.getContraption() == null
                || CreateContraptionSources.kind(entity) == CreateContraptionKind.UNKNOWN) return;
        activeServer=level.getServer();
        if(entity.getVehicle() instanceof net.minecraft.world.Container)OWNERS.put(entity.getVehicle(),new Ref(new WeakReference<>(entity),BlockPos.ZERO,true));
        var storage = entity.getContraption().getStorage();
        alias(storage.getAllItems(),entity);alias(storage.getFluids(),entity);
        storage.getAllItemStorages().forEach((pos, handler) -> OWNERS.put(handler, new Ref(new WeakReference<>(entity), pos,false)));
        storage.getFluids().storages.forEach((pos, handler) -> OWNERS.put(handler, new Ref(new WeakReference<>(entity), pos,false)));
        BOUND.put(entity, new Bound(new WeakReference<>(entity.getContraption()), new WeakReference<>(storage), new WeakReference<>(storage.getAllItems()), new WeakReference<>(storage.getFluids()), new WeakReference<>(entity.getVehicle())));
    }
    public static Ref ref(Object handler) {
        if (!serverThread() || CartAuditContext.restoring()) return null;
        Ref ref = OWNERS.get(handler);
        var entity = ref == null ? null : ref.entity.get();
        if (entity == null || entity.isRemoved() || entity.level().isClientSide || entity.getContraption() == null) return null;
        if (ref.base) return entity.getVehicle() == handler ? ref : null;
        var storage = entity.getContraption().getStorage();
        return storage.getAllItemStorages().get(ref.pos) == handler || storage.getFluids().storages.get(ref.pos) == handler ? ref : null;
    }
    public static void alias(Object handler,AbstractContraptionEntity entity){
        if(handler==null||!serverThread())return;var storage=entity.getContraption().getStorage();
        ALIASES.put(handler,new Alias(new WeakReference<>(entity),new WeakReference<>(storage),handler==storage.getAllItems()||handler==storage.getFluids()));
    }
    public static AbstractContraptionEntity owner(Object handler){
        if(!serverThread())return null;
        var ref=ref(handler);if(ref!=null)return ref.entity.get();
        var alias=ALIASES.get(handler);var entity=alias==null?null:alias.entity.get();
        if(entity==null||entity.isRemoved()||entity.getContraption()==null)return null;
        var storage=entity.getContraption().getStorage();
        if(storage!=alias.manager.get()||alias.aggregate&&handler!=storage.getAllItems()&&handler!=storage.getFluids())return null;
        return entity;
    }
    public static boolean capturing(Ref ref) {
        var entity=ref==null?null:ref.entity.get();return entity!=null&&CreateCargoAudit.active(entity);
    }
    public static boolean locked(Ref ref) { var e = ref == null ? null : ref.entity.get(); return e != null && (CreateCargoAudit.restoreLocked(e) || CreateCartAudit.state(e) != null && CreateCartAudit.state(e).locked); }
    public static void changed(Ref ref, int slot, ItemStack before, ItemStack after) {
        var e = ref == null ? null : ref.entity.get();
        if (e == null || ItemStack.matches(before, after) || !CreateCargoAudit.active(e)) return;
        if(CartMenuTransferAudit.covered(ref,slot))return;
        CompoundTag b = item(e, ref.pos, slot, before), a = item(e, ref.pos, slot, after);b.putBoolean("BaseCart",ref.base);a.putBoolean("BaseCart",ref.base);
        if(CartMenuTransferAudit.capture(ref,b,a,"item; local="+ref.pos.toShortString()+"; slot="+slot))return;
        if(CartAutomationAudit.capture(ref,b,a,"item; local="+ref.pos.toShortString()+"; slot="+slot))return;
        if(CreateCargoAudit.capture(e,b,a))return;
        CreateCargoAudit.emit(e, ActionType.CART_CONTENT_CHANGE, "cargo", b, a, "item; local=" + ref.pos.toShortString() + "; slot=" + slot);
    }
    public static CompoundTag item(AbstractContraptionEntity e, BlockPos pos, int slot, ItemStack stack) {
        CompoundTag n = new CompoundTag(); n.putString("Kind", "item"); n.putLong("LocalPos", pos.asLong()); n.putInt("Slot", slot); n.putBoolean("External", !CreateCargoAudit.internal(e));
        if (!stack.isEmpty()){var item=NbtSerde.snapshotItemStack(stack,e.registryAccess());if(item==null)n.putBoolean("Incomplete",true);else n.put("Item",item);}return n;
    }
    public static net.neoforged.neoforge.fluids.FluidStack[] fluids(net.neoforged.neoforge.fluids.capability.IFluidHandler handler) {
        var result=new net.neoforged.neoforge.fluids.FluidStack[handler.getTanks()];
        for(int i=0;i<result.length;i++)result[i]=handler.getFluidInTank(i).copy();
        return result;
    }
    public static void changedFluids(Ref ref,net.neoforged.neoforge.fluids.FluidStack[] before,net.neoforged.neoforge.fluids.capability.IFluidHandler handler) {
        var e=ref==null?null:ref.entity.get();if(e==null || !CreateCargoAudit.active(e))return;
        for(int i=0;i<before.length;i++) {
            var after=handler.getFluidInTank(i);
            if(net.neoforged.neoforge.fluids.FluidStack.matches(before[i],after))continue;
            CompoundTag b=fluid(e,ref.pos,i,before[i]),a=fluid(e,ref.pos,i,after);
            if(CreateCargoAudit.capture(e,b,a))continue;
            if(CartAutomationAudit.capture(ref,b,a,"fluid; local="+ref.pos.toShortString()+"; tank="+i))continue;
            CreateCargoAudit.emit(e,ActionType.CART_CONTENT_CHANGE,"cargo",b,a,"fluid; local="+ref.pos.toShortString()+"; tank="+i);
        }
    }
    private static CompoundTag fluid(AbstractContraptionEntity e,BlockPos pos,int tank,net.neoforged.neoforge.fluids.FluidStack stack) {
        CompoundTag n=new CompoundTag();n.putString("Kind","fluid");n.putLong("LocalPos",pos.asLong());n.putInt("Tank",tank);n.putBoolean("External",true);
        if(!stack.isEmpty())n.put("Fluid",stack.save(e.registryAccess()));return n;
    }
    private static boolean serverThread(){var server=activeServer;return server!=null&&server.isSameThread();}
    public static void clear() { activeServer=null;MENUS.clear();OWNERS.clear();ALIASES.clear();BOUND.clear();CreateCargoAudit.clear();CreateStructureAudit.clear();CartAutomationAudit.clear(); }
}
