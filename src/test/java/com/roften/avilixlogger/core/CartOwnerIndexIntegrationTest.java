package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.net.*;
import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.fml.config.*;
import java.nio.file.Path;
import java.util.*;
import static com.roften.avilixlogger.core.AsyncPipelineTest.check;

/** Optional real ClickHouse fixture; runs in CI, separate from the HTTP contract mock. */
public final class CartOwnerIndexIntegrationTest {
    public static void main(String[] args)throws Exception{
        String endpoint=System.getenv("AVILIX_TEST_CLICKHOUSE_URL");if(endpoint==null)throw new IllegalStateException("Integration database URL required");
        var config=CommentedConfig.inMemory();LoggerConfig.SPEC.correct(config);
        String db="avilix_gui_test_"+Long.toUnsignedString(System.nanoTime());
        config.set(List.of("clickhouse","url"),endpoint);config.set(List.of("clickhouse","database"),db);config.set(List.of("clickhouse","user"),"logger_test");config.set(List.of("clickhouse","password"),"logger_test");
        var ctor=Class.forName("net.neoforged.fml.config.LoadedConfig").getDeclaredConstructor(CommentedConfig.class,Path.class,ModConfig.class);ctor.setAccessible(true);LoggerConfig.SPEC.acceptConfig((IConfigSpec.ILoadedConfig)ctor.newInstance(config,null,null));
        ClickHouseLogStorage storage=null;
        try{
            storage=new ClickHouseLogStorage();UUID cart=UUID.randomUUID(),other=UUID.randomUUID();var entries=new ArrayList<LogEntry>();
            var checkpoint=entry(cart,1,ActionType.CART_PACK);checkpoint.extra="cart="+cart+"; owner=Owner; sequence=1";entries.add(checkpoint);
            for(int i=2;i<92;i++){var row=entry(i%3==0?cart:other,i,ActionType.BLOCK_PLACE);row.blockAfter="minecraft:stone";entries.add(row);}
            storage.appendBatch("owner-integration-first",entries);
            check(storage.cartOwners(Set.of(cart)).get(cart).equals("Owner"),"materialized owner metadata missing");
            var payload=new C2SCartHistoryPayload(1,"",0,"DifferentActor",false,"Owner","","ALL","*","",12);var query=CartHistoryQuery.build(payload);var page=storage.queryReverse(query);
            check(page.size()==13&&page.stream().allMatch(e->cart.equals(CartAuditContext.id(e.source))),"real owner query filtered after LIMIT, lost sparse matches or confused actor/owner");
            query.beforeId=page.get(11).id;var next=storage.queryReverse(query);check(next.size()==13&&next.getFirst().id==page.get(12).id,"real cursor skipped lookahead or thinned page");
            var transferred=entry(cart,100,ActionType.CART_PLACE);transferred.extra="owner=NewOwner; moved";storage.appendBatch("owner-integration-transfer",List.of(transferred));
            check(storage.cartOwners(Set.of(cart)).get(cart).equals("NewOwner"),"argMaxMerge did not select newest explicit owner");query.beforeId=0;check(storage.queryReverse(query).isEmpty(),"old owner matched after transfer");query.cartOwner="NewOwner";check(storage.queryReverse(query).size()==13,"new owner did not match full construction history");
            System.out.println("CART_OWNER_CLICKHOUSE_NATIVE_OK: real DDL+materialized views+bootstrap+argMaxMerge+owner/actor distinction+sparse pages+cursor+ownership transfer");
        }finally{if(storage!=null)storage.shutdown();LoggerConfig.SPEC.acceptConfig(null);}
    }
    private static LogEntry entry(UUID id,long number,ActionType type){var row=new LogEntry();row.id=number;row.ts=System.currentTimeMillis();row.dim="minecraft:overworld";row.type=type;row.actorName="DifferentActor";row.source=CartAuditContext.prefix(id)+"test:"+number;return row;}
}
