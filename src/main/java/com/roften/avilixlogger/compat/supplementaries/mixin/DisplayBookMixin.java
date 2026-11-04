package com.roften.avilixlogger.compat.supplementaries.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.supplementaries.SupplementariesAutomationAudit;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import java.util.List;

@Pseudo @Mixin(targets="net.mehvahdjukaar.supplementaries.integration.create.NoticeBoardDisplayTarget",remap=false)
public abstract class DisplayBookMixin {
    private static final String METHOD="acceptText(ILjava/util/List;Lcom/simibubi/create/content/redstone/displayLink/DisplayLinkContext;)V";
    @WrapMethod(method=METHOD)
    private void avilixlogger$display(int first,List<MutableComponent> input,DisplayLinkContext context,Operation<Void> original){
        var frame=SupplementariesAutomationAudit.enter(first,input);
        if(frame==null){original.call(first,input,context);return;}
        boolean completed=false;try{original.call(first,input,context);completed=true;}finally{frame.finish(completed);}
    }
    @WrapOperation(method=METHOD,at=@At(value="INVOKE",target="Lcom/simibubi/create/content/redstone/displayLink/DisplayLinkContext;getTargetBlockEntity()Lnet/minecraft/world/level/block/entity/BlockEntity;"),require=1)
    private BlockEntity avilixlogger$nativeTarget(DisplayLinkContext context,Operation<BlockEntity> original){
        var entity=original.call(context);
        if(entity!=null&&CartRestoreLocks.blockLocked(entity.getLevel(),entity.getBlockPos()))return null;
        var frame=SupplementariesAutomationAudit.current();
        if(frame!=null)try{frame.bind(entity,context.blockEntity().getBlockPos());}
        catch(RuntimeException|LinkageError failure){AdaptiveLogDiagnostics.adapterFailure("supplementaries-display-target");}
        return entity;
    }
    @WrapOperation(method=METHOD,at=@At(value="INVOKE",target="Lnet/mehvahdjukaar/supplementaries/common/block/tiles/NoticeBoardBlockTile;getDisplayedItem()Lnet/minecraft/world/item/ItemStack;"),require=1)
    private ItemStack avilixlogger$nativeBook(@Coerce Object board,Operation<ItemStack> original){
        ItemStack book=original.call(board);var frame=SupplementariesAutomationAudit.current();
        if(frame!=null)frame.book(book);return book;
    }
}
