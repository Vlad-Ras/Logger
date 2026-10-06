package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.compat.create.mixin.CartContraptionAccessor;
import com.roften.avilixlogger.compat.create.mixin.CartStorageAccessor;
import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorage;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import net.createmod.catnip.nbt.NBTHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.apache.commons.lang3.tuple.MutablePair;
import java.util.*;

/** Build detached entities incrementally; never call Create's monolithic disassemble during rollback. */
public final class StagedCartRestore {
    private final CompoundTag original, skeleton, cartTag;
    private final List<Part> parts;
    private int index;
    public OrientedContraptionEntity entity;
    public AbstractMinecart cart;
    private Contraption contraption;
    private record Part(String kind, CompoundTag nbt) {}
    /** Called on the preparation worker: all deep copying and list traversal happens here. */
    public StagedCartRestore(CompoundTag envelope) {
        original = envelope.getCompound("Entity"); skeleton = original.copy(); cartTag = envelope.getCompound("Cart").copy();
        CompoundTag full = original.getCompound("Contraption"), base = skeleton.getCompound("Contraption");
        parts = new ArrayList<>();
        CompoundTag paletted = full.getCompound("Blocks");
        ListTag palette = paletted.getList("Palette", Tag.TAG_COMPOUND);
        for (Tag t : paletted.getList("BlockList",Tag.TAG_COMPOUND)) {
            CompoundTag n = ((CompoundTag)t).copy(); int state = n.getInt("State");
            if (state < 0 || state >= palette.size()) throw new IllegalStateException("Неверная палитра конструкции");
            n.put("BlockState", palette.getCompound(state)); parts.add(new Part("block",n));
        }
        if (parts.isEmpty()) throw new IllegalStateException("Снимок конструкции не содержит блоков");
        for (String key : new String[]{"Actors","items","fluids","Superglue","Seats","Passengers","Interactors"}) {
            for (Tag t : full.getList(key,Tag.TAG_COMPOUND)) parts.add(new Part(key, ((CompoundTag)t).copy()));
            base.remove(key);
        }
        for (Tag t : full.getList("CapturedMultiblocks",Tag.TAG_COMPOUND)) {
            CompoundTag group = (CompoundTag)t;
            for (Tag p : group.getList("Parts",Tag.TAG_COMPOUND)) {
                CompoundTag part = ((CompoundTag)p).copy(); part.put("Controller", group.get("Controller")); parts.add(new Part("multiblock",part));
            }
        }
        base.remove("CapturedMultiblocks"); base.remove("DisabledActors");
        base.put("Blocks", new CompoundTag()); base.getCompound("Blocks").put("Palette",new ListTag()); base.getCompound("Blocks").put("BlockList",new ListTag());
        skeleton.remove("Passengers"); cartTag.remove("Passengers");
    }
    public boolean step(ServerLevel level) {
        if (entity == null) {
            var base = EntityType.loadEntityRecursive(cartTag,level,e->e);
            var mounted = EntityType.loadEntityRecursive(skeleton,level,e->e);
            if (!(base instanceof AbstractMinecart mc) || !(mounted instanceof OrientedContraptionEntity oce)) throw new IllegalStateException("Create не смог создать вагонетку");
            cart = mc; entity = oce; contraption = oce.getContraption();
            CreateCartAudit.state(entity).locked = true;
            ((CartStorageAccessor)contraption.getStorage()).avilixlogger$reset();
            return false;
        }
        if (index >= parts.size()) {
            contraption.getStorage().initialize();
            // Disabled-actor masks are restored after actors exist.
            ListTag disabled = original.getCompound("Contraption").getList("DisabledActors",Tag.TAG_COMPOUND);
            for (Tag t : disabled) { var stack = net.minecraft.world.item.ItemStack.parseOptional(level.registryAccess(), (CompoundTag)t); contraption.setActorsActive(stack,false); }
            return true;
        }
        Part part = parts.get(index++); CompoundTag n = part.nbt; var access = (CartContraptionAccessor)contraption;
        switch (part.kind) {
            case "block" -> {
                BlockPos pos = BlockPos.of(n.getLong("Pos"));
                var state = NbtUtils.readBlockState(level.holderLookup(Registries.BLOCK),n.getCompound("BlockState"));
                contraption.getBlocks().put(pos,new StructureBlockInfo(pos,state,n.contains("Data") ? n.getCompound("Data") : null));
                if (n.contains("UpdateTag")) access.avilixlogger$updateTags().put(pos,n.getCompound("UpdateTag"));
                if (n.contains("Legacy")) contraption.getIsLegacy().put(pos,true);
            }
            case "Actors" -> {
                var info = contraption.getBlocks().get(NBTHelper.readBlockPos(n,"Pos"));
                if (info == null) throw new IllegalStateException("Актор без блока");
                contraption.getActors().add(MutablePair.of(info,MovementContext.readNBT(level,info,n,contraption)));
            }
            case "items" -> {
                var storage = MountedItemStorage.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),n.get("storage")).getOrThrow();
                ((CartStorageAccessor)contraption.getStorage()).avilixlogger$addItem(storage,NBTHelper.readBlockPos(n,"pos"));
            }
            case "fluids" -> {
                var storage = MountedFluidStorage.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),n.get("storage")).getOrThrow();
                ((CartStorageAccessor)contraption.getStorage()).avilixlogger$addFluid(storage,NBTHelper.readBlockPos(n,"pos"));
            }
            case "Superglue" -> access.avilixlogger$superglue().add(SuperGlueEntity.readBoundingBox(n));
            case "Seats" -> contraption.getSeats().add(NBTHelper.readBlockPos(n,"Pos"));
            case "Passengers" -> contraption.getSeatMapping().put(n.getUUID("Id"),n.getInt("Seat"));
            case "Interactors" -> {
                BlockPos pos = NBTHelper.readBlockPos(n,"Pos"); var info = contraption.getBlocks().get(pos);
                if (info != null) { var behaviour = MovingInteractionBehaviour.REGISTRY.get(info.state()); if (behaviour != null) contraption.getInteractors().put(pos,behaviour); }
            }
            case "multiblock" -> {
                var info = contraption.getBlocks().get(NBTHelper.readBlockPos(n,"Pos"));
                if (info != null) access.avilixlogger$multiblocks().put(NBTHelper.readBlockPos(n,"Controller"),info);
            }
            default -> throw new IllegalStateException("Неизвестная часть снимка");
        }
        return false;
    }
}
