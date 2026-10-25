package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.curios.CuriosAudit;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import top.theillusivec4.curios.api.event.CurioChangeEvent;
import java.util.UUID;

/** Payloads from the native Curios event API. Does not bypass production AuthCore publication. */
final class CuriosAuditSmoke {
    static void run(ServerLevel level) {
        var player=new ServerPlayer(level.getServer(),level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"EquipmentTarget"),ClientInformation.createDefault());
        com.roften.avilixlogger.compat.curios.CuriosCaptureDefaults.prepare(player);
        var initialized=player.serializeAttachments(level.registryAccess());
        top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(player);
        check(java.util.Objects.equals(initialized,player.serializeAttachments(level.registryAccess())),"native Curios lookup changed prepared attachment state");
        var external=com.roften.avilixlogger.compat.create.CreateExternalPlayerState.capture(player);
        var modified=external.copy();modified.getCompound("Unknown").putString("curios:inventory","changed");
        boolean rejected=false;try{com.roften.avilixlogger.compat.create.CreateExternalPlayerState.validatePair(external,modified);}catch(IllegalStateException expected){rejected=true;}
        check(rejected,"Curios attachment changes were hidden from joint rollback validation");
        var item=new ItemStack(Items.DIAMOND,1024);item.set(DataComponents.CUSTOM_NAME,Component.literal("Ring payload"));
        var row=CuriosAudit.capture(new CurioChangeEvent(player,"ring",2,ItemStack.EMPTY,item));
        check(row!=null&&row.type==ActionType.ENTITY_EQUIPMENT_CHANGE&&row.actorUuid==null&&"SYSTEM".equals(row.actorName),"observed wearer promoted to actor");
        item.setCount(1);row.materializeSnapshots();
        var before=NbtSerde.fromSnbt(row.beBefore);var after=NbtSerde.fromSnbt(row.beAfter);
        check(before.getCompound("Item").isEmpty()&&after.getInt("Slot")==2&&after.getString("SlotType").equals("ring"),"equipment address lost");
        check(NbtSerde.readItemStack(after.getCompound("Item"),level.registryAccess()).getCount()==1024,"detached equipment count lost");
        check(player.getUUID().equals(row.entityUuid)&&"EquipmentTarget".equals(row.entityName),"equipment target identity lost");
        check(CuriosAudit.capture(new CurioChangeEvent(player,"ring",2,item,item.copy()))==null,"unchanged equipment generated an event");
        int old=CaptureTypePolicy.mask();try{CaptureTypePolicy.install(CaptureTypePolicy.changed(old,3,false));check(!CaptureTypePolicy.allows(row.type,row.source,row.extra),"disabled equipment category bypassed");}finally{CaptureTypePolicy.install(old);}
        System.out.println("CURIOS_EVENT_PAYLOAD_OK: optional native 9.5.1 event API, detached expanded stacks, slot and target identity, no guessed actor, no-op and capture mask");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
