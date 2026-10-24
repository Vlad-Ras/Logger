package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.core.LocalizedSearchTerms;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import java.util.*;

/** Build once per language/resource reload, on the client. No registry/name scan on server ticks. */
final class LocalizedLogSearch {
    private static Language language;
    private static List<LocalizedSearchTerms.Name> names=List.of(),blocks=List.of();
    private LocalizedLogSearch(){}
    private static void refresh(){
        var current=Language.getInstance();if(current==language)return;
        var blockNames=new ArrayList<LocalizedSearchTerms.Name>();var all=new LinkedHashSet<LocalizedSearchTerms.Name>();
        for(var block:BuiltInRegistries.BLOCK)blockNames.add(new LocalizedSearchTerms.Name(BuiltInRegistries.BLOCK.getKey(block).toString(),block.getName().getString()));
        all.addAll(blockNames);
        for(var item:BuiltInRegistries.ITEM)all.add(new LocalizedSearchTerms.Name(BuiltInRegistries.ITEM.getKey(item).toString(),item.getDescription().getString()));
        for(var entity:BuiltInRegistries.ENTITY_TYPE)all.add(new LocalizedSearchTerms.Name(BuiltInRegistries.ENTITY_TYPE.getKey(entity).toString(),entity.getDescription().getString()));
        blocks=List.copyOf(blockNames);names=List.copyOf(all);language=current;
    }
    static List<String> search(String text){if(text==null||text.isBlank())return List.of();refresh();return LocalizedSearchTerms.resolve(text,names);}
    static List<String> blocks(String text){if(text==null||text.isBlank())return List.of();refresh();return LocalizedSearchTerms.resolve(text,blocks);}
    static void clear(){language=null;names=List.of();blocks=List.of();}
}
