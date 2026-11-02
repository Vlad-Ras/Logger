package com.roften.avilixlogger.compat.industry.mixin;
import com.roften.avilixlogger.compat.teams.OptionalStateMixinPlugin;
public final class IndustrialMixinPlugin extends OptionalStateMixinPlugin {
    public IndustrialMixinPlugin(){super("create","6.0.10","avilixlogger_industry.mixins.json");}
    @Override public void onLoad(String pkg){
        var list=net.neoforged.fml.loading.LoadingModList.get();
        var create=list==null?null:list.getModFileById("create");
        if(create==null||create.getMods().stream().noneMatch(m->m.getModId().equals("create")&&m.getVersion().toString().equals("6.0.10")))return;
        boolean supported=false;
        for(String id:new String[]{"create_enchantment_industry","createdieselgenerators"}){
            var file=list.getModFileById(id);String expected=id.equals("createdieselgenerators")?"1.21.1-1.3.15":"2.4.2";
            if(file!=null&&file.getMods().stream().anyMatch(m->m.getModId().equals(id)&&m.getVersion().toString().equals(expected)))supported=true;
        }
        if(!supported)return;
        super.onLoad(pkg);
    }
}
