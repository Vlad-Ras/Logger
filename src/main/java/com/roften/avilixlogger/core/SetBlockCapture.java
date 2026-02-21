package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;

import java.util.UUID;

/**
 * Small DTO used by {@code ServerLevelSetBlockMixin} to remember pre-state around a setBlock call.
 *
 * <p>IMPORTANT: This class must NOT live in the mixin package. Mixin packages are restricted and
 * classes from them cannot be referenced by transformed target classes.</p>
 */
public record SetBlockCapture(BlockPos pos, String dim, String beforeState, String beforeBe, String source,
                              UUID actorUuid, String actorName) {
}
