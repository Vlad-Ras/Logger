package com.roften.avilixlogger.core;

import com.google.gson.JsonParser;
import com.roften.avilixlogger.compat.create.mixin.CreateMixinPlugin.Preflight;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.io.InputStreamReader;

/** Real target bytecode plus injected missing methods, fields and invocation sites. */
public final class CreatePreflightTest {
    public static void main(String[] args) throws Exception {
        var loader = CreatePreflightTest.class.getClassLoader();
        var config = JsonParser.parseReader(new InputStreamReader(loader.getResourceAsStream("avilixlogger_create_trains.mixins.json"))).getAsJsonObject();
        int failed = 0;
        for (var name : config.getAsJsonArray("mixins")) {
            try { new Preflight(n -> raw(loader, n)).check("com/roften/avilixlogger/compat/create/mixin/" + name.getAsString()); }
            catch (Exception e) { failed++; System.err.println(e.getMessage()); }
        }
        if (failed != 0) throw new AssertionError(failed + " invalid mixins");
        var mixin = "com/roften/avilixlogger/compat/create/mixin/MountedItemStorageAuditMixin";
        String target = "com/simibubi/create/api/contraption/storage/item/WrapperMountedItemStorage";
        for (int fault = 0; fault < 3; fault++) {
            final int selected = fault;
            boolean rejected = false;
            try {
                new Preflight(n -> {
                    var node = raw(loader, n);
                    if (n.equals(target)) {
                        if (selected == 0) throw new IllegalStateException("missing target");
                        if (selected == 1) node.methods.removeIf(m -> m.name.equals("insertItem"));
                        if (selected == 2) node.methods.stream().filter(m -> m.name.equals("insertItem")).forEach(m -> m.desc = "()V");
                    }
                    return node;
                }).check(mixin);
            } catch (Exception e) { rejected = true; }
            if (!rejected) throw new AssertionError("Fault " + selected + " was accepted");
        }
        for(String changed : new String[]{"field", "invocation"}){
            boolean rejected=false;
            String checked=changed.equals("field")?"CartContraptionAccessor":"MountedContainerTransferMixin";
            try{new Preflight(n->{
                var node=raw(loader,n);
                if(changed.equals("field") && n.equals("com/simibubi/create/content/contraptions/Contraption"))node.fields.removeIf(f->f.name.equals("updateTags"));
                if(changed.equals("invocation") && n.equals("com/simibubi/create/api/contraption/dispenser/DefaultMountedDispenseBehavior"))
                    node.methods.forEach(m->{for(var insn:m.instructions)if(insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals("addItem"))call.name="missingInvocation";});
                return node;
            }).check("com/roften/avilixlogger/compat/create/mixin/"+checked);}catch(Exception expected){rejected=true;}
            if(!rejected)throw new AssertionError("Injected "+changed+" fault was accepted");
        }
        for(var name:new String[]{"BearingControllerAccessMixin","ClockworkControllerAccessMixin","LinearControllerAccessMixin","KineticSnapshotAccessMixin"}){
            boolean rejected=false;
            try{new Preflight(n->{var node=raw(loader,n);if(!n.startsWith("com/roften/avilixlogger/"))node.fields.forEach(field->{if(java.util.Set.of("movedContraption","hourHand","wasMoved").contains(field.name))field.desc="Ljava/lang/Object;";});return node;}).check("com/roften/avilixlogger/compat/create/mixin/"+name);}
            catch(Exception expected){rejected=true;}
            if(!rejected)throw new AssertionError("Controller shadow type mismatch accepted: "+name);
        }
        boolean inheritedRejected=false;
        try{new Preflight(n->{var node=raw(loader,n);
            if(n.equals("com/simibubi/create/content/contraptions/bearing/MechanicalBearingBlockEntity"))node.fields.removeIf(field->field.name.equals("movedContraption"));
            if(n.equals("com/simibubi/create/content/kinetics/base/GeneratingKineticBlockEntity"))node.fields.add(new org.objectweb.asm.tree.FieldNode(org.objectweb.asm.Opcodes.ACC_PROTECTED,"movedContraption","Lcom/simibubi/create/content/contraptions/ControlledContraptionEntity;",null,null));return node;
        }).check("com/roften/avilixlogger/compat/create/mixin/BearingControllerAccessMixin");}catch(Exception expected){inheritedRejected=true;}
        if(!inheritedRejected)throw new AssertionError("Inherited shadow accepted although Mixin requires a declared target field");
        System.out.println("CREATE_PREFLIGHT_OK");
    }
    private static ClassNode raw(ClassLoader loader, String name) throws Exception {
        try (var in = loader.getResourceAsStream(name + ".class")) {
            if (in == null) throw new IllegalStateException("missing " + name);
            var node = new ClassNode(); new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES); return node;
        }
    }
}
