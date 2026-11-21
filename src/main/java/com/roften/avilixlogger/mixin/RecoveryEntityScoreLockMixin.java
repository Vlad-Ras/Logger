package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.RecoveryEntityReservations;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.scores.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Replay only mutates entities without external score state; keep that precondition stable. */
@Mixin(Scoreboard.class)
public abstract class RecoveryEntityScoreLockMixin {
    @WrapMethod(method="getOrCreatePlayerScore(Lnet/minecraft/world/scores/ScoreHolder;Lnet/minecraft/world/scores/Objective;Z)Lnet/minecraft/world/scores/ScoreAccess;")
    private ScoreAccess avilixlogger$recoveryScore(ScoreHolder holder,Objective objective,boolean force,Operation<ScoreAccess> original) {
        if(!RecoveryEntityReservations.scoreLocked(holder.getScoreboardName()))return original.call(holder,objective,force);
        var existing=((Scoreboard)(Object)this).getPlayerScoreInfo(holder,objective);
        return new ScoreAccess(){public int get(){return existing==null?0:existing.value();}public void set(int value){}public boolean locked(){return existing==null||existing.isLocked();}public void lock(){}public void unlock(){}public Component display(){return existing instanceof Score s?s.display():null;}public void display(Component value){}public void numberFormatOverride(NumberFormat format){}};
    }
    @Inject(method="addPlayerToTeam",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$recoveryTeam(String name,PlayerTeam team,CallbackInfoReturnable<Boolean> result) {
        if(RecoveryEntityReservations.scoreLocked(name))result.setReturnValue(false);
    }
}
