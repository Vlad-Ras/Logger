package com.roften.avilixlogger.compat.supplementaries;

import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Detached display data: never deserialize a mod item or execute text click events. */
final class SupplementariesDisplayDetails {
    private SupplementariesDisplayDetails(){}
    static void append(List<Component> out,ServerLevel level,CompoundTag before,CompoundTag after){
        if(before==null||after==null||Objects.equals(before.get("Items"),after.get("Items")))return;
        item(out,level,"До",before);item(out,level,"После",after);
    }
    private static void item(List<Component> out,ServerLevel level,String label,CompoundTag root){
        var items=root.getList("Items",Tag.TAG_COMPOUND);
        if(items.isEmpty()){out.add(Component.literal(label+": доска пуста"));return;}
        if(items.size()!=1)return;
        var item=items.getCompound(0);String id=item.getString("id");
        out.add(Component.literal(label+": предмет на доске "+id));
        var components=item.getCompound("components");
        if(id.equals("minecraft:filled_map")){
            if(components.contains("minecraft:map_id",Tag.TAG_INT))out.add(Component.literal(label+": ID карты "+components.getInt("minecraft:map_id")));
            return;
        }
        boolean written=id.equals("minecraft:written_book");
        if(!written&&!id.equals("minecraft:writable_book"))return;
        var book=components.getCompound(written?"minecraft:written_book_content":"minecraft:writable_book_content");
        if(written){
            var title=book.get("title");out.add(Component.literal(label+": название "+plain(title)));
            if(title instanceof CompoundTag t&&t.contains("filtered"))out.add(Component.literal(label+": название (фильтр) "+plain(t.get("filtered"))));
            out.add(Component.literal(label+": автор "+book.getString("author")+", поколение "+book.getInt("generation")));
        }
        if(!(book.get("pages") instanceof ListTag pages)||pages.size()>100)return;
        for(int i=0;i<pages.size();i++){
            Tag page=pages.get(i);Tag raw=page instanceof CompoundTag t&&t.contains("raw")?t.get("raw"):page;
            String prefix=label+" [страница "+(i+1)+"]: ";
            out.add(Component.literal(prefix+(written?component(level,raw):plain(raw))));
            if(page instanceof CompoundTag t&&t.contains("filtered"))out.add(Component.literal(label+" [страница "+(i+1)+", фильтр]: "+(written?component(level,t.get("filtered")):plain(t.get("filtered")))));
        }
    }
    private static String plain(Tag tag){return tag instanceof StringTag text?text.getAsString():"";}
    private static String component(ServerLevel level,Tag tag){
        if(tag==null||level==null)return "";
        try{return net.minecraft.world.item.component.WrittenBookContent.CONTENT_CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),tag).result().map(Component::getString).orElse("");}
        catch(RuntimeException ignored){return "";}
    }
}
