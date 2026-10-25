package com.roften.avilixlogger.compat.scguns.mixin;

import com.roften.avilixlogger.compat.create.mixin.CreateMixinPlugin;
import com.roften.avilixlogger.compat.scguns.ScgunsSupport;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;
import java.util.*;

/** All hooks are checked together against raw bytecode before any target is initialized. */
public final class ScgunsMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String pkg) {
        var list=LoadingModList.get();var file=list==null?null:list.getModFileById("scguns");
        if(file==null)return;
        try {
            if(file.getMods().stream().noneMatch(m->m.getModId().equals("scguns")&&m.getVersion().toString().equals("1.5")))
                throw new IllegalStateException("Unsupported Scorched Guns version");
            var checker=new CreateMixinPlugin.Preflight(name->{
                try(var input=Objects.requireNonNull(MixinService.getService().getResourceAsStream(name+".class"))) {
                    var node=new ClassNode();new ClassReader(input).accept(node,ClassReader.SKIP_FRAMES);return node;
                }
            });
            for(String name:List.of("ServerGunFireMixin","ScgunsProjectileTraceMixin","VanillaProjectileTraceMixin","ReloadTrackerMixin","ReloadRequestMixin","PlayerReloadTraceMixin","GunLoadedStateMixin","MovementReloadStateMixin","AnimatedReloadStateMixin"))
                checker.check(pkg.replace('.','/')+'/'+name);
            ScgunsSupport.publish(true);
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/Scguns").info("Scorched Guns 1.5 audit contracts verified");
        } catch(Exception|LinkageError failure) {
            ScgunsSupport.publish(false);
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/Scguns").warn("Scorched Guns integration disabled: {}",failure.toString());
        }
    }
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){return ScgunsSupport.available();}
    public void acceptTargets(Set<String> mine,Set<String> other){}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
