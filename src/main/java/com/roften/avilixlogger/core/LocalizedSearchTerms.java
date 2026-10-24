package com.roften.avilixlogger.core;

import java.util.*;

/** Language-neutral IDs resolved by the client; bounded alternatives are sent with the query. */
public final class LocalizedSearchTerms {
    public static final int MAX=256;
    private static final java.util.regex.Pattern ID=java.util.regex.Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
    public record Name(String id,String label){public Name{label=label.toLowerCase(Locale.ROOT);}}
    private LocalizedSearchTerms(){}
    public static List<String> resolve(String text,Collection<Name> names){
        if(text==null||text.isBlank())return List.of();String needle=text.strip().toLowerCase(Locale.ROOT);
        var found=new TreeSet<String>();for(var name:names)if(name.label().contains(needle)){
            found.add(name.id());if(found.size()>MAX)throw new IllegalArgumentException("Слишком широкое название: уточните поиск (больше 256 блоков/предметов)");
        }return List.copyOf(found);
    }
    public static List<String> checked(List<String> values){
        if(values==null||values.isEmpty())return List.of();if(values.size()>MAX)throw new IllegalArgumentException("Слишком много вариантов поиска");
        for(var value:values)if(value==null||value.length()>128||!ID.matcher(value).matches())throw new IllegalArgumentException("Некорректный ID поиска");
        return values.stream().distinct().toList();
    }
}
