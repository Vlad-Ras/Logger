package com.roften.avilixlogger.core;

import com.google.gson.JsonParser;
import com.roften.avilixlogger.compat.create.mixin.CreateMixinPlugin.Preflight;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import java.nio.file.*;
import java.io.InputStreamReader;
import java.util.*;
import java.util.zip.ZipFile;

/** Read operator JAR bytecode without adding those JARs to the JVM classpath or executing mods. */
public final class ExactModpackPreflightTest {
    private final Map<String,List<Path>> classes=new HashMap<>();
    private final Set<String> exactReads=new HashSet<>();
    private final ClassLoader loader=ExactModpackPreflightTest.class.getClassLoader();
    private ExactModpackPreflightTest(Path directory)throws Exception {
        try(var paths=Files.list(directory)) {
            for(var path:paths.filter(p->p.getFileName().toString().endsWith(".jar")).sorted().toList())
                try(var jar=new ZipFile(path.toFile())) {
                    jar.stream().filter(e->e.getName().endsWith(".class")).forEach(e->classes.computeIfAbsent(e.getName(),ignored->new ArrayList<>()).add(path));
                }
        }
        if(classes.isEmpty())throw new IllegalArgumentException("No class files in supplied mods directory");
    }
    private ClassNode raw(String name)throws Exception {
        byte[] bytes=null;
        // Current Logger mixins always come from this build, never from an old uploaded Logger.
        var candidates=name.startsWith("com/roften/avilixlogger/")?null:classes.get(name+".class");
        if(candidates!=null) {
            for(var path:candidates)try(var jar=new ZipFile(path.toFile());var input=jar.getInputStream(jar.getEntry(name+".class"))) {
                byte[] next=input.readAllBytes();
                if(bytes!=null&&!Arrays.equals(bytes,next))throw new IllegalStateException("Conflicting exact class "+name);
                bytes=next;
            }
            exactReads.add(name);
        } else try(var input=Objects.requireNonNull(loader.getResourceAsStream(name+".class"),"Missing class "+name)) { bytes=input.readAllBytes(); }
        var node=new ClassNode();new ClassReader(bytes).accept(node,ClassReader.SKIP_FRAMES);return node;
    }
    private void group(String config,String requiredClass)throws Exception {
        if(!classes.containsKey(requiredClass+".class")) {System.out.println("EXACT_PREFLIGHT_SKIP "+config+": mod absent");return;}
        var json=JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(loader.getResourceAsStream(config)),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        String pkg=json.get("package").getAsString().replace('.','/');int checked=0;var checker=new Preflight(this::raw);
        for(var name:json.getAsJsonArray("mixins")) {checker.check(pkg+'/'+name.getAsString());checked++;}
        System.out.println("EXACT_PREFLIGHT_OK "+config+": "+checked+" mixins; supplied raw bytecode");
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected exact mods directory");
        var test=new ExactModpackPreflightTest(Path.of(args[0]));
        test.group("avilixlogger_create_trains.mixins.json","com/simibubi/create/content/contraptions/Contraption");
        test.group("avilixlogger_backpacked.mixins.json","com/mrcrayfish/backpacked/inventory/BackpackInventory");
        test.group("avilixlogger_ae2.mixins.json","appeng/menu/AEBaseMenu");
        test.group("avilixlogger_chipped.mixins.json","earth/terrarium/chipped/common/menus/WorkbenchMenu");
        test.group("avilixlogger_airplanes.mixins.json","immersive_aircraft/entity/VehicleEntity");
        if(test.exactReads.isEmpty())throw new AssertionError("No supplied target bytecode was checked");
        System.out.println("EXACT_MODPACK_PREFLIGHT_OK: "+test.exactReads.size()+" supplied target classes; no mod execution; raw contracts do not prove transformed mixin conflicts or gameplay coverage");
    }
}
