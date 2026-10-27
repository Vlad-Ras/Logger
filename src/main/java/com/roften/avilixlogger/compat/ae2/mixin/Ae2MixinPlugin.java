package com.roften.avilixlogger.compat.ae2.mixin;

import com.roften.avilixlogger.compat.create.mixin.CreateMixinPlugin;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;
import java.util.*;

/** Optional, all-or-none raw contract verification; never initializes AE2 classes. */
public final class Ae2MixinPlugin implements IMixinConfigPlugin {
    private boolean available;
    private boolean quickMove;

    public static void verify(CreateMixinPlugin.Classes classes, String version) throws Exception {
        if (!version.equals("19.2.12") && !version.equals("19.2.18")) throw new IllegalStateException("Unsupported AE2 " + version);
        boolean quickMove = version.equals("19.2.18");
        var checker = new CreateMixinPlugin.Preflight(classes);
        for (String name : List.of("NetworkStorageMixin", "StorageHelperMixin", "AEBaseMenuMixin",
                "ConfigButtonPacketMixin", "SetStockAmountMenuMixin", "PriorityMenuMixin", "StorageLevelEmitterMenuMixin",
                "PatternTrackerAccess", "PatternAccessTermMenuMixin"))
            checker.check("com/roften/avilixlogger/compat/ae2/mixin/" + name);
        if (quickMove) checker.check("com/roften/avilixlogger/compat/ae2/mixin/PatternAccessQuickMoveMixin");
        var logic = classes.read("appeng/parts/encoding/PatternEncodingLogic");
        for (String contract : List.of("getMode()Lappeng/parts/encoding/EncodingMode;", "isSubstitution()Z", "isFluidSubstitution()Z",
                "getStonecuttingRecipeId()Lnet/minecraft/resources/ResourceLocation;"))
            if (logic.methods.stream().noneMatch(m -> (m.name + m.desc).equals(contract)))
                throw new IllegalStateException("Missing AE2 pattern logic " + contract);
        var host = classes.read("appeng/helpers/IPatternTerminalMenuHost");
        if (host.methods.stream().noneMatch(m -> m.name.equals("getLogic") && m.desc.equals("()Lappeng/parts/encoding/PatternEncodingLogic;")))
            throw new IllegalStateException("Missing AE2 pattern menu host logic");
        var remote = classes.read("appeng/menu/implementations/PatternAccessTermMenu");
        if (!quickMove && remote.methods.stream().anyMatch(m -> m.name.equals("quickMovePattern")))
            throw new IllegalStateException("Unexpected quickMovePattern in AE2 19.2.12");
        for (String method : quickMove ? List.of("doAction", "quickMovePattern") : List.of("doAction")) {
            var body = remote.methods.stream().filter(m -> m.name.equals(method)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("Missing declared remote override " + method));
            int reads = 0;
            for (var instruction : body.instructions) if (instruction instanceof org.objectweb.asm.tree.FieldInsnNode field
                    && field.getOpcode() == org.objectweb.asm.Opcodes.GETFIELD
                    && field.owner.equals("appeng/menu/implementations/PatternAccessTermMenu$ContainerTracker")
                    && field.name.equals("server") && field.desc.equals("Lappeng/api/inventories/InternalInventory;")) reads++;
            if (reads != (method.equals("doAction") ? 5 : 1)) throw new IllegalStateException("Unsupported remote inventory reads: " + method + '/' + reads);
        }
    }

    public void onLoad(String pkg) {
        var list = LoadingModList.get();
        var file = list == null ? null : list.getModFileById("ae2");
        if (file == null) return;
        try {
            var version = file.getMods().stream().filter(m -> m.getModId().equals("ae2"))
                    .findFirst().orElseThrow().getVersion().toString();
            if (!version.equals("19.2.12") && !version.equals("19.2.18"))
                throw new IllegalStateException("Unsupported AE2 " + version + "; validated versions: 19.2.12, 19.2.18");
            verify(name -> {
                try (var input = Objects.requireNonNull(MixinService.getService().getResourceAsStream(name + ".class"))) {
                    var node = new ClassNode();
                    new ClassReader(input).accept(node, ClassReader.SKIP_FRAMES);
                    return node;
                }
            }, version);
            quickMove = version.equals("19.2.18");
            available = true;
        } catch (Exception | LinkageError failure) {
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2").warn("AE2 audit integration disabled: {}", failure.toString());
        }
    }

    public String getRefMapperConfig() { return null; }
    public boolean shouldApplyMixin(String target, String mixin) { return available && (quickMove || !mixin.endsWith(".PatternAccessQuickMoveMixin")); }
    public void acceptTargets(Set<String> mine, Set<String> other) {}
    public List<String> getMixins() { return null; }
    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
