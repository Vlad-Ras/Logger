package com.roften.avilixlogger.compat.backpacked.mixin;
import com.roften.avilixlogger.compat.backpacked.BackpackSupport;
import net.neoforged.fml.loading.LoadingModList;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.*;
import java.util.*;
public final class BackpackedMixinPlugin implements IMixinConfigPlugin {
    public void onLoad(String name) {}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){
        var list=LoadingModList.get();if(list==null)return false;
        var file=list.getModFileById("backpacked");
        return file!=null&&file.getMods().stream().anyMatch(m->m.getModId().equals("backpacked")&&BackpackSupport.supported(m.getVersion().toString()));
    }
    public void acceptTargets(Set<String> mine,Set<String> other){}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info){}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info){}
}
