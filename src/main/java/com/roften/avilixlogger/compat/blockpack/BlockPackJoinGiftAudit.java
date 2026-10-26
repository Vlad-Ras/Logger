package com.roften.avilixlogger.compat.blockpack;

import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/** Bounded counts around the native grant; no tick polling, capability lookup or received UUID-set snapshot. */
public final class BlockPackJoinGiftAudit {
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private BlockPackJoinGiftAudit(){}
    public static Scope begin(ServerPlayer player,ItemStack requested){
        if(!CaptureTypePolicy.enabled(4)||player==null||!player.server.isSameThread()||!ItemTransactionAudit.enabled()||requested==null||requested.isEmpty())return null;
        try {var tag=ItemTransactionAudit.item(requested,player.registryAccess());if(tag==null)return null;var scope=new Scope(player,ItemTransactionAudit.party(player),tag,CURRENT.get());CURRENT.set(scope);return scope;}
        catch(RuntimeException|LinkageError failure){AdaptiveLogDiagnostics.adapterFailure("blockpack-gift-before");return null;}
    }
    public static long count(net.minecraft.world.entity.player.Inventory inventory,ItemStack prototype){
        if((long)inventory.items.size()+inventory.armor.size()+inventory.offhand.size()>4096)throw new IllegalStateException("Unexpected gift inventory size");
        return countStacks(inventory.items,prototype)+countStacks(inventory.armor,prototype)+countStacks(inventory.offhand,prototype);
    }
    private static long countStacks(java.util.List<ItemStack> stacks,ItemStack prototype){long result=0;for(int i=0;i<stacks.size();i++){var stack=stacks.get(i);if(ItemStack.isSameItemSameComponents(stack,prototype))result+=stack.getCount();}return result;}
    public static Scope current(){return CURRENT.get();}
    public static boolean active(){return CURRENT.get()!=null;}
    public static final class Scope implements AutoCloseable {
        private final ServerPlayer player;private final ItemTransactionAudit.Party recipient;private final CompoundTag requested;private final Scope previous;
        private int inserted;private boolean sawAdd,valid=true,closed;private ItemTransactionAudit.Drop drop;
        Scope(ServerPlayer player,ItemTransactionAudit.Party recipient,CompoundTag requested,Scope previous){this.player=player;this.recipient=recipient;this.requested=requested;this.previous=previous;}
        public void invalid(){valid=false;}
        public void added(int offered,long before,long after){sawAdd=true;if(offered!=requested.getInt("count")||after<before||after-before>offered){valid=false;return;}inserted=(int)(after-before);}
        public void dropped(ItemEntity entity){
            if(!ItemTransactionAudit.enabled())return;
            try {boolean accepted=entity!=null&&!entity.isRemoved()&&entity.level()==player.level()&&player.serverLevel().getEntity(entity.getUUID())==entity;
                drop=entity==null?new ItemTransactionAudit.Drop(null,false,null,recipient.dim(),recipient.x(),recipient.y(),recipient.z()):new ItemTransactionAudit.Drop(entity.getUUID(),accepted,ItemTransactionAudit.item(entity.getItem(),player.registryAccess()),entity.level().dimension().location().toString(),entity.getX(),entity.getY(),entity.getZ());
            }catch(RuntimeException|LinkageError failure){valid=false;AdaptiveLogDiagnostics.adapterFailure("blockpack-gift-drop");}
        }
        public void finish(boolean completed){
            if(closed)return;
            try {boolean enabled=ItemTransactionAudit.enabled();if(enabled&&(!valid||!sawAdd))AdaptiveLogDiagnostics.adapterFailure("blockpack-gift-invariant");
                if(enabled&&valid&&sawAdd){var row=ItemTransactionAudit.gift(recipient,requested,inserted,drop,completed);close();if(row!=null)LoggerRuntime.storage(player.serverLevel()).append(row);}}
            catch(RuntimeException|LinkageError failure){AdaptiveLogDiagnostics.adapterFailure("blockpack-gift-after");}
            finally{close();}
        }
        public void close(){if(closed)return;closed=true;if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
}
