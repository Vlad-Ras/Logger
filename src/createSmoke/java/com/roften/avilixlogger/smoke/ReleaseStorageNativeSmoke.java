package com.roften.avilixlogger.smoke;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Isolated data-kernel regression; production authentication and publishing stay unchanged. */
@EventBusSubscriber(modid="avilixlogger")
public final class ReleaseStorageNativeSmoke {
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if(!Boolean.getBoolean("avilixlogger.storageSmoke"))return;
        try {
            BlockMenuStorageSmoke.run(event.getServer().overworld());
            if(net.neoforged.fml.ModList.get().isLoaded("openpartiesandclaims")) {
                ScgunsNativeSmoke.check(com.roften.avilixlogger.compat.opac.OpenPacStateAudit.available(),"OPAC mutation mixins unavailable");
                var api=Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
                var instance=api.getMethod("get",net.minecraft.server.MinecraftServer.class).invoke(null,event.getServer());
                var manager=api.getMethod("getServerClaimsManager").invoke(instance);
                var dim=event.getServer().overworld().dimension().location();int x=2,z=2;
                var snapshot=com.roften.avilixlogger.compat.opac.OpenPacStateAudit.class.getDeclaredMethod("claimSnapshot",Object.class,net.minecraft.resources.ResourceLocation.class,int.class,int.class);snapshot.setAccessible(true);
                var first=(net.minecraft.nbt.CompoundTag)snapshot.invoke(null,manager,dim,x,z);
                ScgunsNativeSmoke.check(!first.getBoolean("Claimed"),"fixture chunk already claimed");
                var owner=java.util.UUID.fromString("62000000-0000-0000-0000-000000000001");
                var claim=manager.getClass().getMethod("claim",net.minecraft.resources.ResourceLocation.class,java.util.UUID.class,int.class,int.class,int.class,boolean.class);
                try {
                    claim.invoke(manager,dim,owner,0,x,z,false);
                    var after=(net.minecraft.nbt.CompoundTag)snapshot.invoke(null,manager,dim,x,z);
                    ScgunsNativeSmoke.check(after.getBoolean("Claimed")&&after.getString("OwnerUUID").equals(owner.toString())&&after.getInt("ChunkX")==x&&after.getInt("ChunkZ")==z,"OPAC physical claim evidence mismatch");
                    ScgunsNativeSmoke.check(com.roften.avilixlogger.core.ModAuditDetails.claimSummary(first,after).contains("заприватил"),"OPAC claim direction lost");
                } finally {manager.getClass().getMethod("unclaim",net.minecraft.resources.ResourceLocation.class,int.class,int.class).invoke(manager,dim,x,z);}
                var last=(net.minecraft.nbt.CompoundTag)snapshot.invoke(null,manager,dim,x,z);
                ScgunsNativeSmoke.check(!last.getBoolean("Claimed"),"OPAC unclaim did not clear fixture");
                System.out.println("OPAC_STATE_NATIVE_OK: exact chunk/owner and native claim/unclaim data kernel; not authenticated publication");
            }
            if(net.neoforged.fml.ModList.get().isLoaded("create_enchantment_industry"))
                ScgunsNativeSmoke.check(com.roften.avilixlogger.compat.industry.IndustrialState.class.isAssignableFrom(Class.forName("com.simibubi.create.foundation.blockEntity.SmartBlockEntity")),"industrial mixins unavailable");
            System.out.println("STORAGE_BLOCK_ADDRESS_NATIVE_OK: physical second container, partial slot delta, GUI details and legacy rollback");
        } catch(Throwable failure){failure.printStackTrace();System.out.println("STORAGE_BLOCK_ADDRESS_NATIVE_FAILED");}
        finally {event.getServer().halt(false);}
    }
}
