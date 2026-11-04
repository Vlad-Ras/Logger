package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.minecart.CouplingHandler;
import com.simibubi.create.content.contraptions.minecart.capability.MinecartController;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.nbt.*;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import java.util.*;

/** Sparse controller changes grouped across both ends and native chain reversal. */
public final class CreateCouplingAudit {
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private record State(CompoundTag before,AbstractMinecart cart,AbstractContraptionEntity construction) {}
    private CreateCouplingAudit() {}
    public static boolean locked(AbstractMinecart cart){
        if(cart==null||!CartAuditContext.rollbackActive||CartAuditContext.restoring())return false;
        var passenger=CreateCartAudit.passenger(cart);
        return CartAuditContext.LOCKED_ENTITIES.contains(cart.getUUID())||passenger!=null&&CreateCargoAudit.restoreLocked(passenger);
    }
    /** Only on a coupling action during rollback; never scan the world or poll idle chains. */
    public static boolean lockedChain(MinecartController controller){
        if(!CartAuditContext.rollbackActive||CartAuditContext.restoring()||controller==null||controller.isEmpty())return false;
        var pending=new ArrayDeque<MinecartController>();var seen=new HashSet<UUID>();pending.add(controller);
        while(!pending.isEmpty()){
            var current=pending.removeFirst();var cart=current.cart();if(cart==null||!seen.add(cart.getUUID()))continue;
            if(seen.size()>256||locked(cart))return true;
            for(boolean main:new boolean[]{true,false}){var id=current.getCoupledCart(main);if(id==null)continue;
                if(CartAuditContext.LOCKED_ENTITIES.contains(id))return true;
                var peer=com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController.getIfPresent(cart.level(),id);
                if(peer!=null&&!peer.isEmpty())pending.addLast(peer);
            }
        }return false;
    }
    public static Scope begin(){if(CURRENT.get()!=null)return null;var scope=new Scope();CURRENT.set(scope);return scope;}
    public static Scope controller(MinecartController controller,boolean chain){
        var cart=controller.cart();if(CreateStructuralAudit.cartCovered(controller)||cart==null||cart.level().isClientSide||CartAuditContext.restoring())return null;var entity=cart==null?null:CreateCartAudit.passenger(cart);
        if(CURRENT.get()==null&&(entity==null||!CreateCargoAudit.active(entity)))return null;
        var own=begin();var scope=CURRENT.get();scope.add(controller);
        if(chain){boolean forward=controller.isLeadingCoupling();var current=controller;var seen=new HashSet<UUID>();
            while(current!=null&&!current.isEmpty()){
                var member=current.cart();if(member==null||!seen.add(member.getUUID()))break;
                if(seen.size()>256){scope.complete=false;break;}scope.add(current);
                current=CouplingHandler.getNextInCouplingChain(cart.level(),current,forward);
            }
        }return own;
    }
    public static CompoundTag data(MinecartController controller){
        var cart=controller.cart();var nativeTag=controller.serializeNBT(cart.registryAccess());var tag=new CompoundTag();
        tag.putUUID("CartEntity",cart.getUUID());tag.putString("Dimension",cart.level().dimension().location().toString());
        tag.putString("CartType",net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(cart.getType()).toString());tag.put("Native",nativeTag.copy());
        var passenger=CreateCartAudit.passenger(cart);
        if(passenger!=null){tag.putUUID("Construction",CreateContraptionSources.id(passenger));tag.putUUID("ConstructionEntity",passenger.getUUID());
            if(passenger instanceof com.simibubi.create.content.contraptions.OrientedContraptionEntity oriented&&oriented.getCouplingId()!=null)tag.putUUID("CouplingId",oriented.getCouplingId());}

        for(String field:List.of("MainCoupling","ConnectedCoupling"))if(nativeTag.contains(field,10))tag.put(field,nativeTag.getCompound(field).copy());
        return tag;
    }
    public static final class Scope implements AutoCloseable {
        final UUID operation=UUID.randomUUID();final Map<MinecartController,State> states=new LinkedHashMap<>();boolean complete=true,success,closed;
        void add(MinecartController controller){if(states.containsKey(controller))return;if(states.size()>=256){complete=false;return;}
            var pending=new ArrayDeque<MinecartController>();pending.add(controller);
            while(!pending.isEmpty()){
                var member=pending.removeFirst();if(states.containsKey(member))continue;
                var cart=member.cart();if(cart==null||cart.isRemoved()||states.size()>=256){complete=false;continue;}
                states.put(member,new State(data(member),cart,CreateCartAudit.passenger(cart)));
                for(boolean main:new boolean[]{true,false}){var id=member.getCoupledCart(main);if(id==null)continue;
                    var entity=((net.minecraft.server.level.ServerLevel)cart.level()).getEntity(id);
                    if(!(entity instanceof AbstractMinecart peer)){complete=false;continue;}
                    var next=peer.getData(com.simibubi.create.AllAttachmentTypes.MINECART_CONTROLLER);if(next.isEmpty()){complete=false;continue;}pending.add(next);
                }
            }}
        public void success(){success=true;}
        public void close(){if(closed)return;closed=true;CURRENT.remove();try{finish();}catch(RuntimeException failure){CreateCartAudit.failed("coupling snapshot",failure);}}
        private void finish(){
            if(CreateStructuralAudit.coupled())return;
            boolean changed=false;var before=new ListTag();var after=new ListTag();var participants=new LinkedHashSet<AbstractContraptionEntity>();
            for(var entry:states.entrySet()){
                var current=data(entry.getKey());var old=entry.getValue().before;
                changed|=!current.equals(old);before.add(old);after.add(current);
                if(entry.getValue().construction!=null&&entry.getValue().construction.isRemoved())complete=false;
                if(!Objects.equals(old.get("Construction"),current.get("Construction"))||!Objects.equals(old.get("ConstructionEntity"),current.get("ConstructionEntity")))complete=false;
                var entity=entry.getValue().construction;if(entity!=null)participants.add(entity);
            }
            if(!changed)return;
            var b=envelope(before);var a=envelope(after);
            for(var participant:participants){if(CreateCargoAudit.capture(participant,ActionType.CART_STATUS,"coupling",b,a,"minecart coupling changed; operation="+operation))continue;
                CreateCargoAudit.emit(participant,ActionType.CART_STATUS,"coupling",b,a,"minecart coupling changed; operation="+operation);}
        }
        private CompoundTag envelope(ListTag carts){var n=new CompoundTag();n.putInt("Format",2);n.putString("Kind","coupling");n.putUUID("Operation",operation);n.putBoolean("Complete",complete&&success);n.put("Carts",carts);return n;}
    }
    public static void clear(){CURRENT.remove();}
}
