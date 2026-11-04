package com.roften.avilixlogger.core;

/** All twelve GUI categories use a single runtime/server-side policy. */
public final class CaptureTypePolicyTest {
    public static void run() {
        long previous = CaptureTypePolicy.mask();
        try {
            CaptureTypePolicy.install(0);
            for(int i=1;i<=CaptureTypePolicy.GROUPS;i++)check(CaptureTypePolicy.enabled(i),"default type disabled");
            long mask = CaptureTypePolicy.changed(0,11,false);
            CaptureTypePolicy.install(mask);
            String bearing=CreateContraptionKind.BEARING.source(java.util.UUID.randomUUID(),"work");
            check(!CaptureTypePolicy.enabled(11),"bearing switch did not take");
            check(!CaptureTypePolicy.allowsCreateKind(CreateContraptionKind.BEARING),"bearing hot-path allowed");
            check(CaptureTypePolicy.allowsCreateKind(CreateContraptionKind.TRAIN),"bearings muted train");
            check(!CaptureTypePolicy.allows(ActionType.BLOCK_BREAK,bearing,null),"bearing block row passed");
            check(CaptureTypePolicy.allows(ActionType.PLAYER_DEATH,bearing,null),"bearing switch suppressed a player's death");
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
            CaptureTypePolicy.install(CaptureTypePolicy.changed(0,26,false));
            check(!CaptureTypePolicy.allows(ActionType.ME_CRAFT_FINISH,"ae2:crafting",null),"AE2 craft handler survived");
            check(CaptureTypePolicy.allows(ActionType.ME_TAKE,"ae2:menu",null),"AE2 craft switch disabled resources");
            CaptureTypePolicy.install(CaptureTypePolicy.changed(0,13,false));
            check(!CaptureTypePolicy.allows(ActionType.ME_TAKE,"ae2:menu",null),"full AE2 switch survived");
            check(CaptureTypePolicy.allows(ActionType.CONTAINER_PUT,"player:menu",null),"AE2 switch disabled normal containers");
            long mechanism = CaptureTypePolicy.changed(0, CaptureTypePolicy.CREATE_DEPLOYER, false);
            CaptureTypePolicy.install(mechanism);
            check(!CaptureTypePolicy.enabled(CaptureTypePolicy.CREATE_DEPLOYER) && CaptureTypePolicy.enabled(CaptureTypePolicy.CREATE_DRILL), "mechanism switches not independent");
            check(CaptureTypePolicy.allowsCreateKind(CreateContraptionKind.MINECART), "mechanism switch disabled lifecycle");
            check(CaptureTypePolicy.allows(ActionType.CART_PACK, "create:cart:sample", null), "mechanism switch disabled pack snapshot");
            boolean outer = CaptureTypePolicy.suppress(true);
            try {
                check(!CaptureTypePolicy.allows(ActionType.BLOCK_BREAK, "mod:create", null), "muted generic callback captured");
                check(CaptureTypePolicy.allows(ActionType.CART_ROLLBACK, "create:cart:sample", null), "committed rollback audit muted");
                boolean inner = CaptureTypePolicy.suppress(false);
                try { check(CaptureTypePolicy.suppressed(), "nested callback reenabled capture"); }
                finally { CaptureTypePolicy.restoreSuppression(inner); }
            } finally { CaptureTypePolicy.restoreSuppression(outer); }
            check(!CaptureTypePolicy.suppressed(), "capture suppression leaked");
            for(int group=31;group<=40;group++) {
                long bit=CaptureTypePolicy.changed(0,group,false);CaptureTypePolicy.install(bit);
                check(!CaptureTypePolicy.enabled(group)&&CaptureTypePolicy.enabled(13)&&CaptureTypePolicy.enabled(30),"extended mechanism bit collided with saved module/overflow switches");
            }
            CaptureTypePolicy.install(CaptureTypePolicy.ALL_DISABLED);
            for(int i=1;i<=CaptureTypePolicy.GROUPS;i++)check(!CaptureTypePolicy.enabled(i),"disable all did not take");
            check(CaptureTypePolicy.changed(CaptureTypePolicy.ALL_DISABLED,7,true)
                    == CaptureTypePolicy.ALL_DISABLED-(1<<6),"enable one touched other types");
            try { CaptureTypePolicy.changed(0,CaptureTypePolicy.GROUPS+1,false);throw new AssertionError("untrusted type accepted");}
            catch(IllegalArgumentException expected){}
        } finally { CaptureTypePolicy.install(previous); }
        System.out.println("CAPTURE_TYPES_2_1_24_OK atomic groups, bearing hot-path, overlapping types, rollback safety");
    }
    private static void check(boolean ok,String message) {if(!ok)throw new AssertionError(message);}
}
