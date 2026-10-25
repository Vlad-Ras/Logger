package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.scguns.ScgunsTurretAudit;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import java.util.*;
import java.util.function.Consumer;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.*;

/** Exact local JAR acceptance. Framework references are isolated from optional-absent discovery. */
public final class ScgunsCoverageNativeSmoke {
    public static void run(ServerLevel level)throws Exception {
        ReloadPackets.run(level);turrets(level);explosions(level);
        check(!com.roften.avilixlogger.auth.AuthCoreBridge.isAuthorized(level.getServer()),"coverage must not bypass AuthCore");
        System.out.println("SCGUNS_COVERAGE_NATIVE_OK: native reload packets, turret bursts/save-load/owner isolation and direct explosion damage; capture unauthorized");
    }
    private static final class ReloadPackets {
        private static final class Context extends com.mrcrayfish.framework.api.network.MessageContext {
            private boolean handled;private int executed;
            Context(ServerPlayer player){super(net.minecraft.network.protocol.PacketFlow.SERVERBOUND,player);}
            public void setHandled(boolean value){handled=value;}
            public void execute(Runnable task){executed++;task.run();}
            public void disconnect(net.minecraft.network.chat.Component reason){throw new AssertionError(reason.getString());}
        }
        @SuppressWarnings("unchecked") static void run(ServerLevel level)throws Exception {
            var request=Class.forName("top.ribs.scguns.network.message.C2SMessageReload");
            var loaded=Class.forName("top.ribs.scguns.network.message.C2SMessageGunLoaded");
            var context=com.mrcrayfish.framework.api.network.MessageContext.class;
            var state=(com.mrcrayfish.framework.api.sync.SyncedDataKey<Player,Boolean>)Class.forName("top.ribs.scguns.init.ModSyncedDataKeys").getField("RELOADING").get(null);
            String[][] cases={{"auvtomag","ramrod_round","6","6"},{"boomstick","shotgun_shell","1","1"},{"laser_musket","energy_core","7","1"}};
            for(var sample:cases){
                var p=player(level,"Reload"+sample[0]);var gun=item(sample[0]);ammo(gun,0);p.setItemInHand(InteractionHand.MAIN_HAND,gun);p.getInventory().setItem(9,item(sample[1],16));
                var start=new Context(p);request.getMethod("handle",request,context).invoke(null,request.getConstructor(boolean.class).newInstance(true),start);
                check(start.handled&&start.executed==1&&state.getValue(p),"native reload start failed: "+sample[0]);
                if(!sample[0].equals("boomstick")){
                    request.getMethod("handle",request,context).invoke(null,request.getConstructor(boolean.class).newInstance(false),new Context(p));
                    check(state.getValue(p)&&WeaponCombatAudit.ammo(gun)==0,"critical reload interrupted: "+sample[0]);
                }
                loaded.getMethod("handle",loaded,context).invoke(null,loaded.getConstructor().newInstance(),new Context(p));
                check(WeaponCombatAudit.ammo(gun)==Integer.parseInt(sample[2]),"native loaded amount differs: "+sample[0]+" = "+WeaponCombatAudit.ammo(gun));
                check(p.getInventory().getItem(9).getCount()==16-Integer.parseInt(sample[3]),"native reload consumed wrong ammo: "+sample[0]);
                request.getMethod("handle",request,context).invoke(null,request.getConstructor(boolean.class).newInstance(false),new Context(p));
                check(!state.getValue(p),"native reload stop failed: "+sample[0]);
            }
            // Retain a fixture session as if capture had been enabled at reload start.
            // AuthCore remains disabled; only native stop must free Logger-owned evidence.
            var p=player(level,"ReloadSlotCancel");var gun=item("boomstick");ammo(gun,0);p.setItemInHand(InteractionHand.MAIN_HAND,gun);p.getInventory().setItem(9,item("shotgun_shell",8));
            request.getMethod("handle",request,context).invoke(null,request.getConstructor(boolean.class).newInstance(true),new Context(p));
            var constructor=WeaponCombatAudit.ReloadSession.class.getDeclaredConstructor(ItemStack.class,CompoundTag.class,int.class);constructor.setAccessible(true);
            var access=(WeaponReloadAccess)p;access.avilixlogger$reload(constructor.newInstance(gun,new CompoundTag(),0));
            p.getInventory().selected=1;p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.DIAMOND));
            request.getMethod("handle",request,context).invoke(null,request.getConstructor(boolean.class).newInstance(false),new Context(p));
            check(!state.getValue(p)&&access.avilixlogger$reload()==null&&WeaponCombatAudit.ammo(gun)==0&&p.getInventory().getItem(9).getCount()==8,"disabled capture retained reload stack or changed native slot-cancel behavior");
            var spectator=player(level,"ReloadSpectator");spectator.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);spectator.setItemInHand(InteractionHand.MAIN_HAND,item("boomstick"));
            request.getMethod("handle",request,context).invoke(null,request.getConstructor(boolean.class).newInstance(true),new Context(spectator));
            check(!state.getValue(spectator),"spectator started reload");
            System.out.println("SCGUNS_RELOAD_PACKETS_OK: magazine, manual insert, single item; critical cancel refusal, slot cancel, spectator refusal, retained-session cleanup without capture");
        }
    }
    private static void turrets(ServerLevel level)throws Exception {
        var type=Class.forName("top.ribs.scguns.blockentity.TurretBlockEntity");
        check(Arrays.stream(type.getDeclaredMethods()).anyMatch(m->m.getName().contains("avilixlogger$fire")),"turret fire wrapper absent");
        var fire=type.getDeclaredMethod("fireWeapon",int.class);fire.setAccessible(true);var target=type.getDeclaredField("target");target.setAccessible(true);
        var owner=player(level,"TurretOwner");
        for(int index=0;index<2;index++){
            String id=index==0?"basic_turret":"shotgun_turret";var pos=new BlockPos(12+index*8,100,28);
            var block=BuiltInRegistries.BLOCK.get(ResourceLocation.parse("scguns:"+id));check(block!=Blocks.AIR,"native turret block absent");
            level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());level.setBlockAndUpdate(pos,block.defaultBlockState());
            var turret=level.getBlockEntity(pos);check(turret!=null&&type.isInstance(turret),"native turret BE absent");type.getMethod("setOwner",ServerPlayer.class).invoke(turret,owner);
            var victim=EntityType.ZOMBIE.create(level);check(victim!=null,"native turret target absent");victim.setPos(pos.getX()+.5,100,pos.getZ()+6.5);target.set(turret,victim);
            var inventory=(net.neoforged.neoforge.items.ItemStackHandler)type.getMethod("getItemStackHandler").invoke(turret);inventory.setStackInSlot(0,item(index==0?"standard_copper_round":"shotgun_shell",4));
            var bullets=new ArrayList<Entity>();Consumer<EntityJoinLevelEvent> joined=e->{if(e.getEntity().getClass().getName().equals("top.ribs.scguns.entity.projectile.turret.TurretProjectileEntity"))bullets.add(e.getEntity());};
            NeoForge.EVENT_BUS.addListener(joined);
            try {fire.invoke(turret,0);}finally{NeoForge.EVENT_BUS.unregister(joined);}
            check(bullets.size()==(index==0?1:8)&&inventory.getStackInSlot(0).getCount()==3,"turret native burst/ammo changed: "+id+" = "+bullets.size());
            var first=bullets.getFirst();check(first instanceof WeaponTraceAccess,"turret arrow lacks trace interface");
            var shot=ScgunsTurretAudit.capture(turret,owner.getUUID(),owner.getGameProfile().getName(),level,first.position(),first.getDeltaMovement());
            for(var bullet:bullets)((WeaponTraceAccess)bullet).avilixlogger$shot(shot);
            var saved=first.saveWithoutId(new CompoundTag());var restored=first.getType().create(level);check(restored!=null,"turret restore fixture absent");restored.load(saved);
            var historical=((WeaponTraceAccess)restored).avilixlogger$shot();type.getMethod("setOwner",ServerPlayer.class).invoke(turret,player(level,"ChangedOwner"));owner.setPos(900,140,900);
            check(historical!=null&&historical.fromMechanism()&&historical.actor()==null&&historical.shooter()==null&&historical.mechanism().getUUID("Owner").equals(owner.getUUID())&&historical.id().equals(shot.id()),"turret save/load/current owner altered history");
            var row=WeaponCombatAudit.entry(historical,ActionType.WEAPON_KILL,"kill",victim,restored);row.materializeSnapshots();check(row.actorUuid==null&&WeaponCombatTrace.pose(row,"source").equals(shot.origin()),"turret attributed owner as personal shooter");
            for(var bullet:bullets)bullet.discard();restored.discard();victim.discard();level.removeBlock(pos,false);level.removeBlock(pos.below(),false);
        }
        System.out.println("SCGUNS_TURRET_NATIVE_OK: basic=1, shotgun=8, one ammo each, inherited save/load, historical owner isolated from human actor");
    }
    private static void explosions(ServerLevel level)throws Exception {
        var shooter=player(level,"ExplosionActor");var gun=item("auvtomag");ammo(gun,5);shooter.setItemInHand(InteractionHand.MAIN_HAND,gun);
        var projectileType=Class.forName("top.ribs.scguns.entity.projectile.ProjectileEntity");
        var projectiles=new ArrayList<Entity>();Consumer<EntityJoinLevelEvent> joined=e->{if(projectileType.isInstance(e.getEntity()))projectiles.add(e.getEntity());};
        NeoForge.EVENT_BUS.addListener(joined);
        try{
            var packet=Class.forName("top.ribs.scguns.network.message.C2SMessageShoot");Class.forName("top.ribs.scguns.common.network.ServerPlayHandler").getMethod("handleShoot",packet,ServerPlayer.class).invoke(null,packet.getConstructor(float.class,float.class).newInstance(0f,0f),shooter);
        }finally{NeoForge.EVENT_BUS.unregister(joined);}
        check(!projectiles.isEmpty(),"explosion source fixture missing");var projectile=projectiles.getFirst();var shot=WeaponCombatTrace.capture(shooter,gun,WeaponCombatTrace.Pose.capture(shooter));((WeaponTraceAccess)projectile).avilixlogger$shot(shot);
        var type=Class.forName("top.ribs.scguns.entity.projectile.ProjectileEntity");int index=0;
        for(String method:List.of("createExplosion","createRocketExplosion","createFireExplosion")){
            var point=new Vec3(-20+index++*10,100,12);level.getChunkAt(BlockPos.containing(point));projectile.setPos(point);var victim=EntityType.COW.create(level);check(victim!=null,"explosion target fixture missing");victim.setPos(point.add(.7,0,0));victim.setNoGravity(true);level.addFreshEntity(victim);
            int[] hits={0};Consumer<LivingDamageEvent.Post> damage=e->{if(e.getEntity()==victim&&e.getNewDamage()>0){check(e.getSource().getDirectEntity()==projectile,"explosion lost direct projectile");hits[0]++;}};NeoForge.EVENT_BUS.addListener(damage);
            try{if(method.equals("createRocketExplosion"))type.getMethod(method,Entity.class,float.class,float.class,boolean.class).invoke(null,projectile,2f,1f,true);
                else type.getMethod(method,Entity.class,float.class,boolean.class).invoke(null,projectile,2f,true);
                check(hits[0]>0,"native explosion produced no confirmed damage: "+method);
                var row=WeaponCombatAudit.entry(((WeaponTraceAccess)projectile).avilixlogger$shot(),ActionType.PROJECTILE_HIT,"damage",victim,projectile);row.materializeSnapshots();check(WeaponCombatTrace.pose(row,"source").equals(shot.origin()),"explosion followed projectile instead of original shooter");
            }finally{NeoForge.EVENT_BUS.unregister(damage);victim.discard();}
        }
        for(var entity:projectiles)entity.discard();
        System.out.println("SCGUNS_EXPLOSION_NATIVE_OK: ordinary/rocket/fire positive damage preserve direct projectile and historical source");
    }
}
