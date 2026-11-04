package com.roften.avilixlogger.compat.dieselgenerators.mixin;
import com.roften.avilixlogger.compat.teams.OptionalStateMixinPlugin;
public final class DieselGeneratorsMixinPlugin extends OptionalStateMixinPlugin {
    public DieselGeneratorsMixinPlugin(){super("createdieselgenerators","1.21.1-1.3.15","avilixlogger_dieselgenerators.mixins.json");}
    @Override public void onLoad(String pkg){
        var list=net.neoforged.fml.loading.LoadingModList.get();
        var create=list==null?null:list.getModFileById("create");
        if(create==null||create.getMods().stream().noneMatch(m->m.getModId().equals("create")&&m.getVersion().toString().equals("6.0.10")))return;
        super.onLoad(pkg);
    }
}
