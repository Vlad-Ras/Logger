package com.roften.avilixlogger.smoke;
import com.roften.avilixlogger.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.damagesource.DamageSource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;

final class PlayerDeathNativeSmoke {
    static void run(MinecraftServer server)throws Exception {
        var world=server.overworld();
        var killer=player(world,"Roften");
        killer.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.NETHERITE_SWORD));
        var sword=world.damageSources().playerAttack(killer);
        var victim=player(world,"Viant");
        java.util.function.Consumer<LivingDeathEvent> cancel=event->{if(event.getEntity()==victim)event.setCanceled(true);};
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,cancel);
        long before=PlayerDeathAudit.observedCount();
        try {victim.die(sword);check(PlayerDeathAudit.observedCount()==before,"canceled death was logged");}
        finally {NeoForge.EVENT_BUS.unregister(cancel);}
        var death=PlayerDeathAudit.entry(victim,sword);
        var kill=DeathLogDetails.killerRow(death,sword);
        check(kill!=null&&kill.actorUuid.equals(killer.getUUID())&&death.actorUuid.equals(victim.getUUID()),"victim/killer identities confused");
        check(DeathLogDetails.message(death).contains("Viant был убит игроком Roften при помощи"),"victim phrase missing");
        check(DeathLogDetails.message(kill).contains("Roften убил игрока Viant при помощи"),"killer phrase missing");
        check(LogText.toChatLine(world,kill).getString().contains("Roften убил игрока Viant"),"killer feed uses generic death label");
        completed(server,"SwordVictim",sword);
        completed(server,"FallVictim",world.damageSources().fall());
        completed(server,"ExplosionVictim",world.damageSources().explosion(null,null));
        // Optional evidence failure must preserve a basic death row, without entity/inventory NBT.
        var broken=new DamageSource(world.damageSources().fall().typeHolder()) {
            @Override public net.minecraft.network.chat.Component getLocalizedDeathMessage(net.minecraft.world.entity.LivingEntity entity){throw new IllegalStateException("fixture bad optional message");}
        };
        var fallback=PlayerDeathAudit.entry(victim,broken);
        check(DeathLogDetails.message(fallback)!=null&&fallback.actorUuid.equals(victim.getUUID())&&fallback.entityNbt==null,"basic fallback lost");
        int previous=CaptureTypePolicy.mask();try{
            CaptureTypePolicy.install(CaptureTypePolicy.changed(0,3,false));
            check(CaptureTypePolicy.allows(ActionType.PLAYER_DEATH,null,null)&&CaptureTypePolicy.allows(ActionType.PLAYER_KILL,null,null),"entity switch muted player deaths");
        }finally{CaptureTypePolicy.install(previous);}
        check(!com.roften.avilixlogger.auth.AuthCoreBridge.isAuthorized(server),"fixture bypassed production authorization");
        System.out.println("PLAYER_DEATH_NATIVE_OK: completed native sword/fall/explosion deaths, LOWEST cancellation excluded, actual accepted hook, victim/killer phrases and fallback; AuthCore publication remains denied");
    }
    static ServerPlayer player(net.minecraft.server.level.ServerLevel level,String name)throws ReflectiveOperationException {
        var player=SupplementariesTextSmoke.player(level,name);
        // Only this fixture's vanilla 60-tick spawn protection, never Logger/AuthCore state.
        var timer=ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");timer.setAccessible(true);timer.setInt(player,0);
        return player;
    }
    private static void completed(MinecraftServer server,String name,DamageSource source)throws Exception {
        ServerPlayer player=player(server.overworld(),name);
        player.setPos(24,90,24);player.getInventory().setItem(0,new ItemStack(Items.DIAMOND));
        long before=PlayerDeathAudit.observedCount();
        check(player.hurt(source,1000F),"native fatal damage refused: "+name);
        check(player.isDeadOrDying()&&PlayerDeathAudit.observedCount()==before+1,"completed death did not reach observer exactly once: "+name);
        var row=PlayerDeathAudit.entry(player,source);
        check(row.actorUuid.equals(player.getUUID())&&row.x==24&&row.y==90&&row.z==24&&!DeathLogDetails.message(row).isBlank(),"completed death evidence lost: "+name);
        if(name.equals("ExplosionVictim"))check(DeathLogDetails.describe(row).stream().anyMatch(c->c.getString().contains("explosion")),"explosion cause missing");
        check(player.getInventory().isEmpty(),"native death drops interrupted: "+name);
        if(net.neoforged.fml.ModList.get().isLoaded("corpse")) {
            var corpses=server.overworld().getEntities((net.minecraft.world.entity.Entity)null,player.getBoundingBox().inflate(3),e->net.minecraft.world.entity.EntityType.getKey(e.getType()).toString().equals("corpse:corpse"));
            boolean found=corpses.stream().anyMatch(c->{try{return c.getClass().getMethod("getCorpseUUID").invoke(c).equals(java.util.Optional.of(player.getUUID()));}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}});
            check(found,"Corpse did not create this player's body: "+name);
            System.out.println("PLAYER_DEATH_CORPSE_NATIVE_OK: "+name);
        }
    }
}
