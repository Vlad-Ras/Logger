package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.sun.net.httpserver.HttpServer;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.roften.avilixlogger.core.AsyncPipelineTest.*;

/** HTTP contract test: a failure after the feed insert must retry identical data and tokens. */
final class ClickHouseDeliveryTest {
    static void run() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> requests = new java.util.concurrent.ConcurrentHashMap<>();
        List<String> selects = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicBoolean failed = new AtomicBoolean();
        AtomicBoolean schemaFailure = new AtomicBoolean();
        var legacyRows=new java.util.concurrent.atomic.AtomicReference<List<LogEntry>>();
        java.util.concurrent.atomic.AtomicReference<Throwable> handlerFailure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> lookupResponse = new java.util.concurrent.atomic.AtomicReference<>();
        http.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if(body.startsWith("SELECT")&&body.contains("JSONEachRow"))selects.add(body);
            int status = schemaFailure.get() && body.startsWith("SELECT") ? 500 : 200;
            try {
                if (body.startsWith("INSERT")) {
                    String query = exchange.getRequestURI().getRawQuery();
                    check(query.contains("async_insert=0") && query.contains("insert_deduplication_token="), "insert lacks durable acknowledgment/deduplication");
                    String previous = requests.putIfAbsent(query, body);
                    check(previous == null || previous.equals(body), "partial batch retry changed payload");
                    if (body.contains("_containers") && failed.compareAndSet(false, true)) status = 503;
                }
            } catch (Throwable failure) { handlerFailure.set(failure); status = 500; }
            String responseText=body.startsWith("SELECT") ? body.contains("JSONEachRow")?Objects.toString(lookupResponse.get(),""):"0\n" : status == 200 ? "" : "simulated failure";
            if(schemaFailure.get()&&body.startsWith("SELECT"))responseText="UNKNOWN_IDENTIFIER target_id";
            if(legacyRows.get()!=null&&body.startsWith("SELECT id, data")){
                var cursor=java.util.regex.Pattern.compile("AND id < ([0-9]+)").matcher(body);long beforeId=cursor.find()?Long.parseLong(cursor.group(1)):Long.MAX_VALUE;
                var limit=java.util.regex.Pattern.compile("LIMIT ([0-9]+)").matcher(body);check(limit.find(),"legacy LIMIT missing");int max=Integer.parseInt(limit.group(1));
                responseText=legacyRows.get().stream().filter(e->e.id<beforeId).limit(max).map(e->e.id+"\t"+Base64.getEncoder().encodeToString(GzipJson.toGzippedJsonBytes(e))+"\n").collect(java.util.stream.Collectors.joining());
            }
            byte[] response=responseText.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
            if (response.length > 0) exchange.getResponseBody().write(response);
            exchange.close();
        });
        http.start();
        ClickHouseLogStorage database = null;
        try {
            CommentedConfig config = CommentedConfig.inMemory();
            LoggerConfig.SPEC.correct(config);
            config.set(List.of("clickhouse", "url"), "http://127.0.0.1:" + http.getAddress().getPort());
            config.set(List.of("clickhouse", "storeNonRollbackDetails"), false);
            // FML seals ILoadedConfig. Use its real wrapper for this headless test;
            // no file/container is needed because the already-correct config is never saved.
            var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                    .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
            constructor.setAccessible(true);
            LoggerConfig.SPEC.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null));
            database = new ClickHouseLogStorage();
            LogEntry first = row("delta"), second = row("delta");
            first.type = second.type = ActionType.CONTAINER_PUT;
            first.dim = second.dim = "minecraft:overworld";
            first.itemStackNbt = second.itemStackNbt = "{id:'minecraft:stone',count:1}";
            first.count = 2; second.count = 3;
            List<LogEntry> batch = List.of(first, second);
            try { database.appendBatch("retry-test", batch); throw new AssertionError("failed detail insert was acknowledged"); }
            catch (java.io.IOException expected) {}
            database.appendBatch("retry-test", batch);
            check(handlerFailure.get() == null, "HTTP contract violation: " + handlerFailure.get());
            check(failed.get() && requests.size() == 2, "split feed/detail retry was not exercised");
            check(first.count == 2 && second.count == 3, "coalescing mutated original item counts");
            LogEntry loot=row("loot");loot.type=ActionType.ITEM_PICKUP;loot.dim="minecraft:overworld";loot.count=12;loot.actorUuid=UUID.randomUUID();
            UUID cart=UUID.randomUUID();loot.source=CartAuditContext.prefix(cart)+"loot_pickup:0";
            var before=new net.minecraft.nbt.CompoundTag();before.putString("Kind","loot_items");before.putUUID("Drop",UUID.randomUUID());var after=before.copy();after.putInt("Remaining",0);
            loot.deferSnapshot(LogEntry.SnapshotField.PLAYER_INV_BEFORE,before);loot.deferSnapshot(LogEntry.SnapshotField.PLAYER_INV_AFTER,after);
            var encoded=loot.copyForQueue();encoded.materializeSnapshots();check(loot.playerInvBefore==null&&encoded.playerInvBefore.equals(before.toString()),"recipient snapshot was serialized on the producer or lost in queue copy");
            database.appendBatch("cart-loot-test",List.of(encoded));
            String itemInsert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_items")).findFirst().orElseThrow();
            var itemRow=com.google.gson.JsonParser.parseString(itemInsert.substring(itemInsert.indexOf('\n')+1).trim()).getAsJsonObject();
            check(itemRow.get("player_inv_before").getAsString().equals(encoded.playerInvBefore)&&itemRow.get("player_inv_after").getAsString().equals(encoded.playerInvAfter),"split ITEMS omitted linked pickup snapshots");
            itemRow.addProperty("entity_uuid","");itemRow.addProperty("entity_type","");lookupResponse.set(itemRow.toString()+"\n");
            var query=new LogQuery();query.type=ActionType.ITEM_PICKUP;query.cartId=cart;query.requireDetails=true;query.dim="*";query.beforeId=Long.MAX_VALUE;
            var restored=database.queryReverse(query);check(restored.size()==1&&restored.getFirst().entityUuid==null&&encoded.playerInvBefore.equals(restored.getFirst().playerInvBefore)&&encoded.playerInvAfter.equals(restored.getFirst().playerInvAfter),"split ITEMS lookup lost pickup envelope or assumed an entity column");
            check(handlerFailure.get()==null,"pickup HTTP contract violation: "+handlerFailure.get());
            UUID bagId=UUID.randomUUID();LogEntry bag=row("bag_loss");bag.type=ActionType.BACKPACK_DESTROY;bag.dim="minecraft:overworld";bag.entityUuid=bagId;bag.source=BackpackIds.prefix(bagId)+"destroy";
            bag.itemStackNbt="{id:'backpacked:backpack',count:1}";
            bag.containerSlotsBefore="{Size:1,Items:[{Slot:0,id:'minecraft:diamond',count:12}]}";
            bag.containerSlotsAfter="{Size:1,Items:[]}";
            database.appendBatch("backpack-test",List.of(bag));
            String bagInsert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_compat")).findFirst().orElseThrow();
            var bagRow=com.google.gson.JsonParser.parseString(bagInsert.substring(bagInsert.indexOf('\n')+1).trim()).getAsJsonObject();
            check(bagRow.get("item_stack").getAsString().equals(bag.itemStackNbt)&&bagRow.get("container_slots_before").getAsString().equals(bag.containerSlotsBefore),"Backpack recovery/details snapshots discarded as non-rollback data");
            var bagQuery=new LogQuery();bagQuery.dim="*";bagQuery.backpackId=bagId;bagQuery.types=BackpackIds.types();bagQuery.requireDetails=true;
            lookupResponse.set(bagRow+"\n");var bagRead=database.queryReverse(bagQuery);
            check(bagRead.size()==1&&bagRead.getFirst().type==ActionType.BACKPACK_DESTROY&&bagRead.getFirst().itemStackNbt.equals(bag.itemStackNbt),"Backpack COMPAT lookup lost full recovery snapshot");
            lookupResponse.set(null);selects.clear();bagQuery.requireDetails=false;database.queryReverse(bagQuery);
            check(!selects.isEmpty()&&selects.stream().allMatch(sql->sql.contains("startsWith(source, '"+BackpackIds.prefix(bagId)+"')")),"GUI/feed/fallback query broadened mandatory backpack ID");
            selects.clear();var cartQuery=new LogQuery();cartQuery.dim="*";cartQuery.sourcePrefix="create:cart:";database.queryReverse(cartQuery);
            check(!selects.isEmpty()&&selects.stream().allMatch(sql->sql.contains("startsWith(source, 'create:cart:')")),"GUI cart category broadened source prefix");
            check(handlerFailure.get()==null,"Backpack HTTP contract violation: "+handlerFailure.get());
            System.out.println("Backpack HTTP checks passed: persisted recovery snapshots and mandatory GUI/feed/fallback ID filters.");
            UUID autoCart=UUID.randomUUID();var automation=row("cart_automation");automation.type=ActionType.CART_TRANSFER;automation.dim="minecraft:overworld";automation.source=CartAuditContext.prefix(autoCart)+"automation_transfer:1";
            automation.beBefore="{Kind:'automation',Slots:[{Kind:'fluid',Fluid:{id:'minecraft:water',amount:100}}]}";
            automation.beAfter="{Kind:'automation',Slots:[{Kind:'fluid',Fluid:{id:'minecraft:water',amount:850}}]}";
            database.appendBatch("cart-automation-test",List.of(automation));
            String autoInsert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_compat")&&body.contains(autoCart.toString())).findFirst().orElseThrow();
            var autoRow=com.google.gson.JsonParser.parseString(autoInsert.substring(autoInsert.indexOf('\n')+1).trim()).getAsJsonObject();
            check(autoRow.get("be_before").getAsString().equals(automation.beBefore)&&autoRow.get("be_after").getAsString().equals(automation.beAfter),"Create automation snapshots discarded with non-rollback details disabled");
            var autoQuery=new LogQuery();autoQuery.type=ActionType.CART_TRANSFER;autoQuery.cartId=autoCart;autoQuery.requireDetails=true;autoQuery.dim="*";lookupResponse.set(autoRow+"\n");
            var autoRead=database.queryReverse(autoQuery);check(autoRead.size()==1&&autoRead.getFirst().beBefore.equals(automation.beBefore)&&autoRead.getFirst().beAfter.equals(automation.beAfter),"Create automation COMPAT lookup lost either endpoint");
            System.out.println("Create automation HTTP checks passed: both endpoint snapshots persist with non-rollback details disabled.");
            lookupResponse.set(null);selects.clear();
            var search=new LogQuery();search.dim="*";search.sinceTs=1760000000000L;search.untilTs=1790000000000L;
            search.textFilter="O'Reilly needle";search.trainNameFilter="Express";search.owner="Alice";search.blockIdFilter="stone";search.planeOnly=true;search.planeNameFilter="aircraft";
            database.queryReverse(search);
            check(!selects.isEmpty()&&selects.stream().allMatch(sql->sql.contains("ts_ms >= 1760000000000")&&sql.contains("ts_ms <= 1790000000000")&&sql.contains("positionCaseInsensitiveUTF8")&&sql.contains("express")&&sql.contains("alice")&&sql.contains("stone")&&sql.contains("needle")&&sql.contains("action IN (")),"GUI/feed/split query dropped a date/search/category criterion");
            check(selects.getFirst().contains("target_id")&&!selects.getFirst().contains("entity_nbt"),"ordinary GUI search fetched heavy snapshots");
            search.textFilter="сломал";selects.clear();database.queryReverse(search);
            check(selects.stream().allMatch(sql->sql.contains("OR action = "+ActionType.BLOCK_BREAK.ordinal())),"display action text search was not pushed into SQL");
            System.out.println("GUI_SEARCH_HTTP_OK: date bounds, all search predicates, action labels and escaped input preserved before LIMIT on feed and split fallback");
            schemaFailure.set(true);selects.clear();boolean refused=false;
            try{database.queryReverse(search);}catch(IllegalStateException expected){refused=true;}
            check(refused&&selects.stream().allMatch(sql->sql.contains("needle")||sql.contains("сломал")),"schema fallback silently removed a selected search filter");schemaFailure.set(false);
            database.shutdown();database=null;
            config.set(List.of("clickhouse","schemaMode"),"legacy");
            LoggerConfig.SPEC.acceptConfig((IConfigSpec.ILoadedConfig)constructor.newInstance(config,null,null));
            database=new ClickHouseLogStorage();var older=new ArrayList<LogEntry>();
            UUID legacyCart=UUID.randomUUID();
            for(int i=1000;i>0;i--){var entry=row(i%47==0?"needle Express owner:alice":"unrelated");entry.id=i;entry.source=CartAuditContext.prefix(i%47==0?legacyCart:UUID.randomUUID())+"test";entry.type=ActionType.BLOCK_PLACE;entry.blockAfter="minecraft:stone";older.add(entry);}legacyRows.set(older);
            var legacyQuery=new LogQuery();legacyQuery.dim="*";legacyQuery.limit=10;legacyQuery.cartId=legacyCart;legacyQuery.textFilter="needle";legacyQuery.owner="alice";legacyQuery.trainNameFilter="Express";
            var legacyFirst=database.queryReverse(legacyQuery);check(legacyFirst.size()==10&&legacyFirst.stream().allMatch(e->LogSearchPredicate.matches(e,legacyQuery)),"compressed legacy sparse page lost required filters");
            legacyQuery.beforeId=legacyFirst.getLast().id;var legacyNext=database.queryReverse(legacyQuery);check(legacyNext.size()==10&&legacyNext.getFirst().id<legacyFirst.getLast().id,"legacy cursor skipped matches or underfilled page");
            System.out.println("GUI_SEARCH_COMPAT_OK: schema errors refuse broadened pages; compressed legacy search refills sparse matches with exact construction/cursor filters");
        } finally {
            if (database != null) database.shutdown();
            http.stop(0);
            LoggerConfig.SPEC.acceptConfig(null);
        }
    }
}
