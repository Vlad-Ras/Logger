package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.item.ItemEntity;
import java.util.*;

/** Real Create entities, inventory replacement and drop consumption, without authorisation bypass. */
public final class CartStateRecoveryNativeSmoke {
    public static void run(MinecraftServer server,CompoundTag snapshot)throws Exception{
        var level=server.overworld();var actor=net.neoforged.neoforge.common.util.FakePlayerFactory.get(level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"state_recipient"));
        var item=CartRecoveryPlan.fromSnapshot(snapshot.getCompound("Entity").getCompound("Contraption").getCompound(CartAuditState.KEY).getUUID("Id"),1,0,"after",snapshot);
        var stack=ItemStack.CODEC.parse(level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),item.item()).getOrThrow();
        try{
            actor.getInventory().clearContent();actor.getInventory().selected=0;actor.getInventory().setItem(0,stack.copy());
            var held=CartStateRecoveryOrigin.find(actor,item.id(),CartStateRecoveryOrigin.Mode.HELD);held.validate(actor);check(held.backup(actor).contains("Item"),"original held item not backed up");
            for(int i=1;i<36;i++)actor.getInventory().setItem(i,new ItemStack(net.minecraft.world.item.Items.STONE,64));check(actor.getInventory().getFreeSlot()<0,"full-inventory fixture");
            var replacement=stack.copy();replacement.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Restored"));held.deliver(actor,replacement);check(ItemStack.matches(replacement,actor.getInventory().getItem(0)),"held replacement not delivered");
            reject(()->held.validate(actor),"stale held preview accepted");
            actor.getInventory().clearContent();
            var staged=new StagedCartRestore(snapshot);while(!staged.step(level)){}staged.cart.setUUID(UUID.randomUUID());staged.entity.setUUID(UUID.randomUUID());staged.cart.setPos(level.getSharedSpawnPos().above(10).getCenter());staged.entity.setPos(staged.cart.position());staged.entity.startRiding(staged.cart,true);
            check(level.addFreshEntity(staged.cart)&&level.addFreshEntity(staged.entity),"live source spawn");CreateCartAudit.attach(staged.entity);
            reject(()->CartStateRecoveryOrigin.find(actor,item.id(),CartStateRecoveryOrigin.Mode.MISSING).validate(actor),"missing mode duplicated live original");
            var live=CartStateRecoveryOrigin.find(actor,item.id(),CartStateRecoveryOrigin.Mode.WORLD);
            try{live.validate(actor);live.lock();check(CreateCartAudit.state(staged.entity).locked,"live source not locked");check(live.backup(actor).contains("Original"),"live source not backed up");live.deliver(actor,stack.copy());check(staged.entity.isRemoved()&&staged.cart.isRemoved(),"original contraption/base survived item grant");check(item.id().equals(CreateCartAudit.itemId(actor.getInventory().getItem(0))),"live recovery wrong recipient/item");}
            finally{live.unlock();if(!staged.entity.isRemoved())((CartEntityAccess)staged.entity).avilixlogger$discardForRollback();if(!staged.cart.isRemoved())staged.cart.discard();}
            actor.getInventory().clearContent();var drop=new ItemEntity(level,0,128,0,stack.copy());check(level.addFreshEntity(drop),"drop spawn");seedDrop(drop,item.id());
            var origin=CartStateRecoveryOrigin.find(actor,item.id(),CartStateRecoveryOrigin.Mode.WORLD);origin.deliver(actor,stack.copy());check(drop.isRemoved()&&!actor.getInventory().getItem(0).isEmpty(),"drop original not consumed");
            actor.getInventory().clearContent();
            System.out.println("CART_STATE_REPLACEMENT_NATIVE_OK: held-full-inventory+stale-preview+live-original-duplicate-guard+live-contraption+base-cart+drop+original-backup+recipient-inventory; no world-action rollback");
        }finally{actor.getInventory().clearContent();}
    }
    @SuppressWarnings("unchecked") private static void seedDrop(ItemEntity drop,UUID id)throws Exception{
        var type=Class.forName(CartItemTracker.class.getName()+"$Entry");var ctor=type.getDeclaredConstructor(UUID.class,java.lang.ref.WeakReference.class);ctor.setAccessible(true);
        var entities=CartItemTracker.class.getDeclaredField("ENTITIES");entities.setAccessible(true);((Map<UUID,Object>)entities.get(null)).put(drop.getUUID(),ctor.newInstance(id,new java.lang.ref.WeakReference<>(drop)));
        var carts=CartItemTracker.class.getDeclaredField("CARTS");carts.setAccessible(true);((Map<UUID,Set<UUID>>)carts.get(null)).computeIfAbsent(id,k->new HashSet<>()).add(drop.getUUID());
    }
    private static void reject(Runnable r,String message){try{r.run();}catch(IllegalStateException expected){return;}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
