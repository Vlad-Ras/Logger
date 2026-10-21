package com.roften.avilixlogger.core;

import java.util.*;

/** Language-neutral IDs resolved by the client; bounded alternatives are sent with the query. */
public final class LocalizedSearchTerms {
    public static final int MAX=256;
    public record Name(String id,String label){}
    private LocalizedSearchTerms(){}
    public static List<String> resolve(String text,Collection<Name> names){
        if(text==null||text.isBlank())return List.of();String needle=text.strip().toLowerCase(Locale.ROOT);
        var found=new TreeSet<String>();for(var name:names)if(name.label().toLowerCase(Locale.ROOT).contains(needle)){
            found.add(name.id());if(found.size()>MAX)throw new IllegalArgumentException("Слишком широкое название: уточните поиск (больше 256 блоков/предметов)");
        }return List.copyOf(found);
    }
    public static List<String> checked(List<String> values){
        if(values==null||values.isEmpty())return List.of();if(values.size()>MAX)throw new IllegalArgumentException("Слишком много вариантов поиска");
        for(var value:values)if(value==null||value.length()>128||!value.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("Некорректный ID поиска");
        return values.stream().distinct().toList();
    }
}
