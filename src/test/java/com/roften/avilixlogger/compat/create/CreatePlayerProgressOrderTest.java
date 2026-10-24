package com.roften.avilixlogger.compat.create;

import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;

/** The allocation-free comparator must preserve old persisted NBT order exactly. */
public final class CreatePlayerProgressOrderTest {
    private CreatePlayerProgressOrderTest() {}
    public static void run() {
        var ids = new ArrayList<ResourceLocation>();
        for (String namespace : List.of("a", "a-", "a.", "a0", "a9", "a_", "afoo", "b", "create", "minecraft")) {
            for (String path : List.of("a", "zzz", "test/one")) {
                ids.add(ResourceLocation.fromNamespaceAndPath(namespace, path));
            }
        }
        for (var left : ids) for (var right : ids) {
            int oldOrder = Integer.signum(left.toString().compareTo(right.toString()));
            int newOrder = Integer.signum(CreatePlayerProgress.compareAdvancementIds(left, right));
            if (oldOrder != newOrder)
                throw new AssertionError("Advancement ordering changed persisted snapshot: " + left + " / " + right);
        }
        System.out.println("ADVANCEMENT_ORDER_OK: legacy lexicographic ordering preserved without per-comparison strings");
    }
}
