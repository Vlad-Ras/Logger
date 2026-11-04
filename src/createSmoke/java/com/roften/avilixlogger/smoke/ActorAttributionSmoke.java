package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.item.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.common.NeoForge;
import java.util.*;

/** Test source set only: no changes or bypasses to AuthCore. Checks actual transformed calls. */
@EventBusSubscriber(modid="avilixlogger")
public final class ActorAttributionSmoke {
    private static final Map<UUID, UUID> REMOVALS = new HashMap<>();
    private static int checks;
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message); checks++;
    }
    private static ActorTracker.ActorRef resolved(net.minecraft.server.level.ServerLevel world, Entity entity) throws Exception {
        var m=LoggerEventHandlers.class.getDeclaredMethod("resolveActorForEntity",net.minecraft.server.level.ServerLevel.class,Entity.class,net.minecraft.nbt.CompoundTag.class);
        m.setAccessible(true);return (ActorTracker.ActorRef)m.invoke(null,world,entity,NbtSerde.snapshotEntity(world,entity));
    }
    private static Entity plane(net.minecraft.server.level.ServerLevel world) {
        var type=BuiltInRegistries.ENTITY_TYPE.get(ResourceLocation.parse("immersive_aircraft:quadrocopter"));
        Entity e=type.create(world);e.setPos(0,150,0);check(world.addFreshEntity(e),"native helicopter join failed");return e;
    }
    @EventBusSubscriber(modid="avilixlogger", bus=EventBusSubscriber.Bus.MOD)
    public static final class RegistryFixture {
    @SubscribeEvent
    public static void register(net.neoforged.neoforge.registries.RegisterEvent event) {
        if (!Boolean.getBoolean("avilixlogger.actorSmoke")) return;
        if (event.getRegistryKey().equals(net.minecraft.core.registries.Registries.ITEM)) {
            event.register(net.minecraft.core.registries.Registries.ITEM, helper -> helper.register(
                    ResourceLocation.parse("avilixlogger:smoke_throwing"), new Item(new Item.Properties()) {
                @Override public InteractionResultHolder<ItemStack> use(Level level, net.minecraft.world.entity.player.Player p,
                                                                        InteractionHand hand) { throw new IllegalStateException("item fixture"); }
            }));
        }
    }
    }
    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if(!Boolean.getBoolean("avilixlogger.actorSmoke"))return;
        event.getServer().overworld().setChunkForced(0,0,true);
        java.util.concurrent.atomic.AtomicInteger ticks=new java.util.concurrent.atomic.AtomicInteger();
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.tick.ServerTickEvent.Post tick)-> {
            if (ticks.incrementAndGet()==20) exercise(event);
        });
    }
    private static void exercise(ServerStartedEvent event) {
        try {
            var world=event.getServer().overworld();
            world.getChunk(0,0);
            var managerField=net.minecraft.server.level.ServerLevel.class.getDeclaredField("entityManager");managerField.setAccessible(true);
            ((net.minecraft.world.level.entity.PersistentEntitySectionManager<Entity>)managerField.get(world)).updateChunkStatus(
                    new net.minecraft.world.level.ChunkPos(0,0),net.minecraft.server.level.FullChunkStatus.ENTITY_TICKING);
            ServerPlayer absent=new ServerPlayer(event.getServer(),world,new GameProfile(UUID.randomUUID(),"AbsentOwner"),ClientInformation.createDefault());
            ServerPlayer actor=new ServerPlayer(event.getServer(),world,new GameProfile(UUID.randomUUID(),"ActualActor"),ClientInformation.createDefault());
            absent.setPos(10000,150,10000);actor.setPos(0,150,0);
            var entity=new Minecart(world,0,150,0);world.addFreshEntity(entity);
            entity.getPersistentData().putString("owner_uuid",absent.getUUID().toString());
            entity.getPersistentData().putString("avilixlogger_owner_uuid",absent.getUUID().toString());
            entity.getPersistentData().putString("avilixlogger_owner_name",absent.getName().getString());
            var tracker=ActorTracker.class.getDeclaredField("ENTITY_ACTOR");tracker.setAccessible(true);
            ((Map<UUID,ActorTracker.ActorRef>)tracker.get(null)).put(entity.getUUID(),new ActorTracker.ActorRef(System.currentTimeMillis(),absent.getUUID(),"AbsentOwner"));
            check(resolved(world,entity)==null,"owner/last visitor charged for an unrelated removal");
            try(var scope=CauseContext.push(actor,CauseContext.Kind.ATTACK_ENTITY,entity.blockPosition(),ItemStack.EMPTY)){
                check(resolved(world,entity).actorUuid().equals(actor.getUUID()),"current actor was replaced by old owner/visitor");
                try(var isolated=CauseContext.unattributed()){check(CauseContext.peek()==null,"simulation inherited actor");check(resolved(world,entity)==null,"isolated simulation resolved actor");}
                check(CauseContext.peek().actorUuid().equals(actor.getUUID()),"nested isolation lost outer actor");
            }
            check(CauseContext.peek()==null,"action scope leaked");
            var outer=CauseContext.push(absent,CauseContext.Kind.OTHER,BlockPos.ZERO,ItemStack.EMPTY);
            var inner=CauseContext.push(actor,CauseContext.Kind.OTHER,BlockPos.ZERO,ItemStack.EMPTY);
            outer.close();check(CauseContext.peek().actorUuid().equals(actor.getUUID()),"out of order close popped another actor");
            inner.close();check(CauseContext.peek()==null,"closed actor survived");outer.close();
            try(var scope=CauseContext.push(actor,CauseContext.Kind.COMMAND,BlockPos.ZERO,ItemStack.EMPTY)){throw new IllegalStateException("fixture");}catch(IllegalStateException expected){}
            check(CauseContext.peek()==null,"exception scope leaked");
            var fake=net.neoforged.neoforge.common.util.FakePlayerFactory.get(world,new GameProfile(UUID.randomUUID(),"[Machine]"));
            try(var scope=CauseContext.push(fake,CauseContext.Kind.USE_ITEM,BlockPos.ZERO,ItemStack.EMPTY)){check(CauseContext.peek()==null,"FakePlayer treated as a human");}
            check(RecentPlayerActionTracker.resolveBest(world,entity.blockPosition(),24,120000,null)==null,"proximity guessed actor");
            check(CreateOwnershipTracker.resolveForSystemChange(world,entity.blockPosition(),entity)==null,"nearby Create owner guessed actor");
            check(com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks.consumeRecentPlacer(world,entity)==null,"random plane placer reused");
            // Real transformed game-mode method must clear actor after a mod item throws.
            Item bomb=BuiltInRegistries.ITEM.get(ResourceLocation.parse("avilixlogger:smoke_throwing"));
            actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(bomb));
            boolean thrown=false;try{actor.gameMode.useItem(actor,world,actor.getMainHandItem(),InteractionHand.MAIN_HAND);}catch(IllegalStateException expected){thrown=true;}
            check(thrown,"throwing native useItem did not execute");check(CauseContext.peek()==null,"gameMode wrapper leaked actor after exception");
            actor.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
            NeoForge.EVENT_BUS.addListener((EntityLeaveLevelEvent leave)->{
                CauseContext.Cause cause=CauseContext.peek();REMOVALS.put(leave.getEntity().getUUID(),cause==null?null:cause.actorUuid());
            });
            if(net.neoforged.fml.ModList.get().isLoaded("immersive_aircraft")){
                Entity craft=plane(world);check(craft.getClass().getName().contains("Quadrocopter"),"real helicopter unavailable");
                craft.getPersistentData().putString("owner_uuid",absent.getUUID().toString());
                craft.hurt(world.damageSources().playerAttack(actor),1);
                check(CauseContext.peek()==null,"vehicle hurt scope leaked");
                craft.hurt(world.damageSources().generic(),100000);
                check(craft.isRemoved(),"environmental vehicle damage did not remove helicopter");
                check(REMOVALS.containsKey(craft.getUUID())&&REMOVALS.get(craft.getUUID())==null,"earlier hitter charged for later environmental damage: "+REMOVALS+"; expected="+craft.getUUID());
                Entity direct=plane(world);direct.getPersistentData().putString("owner_uuid",absent.getUUID().toString());
                direct.hurt(world.damageSources().playerAttack(actor),100000);
                check(direct.isRemoved(),"player hit did not remove helicopter");
                check(actor.getUUID().equals(REMOVALS.get(direct.getUUID())),"actual helicopter attacker not retained");
                check(CauseContext.peek()==null,"vehicle action leaked actor");
                Entity shielded=plane(world);
                try(var scope=CauseContext.push(absent,CauseContext.Kind.COMMAND,BlockPos.ZERO,ItemStack.EMPTY)){
                    shielded.hurt(world.damageSources().generic(),100000);
                    check(REMOVALS.containsKey(shielded.getUUID())&&REMOVALS.get(shielded.getUUID())==null,"environmental damage inherited unrelated outer actor");
                }
            }
            entity.discard();
            check(CauseContext.peek()==null,"final scope leaked");
            System.out.println("ACTOR_ATTRIBUTION_NATIVE_OK: "+checks+" checks");
            event.getServer().halt(false);
        } catch(Throwable failure){failure.printStackTrace();System.out.println("ACTOR_ATTRIBUTION_NATIVE_FAILED");event.getServer().halt(false);}
    }
}
