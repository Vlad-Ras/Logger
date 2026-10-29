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
                Map.entry("appeng/menu/implementations/PatternAccessTermMenu", "quickMovePattern"),
                Map.entry("appeng/me/service/CraftingService", "submitJob"),
                Map.entry("appeng/me/cluster/implementations/CraftingCPUCluster", "submitJob"),
                Map.entry("appeng/menu/me/crafting/CraftingCPUMenu", "cancelCrafting"),
                Map.entry("appeng/crafting/execution/CraftingCpuLogic", "getLastLink"),
                Map.entry("appeng/crafting/execution/CraftingCpuLogic", "finishJob"),
                Map.entry("appeng/crafting/execution/CraftingCpuLogic", "getFinalJobOutput"),
                Map.entry("appeng/crafting/CraftingLink", "cancel"),
                Map.entry("appeng/crafting/CraftingLinkNexus", "cancel"),
                Map.entry("appeng/me/cluster/implementations/CraftingCPUCluster", "getLevel"));
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
        refused = false;
        try { verify(name -> {
            var node = read(name);
            if (name.equals("appeng/me/cluster/implementations/CraftingCPUCluster")) node.fields.removeIf(f -> f.name.equals("craftingLogic"));
            return node;
        }); } catch (IllegalStateException expected) { refused = true; }
        if (!refused) throw new AssertionError("AE2 preflight accepted missing craftingLogic");
        refused = false;
        try { verify(name -> {
            var node = read(name);
            if (name.equals("appeng/menu/me/crafting/CraftingCPUMenu")) {
                var method = node.methods.stream().filter(m -> m.name.equals("cancelCrafting")).findFirst().orElseThrow();
                for (var instruction : method.instructions) if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("cancelJob")) {
                    method.instructions.insertBefore(call, new org.objectweb.asm.tree.MethodInsnNode(call.getOpcode(), call.owner, call.name, call.desc, call.itf)); break;
                }
            }
            return node;
        }); } catch (IllegalStateException expected) { refused = true; }
        if (!refused) throw new AssertionError("AE2 preflight accepted duplicate cancellation call");
        refused = false;
        try { verify(name -> {
            var node = read(name);
            if (name.equals("appeng/crafting/execution/CraftingCpuLogic")) node.fields.removeIf(f -> f.name.equals("cluster"));
            return node;
        }); } catch (IllegalStateException expected) { refused = true; }
        if (!refused) throw new AssertionError("AE2 preflight accepted missing CPU logic cluster");
        for (var target : Map.of("appeng/crafting/CraftingLink", List.of("req", "cpu", "canceled"),
                "appeng/crafting/CraftingLinkNexus", List.of("req", "cpu", "canceled", "done")).entrySet()) {
            for (String field : target.getValue()) {
                refused = false;
                try { verify(name -> { var node = read(name); if (name.equals(target.getKey())) node.fields.removeIf(f -> f.name.equals(field)); return node; }); }
                catch (IllegalStateException expected) { refused = true; }
                if (!refused) throw new AssertionError("AE2 preflight accepted missing linked cancellation field " + target.getKey() + '.' + field);
            }
        }
        for (boolean reverse : List.of(false, true)) {
            refused = false;
            try { verify(name -> {
                var node = read(name);
                if (name.equals("appeng/crafting/CraftingLink")) {
                    var method = node.methods.stream().filter(m -> m.name.equals("cancel")).findFirst().orElseThrow();
                    org.objectweb.asm.tree.MethodInsnNode flag = null, nexus = null;
                    for (var instruction : method.instructions) if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                        if (call.name.equals("setCanceled")) flag = call;
                        if (call.owner.equals("appeng/crafting/CraftingLinkNexus") && call.name.equals("cancel")) nexus = call;
                    }
                    if (reverse) { method.instructions.remove(nexus); method.instructions.insertBefore(flag, nexus); }
                    else { var value = flag.getPrevious(); method.instructions.set(value, new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ICONST_0)); }
                }
                return node;
            }); } catch (IllegalStateException expected) { refused = true; }
            if (!refused) throw new AssertionError("AE2 preflight accepted changed CPU cancel flag/order");
        }
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
