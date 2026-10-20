package com.roften.avilixlogger.core;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;

public final class CreateContraptionKindTest {
    public static void main(String[] args) { run(); }
    public static void run() {
        var id = UUID.randomUUID();
        check(CreateContraptionKind.fromType("create:mounted") == CreateContraptionKind.MINECART, "mounted");
        check(CreateContraptionKind.fromType("create:carriage") == CreateContraptionKind.TRAIN, "carriage");
        for (var type : new String[]{"bearing", "clockwork"})
            check(CreateContraptionKind.fromType("create:" + type) == CreateContraptionKind.BEARING, type);
        for (var type : new String[]{"piston", "pulley", "gantry", "elevator", "stabilized"})
            check(CreateContraptionKind.fromType("create:" + type) == CreateContraptionKind.OTHER, type);
        check(CreateContraptionKind.fromType("othermod:bearing") == CreateContraptionKind.UNKNOWN, "foreign bearing");
        check(CreateContraptionKind.fromType(null) == CreateContraptionKind.UNKNOWN, "null registry type");
        check(CreateContraptionKind.fromType("create:future_type") == CreateContraptionKind.UNKNOWN, "unverified type guessed");
        check(CreateContraptionKind.fromSource("create:trainwreck") == CreateContraptionKind.UNKNOWN, "prefix alias");
        check(CreateContraptionKind.fromSource("create:train") == CreateContraptionKind.TRAIN, "legacy train");
        for (var kind : CreateContraptionKind.values()) if (kind != CreateContraptionKind.UNKNOWN) {
            var source = kind.source(id, "work");
            check(CreateContraptionKind.fromSource(source) == kind && id.equals(CreateContraptionKind.id(source)), "source round trip");
            check(CreateContraptionKind.id(kind.prefix() + id + "Xwork") == null, "invalid delimiter accepted");
        }
        check(CreateContraptionKind.forGuiPreset(5) == CreateContraptionKind.TRAIN, "existing train preset changed");
        check(CreateContraptionKind.forGuiPreset(9) == CreateContraptionKind.MINECART, "existing cart preset changed");
        check(CreateContraptionKind.forGuiPreset(10) == CreateContraptionKind.UNKNOWN, "backpacks reclassified");
        check(CreateContraptionKind.forGuiPreset(11) == CreateContraptionKind.BEARING, "bearing preset");
        check(CreateContraptionKind.forGuiPreset(12) == CreateContraptionKind.OTHER, "other preset");
        for (var type : new String[]{"create:bearing", "create:clockwork", "create:piston", "create:carriage", "create:mounted"}) {
            var row = new LogEntry(); row.entityUuid = id; row.source = "mod:create";
            var tag = new CompoundTag(); var contraption = new CompoundTag(); contraption.putString("Type", type); tag.put("Contraption", contraption);
            var trainId = UUID.randomUUID(); if (type.equals("create:carriage")) tag.putUUID("TrainId", trainId);
            var logicalCartId = UUID.randomUUID();
            var persistentId=UUID.randomUUID();if(!type.equals("create:mounted")&&!type.equals("create:carriage"))contraption.putUUID("AvilixContraptionId",persistentId);
            if (type.equals("create:mounted")) {
                var metadata = new CompoundTag(); metadata.putUUID("Id", logicalCartId);
                contraption.put(com.roften.avilixlogger.compat.create.CartAuditState.KEY, metadata);
            }
            row.deferSnapshot(LogEntry.SnapshotField.ENTITY, tag); row.materializeSnapshots();
            check(CreateContraptionKind.fromSource(row.source) == CreateContraptionKind.fromType(type), "snapshot classification");
            check((type.equals("create:carriage") ? trainId : type.equals("create:mounted") ? logicalCartId : persistentId).equals(CreateContraptionKind.id(row.source)), "logical construction ID lost");
            var parent = new LogEntry(); parent.entityUuid = id; parent.source = CreateContraptionKind.MINECART.source(id, "work");
            parent.deferSnapshot(LogEntry.SnapshotField.ENTITY, tag); parent.materializeSnapshots();
            check(CreateContraptionKind.fromSource(parent.source) == CreateContraptionKind.MINECART, "effect reassigned from parent construction");
        }
        var stamp = new CreateMechanismContext.Stamp("create:mechanical_drill", 2, -1, 3);
        var previous = CreateMechanismContext.enter(stamp);
        try {
            String source = CreateMechanismContext.decorate(CreateContraptionKind.MINECART.source(id, "work"));
            check(id.equals(CartAuditContext.id(source)), "mechanism changed logical cart ID");
            check("work".equals(CartAuditContext.phase(source)), "mechanism changed phase");
            check(CreateMechanismContext.description(source).equals("create:mechanical_drill@2,-1,3"), "mechanism location lost");
            check(CreateMechanismContext.decorate(source).equals(source), "descriptor duplicated");
            check(CreateMechanismContext.decorate("player:interact").equals("player:interact"), "unrelated source decorated");
            var nested = CreateMechanismContext.enter(new CreateMechanismContext.Stamp("create:deployer", 4, 0, 5));
            try { check(CreateMechanismContext.current().blockId().equals("create:deployer"), "nested actor"); }
            finally { CreateMechanismContext.restore(nested); }
            check(CreateMechanismContext.current() == stamp, "outer actor not restored");
        } finally { CreateMechanismContext.restore(previous); }
        check(CreateMechanismContext.current() == null, "actor scope leaked");
        System.out.println("CREATE_CONTRAPTION_KIND_TEST_OK types, legacy sources, stable presets, native snapshots, mechanism identity and nested scopes");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
