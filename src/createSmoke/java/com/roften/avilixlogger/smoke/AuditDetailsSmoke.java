package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import java.util.*;

/** Native sign editing and DamageSource identity; publication still requires production AuthCore. */
final class AuditDetailsSmoke {
    static void run(ServerLevel level){
        var config=com.roften.avilixlogger.LoggerConfig.VALUES;
        int cpu=config.asyncMaxQueuedPayloadMiB.get(),journal=config.clickHouseMaxQueuedPayloadMiB.get();
        try{
            config.asyncMaxQueuedPayloadMiB.set(128);config.clickHouseMaxQueuedPayloadMiB.set(256);
            check(com.roften.avilixlogger.LoggerConfig.captureQueueBytes()<=16L*1024*1024&&com.roften.avilixlogger.LoggerConfig.journalQueueBytes()<=32L*1024*1024,"old config bypassed new memory ceilings");
        }finally{config.asyncMaxQueuedPayloadMiB.set(cpu);config.clickHouseMaxQueuedPayloadMiB.set(journal);}
        System.out.println("MEMORY_UPGRADE_NATIVE_OK: previous 128/256 MiB values respect new per-queue ceilings");
        var editor=new ServerPlayer(level.getServer(),level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"SignEditor"),net.minecraft.server.level.ClientInformation.createDefault());
        BlockPos pos=level.getSharedSpawnPos().offset(300,90,280);
        level.setBlock(pos,Blocks.OAK_SIGN.defaultBlockState(),3);var sign=(SignBlockEntity)level.getBlockEntity(pos);
        var original=NbtSerde.snapshotBlockEntity(level,sign);
        sign.setAllowedPlayerEditor(editor.getUUID());
        sign.updateSignText(editor,true,List.of(net.minecraft.server.network.FilteredText.passThrough("Первая"),net.minecraft.server.network.FilteredText.passThrough("Строка"),net.minecraft.server.network.FilteredText.passThrough(""),net.minecraft.server.network.FilteredText.passThrough("")));
        check(sign.getText(true).getMessages(false)[0].getString().equals("Первая"),"native front edit failed");
        var front=NbtSerde.snapshotBlockEntity(level,sign);check(SignTextAudit.changed(original,front),"front edit lost");
        sign.setAllowedPlayerEditor(editor.getUUID());sign.updateSignText(editor,false,List.of(net.minecraft.server.network.FilteredText.passThrough("Обратная"),net.minecraft.server.network.FilteredText.passThrough(""),net.minecraft.server.network.FilteredText.passThrough(""),net.minecraft.server.network.FilteredText.passThrough("")));
        var both=NbtSerde.snapshotBlockEntity(level,sign);check(SignTextAudit.changed(front,both),"back edit lost");
        LogEntry row;
        try(var cause=CauseContext.push(editor,CauseContext.Kind.USE_BLOCK,pos,ItemStack.EMPTY)){row=SignTextAudit.row(level,sign,original,both);}
        check(row!=null&&row.actorUuid.equals(editor.getUUID())&&row.actorName.equals("SignEditor"),"sign author lost");row.materializeSnapshots();
        String details=SignLogDetails.describe(level,row).stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));
        check(details.contains("Первая")&&details.contains("Обратная")&&details.contains("До [1]")&&details.contains("После [1]"),"both before/after sign sides not rendered");
        check(!SignTextAudit.changed(both,both.copy()),"no-op text created an edit");
        sign.setText(sign.getText(true).setHasGlowingText(true),true);check(!SignTextAudit.changed(both,NbtSerde.snapshotBlockEntity(level,sign)),"glow-only change became a text edit");
        CartAuditContext.rollbackActive=true;CartRestoreLocks.block(level,pos);
        try{
            var replacement=sign.getText(true).setMessage(1,Component.literal("Reserved"));
            check(!sign.setText(replacement,true),"reserved sign accepted a direct edit");
            boolean restoring=CartAuditContext.restoring(true);
            try{check(sign.setText(replacement,true),"rollback could not restore its reserved sign");}finally{CartAuditContext.restoring(restoring);}
        }finally{CartRestoreLocks.clear();CartAuditContext.rollbackActive=false;}
        sign.setWaxed(true);sign.setAllowedPlayerEditor(editor.getUUID());sign.updateSignText(editor,true,List.of(net.minecraft.server.network.FilteredText.passThrough("Refused")));
        check(sign.getText(true).getMessages(false)[0].getString().equals("Первая"),"waxed sign was changed");level.removeBlock(pos,false);
        var arrow=new Arrow(level,editor,new ItemStack(Items.ARROW),null);var source=level.damageSources().arrow(arrow,editor);
        var death=new LogEntry();death.type=ActionType.PLAYER_DEATH;death.actorName="Victim";death.extra=DeathLogDetails.capture(new ServerPlayer(level.getServer(),level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"Victim"),net.minecraft.server.level.ClientInformation.createDefault()),source,"Victim was shot by SignEditor");
        String context=DeathLogDetails.describe(death).stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));
        check(context.contains("minecraft:arrow")&&context.contains(arrow.getUUID().toString())&&context.contains(editor.getUUID().toString())&&context.contains("Ответственный источник"),"projectile/direct/responsible damage identities collapsed");
        check(DeathLogDetails.message(death).equals("Victim was shot by SignEditor"),"death text lost");
        death.extra=DeathLogDetails.capture(editor,level.damageSources().fall(),"fall");check(!DeathLogDetails.describe(death).stream().anyMatch(c->c.getString().startsWith("Ответственный источник")),"environmental death invented a killer");
        death.extra="death_message:legacy victim";check(DeathLogDetails.message(death).equals("legacy victim"),"old death text unreadable");
        death.extra="{\"AvilixDeath\":{\"Version\":1,\"Message\":{}}}";check(DeathLogDetails.message(death)==null,"malformed context broke fallback");
        System.out.println("DEATH_SIGN_NATIVE_OK: native front/back edits, wax refusal, exact author, before/after GUI, no-op/glow separation, projectile and responsible UUIDs, environment, legacy and corrupt context");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
