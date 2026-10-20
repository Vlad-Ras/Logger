package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.world.scores.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(Scoreboard.class)
public abstract class ConsumptionScoreboardLockMixin {
    @Inject(method="getOrCreatePlayerScore(Lnet/minecraft/world/scores/ScoreHolder;Lnet/minecraft/world/scores/Objective;Z)Lnet/minecraft/world/scores/ScoreAccess;",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$access(ScoreHolder holder,Objective objective,boolean writable,CallbackInfoReturnable<ScoreAccess> cir){
        if(!CartRestoreLocks.scoreHolderLocked(holder.getScoreboardName()))return;var value=((Scoreboard)(Object)this).getPlayerScoreInfo(holder,objective);
        cir.setReturnValue(new ScoreAccess(){public int get(){return value==null?0:value.value();}public void set(int ignored){}public boolean locked(){return value==null||value.isLocked();}
            public void unlock(){}public void lock(){}public Component display(){return value instanceof Score score?score.display():null;}public void display(Component ignored){}public void numberFormatOverride(NumberFormat ignored){}});
    }
    @Inject(method="resetAllPlayerScores",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$reset(ScoreHolder holder,CallbackInfo ci){if(CartRestoreLocks.scoreHolderLocked(holder.getScoreboardName()))ci.cancel();}
    @Inject(method="resetSinglePlayerScore",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$resetOne(ScoreHolder holder,Objective objective,CallbackInfo ci){if(CartRestoreLocks.scoreHolderLocked(holder.getScoreboardName()))ci.cancel();}
    @Inject(method="removeObjective",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$objective(Objective objective,CallbackInfo ci){if(CartRestoreLocks.handlerLocked(objective))ci.cancel();}
}
