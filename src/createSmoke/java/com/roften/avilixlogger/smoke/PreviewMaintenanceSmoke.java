package com.roften.avilixlogger.smoke;

import java.util.*;

/** Exercise actual coordinator caches, including the heavy plan/item/NBT owners. */
final class PreviewMaintenanceSmoke {
    static void run() throws Exception {
        for(String name:List.of("CartItemRecovery","CartRollbackCoordinator","CreateTransferRollbackCoordinator","CreateStructuralRollbackCoordinator","CreateCouplingRollbackCoordinator"))
            cache(Class.forName("com.roften.avilixlogger.compat.create."+name),false);
        if(net.neoforged.fml.ModList.get().isLoaded("backpacked"))
            cache(Class.forName("com.roften.avilixlogger.compat.backpacked.BackpackRecovery"),true);
        System.out.println("PREVIEW_EXPIRY_NATIVE_OK: actual coordinator caches expire and retain valid replacements");
    }
    @SuppressWarnings("unchecked")
    private static void cache(Class<?> owner,boolean stringKey) throws Exception {
        var field=owner.getDeclaredField("PREVIEWS");field.setAccessible(true);
        var cache=(Map<Object,Object>)field.get(null);var original=new HashMap<>(cache);
        var type=Arrays.stream(owner.getDeclaredClasses()).filter(c->c.getSimpleName().equals("Preview")).findFirst().orElseThrow();
        var fields=type.getRecordComponents();var signature=Arrays.stream(fields).map(java.lang.reflect.RecordComponent::getType).toArray(Class<?>[]::new);
        var constructor=type.getDeclaredConstructor(signature);constructor.setAccessible(true);
        Object expiredKey=stringKey?"expiry-old":UUID.randomUUID(), validKey=stringKey?"expiry-current":UUID.randomUUID();
        try{
            cache.clear();Object valid=null;
            for(long expires:new long[]{99,100}){
                var values=new Object[fields.length];
                for(int i=0;i<fields.length;i++){
                    var f=fields[i];values[i]=f.getType()==long.class?(f.getName().equals("expires")?expires:1L):f.getType()==boolean.class?false:f.getType()==UUID.class?UUID.randomUUID():null;
                }
                Object preview=constructor.newInstance(values);
                cache.put(expires==99?expiredKey:validKey,preview);if(expires==100)valid=preview;
            }
            owner.getMethod("expirePreviews",long.class).invoke(null,100L);
            if(cache.size()!=1||cache.get(validKey)!=valid)throw new AssertionError(owner.getSimpleName()+" consumed a current preview or retained an expired payload");
            owner.getMethod("expirePreviews",long.class).invoke(null,101L);
            if(!cache.isEmpty())throw new AssertionError(owner.getSimpleName()+" retained an expired payload");
        }finally{cache.clear();cache.putAll(original);}
    }
}
