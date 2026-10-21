package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.roften.avilixlogger.compat.create.CreateScoreRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.scores.*;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(Scoreboard.class)
public abstract class ScoreboardRuntimeReservationMixin {
    @WrapMethod(method="getOrCreatePlayerScore(Lnet/minecraft/world/scores/ScoreHolder;Lnet/minecraft/world/scores/Objective;Z)Lnet/minecraft/world/scores/ScoreAccess;")
    private ScoreAccess avilixlogger$score(ScoreHolder holder,Objective objective,boolean force,Operation<ScoreAccess> original){
        if(!CartRestoreLocks.resourceLocked("create-score:"+holder.getScoreboardName()))return original.call(holder,objective,force);
        var existing=((Scoreboard)(Object)this).getPlayerScoreInfo(holder,objective);
        return new ScoreAccess(){public int get(){return existing==null?0:existing.value();}public void set(int value){}public boolean locked(){return existing==null||existing.isLocked();}public void lock(){}public void unlock(){}public Component display(){return existing instanceof Score s?s.display():null;}public void display(Component value){}public void numberFormatOverride(NumberFormat format){}};
    }
    @Inject(method="resetSinglePlayerScore",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$reset(ScoreHolder holder,Objective objective,CallbackInfo ci){if(CartRestoreLocks.resourceLocked("create-score:"+holder.getScoreboardName()))ci.cancel();}
    @Inject(method="resetAllPlayerScores",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$resetAll(ScoreHolder holder,CallbackInfo ci){if(CartRestoreLocks.resourceLocked("create-score:"+holder.getScoreboardName()))ci.cancel();}
    @Inject(method="removeObjective",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$remove(Objective objective,CallbackInfo ci){if(CartRestoreLocks.resourceLocked(CreateScoreRuntime.LOCK))ci.cancel();}
    @Inject(method="addObjective",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$add(CallbackInfoReturnable<Objective> ci){if(CartRestoreLocks.resourceLocked(CreateScoreRuntime.LOCK))throw new IllegalStateException("Scoreboard зарезервирован откатом активатора");}
    @Inject(method="addPlayerToTeam",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$joinTeam(String holder,PlayerTeam team,CallbackInfoReturnable<Boolean> ci){if(CartRestoreLocks.resourceLocked("create-score:"+holder))ci.setReturnValue(false);}
    @Inject(method="removePlayerFromTeam(Ljava/lang/String;)Z",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$leaveTeam(String holder,CallbackInfoReturnable<Boolean> ci){if(CartRestoreLocks.resourceLocked("create-score:"+holder))ci.setReturnValue(false);}
    @Inject(method="removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$leaveTeam(String holder,PlayerTeam team,CallbackInfo ci){if(CartRestoreLocks.resourceLocked("create-score:"+holder))ci.cancel();}
    @Inject(method="removePlayerTeam",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$removeTeam(PlayerTeam team,CallbackInfo ci){if(CartRestoreLocks.resourceLocked(CreateScoreRuntime.LOCK))ci.cancel();}

}
