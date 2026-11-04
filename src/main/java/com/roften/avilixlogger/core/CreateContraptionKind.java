package com.roften.avilixlogger.core;

import java.util.UUID;

/** Exact Create registry types. No dependency on an optional mod or entity class names. */
public enum CreateContraptionKind {
    MINECART("cart", "Вагонеточная конструкция"),
    TRAIN("train", "Поезд"),
    BEARING("bearing", "Конструкция на подшипнике"),
    OTHER("contraption", "Другая конструкция Create"),
    UNKNOWN("", "Неизвестный тип конструкции");

    private final String label;
    private final String prefix;
    private static final CreateContraptionKind[] KINDS = values();
    CreateContraptionKind(String key, String label) { this.label = label; this.prefix = key.isEmpty() ? "" : "create:" + key + ":"; }
    public String label() { return label; }
    public String prefix() { return prefix; }
    public static CreateContraptionKind forGuiPreset(int preset) {
        return switch (preset) {
            case 5 -> TRAIN;
            case 9 -> MINECART;
            case 11 -> BEARING;
            case 12 -> OTHER;
            default -> UNKNOWN;
        };
    }
    public String source(UUID id, String phase) {
        if (this == UNKNOWN || id == null) throw new IllegalArgumentException("A known construction and ID are required");
        return prefix() + id + ":" + phase;
    }
    public static CreateContraptionKind fromType(String type) {
        if (type == null) return UNKNOWN;
        return switch (type) {
            case "create:mounted" -> MINECART;
            case "create:carriage" -> TRAIN;
            case "create:bearing", "create:clockwork" -> BEARING;
            case "create:piston", "create:pulley", "create:gantry", "create:elevator", "create:stabilized" -> OTHER;
            default -> UNKNOWN;
        };
    }
    /** Hot-path type lookup without allocating a ResourceLocation.toString(). */
    public static CreateContraptionKind fromLocation(net.minecraft.resources.ResourceLocation type) {
        if (type == null || !"create".equals(type.getNamespace())) return UNKNOWN;
        return switch (type.getPath()) {
            case "mounted" -> MINECART;
            case "carriage" -> TRAIN;
            case "bearing", "clockwork" -> BEARING;
            case "piston", "pulley", "gantry", "elevator", "stabilized" -> OTHER;
            default -> UNKNOWN;
        };
    }
    public static CreateContraptionKind fromSource(String source) {
        if (source == null) return UNKNOWN;
        // Preserve train events emitted before source IDs were introduced.
        if (source.equals("create:train")) return TRAIN;
        for (var kind : KINDS)
            if (kind != UNKNOWN && source.startsWith(kind.prefix())) return kind;
        return UNKNOWN;
    }
    public static String phase(String source){
        var kind=fromSource(source);if(id(source)==null)return "";
        int start=kind.prefix().length()+37;int end=source.indexOf(':',start);
        return end<0?source.substring(start):source.substring(start,end);
    }
    public static UUID id(String source) {
        var kind = fromSource(source);
        if (kind == UNKNOWN || source.equals("create:train")) return null;
        int start = kind.prefix().length();
        if (source.length() <= start + 36 || source.charAt(start + 36) != ':') return null;
        String value = source.substring(start, start + 36);
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equalsIgnoreCase(value) ? id : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }
}
