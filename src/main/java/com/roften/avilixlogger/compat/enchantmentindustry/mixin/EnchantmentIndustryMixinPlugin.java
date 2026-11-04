package com.roften.avilixlogger.compat.enchantmentindustry.mixin;
import com.roften.avilixlogger.compat.teams.OptionalStateMixinPlugin;
public final class EnchantmentIndustryMixinPlugin extends OptionalStateMixinPlugin {
    public EnchantmentIndustryMixinPlugin(){super("create_enchantment_industry","2.4.2","avilixlogger_enchantmentindustry.mixins.json");}
    @Override public void onLoad(String pkg){
        var list=net.neoforged.fml.loading.LoadingModList.get();
        var create=list==null?null:list.getModFileById("create");
        if(create==null||create.getMods().stream().noneMatch(m->m.getModId().equals("create")&&m.getVersion().toString().equals("6.0.10")))return;
        super.onLoad(pkg);
    }
}
