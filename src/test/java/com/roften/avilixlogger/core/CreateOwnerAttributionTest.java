package com.roften.avilixlogger.core;

import java.util.UUID;

/** Known executing mechanism ownership must not leak into unrelated or delayed causes. */
public final class CreateOwnerAttributionTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void run(){
        var id=UUID.randomUUID();var owner=UUID.randomUUID();var actual=UUID.randomUUID();
        var cart=CartAuditContext.enter(new CartAuditContext.Stamp(id,owner,"Owner","actor",1));
        var mechanism=CreateMechanismContext.enter(new CreateMechanismContext.Stamp("create:mechanical_drill",0,0,0));
        try{
            for(String phase:new String[]{"drill","saw","harvester","plough","roller","storage_io","cargo","automation_transfer"}){
                var row=new LogEntry();row.source=CartAuditContext.prefix(id)+phase;row.actorName="SYSTEM";
                CreateActorContext.attribute(row);check(owner.equals(row.actorUuid)&&"Owner".equals(row.actorName),"known mechanism owner lost: "+phase);
                row.actorUuid=actual;row.actorName="Actual";CreateActorContext.attribute(row);
                check(actual.equals(row.actorUuid)&&"Actual".equals(row.actorName),"actual player overwritten");
            }
            for(String source:new String[]{"create:world_creative","minecraft:lava",CartAuditContext.prefix(UUID.randomUUID())+"cargo"}){
                var row=new LogEntry();row.source=source;CreateActorContext.attribute(row);check(row.actorUuid==null,"unrelated source inherited owner");
            }
            CreateMechanismContext.restore(null);
            var delayed=new LogEntry();delayed.source=CartAuditContext.prefix(id)+"item_removed";
            CreateActorContext.attribute(delayed);check(delayed.actorUuid==null,"ownership history became a delayed actor");
        }finally{CreateMechanismContext.restore(mechanism);CartAuditContext.restore(cart);}
        System.out.println("CREATE_OWNER_ATTRIBUTION_OK: executing mechanisms, cargo/manifests, real player preservation, foreign sources and delayed neutrality");
    }
}
