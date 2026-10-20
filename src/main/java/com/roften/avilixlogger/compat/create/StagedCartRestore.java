package com.roften.avilixlogger.compat.create;

import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.AbstractMinecart;

/** Cart adapter for the common incremental contraption payload builder. */
public final class StagedCartRestore {
    private final CompoundTag cartTag;
    private final StagedContraptionRestore payload;
    public OrientedContraptionEntity entity;
    public AbstractMinecart cart;
    public StagedCartRestore(CompoundTag envelope){
        cartTag=envelope.getCompound("Cart").copy();cartTag.remove("Passengers");
        payload=envelope.getString("Form").equals("entity")?new StagedContraptionRestore(envelope.getCompound("Entity")):null;
    }
    public boolean step(ServerLevel level){
        if(cart==null){var restored=EntityType.loadEntityRecursive(cartTag,level,e->e);
            if(!(restored instanceof AbstractMinecart mc))throw new IllegalStateException("Снимок не содержит вагонетку");cart=mc;
            return payload==null;
        }
        if(payload==null)return true;
        boolean complete=payload.step(level);
        if(!(payload.entity instanceof OrientedContraptionEntity ce)||CreateCartAudit.state(ce)==null)throw new IllegalStateException("Снимок не содержит учтённую вагонеточную конструкцию");
        entity=ce;CreateCartAudit.state(entity).locked=true;return complete;
    }
}
