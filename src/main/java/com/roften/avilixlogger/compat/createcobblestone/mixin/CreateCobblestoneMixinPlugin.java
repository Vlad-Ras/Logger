package com.roften.avilixlogger.compat.createcobblestone.mixin;
import com.roften.avilixlogger.compat.teams.OptionalStateMixinPlugin;
public final class CreateCobblestoneMixinPlugin extends OptionalStateMixinPlugin {
    public CreateCobblestoneMixinPlugin() { super("createcobblestone", "1.5.0+neoforge-1.21.1-153", "avilixlogger_createcobblestone.mixins.json"); }
    @Override public void onLoad(String pkg) {
        var list = net.neoforged.fml.loading.LoadingModList.get();
        var create = list == null ? null : list.getModFileById("create");
        if (create == null || create.getMods().stream().noneMatch(mod -> mod.getModId().equals("create") && mod.getVersion().toString().equals("6.0.10"))) return;
        super.onLoad(pkg);
    }
}
