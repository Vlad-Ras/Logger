package com.roften.avilixlogger.smoke;

import com.mojang.authlib.GameProfile;
import com.roften.avilixlogger.compat.scguns.ScgunsSupport;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.util.*;
import java.util.function.Consumer;

/** Real supplied Scguns methods and entities plus detached evidence kernels. No authorization bypass. */
@EventBusSubscriber(modid="avilixlogger")
public final class ScgunsNativeSmoke {
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if(!Boolean.getBoolean("avilixlogger.scgunsSmoke"))return;
        try {run(event.getServer().overworld());}
        catch(Throwable failure){org.slf4j.LoggerFactory.getLogger("AvilixLogger/ScgunsSmoke").error("SCGUNS_NATIVE_FAILED",failure);System.out.println("SCGUNS_NATIVE_FAILED");}
        finally{event.getServer().halt(false);}
    }
    @SuppressWarnings("unchecked") private static void run(ServerLevel level)throws Exception {
        check(ScgunsSupport.available(),"optional preflight disabled exact Scguns 1.5");
        var handler=Class.forName("top.ribs.scguns.common.network.ServerPlayHandler");
        var packet=Class.forName("top.ribs.scguns.network.message.C2SMessageShoot");
        var tracker=Class.forName("top.ribs.scguns.common.ReloadTracker");
        var request=Class.forName("top.ribs.scguns.network.message.C2SMessageReload");
        for(var type:List.of(handler,tracker,request))check(Arrays.stream(type.getDeclaredMethods()).anyMatch(m->m.getName().contains("avilixlogger$")),"wrapper absent: "+type);
        check(WeaponTraceAccess.class.isAssignableFrom(Class.forName("top.ribs.scguns.entity.projectile.ProjectileEntity")),"custom Entity trace missing");
        check(WeaponTraceAccess.class.isAssignableFrom(net.minecraft.world.entity.projectile.Projectile.class),"vanilla projectile trace missing");
        var shooter=player(level,"GunActor");shooter.setPos(-21.125,100.375,17.875);shooter.setYRot(11);shooter.setXRot(4);
        var weapon=item("auvtomag");ammo(weapon,5);shooter.setItemInHand(InteractionHand.MAIN_HAND,weapon);
        int[] posts={0};boolean[] cancel={false};var spawned=new ArrayList<Entity>();
        Consumer<Event> post=e->posts[0]++;
        Consumer<Event> pre=e->{if(cancel[0])((ICancellableEvent)e).setCanceled(true);};
        Consumer<EntityJoinLevelEvent> join=e->{if(e.getEntity().getClass().getName().startsWith("top.ribs.scguns.entity.projectile."))spawned.add(e.getEntity());};
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,(Class<Event>)Class.forName("top.ribs.scguns.event.GunFireEvent$Post"),post);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,false,(Class<Event>)Class.forName("top.ribs.scguns.event.GunFireEvent$Pre"),pre);
        NeoForge.EVENT_BUS.addListener(join);
        try {
            var shoot=handler.getMethod("handleShoot",packet,ServerPlayer.class);
            shoot.invoke(null,packet.getConstructor(float.class,float.class).newInstance(389.25f,-23.5f),shooter);
            check(posts[0]==1 && !spawned.isEmpty(),"native accepted shot not observed");
            check(shooter.getYRot()==29.25f && shooter.getXRot()==-23.5f,"native aim differs from accepted packet");
            int spawnedCount=spawned.size();
            shoot.invoke(null,packet.getConstructor(float.class,float.class).newInstance(29.25f,-23.5f),shooter);
            check(posts[0]==1 && spawned.size()==spawnedCount,"native cooldown rejected shot had effects");
            var cancelled=player(level,"GunCancelled");var cancelledGun=item("auvtomag");ammo(cancelledGun,5);cancelled.setItemInHand(InteractionHand.MAIN_HAND,cancelledGun);
            cancel[0]=true;shoot.invoke(null,packet.getConstructor(float.class,float.class).newInstance(0f,0f),cancelled);cancel[0]=false;
            check(posts[0]==1 && spawned.size()==spawnedCount && WeaponCombatAudit.ammo(cancelledGun)==5,"cancelled shot consumed ammo/spawned projectile");
            var empty=player(level,"GunEmpty");empty.setItemInHand(InteractionHand.MAIN_HAND,item("auvtomag"));
            shoot.invoke(null,packet.getConstructor(float.class,float.class).newInstance(0f,0f),empty);
            check(posts[0]==1 && spawned.size()==spawnedCount,"empty gun accepted a shot");
            var shotgun=player(level,"GunPellets");shotgun.setPos(24.5,100,24.5);var shotgunItem=item("boomstick");ammo(shotgunItem,5);shotgun.setItemInHand(InteractionHand.MAIN_HAND,shotgunItem);
            shoot.invoke(null,packet.getConstructor(float.class,float.class).newInstance(0f,0f),shotgun);
            check(posts[0]==2 && spawned.size()-spawnedCount==26,"shotgun pellets were confused with accepted trigger count");
            var laser=player(level,"GunBeam");laser.setPos(12.5,100,12.5);var laserItem=item("laser_musket");ammo(laserItem,5);laser.setItemInHand(InteractionHand.MAIN_HAND,laserItem);
            int beforeBeam=spawned.size();shoot.invoke(null,packet.getConstructor(float.class,float.class).newInstance(0f,0f),laser);
            check(posts[0]==3 && spawned.size()==beforeBeam,"native beam acceptance expected without projectile entity");

            // Production AuthCore is deliberately unchanged. Kernels below never publish.
            var origin=WeaponCombatTrace.Pose.capture(shooter);
            var shot=WeaponCombatTrace.capture(shooter,weapon,origin);
            check(shot!=null && shooter.getUUID().equals(shot.actor()),"real player actor lost");
            var projectile=spawned.getFirst();var access=(WeaponTraceAccess)projectile;access.avilixlogger$shot(shot);
            var shotWeapon=shot.weapon();shotWeapon.putString("Tamper","caller");
            check(!shot.weapon().contains("Tamper"),"shared immutable shot exposed weapon tag");
            var saved=projectile.saveWithoutId(new CompoundTag());
            check(saved.contains(WeaponCombatTrace.SAVE_KEY),"custom projectile did not persist origin");
            var restored=projectile.getType().create(level);check(restored!=null,"projectile restore fixture missing");restored.load(saved);
            var restoredShot=((WeaponTraceAccess)restored).avilixlogger$shot();
            check(restoredShot!=null && restoredShot.id().equals(shot.id()) && restoredShot.origin().equals(origin),"native entity load lost shot");
            shooter.setPos(900,140,-900);shooter.setYRot(-177);shooter.setXRot(80);weapon.set(DataComponents.CUSTOM_NAME,Component.literal("changed after shot"));
            var victim=player(level,"GunVictim");victim.setPos(8.0625,95.25,-12.625);victim.setYRot(66);victim.setXRot(-7);
            var kill=WeaponCombatAudit.entry(restoredShot,ActionType.WEAPON_KILL,"kill",victim,restored);kill.id=782;
            var death=new LogEntry();death.type=ActionType.PLAYER_DEATH;death.actorUuid=victim.getUUID();death.actorName=victim.getGameProfile().getName();
            WeaponCombatAudit.deathEvidence(death,restoredShot,victim,restored);death.id=783;
            kill.materializeSnapshots();death.materializeSnapshots();
            check(kill.actorUuid.equals(shooter.getUUID()) && kill.entityUuid.equals(victim.getUUID()),"killer/target identity mixed");
            check(death.actorUuid.equals(victim.getUUID()) && death.type==ActionType.PLAYER_DEATH,"death event incorrectly reassigned to killer");
            check(WeaponCombatTrace.pose(kill,"source").equals(origin) && WeaponCombatTrace.pose(death,"source").equals(origin),"moving shooter changed historical origin");
            check(WeaponCombatTrace.pose(kill,"target").equals(WeaponCombatTrace.Pose.capture(victim)),"victim precision/angles lost");
            check(kill.itemStackNbt.equals(death.itemStackNbt) && !kill.itemStackNbt.contains("changed after shot"),"weapon evidence followed current hand");
            var sources=Class.forName("top.ribs.scguns.init.ModDamageTypes$Sources");
            var nativeSource=(net.minecraft.world.damagesource.DamageSource)sources.getMethod("projectile",net.minecraft.core.RegistryAccess.class,
                    Class.forName("top.ribs.scguns.entity.projectile.ProjectileEntity"),LivingEntity.class).invoke(null,level.registryAccess(),projectile,shooter);
            check(nativeSource.getDirectEntity()==projectile && nativeSource.getEntity()==shooter,"native bullet damage attribution differs");
            var nativeVictim=player(level,"NativeGunVictim");nativeVictim.setPos(8,100,8);nativeVictim.setHealth(2);
            // New ServerPlayer fixtures have 60 ticks of vanilla spawn protection.
            // Set only this fixture's vanilla timer; never change Logger/AuthCore policy.
            var spawnProtection=ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");spawnProtection.setAccessible(true);spawnProtection.setInt(nativeVictim,0);
            int[] damageEvents={0},deathEvents={0};
            Consumer<net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post> damage=e->{if(e.getEntity()==nativeVictim && e.getSource()==nativeSource && e.getNewDamage()>0)damageEvents[0]++;};
            Consumer<net.neoforged.neoforge.event.entity.living.LivingDeathEvent> died=e->{if(e.getEntity()==nativeVictim && e.getSource().getEntity()==shooter)deathEvents[0]++;};
            NeoForge.EVENT_BUS.addListener(damage);NeoForge.EVENT_BUS.addListener(died);
            try{check(nativeVictim.hurt(nativeSource,50f)&&nativeVictim.isDeadOrDying()&&damageEvents[0]==1&&deathEvents[0]==1,"confirmed native player hit/death callbacks missing");}
            finally{NeoForge.EVENT_BUS.unregister(damage);NeoForge.EVENT_BUS.unregister(died);}
            var view=origin.viewpoint(1.62);check(view.y()+1.62==origin.eyeY() && view.yaw()==origin.yaw() && view.pitch()==origin.pitch(),"eye reconstruction changed aim");
            var observer=player(level,"GunObserver");observer.teleportTo(level,origin.x(),origin.y(),origin.z(),origin.yaw(),origin.pitch());
            check(observer.getX()==origin.x()&&observer.getY()==origin.y()&&observer.getZ()==origin.z()&&observer.getYRot()==origin.yaw()&&observer.getXRot()==origin.pitch(),"native teleport changed historical precision or aim");
            check(WeaponCombatTrace.describe(kill).stream().anyMatch(c->c.getStyle().getClickEvent()!=null && c.getStyle().getClickEvent().getValue().equals("/log combatpos 782 source")),"exact source link missing");
            var invalid=origin.tag();invalid.putDouble("X",Double.NaN);check(WeaponCombatTrace.Pose.read(invalid)==null,"non-finite coordinate accepted");
            invalid=origin.tag();invalid.remove("Yaw");check(WeaponCombatTrace.Pose.read(invalid)==null,"missing angle replaced with zero");
            invalid=shot.tag();invalid.putInt("Version",9);check(WeaponCombatTrace.Shot.read(invalid)==null,"unsupported evidence version accepted");

            var loader=player(level,"GunReload");var reloadGun=item("auvtomag");ammo(reloadGun,0);loader.setItemInHand(InteractionHand.MAIN_HAND,reloadGun);
            loader.getInventory().setItem(9,item("ramrod_round",24));
            var before=NbtSerde.snapshotItemStack(reloadGun,level.registryAccess());
            var nativeTracker=tracker.getConstructor(Player.class).newInstance(loader);
            tracker.getMethod("increaseAmmo",Player.class).invoke(nativeTracker,loader);
            int transferred=WeaponCombatAudit.ammo(reloadGun);
            check(transferred>0 && transferred<=6,"native ammo transfer did not load actual gun");
            var after=NbtSerde.snapshotItemStack(reloadGun,level.registryAccess());
            var reload=WeaponCombatAudit.reloadEntry(loader,WeaponCombatTrace.Pose.capture(loader),"ammo_transfer",before,after,0,transferred);reload.materializeSnapshots();
            check(reload.type==ActionType.WEAPON_RELOAD && WeaponCombatTrace.context(reload).getInt("AmmoAfter")==transferred,"reload payload lost confirmed ammo");
            int mask=CaptureTypePolicy.mask();try{CaptureTypePolicy.install(CaptureTypePolicy.changed(mask,4,false));
                check(!CaptureTypePolicy.allows(ActionType.WEAPON_RELOAD,"mod:scguns",null),"reload bypassed items mask");
                check(WeaponCombatAudit.beginReload(loader,reloadGun,"disabled")==null,"disabled reload retained scope");
            }finally{CaptureTypePolicy.install(mask);}
            var preview=RollbackEngine.previewPrepared(new RollbackEngine.PreparedRollback(List.of(),List.of(),List.of(kill,reload),0));
            check(preview.applied==0 && preview.skippedReasons.getOrDefault("not_rollbackable",0)==2,"weapon audit entered partial inventory/entity rollback");
            check(!com.roften.avilixlogger.auth.AuthCoreBridge.isAuthorized(level.getServer()),"fixture requires a non-authorized stand; never supply production credentials");
            check(WeaponCombatAudit.beginFire(shooter)==null,"production capture bypassed AuthCore");
            ScgunsCoverageNativeSmoke.run(level);
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/ScgunsSmoke").info("SCGUNS_NATIVE_OK");
            System.out.println("SCGUNS_NATIVE_OK: supplied 1.5 hooks applied; real accepted/cooldown/cancelled/empty gun paths; 26-pellet shotgun and entity-free beam; native player damage/death and ammo transfer; entity save/load; immutable gun/source/target precision and orientation; death/kill actors; exact command links; malformed evidence; capture mask and rollback refusal; AuthCore remained disabled");
        } finally {NeoForge.EVENT_BUS.unregister(post);NeoForge.EVENT_BUS.unregister(pre);NeoForge.EVENT_BUS.unregister(join);}
    }
    static ServerPlayer player(ServerLevel level,String name){
        var profile=new GameProfile(UUID.randomUUID(),name);var player=new ServerPlayer(level.getServer(),level,profile,ClientInformation.createDefault());
        player.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),player,net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false)){
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet){}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener callback){}
        };return player;
    }
    static ItemStack item(String id){return item(id,1);}
    static ItemStack item(String id,int count){var item=BuiltInRegistries.ITEM.get(ResourceLocation.parse("scguns:"+id));check(item!=Items.AIR,"missing native item "+id);return new ItemStack(item,count);}
    static void ammo(ItemStack stack,int count){var data=new CompoundTag();data.putInt("AmmoCount",count);stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));}
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
