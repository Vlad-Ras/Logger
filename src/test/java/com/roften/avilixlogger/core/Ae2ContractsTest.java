package com.roften.avilixlogger.core;

import com.roften.avilixlogger.compat.ae2.mixin.Ae2MixinPlugin;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.util.*;

/** Broken optional targets must disable the integration before any transformed AE2 class loads. */
final class Ae2ContractsTest {
    static void run() throws Exception {
        Ae2MixinPlugin.verify(Ae2ContractsTest::read);
        var faults = Map.of(
                "appeng/menu/AEBaseMenu", "receiveClientAction",
                "appeng/core/network/serverbound/ConfigButtonPacket", "handleOnServer",
                "appeng/menu/implementations/SetStockAmountMenu", "confirm",
                "appeng/api/storage/StorageHelper", "poweredInsert",
                "appeng/me/storage/NetworkStorage", "insert");
        for (var fault : faults.entrySet()) {
            boolean refused = false;
            try {
                Ae2MixinPlugin.verify(name -> {
                    var node = read(name);
                    if (name.equals(fault.getKey())) node.methods.removeIf(m -> m.name.equals(fault.getValue()));
                    return node;
                });
            } catch (IllegalStateException expected) { refused = true; }
            if (!refused) throw new AssertionError("AE2 preflight accepted missing " + fault);
        }
        boolean refused = false;
        try {
            Ae2MixinPlugin.verify(name -> {
                var node = read(name);
                if (name.equals("appeng/menu/implementations/SetStockAmountMenu"))
                    node.fields.removeIf(f -> f.name.equals("host"));
                return node;
            });
        } catch (IllegalStateException expected) { refused = true; }
        if (!refused) throw new AssertionError("AE2 preflight accepted missing stock host");
        System.out.println("AE2_CONTRACTS_OK: all five optional hooks and missing methods/stock field refusal, raw bytecode only");
    }

    private static ClassNode read(String name) throws Exception {
        try (var in = Objects.requireNonNull(Ae2ContractsTest.class.getClassLoader().getResourceAsStream(name + ".class"), name)) {
            var node = new ClassNode();
            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
            return node;
        }
    }
}
