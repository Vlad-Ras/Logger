package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/** A replace operation consumes an identified original, never creates a second copy. Server-thread only. */
final class CartStateRecoveryOrigin {
    enum Mode { MISSING, HELD, WORLD }
    final Mode mode;final int slot;final ItemStack expected;final Entity entity,base;final UUID id;
    private boolean locked;
    private CartStateRecoveryOrigin(Mode mode,int slot,ItemStack expected,Entity entity,Entity base,UUID id){this.mode=mode;this.slot=slot;this.expected=expected;this.entity=entity;this.base=base;this.id=id;}
    static CartStateRecoveryOrigin find(ServerPlayer actor,UUID id,Mode mode){
        if(mode==Mode.HELD){int slot=actor.getInventory().selected;var stack=actor.getInventory().getItem(slot);if(stack.getCount()!=1||!id.equals(CreateCartAudit.itemId(stack)))throw new IllegalStateException("Возьмите в основную руку единственный предмет этой штуковины");return new CartStateRecoveryOrigin(mode,slot,stack.copy(),null,null,id);}
        if(mode==Mode.WORLD){Entity entity=CreateCartAudit.loaded(id);if(entity!=null){validateEntity(entity);return new CartStateRecoveryOrigin(mode,-1,ItemStack.EMPTY,entity,entity.getVehicle(),id);}
            var drops=CartItemTracker.loaded(id);if(drops.size()!=1)throw new IllegalStateException("Нужна одна загруженная конструкция или один предмет-дроп этого ID. Предмет из сундука возьмите в руку");var drop=drops.getFirst();if(drop.getItem().getCount()!=1)throw new IllegalStateException("Дроп содержит несколько копий");return new CartStateRecoveryOrigin(mode,-1,drop.getItem().copy(),drop,null,id);}
        return new CartStateRecoveryOrigin(mode,-1,ItemStack.EMPTY,null,null,id);
    }
    private static void validateEntity(Entity entity){
        if(!(entity instanceof OrientedContraptionEntity oriented)||!(entity.getVehicle() instanceof AbstractMinecart cart)||cart.isRemoved()||oriented.getCouplingId()!=null||!entity.getPassengers().isEmpty()||cart.getPassengers().size()!=1||cart.getPassengers().getFirst()!=entity||!((com.roften.avilixlogger.compat.create.mixin.CartContraptionAccessor)oriented.getContraption()).avilixlogger$subContraptions().isEmpty())throw new IllegalStateException("Сцепка, вложенная конструкция или пассажиры требуют совместного восстановления");
        if(cart instanceof net.minecraft.world.Container container&&!container.isEmpty())throw new IllegalStateException("Освободите инвентарь самой базовой вагонетки; штатный предмет Create его не сохраняет");
    }
    void validate(ServerPlayer actor){
        if(mode==Mode.HELD&&(actor.getInventory().selected!=slot||!ItemStack.matches(expected,actor.getInventory().getItem(slot))))throw new IllegalStateException("Предмет в руке изменился; запросите предпросмотр снова");
        Entity live=CreateCartAudit.loaded(id);if(live!=entity&&live!=null)throw new IllegalStateException("Найдена другая конструкция этого ID");
        if(entity!=null){if(entity.isRemoved()||entity.level().getServer()!=actor.getServer())throw new IllegalStateException("Оригинал выгружен или удалён");if(entity instanceof ItemEntity drop){if(!ItemStack.matches(expected,drop.getItem()))throw new IllegalStateException("Предмет-дроп изменился");}else{validateEntity(entity);if(entity.getVehicle()!=base)throw new IllegalStateException("Базовая вагонетка изменилась");}}
        for(var drop:CartItemTracker.loaded(id))if(drop!=entity)throw new IllegalStateException("Найден дополнительный предмет-дроп этого ID");
        for(var player:actor.getServer().getPlayerList().getPlayers()){
            for(var inv:List.of(player.getInventory(),player.getEnderChestInventory()))for(int i=0;i<inv.getContainerSize();i++)if(id.equals(CreateCartAudit.itemId(inv.getItem(i)))&&!(mode==Mode.HELD&&player==actor&&inv==actor.getInventory()&&i==slot))throw new IllegalStateException("Этот ID найден в инвентаре "+player.getGameProfile().getName());
            if(id.equals(CreateCartAudit.itemId(player.containerMenu.getCarried())))throw new IllegalStateException("Предмет находится на курсоре игрока");
        }
    }
    CompoundTag backup(ServerPlayer actor){
        var tag=new CompoundTag();tag.putString("Mode",mode.name());
        if(mode==Mode.HELD||entity instanceof ItemEntity){tag.put("Item",NbtSerde.snapshotItemStack(expected,actor.registryAccess()));tag.putInt("Slot",slot);}
        else if(entity instanceof OrientedContraptionEntity oriented)tag.put("Original",CreateCartAudit.snapshot(oriented,null));return tag;
    }
    void lock(){if(mode!=Mode.WORLD)return;CartAuditContext.rollbackActive=true;locked=true;
        CartRestoreLocks.keepLoaded((ServerLevel)entity.level(),entity.blockPosition());CartAuditContext.LOCKED_ENTITIES.add(entity.getUUID());
        if(base!=null){CartAuditContext.LOCKED_ENTITIES.add(base.getUUID());CreateCartAudit.state(entity).locked=true;}
    }
    void unlock(){if(!locked)return;if(entity!=null&&CreateCartAudit.state(entity)!=null)CreateCartAudit.state(entity).locked=false;CartAuditContext.LOCKED_ENTITIES.clear();CartRestoreLocks.clear();CartAuditContext.rollbackActive=false;locked=false;}
    void deliver(ServerPlayer actor,ItemStack stack){
        int target=mode==Mode.HELD?slot:actor.getInventory().getFreeSlot();if(target<0)throw new IllegalStateException("Освободите слот инвентаря");
        validate(actor);
        boolean restoring=CartAuditContext.restoring(true);
        try{
            if(entity instanceof ItemEntity drop)drop.discard();
            else if(entity!=null){((CartEntityAccess)entity).avilixlogger$discardForRollback();base.discard();}
            actor.getInventory().setItem(target,stack);actor.getInventory().setChanged();actor.containerMenu.broadcastChanges();
        }finally{CartAuditContext.restoring(restoring);}
    }
    String description(){return switch(mode){case MISSING->"возврат пропавшей; оригинал не найден среди загруженных объектов и онлайн-инвентарей";case HELD->"ЗАМЕНА предмета в вашей основной руке; текущее содержимое заменяется выбранным состоянием";case WORLD->"ЗАМЕНА загруженной конструкции/дропа предметом вам; оригинал и базовая вагонетка удаляются без дропа, текущее содержимое заменяется выбранным состоянием";};}
}
