package com.roften.avilixlogger.smoke;
import com.roften.avilixlogger.core.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
@EventBusSubscriber(modid="avilixlogger")
public final class CreatePreflightFailureSmoke {
    @SubscribeEvent public static void started(ServerStartedEvent event) throws Exception {
        if(!Boolean.getBoolean("avilixlogger.createPreflightFailureSmoke"))return;
        if(!net.neoforged.fml.ModList.get().isLoaded("create"))throw new AssertionError("failure test requires Create");
        if(CreateSupport.available() || CartAutomationHooks.installed())throw new AssertionError("partial integration remained enabled");
        var world=event.getServer().overworld();
        var cart=new net.minecraft.world.entity.vehicle.MinecartChest(world,0,80,0);
        cart.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
        if(cart instanceof com.roften.avilixlogger.compat.create.CartEntityAccess)throw new AssertionError("optional mixins still applied");
        var type=Class.forName("com.simibubi.create.content.contraptions.AbstractContraptionEntity");
        if(com.roften.avilixlogger.compat.create.CartEntityAccess.class.isAssignableFrom(type))throw new AssertionError("Create audit only partially disabled");
        var node=com.roften.avilixlogger.command.CartCommands.node().build();
        var source=event.getServer().createCommandSourceStack();
        if(node.canUse(source))throw new AssertionError("unavailable command exposed");
        var packet=new com.roften.avilixlogger.net.S2COpenGuiPayload();
        if(packet.createAvailable())throw new AssertionError("server advertised incompatible integration");
        var buffer=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),world.registryAccess());
        try {
            com.roften.avilixlogger.net.S2COpenGuiPayload.STREAM_CODEC.encode(buffer,packet);
            if(com.roften.avilixlogger.net.S2COpenGuiPayload.STREAM_CODEC.decode(buffer).createAvailable())throw new AssertionError("server capability lost in codec");
        } finally {buffer.release();}
        System.out.println("CREATE_PREFLIGHT_FAILURE_NATIVE_OK: entire integration disabled; vanilla cart + commands + server GUI capability; "+CreateSupport.diagnostic());
        event.getServer().halt(false);
    }
}
