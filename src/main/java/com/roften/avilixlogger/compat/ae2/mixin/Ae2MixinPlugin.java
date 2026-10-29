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
                "PatternTrackerAccess", "PatternAccessTermMenuMixin", "CraftingServiceMixin", "CraftingCPUClusterMixin", "CraftingCPUMenuMixin", "CraftingCpuLogicMixin", "CraftingLinkAccess", "CraftingLinkMixin", "CraftingLinkNexusMixin"))
            checker.check("com/roften/avilixlogger/compat/ae2/mixin/" + name);
        if (quickMove) checker.check("com/roften/avilixlogger/compat/ae2/mixin/PatternAccessQuickMoveMixin");
        for (var entry : Map.of(
                "appeng/crafting/execution/CraftingCpuLogic", List.of("getLastLink()Lappeng/api/networking/crafting/ICraftingLink;", "getFinalJobOutput()Lappeng/api/stacks/GenericStack;"),
                "appeng/me/cluster/implementations/CraftingCPUCluster", List.of("getSrc()Lappeng/api/networking/security/IActionSource;", "getLevel()Lnet/minecraft/world/level/Level;", "getBoundsMin()Lnet/minecraft/core/BlockPos;", "getBoundsMax()Lnet/minecraft/core/BlockPos;", "getName()Lnet/minecraft/network/chat/Component;")).entrySet()) {
            var node = classes.read(entry.getKey());
            for (String contract : entry.getValue()) if (node.methods.stream().noneMatch(m -> (m.name + m.desc).equals(contract) && (m.access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0))
                throw new IllegalStateException("Missing AE2 crafting contract " + entry.getKey() + '.' + contract);
        }
        var cluster = classes.read("appeng/me/cluster/implementations/CraftingCPUCluster");
        if (cluster.fields.stream().noneMatch(f -> f.name.equals("craftingLogic") && f.desc.equals("Lappeng/crafting/execution/CraftingCpuLogic;") && (f.access & org.objectweb.asm.Opcodes.ACC_PUBLIC) != 0))
            throw new IllegalStateException("Missing AE2 crafting logic field");
        var cancel = classes.read("appeng/menu/me/crafting/CraftingCPUMenu").methods.stream().filter(m -> m.name.equals("cancelCrafting") && m.desc.equals("()V")).findFirst().orElseThrow(() -> new IllegalStateException("Missing AE2 cancelCrafting"));
        int calls = 0;
        for (var instruction : cancel.instructions) if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                && call.owner.equals("appeng/me/cluster/implementations/CraftingCPUCluster") && call.name.equals("cancelJob") && call.desc.equals("()V")) calls++;
        if (calls != 1) throw new IllegalStateException("Unsupported AE2 cancel call count " + calls);
        for (var route : List.of(
                List.of("appeng/me/cluster/implementations/CraftingCPUCluster", "cancelJob", "()V", "appeng/crafting/execution/CraftingCpuLogic", "cancel", "()V", ""),
                List.of("appeng/crafting/execution/CraftingCpuLogic", "cancel", "()V", "appeng/crafting/execution/CraftingCpuLogic", "finishJob", "(Z)V", "false"),
                List.of("appeng/crafting/execution/CraftingCpuLogic", "finishJob", "(Z)V", "appeng/crafting/CraftingLink", "cancel", "()V", ""))) {
            var body = classes.read(route.get(0)).methods.stream().filter(m -> m.name.equals(route.get(1)) && m.desc.equals(route.get(2)))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Missing AE2 CPU cancellation route " + route));
            int invocations = 0;
            for (var instruction : body.instructions) if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
                    && call.owner.equals(route.get(3)) && call.name.equals(route.get(4)) && call.desc.equals(route.get(5))) {
                invocations++;
                if (route.get(6).equals("false")) {
                    var previous = instruction.getPrevious(); while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
                    if (previous == null || previous.getOpcode() != org.objectweb.asm.Opcodes.ICONST_0)
                        throw new IllegalStateException("Unsupported AE2 CPU cancellation success flag");
                }
            }
            if (invocations != 1) throw new IllegalStateException("Unsupported AE2 CPU cancellation route count " + route + '/' + invocations);
        }
        var linkCancel = classes.read("appeng/crafting/CraftingLink").methods.stream()
                .filter(m -> m.name.equals("cancel") && m.desc.equals("()V")).findFirst().orElseThrow(() -> new IllegalStateException("Missing AE2 crafting link cancel"));
        int setFlag = -1, nexusCancel = -1, index = 0, flags = 0, nexusCalls = 0;
        for (var instruction : linkCancel.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                if (call.owner.equals("appeng/crafting/CraftingLink") && call.name.equals("setCanceled") && call.desc.equals("(Z)V")) {
                    var previous = instruction.getPrevious();
                    while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
                    if (previous == null || previous.getOpcode() != org.objectweb.asm.Opcodes.ICONST_1) throw new IllegalStateException("Unsupported AE2 cancel flag value");
                    setFlag = index; flags++;
                }
                if (call.owner.equals("appeng/crafting/CraftingLinkNexus") && call.name.equals("cancel") && call.desc.equals("()V")) { nexusCancel = index; nexusCalls++; }
            }
            index++;
        }
        if (flags != 1 || nexusCalls != 1 || setFlag >= nexusCancel) throw new IllegalStateException("Unsupported AE2 cancellation ordering");
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
