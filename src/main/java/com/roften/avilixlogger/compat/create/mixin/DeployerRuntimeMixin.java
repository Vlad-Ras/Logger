package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateDeployerRuntimeAccess;
import com.roften.avilixlogger.compat.create.CreateLivingRuntime;
import com.roften.avilixlogger.compat.create.CreateCooldownRuntimeAccess;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.*;
@Mixin(value=DeployerFakePlayer.class,remap=false)
public abstract class DeployerRuntimeMixin implements CreateDeployerRuntimeAccess {
    @Shadow Pair<BlockPos,Float> blockBreakingProgress;
    @Shadow ItemStack spawnedItemEffects;
    @Shadow public boolean placedTracks;
    public CompoundTag avilixlogger$deployerRuntime(){
        var tag=new CompoundTag();tag.putBoolean("PlacedTracks",placedTracks);
        if(blockBreakingProgress!=null){tag.putLong("BreakingPos",blockBreakingProgress.getKey().asLong());tag.putFloat("BreakingProgress",blockBreakingProgress.getValue());}
        if(spawnedItemEffects!=null&&!spawnedItemEffects.isEmpty())tag.put("Effects",com.roften.avilixlogger.core.NbtSerde.snapshotItemStack(spawnedItemEffects,((DeployerFakePlayer)(Object)this).registryAccess()));
        var self=(DeployerFakePlayer)(Object)this;tag.put("Living",CreateLivingRuntime.capture(self,self));
        tag.put("Cooldowns",((CreateCooldownRuntimeAccess)self.getCooldowns()).avilixlogger$cooldownRuntime());
        return tag;
    }
    public void avilixlogger$deployerRuntime(CompoundTag tag){
        var self=(DeployerFakePlayer)(Object)this;
        if(tag.contains("Living",10))CreateLivingRuntime.restore(self,self,tag.getCompound("Living"));
        if(tag.contains("Cooldowns",10))((CreateCooldownRuntimeAccess)self.getCooldowns()).avilixlogger$cooldownRuntime(tag.getCompound("Cooldowns"));
        placedTracks=tag.getBoolean("PlacedTracks");
        blockBreakingProgress=tag.contains("BreakingPos",4)?Pair.of(BlockPos.of(tag.getLong("BreakingPos")),tag.getFloat("BreakingProgress")):null;
        spawnedItemEffects=com.roften.avilixlogger.core.NbtSerde.readItemStack(tag.getCompound("Effects"),((DeployerFakePlayer)(Object)this).registryAccess());
    }
}
