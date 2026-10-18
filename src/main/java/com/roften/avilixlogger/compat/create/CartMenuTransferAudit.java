package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.NbtSerde;
import com.roften.avilixlogger.core.CartAuditContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** Capture only while a player clicks a cart menu. No polling on ordinary ticks. */
public final class CartMenuTransferAudit {
    private static Scope current;
    private CartMenuTransferAudit() {}
    public static Scope begin(ServerPlayer player) {
        return begin(player,false);
    }
    public static Scope beginClose(ServerPlayer player){return begin(player,true);}
    private static Scope begin(ServerPlayer player,boolean closing){
        if (current != null || !CreateCartAudit.enabled()) return null;
        var ref = closing?CartStorageAudit.closingMenuRef(player.containerMenu):CartStorageAudit.menuRef(player.containerMenu);
        var entity = ref == null ? null : ref.entity().get();
        if (entity == null || !closing&&entity.isRemoved()) return null;
        Scope scope=new Scope(player,ref,closing);scope.previous=CartAuditContext.enter(CreateCartAudit.state(entity).stamp("menu_transfer"));scope.entered=true;return current=scope;
    }
    static boolean capture(CartStorageAudit.Ref ref, CompoundTag before, CompoundTag after, String detail) {
        Scope scope = current;
        if (scope == null || scope.ref.entity().get() != ref.entity().get()) return false;
        if(covered(ref,after.getInt("Slot")))return true;
        scope.complete=false;
        // Keep the producer bounded even for a pathological custom menu. An incomplete group
        // remains in the audit trail but is deliberately ineligible for rollback.
        if (scope.changes.size() >= 512) { scope.complete = false; return false; }
        scope.changes.add(new Change(before, after, detail));
        return true;
    }
    static boolean covered(CartStorageAudit.Ref ref,int slot){Scope scope=current;return scope!=null && scope.ref.entity().get()==ref.entity().get() && scope.addresses.contains(new SlotKey(ref.base(),ref.pos().asLong(),slot));}
    private record SlotKey(boolean base,long position,int slot){}
    private record Change(CompoundTag before, CompoundTag after, String detail) {}
    private record Address(CartStorageAudit.Ref ref,int slot) {}
    private record Tracked(CartStorageAudit.Ref ref,int localSlot,net.minecraft.world.inventory.Slot view,ItemStack before) {}
    private static Address address(Object handler,int slot,int depth){
        var ref=CartStorageAudit.ref(handler);if(ref!=null)return new Address(ref,slot);
        if(depth<8 && handler instanceof com.roften.avilixlogger.mixin.CartCombinedInventoryAccessor combined){
            int index=combined.avilixlogger$index(slot);if(index>=0)return address(combined.avilixlogger$handler(index),combined.avilixlogger$slot(slot,index),depth+1);
        }
        return null;
    }
    public static final class Scope {
        private final ServerPlayer player;
        private final CartStorageAudit.Ref ref;
        private final UUID id = UUID.randomUUID();
        private final List<Change> changes = new ArrayList<>();
        private final List<Tracked> tracked=new ArrayList<>();
        private final Set<SlotKey> addresses=new HashSet<>();
        private final ItemStack[] inventory;
        private final ItemStack cursor;
        private final net.minecraft.world.inventory.AbstractContainerMenu menu;
        private boolean complete = true;
        private CartAuditContext.Stamp previous;private boolean entered;
        private Scope(ServerPlayer player, CartStorageAudit.Ref ref) {
            this(player,ref,false);
        }
        private Scope(ServerPlayer player, CartStorageAudit.Ref ref,boolean closing) {
            this.player = player; this.ref = ref; menu = player.containerMenu;
            inventory = new ItemStack[player.getInventory().getContainerSize()];
            for (int i=0; i<inventory.length; i++) inventory[i]=player.getInventory().getItem(i).copy();
            cursor = menu.getCarried().copy();
            if(closing)return;
            for(var view:menu.slots){
                if(view.container==player.getInventory())continue;
                Object handler=view.container;
                if(handler instanceof com.roften.avilixlogger.compat.create.mixin.CartMenuContainerAccessor wrapper)handler=wrapper.avilixlogger$handler();
                else if(view instanceof net.neoforged.neoforge.items.SlotItemHandler wrapped)handler=wrapped.getItemHandler();
                var address=address(handler,view.getContainerSlot(),0);
                if(address==null || address.ref.entity().get()!=ref.entity().get() || tracked.size()>=512){complete=false;continue;}
                if(addresses.add(new SlotKey(address.ref.base(),address.ref.pos().asLong(),address.slot)))tracked.add(new Tracked(address.ref,address.slot,view,view.getItem().copy()));
            }
        }
        public void finish(boolean success) {
            finish(success,false);
        }
        public void finish(boolean success,boolean closing){
            current = null;
            try{finishSnapshots(success,closing);}finally{if(entered)CartAuditContext.restore(previous);}
        }
        private void finishSnapshots(boolean success,boolean closing){
            for(var tracked:tracked){
                var now=tracked.view.getItem();if(ItemStack.matches(tracked.before,now))continue;
                var entity=ref.entity().get();if(entity==null)return;
                var b=CartStorageAudit.item(entity,tracked.ref.pos(),tracked.localSlot,tracked.before);var a=CartStorageAudit.item(entity,tracked.ref.pos(),tracked.localSlot,now);
                if(b.getBoolean("Incomplete")||a.getBoolean("Incomplete"))complete=false;
                b.putBoolean("BaseCart",tracked.ref.base());a.putBoolean("BaseCart",tracked.ref.base());changes.add(new Change(b,a,"menu item; local="+tracked.ref.pos().toShortString()+"; slot="+tracked.localSlot));
            }
            var entity = ref.entity().get();
            if (entity == null) return;
            CompoundTag before = envelope(), after = envelope();
            boolean valid = success && complete && (menu == player.containerMenu || closing && player.containerMenu==player.inventoryMenu && menu.getCarried().isEmpty()) && inventory.length == player.getInventory().getContainerSize();
            ListTag bslots=new ListTag(), aslots=new ListTag();
            for (int i=0; i<inventory.length; i++) {
                ItemStack now=player.getInventory().getItem(i);
                if (!ItemStack.matches(inventory[i],now)) { bslots.add(slot(i,inventory[i])); aslots.add(slot(i,now)); }
            }
            if (!ItemStack.matches(cursor,menu.getCarried())) { bslots.add(slot(-1,cursor)); aslots.add(slot(-1,menu.getCarried())); }
            if(changes.isEmpty()&&bslots.isEmpty())return;
            before.put("Slots",bslots); after.put("Slots",aslots);
            before.putBoolean("Complete",valid && complete);after.putBoolean("Complete",valid && complete);
            for (int i=0; i<changes.size(); i++) {
                var change=changes.get(i);
                for (CompoundTag tag : new CompoundTag[]{change.before,change.after}) { tag.putUUID("Transfer",id); tag.putInt("TransferIndex",i); }
                CreateCartAudit.emit(entity,ActionType.CART_CONTENT_CHANGE,"cargo",change.before,change.after,change.detail);
            }
            CreateCartAudit.emit(entity,ActionType.CART_TRANSFER,"menu_transfer",before,after,"player="+player.getUUID()+"; changes="+changes.size());
        }
        private CompoundTag envelope() {
            CompoundTag tag=new CompoundTag(); tag.putInt("Format",1);tag.putString("Kind","menu_items");
            tag.putUUID("Transfer",id);tag.putUUID("Player",player.getUUID());tag.putInt("Changes",changes.size());return tag;
        }
        private CompoundTag slot(int index,ItemStack stack) {
            CompoundTag tag=new CompoundTag();tag.putInt("Slot",index);
            if (!stack.isEmpty()) {var item=NbtSerde.snapshotItemStack(stack,player.registryAccess());if(item==null)complete=false;else tag.put("Item",item);}return tag;
        }
    }
}
