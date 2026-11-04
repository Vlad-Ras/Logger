package com.roften.avilixlogger.core;

import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Bounded, detached comparison of recorded states/settings; never reads the current world. */
public final class BlockChangeDetails {
    private static final int MAX_LINES=32,MAX_NODES=4096,MAX_INPUT=1_048_576;
    private BlockChangeDetails() {}
    public static List<String> changes(LogEntry row){
        if(row==null||row.type==null||!Set.of(ActionType.BLOCK_BREAK,ActionType.BLOCK_PLACE,ActionType.BLOCK_INTERACT,ActionType.BLOCK_USE,
                ActionType.BLOCK_ENTITY_NBT_CHANGE,ActionType.CART_STRUCTURE_CHANGE,ActionType.CART_STATUS).contains(row.type))return List.of();
        var diff=new Diff();
        var before=parse(row.blockBefore,diff);var after=parse(row.blockAfter,diff);
        if(before!=null&&after!=null)diff.visit(before,after,"Блок",0,false);
        var b=parse(row.beBefore,diff);var a=parse(row.beAfter,diff);
        if(b!=null&&a!=null){
            if(row.type==ActionType.CART_STRUCTURE_CHANGE){
                diff.visit(b.get("State"),a.get("State"),"Блок",0,false);
                diff.visit(b.get("Data"),a.get("Data"),"Настройки",0,true);
            }else if(row.type==ActionType.CART_STATUS){
                if(b.contains("DisabledActors")&&a.contains("DisabledActors"))diff.visit(b.get("DisabledActors"),a.get("DisabledActors"),"Отключённые механизмы",0,false);
            }else diff.visit(b,a,"Настройки",0,true);
        }
        if(diff.limited)diff.lines.add("… показана часть изменений; полные значения сохранены в снимках");
        return List.copyOf(diff.lines);
    }
    public static List<Component> describe(LogEntry row){
        var changes=changes(row);if(changes.isEmpty())return List.of();
        var result=new ArrayList<Component>();
        if(row.type==ActionType.CART_STRUCTURE_CHANGE){
            var after=row.beAfter==null||row.beAfter.length()>MAX_INPUT?null:NbtSerde.fromSnbt(row.beAfter);
            if(after!=null&&after.contains("LocalPos"))result.add(Component.literal("Блок внутри конструкции: "+after.getCompound("State").getString("Name")+" · "+net.minecraft.core.BlockPos.of(after.getLong("LocalPos")).toShortString()));
        }
        result.add(Component.literal("Что изменилось:"));for(var line:changes)result.add(Component.literal(line));return List.copyOf(result);
    }
    private static CompoundTag parse(String value,Diff diff){
        if(value==null||value.isBlank())return null;
        if(value.length()>MAX_INPUT){diff.limited=true;return null;}
        return NbtSerde.fromSnbt(value);
    }
    private static final class Diff {
        final List<String> lines=new ArrayList<>();int nodes;boolean limited;
        void visit(Tag before,Tag after,String path,int depth,boolean ignoreAddress){
            if(before==after)return;
            if(++nodes>MAX_NODES||lines.size()>=MAX_LINES||depth>16){limited=true;return;}
            if(before instanceof CompoundTag b&&after instanceof CompoundTag a){
                var keys=new TreeSet<>(b.getAllKeys());keys.addAll(a.getAllKeys());
                for(var key:keys){
                    if(ignoreAddress&&depth==0&&Set.of("id","x","y","z").contains(key))continue;
                    visit(b.get(key),a.get(key),path+"."+(key.length()>80?key.substring(0,80)+"…":key),depth+1,false);
                    if(nodes>MAX_NODES||lines.size()>=MAX_LINES){limited=true;break;}
                }return;
            }
            if(before instanceof CollectionTag<?> b&&after instanceof CollectionTag<?> a&&b.getId()==a.getId()){
                for(int i=0;i<Math.max(b.size(),a.size());i++){
                    visit(i<b.size()?b.get(i):null,i<a.size()?a.get(i):null,path+"["+i+"]",depth+1,false);
                    if(nodes>MAX_NODES||lines.size()>=MAX_LINES){limited=true;break;}
                }return;
            }
            if(Objects.equals(before,after))return;
            if(before instanceof CompoundTag||after instanceof CompoundTag||before instanceof ListTag||after instanceof ListTag){
                // Expand an added/removed branch so individual settings are visible.
                if(before==null&&after instanceof CompoundTag a){visit(new CompoundTag(),a,path,depth,ignoreAddress);return;}
                if(after==null&&before instanceof CompoundTag b){visit(b,new CompoundTag(),path,depth,ignoreAddress);return;}
                if(before==null&&after instanceof ListTag a){visit(new ListTag(),a,path,depth,ignoreAddress);return;}
                if(after==null&&before instanceof ListTag b){visit(b,new ListTag(),path,depth,ignoreAddress);return;}
            }
            lines.add((path.length()>240?path.substring(0,240)+"…":path)+": "+value(before)+" → "+value(after));
        }
        private String value(Tag tag){
            if(tag==null)return "отсутствует";
            if(tag instanceof ByteArrayTag array)return "массив байтов ("+array.size()+")";
            if(tag instanceof IntArrayTag array)return "массив чисел ("+array.size()+")";
            if(tag instanceof LongArrayTag array)return "массив чисел ("+array.size()+")";
            var text=tag instanceof StringTag?tag.getAsString():tag.toString();return text.length()>160?text.substring(0,160)+"…":text;
        }
    }
}
