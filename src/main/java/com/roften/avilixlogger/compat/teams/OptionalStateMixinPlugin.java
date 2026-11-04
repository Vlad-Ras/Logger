package com.roften.avilixlogger.compat.teams;

import com.google.gson.*;
import com.roften.avilixlogger.compat.create.mixin.CreateMixinPlugin;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Resolve all raw targets before enabling a version-pinned optional group. */
public abstract class OptionalStateMixinPlugin implements IMixinConfigPlugin {
    private final String modId, version, config;
    private boolean enabled;
    protected OptionalStateMixinPlugin(String modId, String version, String config) { this.modId = modId; this.version = version; this.config = config; }
    public void onLoad(String pkg) {
        var list = LoadingModList.get(); var file = list == null ? null : list.getModFileById(modId); if (file == null) return;
        try {
            if (file.getMods().stream().noneMatch(mod -> mod.getModId().equals(modId) && mod.getVersion().toString().equals(version))) throw new IllegalStateException("Unsupported " + modId + " version");
            var preflight = new CreateMixinPlugin.Preflight(name -> {
                try (var input = Objects.requireNonNull(MixinService.getService().getResourceAsStream(name + ".class"))) {
                    var node = new ClassNode(); new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES); return node;
                }
            });
            try (var input = Objects.requireNonNull(MixinService.getService().getResourceAsStream(config));
                 var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                for (var mixin : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("mixins")) preflight.check(pkg.replace('.', '/') + '/' + mixin.getAsString());
            }
            enabled = true;
        } catch (Exception | LinkageError failure) { org.slf4j.LoggerFactory.getLogger("AvilixLogger/" + modId).warn("Optional mutation audit disabled: {}", failure.toString()); }
    }
    public boolean shouldApplyMixin(String target, String mixin) { return enabled; }
    public String getRefMapperConfig() { return null; }
    public void acceptTargets(Set<String> mine, Set<String> others) {}
    public List<String> getMixins() { return null; }
    public void preApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
    public void postApply(String name, ClassNode node, String mixin, IMixinInfo info) {}
}
