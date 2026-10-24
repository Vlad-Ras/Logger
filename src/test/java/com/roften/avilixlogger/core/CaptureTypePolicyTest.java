package com.roften.avilixlogger.core;

/** All twelve GUI categories use a single runtime/server-side policy. */
public final class CaptureTypePolicyTest {
    public static void run() {
        int previous=CaptureTypePolicy.mask();
        try {
            CaptureTypePolicy.install(0);
            for(int i=1;i<=12;i++)check(CaptureTypePolicy.enabled(i),"default type disabled");
            int mask=CaptureTypePolicy.changed(0,11,false);
            CaptureTypePolicy.install(mask);
            String bearing=CreateContraptionKind.BEARING.source(java.util.UUID.randomUUID(),"work");
            check(!CaptureTypePolicy.enabled(11),"bearing switch did not take");
            check(!CaptureTypePolicy.allowsCreateKind(CreateContraptionKind.BEARING),"bearing hot-path allowed");
            check(CaptureTypePolicy.allowsCreateKind(CreateContraptionKind.TRAIN),"bearings muted train");
            check(!CaptureTypePolicy.allows(ActionType.BLOCK_BREAK,bearing,null),"bearing block row passed");
            check(CaptureTypePolicy.allows(ActionType.BLOCK_BREAK,"player:use",null),"nonbearing block row lost");
            mask=CaptureTypePolicy.changed(mask,11,true);
            mask=CaptureTypePolicy.changed(mask,2,false);
            CaptureTypePolicy.install(mask);
            check(!CaptureTypePolicy.allows(ActionType.ME_TAKE,"mod:ae2",null),"AE2 inventory row survived");
            check(!CaptureTypePolicy.allows(ActionType.BLOCK_ENTITY_NBT_CHANGE,"mod:generic",null),"shared container NBT survived");
            check(CaptureTypePolicy.allows(ActionType.BLOCK_PLACE,"player:use",null),"container masked normal block");
            CaptureTypePolicy.install(CaptureTypePolicy.changed(mask,3,false));
            check(!CaptureTypePolicy.allows(ActionType.ENTITY_DEATH,"mod:generic",null),"entity class not disabled");
            check(CaptureTypePolicy.allows(ActionType.ITEM_CRAFT,"player:craft",null),"entity mask disabled items");
            CaptureTypePolicy.install(CaptureTypePolicy.changed(CaptureTypePolicy.mask(),10,false));
            check(!CaptureTypePolicy.allows(ActionType.BACKPACK_PUT,"mod:backpacked",null),"backpack class not disabled");
            check(CaptureTypePolicy.allows(ActionType.CART_ROLLBACK,"create:cart:sample",null),"rollback audit must remain available");
            CaptureTypePolicy.install(CaptureTypePolicy.ALL_DISABLED);
            for(int i=1;i<=12;i++)check(!CaptureTypePolicy.enabled(i),"disable all did not take");
            check(CaptureTypePolicy.changed(CaptureTypePolicy.ALL_DISABLED,7,true)
                    == CaptureTypePolicy.ALL_DISABLED-(1<<6),"enable one touched other types");
            try { CaptureTypePolicy.changed(0,13,false);throw new AssertionError("untrusted type accepted");}
            catch(IllegalArgumentException expected){}
        } finally { CaptureTypePolicy.install(previous); }
        System.out.println("CAPTURE_TYPES_2_1_24_OK atomic groups, bearing hot-path, overlapping types, rollback safety");
    }
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
