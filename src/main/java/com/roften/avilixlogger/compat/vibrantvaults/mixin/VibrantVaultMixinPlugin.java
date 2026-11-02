package com.roften.avilixlogger.compat.vibrantvaults.mixin;
import com.roften.avilixlogger.compat.teams.OptionalStateMixinPlugin;
public final class VibrantVaultMixinPlugin extends OptionalStateMixinPlugin {
    public VibrantVaultMixinPlugin() { super("create_vibrant_vaults", "0.3.2", "avilixlogger_vibrantvaults.mixins.json"); }
    @Override public void onLoad(String pkg) {
        var list = net.neoforged.fml.loading.LoadingModList.get();
        var create = list == null ? null : list.getModFileById("create");
        if (create == null || create.getMods().stream().noneMatch(mod -> mod.getModId().equals("create") && mod.getVersion().toString().equals("6.0.10"))) return;
        super.onLoad(pkg);
    }
}
