package com.roften.avilixlogger.core;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves an arbitrary mod call-site without maintaining a hard-coded compatibility list. */
public final class MutationSourceResolver {
    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static final ConcurrentHashMap<String, String> CLASS_SOURCES = new ConcurrentHashMap<>();
    private static final String NONE = "";

    private MutationSourceResolver() {}

    public static String resolveExternalSource() {
        try {
            return WALKER.walk(frames -> frames.limit(64)
                    .map(StackWalker.StackFrame::getClassName)
                    .map(MutationSourceResolver::sourceForClass)
                    .filter(source -> source != null && !source.isBlank())
                    .findFirst().orElse(null));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String sourceForClass(String className) {
        if (className == null || className.isBlank()) return null;
        String cached = CLASS_SOURCES.computeIfAbsent(className, MutationSourceResolver::classify);
        return cached.isEmpty() ? null : cached;
    }

    private static String classify(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        if (isInfrastructure(lower)) return NONE;
        if (lower.startsWith("com.sk89q.worldedit.")) return "mod:worldedit";
        if (lower.startsWith("com.simibubi.create.")) return "mod:create";
        if (lower.startsWith("dev.eriksonn.aeronautics.") || lower.startsWith("dev.ryanhcode.sable.")
                || lower.startsWith("dev.simulated_team.simulated.")) return "mod:aeronautics";

        String packageName = className;
        int classSeparator = packageName.lastIndexOf('.');
        if (classSeparator <= 0) return NONE;
        packageName = packageName.substring(0, classSeparator);
        String[] parts = packageName.split("\\.");
        int take = Math.min(parts.length, 3);
        if (take <= 0) return NONE;
        StringBuilder id = new StringBuilder("mod:");
        for (int i = 0; i < take; i++) {
            if (i > 0) id.append('.');
            id.append(parts[i].toLowerCase(Locale.ROOT));
        }
        return id.toString();
    }

    private static boolean isInfrastructure(String name) {
        return name.startsWith("com.roften.avilixlogger.")
                || name.startsWith("net.minecraft.")
                || name.startsWith("net.neoforged.")
                || name.startsWith("org.spongepowered.")
                || name.startsWith("org.objectweb.asm.")
                || name.startsWith("org.jetbrains.")
                || name.startsWith("com.mojang.")
                || name.startsWith("com.llamalad7.mixinextras.")
                || name.startsWith("com.electronwill.nightconfig.")
                || name.startsWith("cpw.mods.")
                || name.startsWith("net.minecraftforge.")
                || name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jdk.")
                || name.startsWith("sun.")
                || name.startsWith("io.netty.")
                || name.startsWith("it.unimi.")
                || name.startsWith("com.google.")
                || name.startsWith("org.apache.")
                || name.startsWith("org.slf4j.");
    }
}
