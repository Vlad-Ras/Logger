package com.roften.avilixlogger.core;

import java.util.Locale;

/** Resolves explicit tick context and object classes without walking the server stack. */
public final class MutationSourceResolver {
    public static final String VANILLA_SIMULATION = "minecraft:simulation";
    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();
    private static final ClassValue<String> CLASS_SOURCES = new ClassValue<>() {
        @Override protected String computeValue(Class<?> type) { return classify(type.getName()); }
    };
    private static final String NONE = "";

    private MutationSourceResolver() {}

    public static String resolveExternalSource() {
        return CONTEXT.get();
    }

    public static String sourceFor(Object object) {
        if (object == null) return null;
        String source = CLASS_SOURCES.get(object.getClass());
        return source.isEmpty() ? null : source;
    }

    /** Allocation-free enter/restore for callbacks executed on every tick. */
    public static String enter(String source) {
        String previous = CONTEXT.get();
        CONTEXT.set(source);
        return previous;
    }

    public static void restore(String previous) { CONTEXT.set(previous); }

    public static Scope push(String source) { return new Scope(enter(source)); }

    public static final class Scope implements AutoCloseable {
        private final String previous;
        private boolean closed;
        private Scope(String previous) { this.previous = previous; }
        public void close() {
            if (closed) return;
            closed = true;
            restore(previous);
        }
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
