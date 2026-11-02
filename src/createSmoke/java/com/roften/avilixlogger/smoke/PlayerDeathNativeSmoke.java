package com.roften.avilixlogger.smoke;
import com.mojang.authlib.GameProfile;
import com.roften.avilixlogger.core.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.entity.EquipmentSlot;
import java.util.UUID;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;

final class PlayerDeathNativeSmoke {
    static void run(MinecraftServer server) {
        var world=server.overworld();
        var killer=new ServerPlayer(server,world,new GameProfile(UUID.randomUUID(),"Roften"),ClientInformation.createDefault());
        var victim=new ServerPlayer(server,world,new GameProfile(UUID.randomUUID(),"Viant"),ClientInformation.createDefault());
        killer.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.NETHERITE_SWORD));
        var dispatched=new java.util.concurrent.atomic.AtomicInteger();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(net.neoforged.bus.api.EventPriority.HIGHEST,(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event)->{
            if(event.getEntity()==victim){dispatched.incrementAndGet();event.setCanceled(true);}
        });
        var source=world.damageSources().playerAttack(killer);
        victim.die(source);victim.die(world.damageSources().fall());
        check(dispatched.get()==2,"native ServerPlayer.die did not publish PvP/environment death events");
        var death=new LogEntry();death.type=ActionType.PLAYER_DEATH;death.actorUuid=victim.getUUID();death.actorName="Viant";death.dim=world.dimension().location().toString();
        death.extra=DeathLogDetails.capture(victim,source,source.getLocalizedDeathMessage(victim).getString());
        var kill=DeathLogDetails.killerRow(death,source);
        check(kill!=null && kill.actorUuid.equals(killer.getUUID()) && death.actorUuid.equals(victim.getUUID()),"victim/killer identities confused");
        check(DeathLogDetails.message(death).contains("Viant был убит игроком Roften при помощи"),"victim phrase missing");
        check(DeathLogDetails.message(kill).contains("Roften убил игрока Viant при помощи"),"killer phrase missing");
        check(DeathLogDetails.describe(death).stream().anyMatch(line->line.getString().contains("minecraft:player_attack")),"damage type missing");
        check(LogText.toChatLine(world,kill).getString().contains("Roften убил игрока Viant"),"killer feed uses generic death label");
        var fall=world.damageSources().fall();death.extra=DeathLogDetails.capture(victim,fall,fall.getLocalizedDeathMessage(victim).getString());
        check(DeathLogDetails.killerRow(death,fall)==null && !DeathLogDetails.message(death).isBlank(),"natural death invented a killer or lost its cause");
        int previous=CaptureTypePolicy.mask();try{
            CaptureTypePolicy.install(CaptureTypePolicy.changed(0,3,false));
            check(CaptureTypePolicy.allows(ActionType.PLAYER_DEATH,null,null) && CaptureTypePolicy.allows(ActionType.PLAYER_KILL,null,null),"entity switch muted player deaths");
        }finally{CaptureTypePolicy.install(previous);}
        System.out.println("PLAYER_DEATH_NATIVE_OK: real players, netherite sword, both identities and phrases, environmental cause, independent death switch; data kernel without authenticated publication");
    }
}
