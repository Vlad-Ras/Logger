package com.roften.avilixlogger.client.gui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.roften.avilixlogger.net.S2CRollbackPreviewPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Draws the finite rollback preview as a pulsing cyan box in the world. */
@OnlyIn(Dist.CLIENT)
public final class RollbackPreviewRenderer {

    private static S2CRollbackPreviewPayload preview;

    private RollbackPreviewRenderer() {}

    public static void accept(S2CRollbackPreviewPayload payload) {
        preview = payload != null && payload.visible() ? payload : null;
    }

    public static void clear() {
        preview = null;
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        S2CRollbackPreviewPayload current = preview;
        if (current == null) return;

        long now = System.currentTimeMillis();
        if (current.expiresAt() <= now) {
            preview = null;
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) return;
        String currentDimension = minecraft.level.dimension().location().toString();
        if (!currentDimension.equals(current.dimension())) return;

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());

        double pulse = 0.65D + 0.25D * Math.sin(now / 180.0D);
        AABB box = new AABB(
                current.minX(), current.minY(), current.minZ(),
                current.maxX() + 1.0D, current.maxY() + 1.0D, current.maxZ() + 1.0D
        ).inflate(0.002D);

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        LevelRenderer.renderLineBox(poseStack, lines, box, 0.10F, 0.85F, 1.00F, (float) pulse);
        poseStack.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
