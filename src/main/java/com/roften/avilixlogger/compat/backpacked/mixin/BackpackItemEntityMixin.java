package com.roften.avilixlogger.compat.backpacked.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import com.roften.avilixlogger.core.ActionType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(ItemEntity.class)
public abstract class BackpackItemEntityMixin {
    @WrapMethod(method="hurt")
    private boolean avilixlogger$damage(DamageSource source,float amount,Operation<Boolean> original){
        var e=(ItemEntity)(Object)this;
        if(!BackpackAudit.enabled()||!(e.level() instanceof ServerLevel)||!BackpackAudit.isBag(e.getItem()))return original.call(source,amount);
        var live=BackpackAudit.itemRef(e);BackpackAudit.ensure(live,null,ActionType.BACKPACK_DISCOVER);
        var ref=new BackpackAudit.Ref(live.stack().copy(),live.level(),live.pos(),live.holder(),live.kind(),live.slot(),live.entity());
        boolean result=original.call(source,amount);
        if(e.isRemoved()&&e.getRemovalReason().shouldDestroy())BackpackAudit.lifecycle(ref,source.getEntity() instanceof Player p?p:null,ActionType.BACKPACK_DESTROY,"damage:"+source.getMsgId());
        return result;
    }
    @WrapMethod(method="playerTouch")
    private void avilixlogger$pickup(Player player,Operation<Void> original){
        var e=(ItemEntity)(Object)this;
        if(!BackpackAudit.enabled()||!(e.level() instanceof ServerLevel)||!BackpackAudit.isBag(e.getItem())){original.call(player);return;}
        var live=BackpackAudit.itemRef(e);BackpackAudit.ensure(live,null,ActionType.BACKPACK_DISCOVER);
        var before=new BackpackAudit.Ref(e.getItem().copy(),live.level(),live.pos(),live.holder(),live.kind(),live.slot(),live.entity());
        int count=e.getItem().getCount();original.call(player);
        if(e.isRemoved()||e.getItem().getCount()<count){
            BackpackAudit.lifecycle(new BackpackAudit.Ref(before.stack(),before.level(),player.blockPosition(),player instanceof net.minecraft.server.level.ServerPlayer p?p:null,"inventory",-1,null),player,ActionType.BACKPACK_PICKUP,"item_pickup");
            if(player instanceof net.minecraft.server.level.ServerPlayer p)BackpackAudit.trackPlayer(p,ActionType.BACKPACK_TRANSFER);
        }
    }
    @WrapMethod(method="tick")
    private void avilixlogger$expiry(Operation<Void> original){
        var e=(ItemEntity)(Object)this;
        if(!BackpackAudit.enabled()||!(e.level() instanceof ServerLevel)||!BackpackAudit.isBag(e.getItem())){original.call();return;}
        var ref=BackpackAudit.itemRef(e);original.call();
        if(e.isRemoved()&&e.getRemovalReason().shouldDestroy()){
            var index=com.roften.avilixlogger.compat.backpacked.BackpackIndex.get(ref.level().getServer());
            var r=index==null?null:index.record(com.roften.avilixlogger.core.BackpackIds.id(ref.stack()));
            if(r==null||!r.lost())BackpackAudit.lifecycle(ref,null,ActionType.BACKPACK_DESTROY,"despawn_or_void");
        }
    }
}
