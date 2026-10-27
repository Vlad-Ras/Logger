package com.roften.avilixlogger.core;

import com.roften.avilixlogger.compat.ae2.mixin.Ae2MixinPlugin;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.util.*;

/** Broken optional targets must disable the integration before any transformed AE2 class loads. */
final class Ae2ContractsTest {
    static void run() throws Exception {
        verify(Ae2ContractsTest::read);
        var faults = List.of(
                Map.entry("appeng/menu/AEBaseMenu", "receiveClientAction"),
                Map.entry("appeng/core/network/serverbound/ConfigButtonPacket", "handleOnServer"),
                Map.entry("appeng/menu/implementations/SetStockAmountMenu", "confirm"),
                Map.entry("appeng/api/storage/StorageHelper", "poweredInsert"),
                Map.entry("appeng/me/storage/NetworkStorage", "insert"),
                Map.entry("appeng/menu/implementations/PriorityMenu", "setPriority"),
                Map.entry("appeng/menu/implementations/StorageLevelEmitterMenu", "setValue"),
                Map.entry("appeng/parts/encoding/PatternEncodingLogic", "getMode"),
                Map.entry("appeng/helpers/IPatternTerminalMenuHost", "getLogic"),
                Map.entry("appeng/menu/implementations/PatternAccessTermMenu", "doAction"),
                Map.entry("appeng/menu/implementations/PatternAccessTermMenu", "quickMovePattern"));
        for (var fault : faults) {
            if (!quickMove() && fault.getValue().equals("quickMovePattern")) continue;
            boolean refused = false;
            try {
                verify(name -> {
                    var node = read(name);
                    if (name.equals(fault.getKey())) node.methods.removeIf(m -> m.name.equals(fault.getValue()));
                    return node;
                });
            } catch (IllegalStateException expected) { refused = true; }
            if (!refused) throw new AssertionError("AE2 preflight accepted missing " + fault);
        }
        boolean refused = false;
        try {
            verify(name -> {
                var node = read(name);
                if (name.equals("appeng/menu/implementations/SetStockAmountMenu"))
                    node.fields.removeIf(f -> f.name.equals("host"));
                return node;
            });
        } catch (IllegalStateException expected) { refused = true; }
        if (!refused) throw new AssertionError("AE2 preflight accepted missing stock host");
        for (String field : List.of("container", "serverId", "server")) {
            refused = false;
            try { verify(name -> {
                var node = read(name);
                if (name.equals("appeng/menu/implementations/PatternAccessTermMenu$ContainerTracker")) node.fields.removeIf(f -> f.name.equals(field));
                return node;
            }); } catch (IllegalStateException expected) { refused = true; }
            if (!refused) throw new AssertionError("AE2 preflight accepted missing tracker " + field);
        }
        for (String method : quickMove() ? List.of("doAction", "quickMovePattern") : List.of("doAction")) {
            refused = false;
            try { verify(name -> {
                var node = read(name);
                if (name.equals("appeng/menu/implementations/PatternAccessTermMenu")) {
                    var body = node.methods.stream().filter(m -> m.name.equals(method)).findFirst().orElseThrow();
                    for (var instruction : body.instructions) if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field && field.name.equals("server")) {
                        body.instructions.remove(instruction); break;
                    }
                }
                return node;
            }); } catch (IllegalStateException expected) { refused = true; }
            if (!refused) throw new AssertionError("AE2 preflight accepted changed remote inventory reads " + method);
        }
        System.out.println("AE2_CONTRACTS_OK: version-specific optional hooks, pattern logic/remote inventory API and missing methods/stock field refusal, raw bytecode only");
    }

    private static boolean quickMove() { return !System.getProperty("avilixlogger.ae2Version", "19.2.12").equals("19.2.12"); }
    private static void verify(com.roften.avilixlogger.compat.create.mixin.CreateMixinPlugin.Classes classes) throws Exception {
        Ae2MixinPlugin.verify(classes, System.getProperty("avilixlogger.ae2Version", "19.2.12"));
    }

    private static ClassNode read(String name) throws Exception {
        try (var in = Objects.requireNonNull(Ae2ContractsTest.class.getClassLoader().getResourceAsStream(name + ".class"), name)) {
            var node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
