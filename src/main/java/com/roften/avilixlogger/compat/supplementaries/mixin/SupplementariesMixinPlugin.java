package com.roften.avilixlogger.compat.supplementaries.mixin;

import com.roften.avilixlogger.compat.create.mixin.CreateMixinPlugin;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;
import java.util.*;

/** Verify the pinned native contract without loading or initializing the target. */
public final class SupplementariesMixinPlugin implements IMixinConfigPlugin {
    private boolean available,automation;
    public void onLoad(String pkg) {
        var list=LoadingModList.get();var file=list==null?null:list.getModFileById("supplementaries");
        if(file==null)return;
        try {
            if(file.getMods().stream().noneMatch(m->m.getModId().equals("supplementaries")&&m.getVersion().toString().equals("1.21.1-4.0.2")))
                throw new IllegalStateException("Unsupported Supplementaries version");
            var checker=new CreateMixinPlugin.Preflight(name->{
                try(var input=Objects.requireNonNull(MixinService.getService().getResourceAsStream(name+".class"))) {
                    var node=new ClassNode();new ClassReader(input).accept(node,ClassReader.SKIP_FRAMES);return node;
                }
            });
            for(String name:List.of("TextPacketMixin","TextHolderMixin"))checker.check(pkg.replace('.','/')+'/'+name);
            available=true;
            var create=list.getModFileById("create");
            if(create!=null&&create.getMods().stream().anyMatch(m->m.getModId().equals("create")&&m.getVersion().toString().equals("6.0.10"))){
                try{
                    for(String name:List.of("DisplayTextMixin","DisplayBookMixin"))checker.check(pkg.replace('.','/')+'/'+name);
                    automation=true;
                }catch(Exception|LinkageError failure){
                    org.slf4j.LoggerFactory.getLogger("AvilixLogger/Supplementaries").warn("Supplementaries display audit integration disabled: {}",failure.toString());
                }
            }
        } catch(Exception|LinkageError failure) {
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/Supplementaries").warn("Supplementaries audit integration disabled: {}",failure.toString());
        }
    }
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){return mixin.endsWith("DisplayTextMixin")||mixin.endsWith("DisplayBookMixin")?available&&automation:available;}
    public void acceptTargets(Set<String> mine,Set<String> other){}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
