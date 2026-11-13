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

/** Draws the bounded plan markers in the world without placing preview blocks or entities. */
@OnlyIn(Dist.CLIENT)
public final class RollbackPreviewRenderer {

    private static net.minecraft.resources.ResourceLocation dimension;
    private static AABB boundary;
    private static long expiresAt;
    private static java.util.List<Shape> shapes = java.util.List.of();
    private record Shape(AABB bounds, float red, float green, float blue) {}

    private RollbackPreviewRenderer() {}

    public static void accept(S2CRollbackPreviewPayload payload) {
        clear();
        if (payload == null || !payload.visible()) return;
        dimension = net.minecraft.resources.ResourceLocation.tryParse(payload.dimension());
        if (dimension == null) return;
        expiresAt = payload.expiresAt();
        if ((long) payload.maxX() - payload.minX() <= 1024 && (long) payload.maxZ() - payload.minZ() <= 1024) {
            boundary = new AABB(payload.minX(), payload.minY(), payload.minZ(),
                    payload.maxX() + 1.0, payload.maxY() + 1.0, payload.maxZ() + 1.0);
        }
        var level = Minecraft.getInstance().level;
        var blockBounds = new java.util.HashMap<String, AABB>();
        var next = new java.util.ArrayList<Shape>(payload.markers().size());
        for (var marker : payload.markers()) {
            AABB bounds = new AABB(marker.pos());
            if (marker.kind() == S2CRollbackPreviewPayload.Kind.BLOCK && !marker.id().isEmpty()) {
                if (level != null && level.dimension().location().equals(dimension)) {
                    var relative = blockBounds.computeIfAbsent(marker.id(), RollbackPreviewRenderer::blockBounds);
                    bounds = relative.move(marker.pos().getX(), marker.pos().getY(), marker.pos().getZ());
                }
            }
            bounds = bounds.inflate(0.004);
            next.add(switch (marker.kind()) {
                case BLOCK -> new Shape(bounds, 0.25F, 1.0F, 0.5F);
                case REMOVE -> new Shape(bounds, 1.0F, 0.3F, 0.25F);
                case CONTAINER -> new Shape(bounds, 1.0F, 0.75F, 0.2F);
                case ENTITY -> new Shape(bounds.inflate(0.2), 0.65F, 0.45F, 1.0F);
            });
        }
        shapes = java.util.List.copyOf(next);
    }

    public static void clear() {
        dimension = null;
        boundary = null;
        expiresAt = 0;
        shapes = java.util.List.of();
    }

    private static AABB blockBounds(String id) {
        var key = net.minecraft.resources.ResourceLocation.tryParse(id);
        if (key != null) {
            var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(key);
            if (block.isPresent()) {
                try {
                    var outline = block.get().defaultBlockState().getShape(
                            net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO);
                    if (!outline.isEmpty()) return outline.bounds();
                } catch (RuntimeException ignored) {}
            }
        }
        return new AABB(0, 0, 0, 1, 1, 1);
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (dimension == null) return;

        long now = System.currentTimeMillis();
        if (expiresAt <= now) {
            clear();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) return;
        if (!minecraft.level.dimension().location().equals(dimension)) return;

        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = null;

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        if (boundary != null && event.getFrustum().isVisible(boundary)) {
            lines = buffers.getBuffer(RenderType.lines());
            float pulse = (float) (0.65 + 0.25 * Math.sin(now / 180.0));
            LevelRenderer.renderLineBox(poseStack, lines, boundary, 0.10F, 0.85F, 1.00F, pulse);
        }
        for (Shape shape : shapes) {
            var box = shape.bounds();
            double dx = box.minX - camera.x, dy = box.minY - camera.y, dz = box.minZ - camera.z;
            if (dx * dx + dy * dy + dz * dz <= 96.0 * 96.0 && event.getFrustum().isVisible(box)) {
                if (lines == null) lines = buffers.getBuffer(RenderType.lines());
                LevelRenderer.renderLineBox(poseStack, lines, box, shape.red(), shape.green(), shape.blue(), 0.85F);
            }
        }
        poseStack.popPose();
        if (lines != null) buffers.endBatch(RenderType.lines());
    }
}
