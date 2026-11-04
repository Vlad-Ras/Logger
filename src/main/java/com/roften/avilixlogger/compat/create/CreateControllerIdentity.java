package com.roften.avilixlogger.compat.create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.IControlContraption;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import java.util.UUID;

/** The controller retains identities between assembly cycles. Copies at another address get new IDs. */
public final class CreateControllerIdentity {
    private CreateControllerIdentity() {}
    public static void bind(Level world,IControlContraption controller,Contraption contraption) {
        if(!(world instanceof net.minecraft.server.level.ServerLevel level) || !level.getServer().isSameThread() || contraption.anchor==null)return;
        var pos=controller.getBlockPosition();var be=world.getBlockEntity(pos);if(be==null)return;
        String address=world.dimension().location()+"@"+pos.asLong();
        var data=be.getPersistentData();var tag=data.getCompound("AvilixControllerIdentities");
        if(!address.equals(tag.getString("Address"))){tag=new CompoundTag();tag.putString("Address",address);}
        String key=contraption.getType().holder.key().location()+"@"+contraption.anchor.subtract(pos).asLong();
        UUID id=tag.hasUUID(key)?tag.getUUID(key):UUID.randomUUID();
        // Clockwork can own several constructions. Bound pathological growth without tick scans.
        if(!tag.hasUUID(key)&&tag.getAllKeys().size()>=32)return;
        tag.putUUID(key,id);data.put("AvilixControllerIdentities",tag);be.setChanged();
        ((ContraptionIdentity)contraption).avilixlogger$identity(id);
    }
}
