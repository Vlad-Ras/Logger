package com.roften.avilixlogger.compat.teams;

import net.minecraft.nbt.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Cached reads of finite, explicitly selected state. Never calls an arbitrary object's serializer/toString. */
public final class StateAccess {
    private static final ClassValue<Map<String, Field>> FIELDS = new ClassValue<>() {
        @Override protected Map<String, Field> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };
    private static final ClassValue<Map<String, Method>> NO_ARG_METHODS = new ClassValue<>() {
        @Override protected Map<String, Method> computeValue(Class<?> type) { return new ConcurrentHashMap<>(); }
    };
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Object[] NO_ARGUMENTS = {};
    private StateAccess() {}
    public static Object field(Object value, String name) throws ReflectiveOperationException {
        Class<?> type = value.getClass();
        var fields = FIELDS.get(type);
        Field field = fields.get(name);
        if (field == null) {
            Class<?> owner = type;
            while (owner != null) {
                try { field = owner.getDeclaredField(name); break; } catch (NoSuchFieldException absent) { owner = owner.getSuperclass(); }
            }
            if (field == null) throw new NoSuchFieldException(type.getName() + "#" + name);
            field.setAccessible(true);
            fields.put(name, field);
        }
        return field.get(value);
    }

    public static Object call(Object value, String name) throws ReflectiveOperationException {
        Class<?> type = value.getClass();
        var methods = NO_ARG_METHODS.get(type);
        Method method = methods.get(name);
        if (method == null) {
            method = type.getMethod(name);
            method.setAccessible(true);
            methods.put(name, method);
        }
        return method.invoke(value, NO_ARGUMENTS);
    }
    public static Object call(Object value, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        String key = value.getClass().getName() + "#" + name + Arrays.toString(types);
        Method method = METHODS.get(key);
        if (method == null) {
            method = value.getClass().getMethod(name, types); method.setAccessible(true); METHODS.put(key, method);
        }
        return method.invoke(value, args);
    }
    public static Tag encode(Object value) throws ReflectiveOperationException { return encode(value, 0, new int[]{16384}); }
    private static Tag encode(Object value, int depth, int[] budget) throws ReflectiveOperationException {
        if (depth > 24 || --budget[0] < 0) throw new IllegalStateException("Audit state budget exceeded");
        if (value == null) { var tag = new CompoundTag(); tag.putBoolean("Absent", true); return tag; }
        if (value instanceof Tag tag) return tag.copy();
        if (value instanceof String text) { if (text.length() > 262144) throw new IllegalStateException("Audit string budget"); return StringTag.valueOf(text); }
        if (value instanceof UUID id) return StringTag.valueOf(id.toString());
        if (value instanceof net.minecraft.resources.ResourceLocation id) return StringTag.valueOf(id.toString());
        if (value instanceof Boolean flag) return ByteTag.valueOf(flag);
        if (value instanceof Byte number) return ByteTag.valueOf(number);
        if (value instanceof Short number) return ShortTag.valueOf(number);
        if (value instanceof Integer number) return IntTag.valueOf(number);
        if (value instanceof Long number) return LongTag.valueOf(number);
        if (value instanceof Float number) return FloatTag.valueOf(number);
        if (value instanceof Double number) return DoubleTag.valueOf(number);
        if (value instanceof Enum<?> constant) return StringTag.valueOf(constant.name());
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 4096) throw new IllegalStateException("Audit map budget");
            var result = new CompoundTag();
            for (var entry : map.entrySet()) {
                Object key = entry.getKey();
                if (!(key instanceof String || key instanceof UUID || key instanceof Enum<?> || key instanceof net.minecraft.resources.ResourceLocation)) throw new IllegalStateException("Unsupported audit key");
                result.put(key instanceof Enum<?> e ? e.name() : key.toString(), encode(entry.getValue(), depth + 1, budget));
            }
            return result;
        }
        if (value instanceof Collection<?> values) {
            if (values.size() > 4096) throw new IllegalStateException("Audit collection budget");
            // Compound wrappers allow mixed primitive types without ListTag discarding entries.
            var result = new ListTag(); var encoded = new ArrayList<CompoundTag>();
            for (Object item : values) { var tag = new CompoundTag(); tag.put("Value", encode(item, depth + 1, budget)); encoded.add(tag); }
            if (values instanceof Set<?>) encoded.sort(Comparator.comparing(CompoundTag::toString));
            result.addAll(encoded); return result;
        }
        // NightConfig valueMap is a raw, read-only access path; getStorage() is never used.
        if (value.getClass().getName().startsWith("com.electronwill.nightconfig.")) return encode(call(value, "valueMap"), depth + 1, budget);
        throw new IllegalStateException("Unsupported audit value " + value.getClass().getName());
    }
}
