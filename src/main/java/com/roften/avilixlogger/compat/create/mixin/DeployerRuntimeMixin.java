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
        tag.put("Progress",com.roften.avilixlogger.compat.create.CreatePlayerProgress.capture(self));
        tag.put("Scores",com.roften.avilixlogger.compat.create.CreateScoreRuntime.capture(self));
        var physical=new CompoundTag();physical.putInt("Fire",self.getRemainingFireTicks());physical.putBoolean("Sprinting",self.isSprinting());
        var motion=new net.minecraft.nbt.ListTag();for(double value:new double[]{self.getDeltaMovement().x,self.getDeltaMovement().y,self.getDeltaMovement().z})motion.add(net.minecraft.nbt.DoubleTag.valueOf(value));physical.put("Motion",motion);tag.put("Physical",physical);
        var metadata=((CreateOriginDataAccessor)self).avilixlogger$originData();physical.put("PersistentData",metadata==null?new CompoundTag():metadata.copy());
        return tag;
    }
    public void avilixlogger$deployerRuntime(CompoundTag tag){
        var self=(DeployerFakePlayer)(Object)this;
        if(tag.contains("Living",10))CreateLivingRuntime.restore(self,self,tag.getCompound("Living"));
        if(tag.contains("Cooldowns",10))((CreateCooldownRuntimeAccess)self.getCooldowns()).avilixlogger$cooldownRuntime(tag.getCompound("Cooldowns"));
        if(tag.contains("Progress",10))com.roften.avilixlogger.compat.create.CreatePlayerProgress.restore(self,tag.getCompound("Progress"));
        if(tag.contains("Scores",10))com.roften.avilixlogger.compat.create.CreateScoreRuntime.restore(self,tag.getCompound("Scores"));
        if(tag.contains("Physical",10)){
            var physical=tag.getCompound("Physical");CreateDeployerRuntimeAccess.validatePhysical(physical);var motion=physical.getList("Motion",6);
            self.setRemainingFireTicks(physical.getInt("Fire"));self.setSprinting(physical.getBoolean("Sprinting"));self.setDeltaMovement(motion.getDouble(0),motion.getDouble(1),motion.getDouble(2));
            ((CreateOriginDataAccessor)self).avilixlogger$originData(physical.getCompound("PersistentData").isEmpty()?null:physical.getCompound("PersistentData").copy());
        }
        placedTracks=tag.getBoolean("PlacedTracks");
        blockBreakingProgress=tag.contains("BreakingPos",4)?Pair.of(BlockPos.of(tag.getLong("BreakingPos")),tag.getFloat("BreakingProgress")):null;
        spawnedItemEffects=com.roften.avilixlogger.core.NbtSerde.readItemStack(tag.getCompound("Effects"),((DeployerFakePlayer)(Object)this).registryAccess());
    }
}
