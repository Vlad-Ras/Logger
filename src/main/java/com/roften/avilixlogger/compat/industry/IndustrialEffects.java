package com.roften.avilixlogger.compat.industry;
import com.roften.avilixlogger.compat.teams.ModStateAudit;
import com.roften.avilixlogger.core.ActionType;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
public final class IndustrialEffects {
    private IndustrialEffects(){}
    public static void consumed(Level value,AABB area,FluidStack fluid,int amount){
        if(!(value instanceof ServerLevel level)||amount<=0||!ModStateAudit.enabled(level.getServer(),ActionType.MACHINE_CONTENT_CHANGE))return;
        try{
            var before=new CompoundTag();var pos=net.minecraft.core.BlockPos.containing(area.getCenter());
            before.putString("Dimension",level.dimension().location().toString());before.putInt("X",pos.getX());before.putInt("Y",pos.getY());before.putInt("Z",pos.getZ());
            before.putString("PositionMeaning","native-effect-area-center");
            if(!fluid.isEmpty())before.put("SuppliedFluid",fluid.save(level.registryAccess(),new CompoundTag()));
            var after=before.copy();after.putInt("NativeReturnedConsumptionMB",amount);after.putBoolean("PipeDrainIndependentlyConfirmed",false);
            ModStateAudit.receipt(level.getServer(),"create_enchantment_industry","experience-fluid-effect",ActionType.MACHINE_CONTENT_CHANGE,before,after);
        }catch(RuntimeException|LinkageError failure){IndustrialAudit.fail("experience-effect");}
    }
}
