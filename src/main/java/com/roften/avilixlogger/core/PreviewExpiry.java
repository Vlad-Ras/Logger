package com.roften.avilixlogger.core;

import java.util.Map;
import java.util.function.ToLongFunction;

/** Server-thread preview cleanup; active jobs own their data independently. */
public final class PreviewExpiry {
    private PreviewExpiry() {}

    public static <K,V> void prune(Map<K,V> previews,long now,ToLongFunction<V> expires) {
        if(previews.isEmpty())return;
        var iterator=previews.values().iterator();
        while(iterator.hasNext())if(expires.applyAsLong(iterator.next())<now)iterator.remove();
    }
}
