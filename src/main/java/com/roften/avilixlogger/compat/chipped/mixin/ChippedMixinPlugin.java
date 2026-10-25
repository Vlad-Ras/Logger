package com.roften.avilixlogger.compat.chipped.mixin;

import com.roften.avilixlogger.compat.create.mixin.CreateMixinPlugin;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import org.spongepowered.asm.service.MixinService;
import java.util.*;

/** Verify the pinned native contract without loading or initializing the target. */
public final class ChippedMixinPlugin implements IMixinConfigPlugin {
    private boolean available;
    public void onLoad(String pkg) {
        var list=LoadingModList.get();var file=list==null?null:list.getModFileById("chipped");
        if(file==null)return;
        try {
            if(file.getMods().stream().noneMatch(m->m.getModId().equals("chipped")&&m.getVersion().toString().equals("4.0.2")))
                throw new IllegalStateException("Unsupported Chipped version");
            new CreateMixinPlugin.Preflight(name->{
                try(var input=Objects.requireNonNull(MixinService.getService().getResourceAsStream(name+".class"))) {
                    var node=new ClassNode();new ClassReader(input).accept(node,ClassReader.SKIP_FRAMES);return node;
                }
            }).check(pkg.replace('.','/')+"/WorkbenchMenuMixin");
            available=true;
        } catch(Exception|LinkageError failure) {
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/Chipped").warn("Chipped craft integration disabled: {}",failure.toString());
        }
    }
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){return available;}
    public void acceptTargets(Set<String> mine,Set<String> other){}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
