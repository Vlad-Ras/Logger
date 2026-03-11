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
        String planeName
) {

    /**
     * customTimeMinutes/customRadiusBlocks: -1 means "use presets".
     * Radius: 0 means WORLD.
     */
    public static final GuiFilters DEFAULT = new GuiFilters(1, 1, 0, -1, -1, "", "", "");

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
            },
            buf -> {
                int t = buf.readVarInt();
                int r = buf.readVarInt();
                int y = buf.readVarInt();
                int ctm = buf.readVarInt();
                int crb = buf.readVarInt();
                String actor = buf.readUtf(64);
                String train = buf.readUtf(64);
                String planeName = "";
                try {
                    planeName = buf.readUtf(64);
                } catch (Throwable ignored) {
                    // backward compat
                }
                return new GuiFilters(t, r, y, ctm, crb, actor, train, planeName);
            }
    );
}
