package com.roften.avilixlogger.compat.corpse.mixin;

import com.roften.avilixlogger.compat.create.mixin.CreateMixinPlugin;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;
import java.util.*;

/** Reject the entire optional packet integration before applying an incompatible target. */
public final class CorpseMixinPlugin implements IMixinConfigPlugin {
    private boolean available;
    public void onLoad(String pkg) {
        var list = LoadingModList.get(); var file = list == null ? null : list.getModFileById("corpse");
        if (file == null) return;
        try {
            if (file.getMods().stream().noneMatch(m -> m.getModId().equals("corpse")
                    && m.getVersion().toString().equals("1.21.1-1.1.21")))
                throw new IllegalStateException("Unsupported Corpse version");
            new CreateMixinPlugin.Preflight(name -> {
                try (var input = Objects.requireNonNull(MixinService.getService().getResourceAsStream(name + ".class"))) {
                    var node = new ClassNode(); new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES); return node;
                }
            }).check(pkg.replace('.', '/') + "/TransferItemsMixin");
            available = true;
        } catch (Exception | LinkageError failure) {
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/Corpse").warn("Corpse packet audit disabled: {}", failure.toString());
        }
    }
    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String target, String mixin) { return available; }
    public void acceptTargets(Set<String> mine, Set<String> other) {}
    public List<String> getMixins() { return null; }
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
