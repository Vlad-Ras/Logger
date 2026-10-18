package com.roften.avilixlogger.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Small filter state used by the optional GUI. */
public record GuiFilters(
        int timePresetIdx,
        int radiusPresetIdx,
        int typePresetIdx,
        int customTimeMinutes,
        int customRadiusBlocks,
        String actor,
        String train,
        String planeName,
        String blockId,
        long absoluteSinceTs,
        long absoluteUntilTs,
        String search,
        java.util.List<String> searchIds,
        java.util.List<String> blockIds
) {

    public GuiFilters {
        searchIds=com.roften.avilixlogger.core.LocalizedSearchTerms.checked(searchIds);
        blockIds=com.roften.avilixlogger.core.LocalizedSearchTerms.checked(blockIds);
    }
    public GuiFilters(int t,int r,int y,int minutes,int radius,String actor,String train,String plane,String block,long since,long until,String search){
        this(t,r,y,minutes,radius,actor,train,plane,block,since,until,search,java.util.List.of(),java.util.List.of());
    }
    private static void writeIds(RegistryFriendlyByteBuf buf,java.util.List<String> ids){buf.writeVarInt(ids.size());for(var id:ids)buf.writeUtf(id,128);}
    private static java.util.List<String> readIds(RegistryFriendlyByteBuf buf){
        int size=buf.readVarInt();if(size<0||size>com.roften.avilixlogger.core.LocalizedSearchTerms.MAX)throw new IllegalArgumentException("Слишком много ID поиска");
        var ids=new java.util.ArrayList<String>(size);for(int i=0;i<size;i++)ids.add(buf.readUtf(128));return ids;
    }
    /**
     * customTimeMinutes/customRadiusBlocks: -1 means "use presets".
     * Radius: 0 means WORLD.
     */
    public GuiFilters(int t,int r,int y,int minutes,int radius,String actor,String train,String plane,String block) {
        this(t,r,y,minutes,radius,actor,train,plane,block,-1,-1,"");
    }
    public static final GuiFilters DEFAULT = new GuiFilters(1, 1, 0, -1, -1, "", "", "", "");

    public static final StreamCodec<RegistryFriendlyByteBuf, GuiFilters> STREAM_CODEC = StreamCodec.of(
            (buf, f) -> {
                buf.writeVarInt(f.timePresetIdx);
                buf.writeVarInt(f.radiusPresetIdx);
                buf.writeVarInt(f.typePresetIdx);
                buf.writeVarInt(f.customTimeMinutes);
                buf.writeVarInt(f.customRadiusBlocks);
                buf.writeUtf(f.actor == null ? "" : f.actor, 64);
                buf.writeUtf(f.train == null ? "" : f.train, 64);
                buf.writeUtf(f.planeName == null ? "" : f.planeName, 64);
                buf.writeUtf(f.blockId == null ? "" : f.blockId, 128);
                buf.writeLong(f.absoluteSinceTs); buf.writeLong(f.absoluteUntilTs);
                buf.writeUtf(f.search == null ? "" : f.search, 128);
                writeIds(buf,f.searchIds);writeIds(buf,f.blockIds);
            },
            buf -> {
                int t = buf.readVarInt();
                int r = buf.readVarInt();
                int y = buf.readVarInt();
                int ctm = buf.readVarInt();
                int crb = buf.readVarInt();
                String actor = buf.readUtf(64);
                String train = buf.readUtf(64);
                String planeName = buf.readUtf(64);
                String blockId = buf.readUtf(128);
                return new GuiFilters(t, r, y, ctm, crb, actor, train, planeName, blockId, buf.readLong(), buf.readLong(), buf.readUtf(128),readIds(buf),readIds(buf));
            }
    );
}
