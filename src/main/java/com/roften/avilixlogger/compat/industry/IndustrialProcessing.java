package com.roften.avilixlogger.compat.industry;

import com.roften.avilixlogger.compat.teams.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Printer candidates and native belt installation are separate pieces of evidence. */
public final class IndustrialProcessing {
    private static final ThreadLocal<Print> PRINT = new ThreadLocal<>();
    private IndustrialProcessing() {}
    public static IndustrialAudit.Action commitTick(Object value) {
        if(!IndustrialAudit.handles(value)||!com.roften.avilixlogger.compat.machines.MachineAudit.policy((BlockEntity)value,ActionType.MACHINE_CONTENT_CHANGE))return null;
        try{
            String name=value.getClass().getSimpleName();
            if((name.equals("BlazeEnchanterBlockEntity")||name.equals("ClassicBlazeEnchanterBlockEntity")||name.equals("BlazeForgerBlockEntity"))&&(int)StateAccess.field(value,"processingTime")==0)
                return IndustrialAudit.begin(value,"enchanting-completion",true);
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){IndustrialAudit.fail("commit-marker");}
        return null;
    }
    public static Print print(Object value,Object transported) {
        if(!IndustrialAudit.handles(value))return null;
        var be=(BlockEntity)value;
        try{
            if((int)StateAccess.field(be,"processingTicks")!=5)return null;
            var frame=new Print(be,transported,PRINT.get());
            IndustrialFluids.flushOwner(be);
            frame.action=ModStateAudit.begin(((ServerLevel)be.getLevel()).getServer(),"create_enchantment_industry","native-printing",
                    be,"industrial",ActionType.MACHINE_CONTENT_CHANGE,frame::snapshot);
            if(frame.action==null)return null;
            PRINT.set(frame);return frame;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError failure){IndustrialAudit.fail("print-before");return null;}
    }
    public static void candidate(ItemStack result){var frame=PRINT.get();if(frame!=null)frame.candidate=result.copy();}
    public static void installed(){var frame=PRINT.get();if(frame!=null)frame.installed=true;}
    public static final class Print {
        private final BlockEntity be;private final Object transported;private final Print parent;
        private ModStateAudit.Action action;private ItemStack candidate=ItemStack.EMPTY;private boolean installed;
        private Print(BlockEntity be,Object transported,Print parent){this.be=be;this.transported=transported;this.parent=parent;}
        private CompoundTag snapshot() throws ReflectiveOperationException {
            var tag=IndustrialAudit.contents(be);
            tag.put("TransportedInputOrRemainder",((ItemStack)StateAccess.field(transported,"stack")).saveOptional(be.getLevel().registryAccess()));
            tag.put("CandidateOutput",candidate.saveOptional(be.getLevel().registryAccess()));
            tag.putBoolean("NativeBeltInstallationReturned",installed);return tag;
        }
        public void finish(boolean threw){try{action.finish(threw);}finally{if(parent==null)PRINT.remove();else PRINT.set(parent);IndustrialAudit.baseline(be);}}
    }
}
