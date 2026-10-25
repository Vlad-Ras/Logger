package com.roften.avilixlogger.compat.create.mixin;

import com.google.gson.JsonParser;
import com.roften.avilixlogger.core.CreateSupport;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.Type;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.*;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Checks raw bytecode, without initializing Create or Minecraft targets. All-or-none. */
public final class CreateMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String pkg) {
        var list = LoadingModList.get();
        var file = list == null ? null : list.getModFileById("create");
        if (file == null) { CreateSupport.publish(false, "Create is absent"); return; }
        try {
            var version = file.getMods().stream().filter(m -> m.getModId().equals("create"))
                    .findFirst().orElseThrow().getVersion().toString();
            // New layouts need an audit even if their public API happens to still match.
            if (!version.equals("6.0.11") && !version.equals("6.0.10"))
                throw new IllegalStateException("unsupported Create " + version + "; validated versions: 6.0.10 and 6.0.11");
            var service = MixinService.getService();
            try (var stream = Objects.requireNonNull(service.getResourceAsStream("avilixlogger_create_trains.mixins.json"));
                 var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                var config = JsonParser.parseReader(reader).getAsJsonObject();
                var checker = new Preflight(name -> {
                    try (var bytes = Objects.requireNonNull(service.getResourceAsStream(name + ".class"), "missing class " + name)) {
                        var node = new ClassNode();
                        new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES);
                        return node;
                    }
                });
                for (var entry : config.getAsJsonArray("mixins")) checker.check(pkg.replace('.', '/') + '/' + entry.getAsString());
            }
            CreateSupport.publish(true, "Create " + version + ": all mixin targets verified");
            LoggerFactory.getLogger("AvilixLogger/Create").info(CreateSupport.diagnostic());
        } catch (Exception | LinkageError failure) {
            CreateSupport.publish(false, "Create integration disabled: " + failure.getMessage());
            LoggerFactory.getLogger("AvilixLogger/Create").error(CreateSupport.diagnostic(), failure);
        }
    }
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String target, String mixin) { return CreateSupport.available(); }
    public void acceptTargets(Set<String> mine, Set<String> others) {}
    public List<String> getMixins() { return null; }
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    @FunctionalInterface public interface Classes { ClassNode read(String name) throws Exception; }
    public static final class Preflight {
        private final Classes classes;
        private final Map<String, ClassNode> cache = new HashMap<>();
        public Preflight(Classes classes) { this.classes = classes; }
        private ClassNode read(String name) throws Exception {
            var found = cache.get(name);
            if (found == null) { found = Objects.requireNonNull(classes.read(name), "missing class " + name); cache.put(name, found); }
            return found;
        }
        public void check(String name) throws Exception {
            var mixin = read(name);
            var annotation = annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations).stream()
                    .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
            var targets = new ArrayList<String>();
            for (Object v : values(annotation, "value")) targets.add(((Type) v).getInternalName());
            for (Object v : values(annotation, "targets")) targets.add(v.toString().replace('.', '/'));
            if (targets.isEmpty()) throw new IllegalStateException(name + ": no target");
            for (String target : targets) {
                read(target);
                for (var field : mixin.fields) for (var a : annotations(field.visibleAnnotations, field.invisibleAnnotations))
                    if (a.desc.endsWith("/Shadow;")) require(read(target).fields.stream().anyMatch(f->f.name.equals(field.name)&&f.desc.equals(field.desc)), name + ": declared shadow field " + target + '.' + field.name + field.desc);
                for (var method : mixin.methods) for (var a : annotations(method.visibleAnnotations, method.invisibleAnnotations)) {
                    if (a.desc.endsWith("/Accessor;")) {
                        String member = Objects.toString(value(a, "value"), "");
                        var args = Type.getArgumentTypes(method.desc);
                        String desc = args.length == 0 ? Type.getReturnType(method.desc).getDescriptor() : args[0].getDescriptor();
                        require(field(target, member, desc), name + ": accessor " + target + '.' + member + desc);
                    } else if (a.desc.endsWith("/Invoker;")) {
                        require(!methods(target, Objects.toString(value(a, "value"), "") + method.desc).isEmpty(), name + ": invoker " + method.name);
                    } else if (a.desc.endsWith("/Shadow;")) {
                        require(!methods(target, method.name + method.desc).isEmpty(), name + ": shadow " + method.name);
                    } else if (value(a, "method") != null) {
                        var selected = new ArrayList<MethodNode>();
                        for (Object selector : values(a, "method")) {
                            var candidates = methods(target, selector.toString());
                            require(!candidates.isEmpty(), name + ": method " + target + '.' + selector);
                            if (a.desc.endsWith("/WrapMethod;")) {
                                var parameters = Arrays.stream(Type.getArgumentTypes(method.desc))
                                        .filter(t -> !t.getDescriptor().equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;"))
                                        .toArray(Type[]::new);
                                String descriptor = Type.getMethodDescriptor(Type.getReturnType(method.desc), parameters);
                                require(candidates.stream().anyMatch(m -> wrapperMatches(method,m)), name + ": wrapper signature " + target + '.' + selector + descriptor);
                            } else if(a.desc.endsWith("/Inject;")) {
                                var parameters=Type.getArgumentTypes(method.desc);
                                int callback=0;
                                while(callback<parameters.length && !parameters[callback].getDescriptor().startsWith("Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo"))callback++;
                                if(callback>0 && callback<parameters.length){
                                    final int count=callback;
                                    require(candidates.stream().anyMatch(m->{
                                        var args=Type.getArgumentTypes(m.desc);
                                        return args.length==count && Arrays.equals(args,Arrays.copyOf(parameters,count));
                                    }),name+": inject signature "+target+'.'+selector);
                                }
                            }
                            selected.addAll(candidates);
                        }
                        for (Object at : values(a, "at")) if (at instanceof AnnotationNode point) {
                            String kind = Objects.toString(value(point, "value"), "");
                            String call = Objects.toString(value(point, "target"), "");
                            if (!kind.equals("INVOKE") || call.isEmpty()) continue;
                            int semi = call.indexOf(';'), paren = call.indexOf('(');
                            require(semi > 1 && paren > semi, "invalid invocation " + call);
                            String owner = call.substring(1, semi), member = call.substring(semi + 1, paren), desc = call.substring(paren);
                            boolean exists = selected.stream().anyMatch(m -> {
                                for (var insn : m.instructions) if (insn instanceof MethodInsnNode invoke
                                        && invoke.owner.equals(owner) && invoke.name.equals(member) && invoke.desc.equals(desc)) return true;
                                return false;
                            });
                            require(exists, name + ": injection point " + target + ' ' + call);
                        }
                    }
                }
            }
        }
        private static boolean wrapperMatches(MethodNode handler,MethodNode target) {
            if(!Type.getReturnType(handler.desc).equals(Type.getReturnType(target.desc)))return false;
            var declared=Type.getArgumentTypes(handler.desc);var actual=Type.getArgumentTypes(target.desc);int index=0;
            for(int i=0;i<declared.length;i++) {
                if(declared[i].getDescriptor().equals("Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;"))continue;
                if(index>=actual.length)return false;
                Type supplied=actual[index++];
                if(declared[i].equals(supplied))continue;
                // Mixin explicitly permits @Coerce Object for a native reference parameter.
                // Unannotated Object, primitives, arity and return types remain strict.
                if(!declared[i].getDescriptor().equals("Ljava/lang/Object;")
                        || supplied.getSort()!=Type.OBJECT && supplied.getSort()!=Type.ARRAY
                        || !coerced(handler,i))return false;
            }
            return index==actual.length;
        }
        private static boolean coerced(MethodNode method,int parameter) {
            for(var list:java.util.Arrays.asList(method.visibleParameterAnnotations,method.invisibleParameterAnnotations))
                if(list!=null && parameter<list.length && list[parameter]!=null)
                    for(var annotation:list[parameter])if(annotation.desc.equals("Lorg/spongepowered/asm/mixin/injection/Coerce;"))return true;
            return false;
        }
        private boolean field(String owner, String name, String desc) throws Exception {
            var node = read(owner);
            if (node.fields.stream().anyMatch(f -> f.name.equals(name) && f.desc.equals(desc))) return true;
            return node.superName != null && !node.superName.equals("java/lang/Object") && field(node.superName, name, desc);
        }
        private List<MethodNode> methods(String owner, String selector) throws Exception {
            var node = read(owner);
            int pos = selector.indexOf('(');
            String name = pos < 0 ? selector : selector.substring(0, pos), desc = pos < 0 ? null : selector.substring(pos);
            var found = node.methods.stream().filter(m -> m.name.equals(name) && (desc == null || m.desc.equals(desc))).toList();
            if (!found.isEmpty() || node.superName == null || node.superName.equals("java/lang/Object")) return found;
            return methods(node.superName, selector);
        }
        private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
        private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
            var all = new ArrayList<AnnotationNode>();
            if (visible != null) all.addAll(visible);
            if (invisible != null) all.addAll(invisible);
            return all;
        }
        private static Object value(AnnotationNode a, String key) {
            if (a.values != null) for (int i = 0; i < a.values.size(); i += 2) if (key.equals(a.values.get(i))) return a.values.get(i + 1);
            return null;
        }
        private static List<?> values(AnnotationNode a, String key) {
            Object value = value(a, key);
            return value == null ? List.of() : value instanceof List<?> list ? list : List.of(value);
        }
    }
}
