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
            for(var type:List.of(ActionType.PROJECTILE_SHOOT,ActionType.PROJECTILE_HIT,ActionType.WEAPON_KILL,ActionType.WEAPON_RELOAD,ActionType.PLAYER_DEATH)){
                var weapon=row("weapon-"+type);weapon.type=type;weapon.dim="minecraft:overworld";weapon.source="mod:scguns:"+UUID.randomUUID();
                weapon.beAfter="{Kind:'WeaponCombat',Version:1,Provider:'scguns',Phase:'test',ActorPose:{Dimension:'minecraft:overworld',X:-2.125d,Y:70.25d,Z:9.875d,Yaw:29.25f,Pitch:-23.5f,EyeY:71.87d}}";
                database.appendBatch("weapon-"+type,List.of(weapon));
                String insert=requests.values().stream().filter(body->body.startsWith("INSERT")&&!body.substring(0,body.indexOf('\n')).contains("_feed")&&body.contains(weapon.source)).findFirst().orElseThrow();
                var weaponEncoded=com.google.gson.JsonParser.parseString(insert.substring(insert.indexOf('\n')+1).trim()).getAsJsonObject();
                check(weaponEncoded.get("be_after").getAsString().equals(weapon.beAfter),"weapon evidence discarded from "+type);
                var weaponQuery=new LogQuery();weaponQuery.type=type;weaponQuery.requireDetails=true;weaponQuery.sourcePrefix="mod:scguns:";weaponQuery.exactId=weapon.id;weaponQuery.limit=1;
                lookupResponse.set(weaponEncoded+"\n");selects.clear();var read=database.query(weaponQuery);
                check(read.size()==1 && read.getFirst().beAfter.equals(weapon.beAfter),"weapon details round trip lost "+type);
                check(selects.stream().anyMatch(sql->sql.contains("be_after AS be_after") && sql.contains("id = "+weapon.id)),"exact weapon query omitted evidence/cursors");
            }
            System.out.println("Weapon HTTP checks passed: shot/hit/kill/reload/death evidence survives disabled non-rollback details and exact ID lookup.");
            for(boolean gift:List.of(false,true)){
                var settings=gift?com.roften.avilixlogger.compat.blockpack.BlockPackGiftAudit.capture(new com.roften.avilixlogger.compat.blockpack.BlockPackGiftAudit.Actor(UUID.randomUUID(),"GiftActor","minecraft:overworld",1,2,3),true,false):row("menu properties");
                if(!gift){settings.type=ActionType.GUI_CHANGE;settings.source="player:container_button";settings.beBefore="{Properties:[I;0,2]}";settings.beAfter="{Properties:[I;1,3]}";}
                settings.materializeSnapshots();database.appendBatch("settings-evidence-"+gift,List.of(settings));
                String insert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_containers")&&body.contains(settings.source)).findFirst().orElseThrow();
                var settingsEncoded=com.google.gson.JsonParser.parseString(insert.substring(insert.indexOf('\n')+1).trim()).getAsJsonObject();
                check(settingsEncoded.get("be_before").getAsString().equals(settings.beBefore)&&settingsEncoded.get("be_after").getAsString().equals(settings.beAfter),"settings meaning pruned as optional detail");
                var q=new LogQuery();q.type=ActionType.GUI_CHANGE;q.requireDetails=true;q.exactId=settings.id;q.limit=1;lookupResponse.set(settingsEncoded+"\n");
                var rows=database.query(q);check(rows.size()==1&&rows.getFirst().beBefore.equals(settings.beBefore)&&rows.getFirst().beAfter.equals(settings.beAfter),"settings exact ID lookup lost before/after");
            }
            System.out.println("GUI_SETTING_HTTP_OK: menu DataSlot and BlockPack global gift values survive optional-detail pruning and exact ID lookup");
            var interaction=row("wrench-setting");interaction.type=ActionType.BLOCK_INTERACT;interaction.source="player:state_change_test";
            interaction.blockBefore="{Name:'minecraft:oak_trapdoor',Properties:{open:'false'}}";interaction.blockAfter="{Name:'minecraft:oak_trapdoor',Properties:{open:'true'}}";
            database.appendBatch("block-interaction-evidence",List.of(interaction));
            String interactionInsert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_blocks")&&body.contains(interaction.source)).findFirst().orElseThrow();
            var interactionEncoded=com.google.gson.JsonParser.parseString(interactionInsert.substring(interactionInsert.indexOf('\n')+1).trim()).getAsJsonObject();
            check(interactionEncoded.get("block_before").getAsString().equals(interaction.blockBefore)&&interactionEncoded.get("block_after").getAsString().equals(interaction.blockAfter),"restorable block interaction pruned as optional");
            var iq=new LogQuery();iq.type=ActionType.BLOCK_INTERACT;iq.requireDetails=true;iq.exactId=interaction.id;iq.limit=1;lookupResponse.set(interactionEncoded+"\n");
            var interactionRead=database.query(iq);check(interactionRead.size()==1&&BlockChangeDetails.changes(interactionRead.getFirst()).stream().anyMatch(s->s.contains("open")),"stored block setting unavailable by exact ID/details");
            var observation=row("observation-only");observation.type=ActionType.BLOCK_INTERACT;observation.source="player:observation_only_test";observation.blockAfter=interaction.blockAfter;
            database.appendBatch("block-interaction-observation",List.of(observation));
            check(requests.values().stream().noneMatch(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_blocks")&&body.contains(observation.source)),"after-only observation became mandatory full detail");
            System.out.println("BLOCK_INTERACTION_HTTP_OK: restorable before/after and property details survive optional pruning; after-only observation stays optional");
            var transactionBuyer=new ItemTransactionAudit.Party(UUID.randomUUID(),"ReceiptBuyer","minecraft:player","minecraft:overworld",-3,80,4);
            var transactionSeller=new ItemTransactionAudit.Party(UUID.randomUUID(),"ReceiptSeller","bf_blockpack:wandering_trader","minecraft:overworld",19,75,-22);
            var receipts=List.of(ItemTransactionAudit.gift(transactionBuyer,ItemTransactionAuditTest.stack("bf_blockpack:guide_book",1),1,null,true),ItemTransactionAudit.trade(transactionBuyer,transactionSeller,ItemTransactionAuditTest.stack("minecraft:emerald",3),ItemTransactionAuditTest.stack("minecraft:diamond",1),ItemTransactionAuditTest.stack("bf_blockpack:workbench",2),1));
            for(var receipt:receipts){
                receipt.materializeSnapshots();database.appendBatch("item-receipt-"+receipt.type,List.of(receipt));
                String insert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_items")&&body.contains(receipt.source)).findFirst().orElseThrow();
                var stored=com.google.gson.JsonParser.parseString(insert.substring(insert.indexOf('\n')+1).trim()).getAsJsonObject();
                check(stored.get("be_after").getAsString().equals(receipt.beAfter)&&stored.get("item_stack").getAsString().equals(receipt.itemStackNbt),"item receipt semantics pruned as optional detail");
                var rq=new LogQuery();rq.type=receipt.type;rq.requireDetails=true;rq.exactId=receipt.id;rq.limit=1;lookupResponse.set(stored+"\n");var read=database.query(rq);
                check(read.size()==1&&read.getFirst().count==receipt.count&&read.getFirst().beAfter.equals(receipt.beAfter)&&!ItemTransactionAudit.describe(read.getFirst()).isEmpty(),"receipt exact-ID lookup lost quantity or human details");
            }
            var conversion=row("chipped-conversion-proof");conversion.type=ActionType.ITEM_TRANSFORM;conversion.source="mod:chipped:workbench";conversion.playerInvBefore="{Items:[{Slot:0,id:'minecraft:stone',count:2}]}";conversion.playerInvAfter="{Items:[{Slot:0,id:'chipped:carved_stone',count:2}]}";
            database.appendBatch("conversion-required-detail",List.of(conversion));check(requests.values().stream().anyMatch(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_items")&&body.contains(conversion.source)&&body.contains("player_inv_before")),"conversion inventory receipt pruned as optional");
            System.out.println("ITEM_RECEIPT_HTTP_OK: gift/trade payload, costs, actor/target, item quantity and exact ID/details survive optional pruning; conversion inventory states retained");
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
            var action=row("deployer");action.type=ActionType.CREATE_DEPLOYER_ACTION;action.dim="minecraft:overworld";
            var actionId=UUID.randomUUID();action.source=CreateContraptionKind.TRAIN.source(actionId,"deployer")+":mechanism=create:deployer@1,2,3";
            action.beBefore="{Kind:'deployer_action',HeldItem:{id:'minecraft:stone',count:2}}";
            action.beAfter="{Kind:'deployer_action',HeldItem:{id:'minecraft:stone',count:1},Result:'item_use',Complete:1b}";
            database.appendBatch("deployer-action-test",List.of(action));
            String actionInsert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_compat")&&body.contains(actionId.toString())).findFirst().orElseThrow();
            var actionRow=com.google.gson.JsonParser.parseString(actionInsert.substring(actionInsert.indexOf('\n')+1).trim()).getAsJsonObject();
            check(actionRow.get("be_before").getAsString().equals(action.beBefore)&&actionRow.get("be_after").getAsString().equals(action.beAfter),"Deployer hand snapshots discarded as non-rollback details");
            var actionQuery=new LogQuery();actionQuery.type=ActionType.CREATE_DEPLOYER_ACTION;actionQuery.requireDetails=true;actionQuery.dim="*";actionQuery.sourcePrefix=CreateContraptionKind.TRAIN.prefix()+actionId+":";
            lookupResponse.set(actionRow+"\n");var actionRead=database.queryReverse(actionQuery);
            check(actionRead.size()==1&&actionRead.getFirst().source.equals(action.source)&&actionRead.getFirst().beAfter.equals(action.beAfter),"Deployer result lost on details lookup");
            check(CreateDeployerDetails.describe(null,actionRead.getFirst()).stream().anyMatch(line->line.getString().contains("minecraft:stone ×2")&&line.getString().contains("minecraft:stone ×1")),"GUI lost deployer hand before/after");
            System.out.println("Deployer HTTP checks passed: held-item states, exact train/mechanism source and GUI details persist.");
            for(var kind:CreateContraptionKind.values())if(kind!=CreateContraptionKind.UNKNOWN){
                var cargo=row("typed-cargo");cargo.type=ActionType.CART_CONTENT_CHANGE;cargo.dim="minecraft:overworld";
                var construction=UUID.randomUUID();var member=UUID.randomUUID();cargo.source=kind.source(construction,"cargo:mechanism=create:deployer@1,2,3");
                var beforeCargo=NbtSerde.fromSnbt("{Kind:'item',LocalPos:0L,Slot:25,Item:{id:'minecraft:stone',count:2}}");
                beforeCargo.putUUID("StorageEntity",member);beforeCargo.putUUID("Construction",construction);beforeCargo.putString("ConstructionKind",kind.name());
                var afterCargo=beforeCargo.copy();afterCargo.getCompound("Item").putInt("count",1);afterCargo.putString("Operation","deployer_take");afterCargo.putBoolean("External",true);
                cargo.beBefore=beforeCargo.toString();cargo.beAfter=afterCargo.toString();
                database.appendBatch("cargo-kind-"+kind.name(),List.of(cargo));
                var insert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_compat")&&body.contains(construction.toString())).findFirst().orElseThrow();
                var cargoEncoded=com.google.gson.JsonParser.parseString(insert.substring(insert.indexOf('\n')+1).trim()).getAsJsonObject();
                check(cargoEncoded.get("be_before").getAsString().equals(cargo.beBefore)&&cargoEncoded.get("be_after").getAsString().equals(cargo.beAfter),"typed cargo snapshots discarded");
                var cargoQuery=new LogQuery();cargoQuery.type=cargo.type;cargoQuery.sourcePrefix=kind.prefix()+construction+":";cargoQuery.requireDetails=true;cargoQuery.dim="*";
                lookupResponse.set(cargoEncoded+"\n");var cargoRead=database.queryReverse(cargoQuery).getFirst();
                check(cargo.source.equals(cargoRead.source)&&cargo.beAfter.equals(cargoRead.beAfter),"typed cargo details lost");
                check(NbtSerde.fromSnbt(cargoRead.beAfter).getUUID("StorageEntity").equals(member),"cargo member identity lost");
                var details=CartLogDetails.describe(null,cargoRead);
                check(details.stream().anyMatch(line->line.getString().contains("слот 25"))&&details.stream().anyMatch(line->line.getString().contains("активатор взял")),"typed cargo GUI cell/reason missing");
                var infinite=row("infinite-"+kind.name());infinite.type=ActionType.CART_TRANSFER;infinite.dim="minecraft:overworld";
                infinite.source=kind.source(construction,"storage_io");
                infinite.beBefore="{Kind:'storage_io',ResourceKind:'fluid',Direction:'out',Supplied:{id:'minecraft:lava',amount:8000},Complete:0b}";
                infinite.beAfter="{Kind:'storage_io',ResourceKind:'fluid',Direction:'out',Supplied:{id:'minecraft:lava',amount:8000},Payload:{id:'minecraft:lava',amount:125},Complete:0b}";
                database.appendBatch("infinite-kind-"+kind.name(),List.of(infinite));
                var ioInsert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_compat")&&body.contains(infinite.source)).findFirst().orElseThrow();
                var ioEncoded=com.google.gson.JsonParser.parseString(ioInsert.substring(ioInsert.indexOf('\n')+1).trim()).getAsJsonObject();
                check(ioEncoded.get("be_before").getAsString().equals(infinite.beBefore)&&ioEncoded.get("be_after").getAsString().equals(infinite.beAfter),"infinite storage quantity/template discarded");
                cargoQuery.type=infinite.type;lookupResponse.set(ioEncoded+"\n");var ioRead=database.queryReverse(cargoQuery).getFirst();
                check(infinite.source.equals(ioRead.source)&&infinite.beAfter.equals(ioRead.beAfter),"infinite storage IO lost in HTTP lookup");
                check(CartLogDetails.describe(null,ioRead).stream().anyMatch(line->line.getString().contains("Выдано")),"infinite fluid details missing");
            }
            System.out.println("Typed cargo HTTP checks passed: cart/train/bearing/other sources, before/after cells and mechanism reason persist.");
            System.out.println("Infinite storage HTTP checks passed: supplied templates and actual IO quantities persist for all four construction kinds.");
            var named=row("named attack "+"x".repeat(700));named.type=ActionType.ENTITY_ATTACK;named.dim="minecraft:overworld";
            named.actorName="ActorHTTP";named.entityType="minecraft:player";named.entityUuid=UUID.randomUUID();named.entityName="VictimHTTP";
            database.appendBatch("named-attack-test",List.of(named));
            var namedFeed=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_feed")&&body.contains("VictimHTTP")).findFirst().orElseThrow();
            var namedEncoded=com.google.gson.JsonParser.parseString(namedFeed.substring(namedFeed.indexOf('\n')+1).trim()).getAsJsonObject();
            check(namedEncoded.get("extra").getAsString().contains("VictimHTTP"),"feed truncation discarded target nickname");
            var namedQuery=new LogQuery();namedQuery.type=named.type;namedQuery.dim="*";lookupResponse.set(namedEncoded+"\n");selects.clear();
            var namedRead=database.queryReverse(namedQuery).getFirst();
            check(namedRead.actorName.equals("ActorHTTP")&&namedRead.entityName.equals("VictimHTTP")&&namedRead.entityUuid.equals(named.entityUuid),"feed confused author and offline target");
            check(selects.stream().anyMatch(sql->sql.contains("_feed")),"named victim not tested through lightweight feed");
            var npc=row("{\"modSetting\":\"keep me\"}");npc.type=ActionType.ENTITY_DEATH;npc.dim="minecraft:overworld";npc.entityType="minecraft:villager";npc.entityUuid=UUID.randomUUID();npc.entityName="NamedNpcHTTP";
            database.appendBatch("named-npc-test",List.of(npc));
            var npcInsert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_entities")&&body.contains("NamedNpcHTTP")).findFirst().orElseThrow();
            var npcEncoded=com.google.gson.JsonParser.parseString(npcInsert.substring(npcInsert.indexOf('\n')+1).trim()).getAsJsonObject();
            namedQuery.type=npc.type;namedQuery.requireDetails=true;lookupResponse.set(npcEncoded+"\n");var npcRead=database.queryReverse(namedQuery).getFirst();
            check(npcRead.entityName.equals(npc.entityName)&&npcRead.extra.equals(npc.extra),"detail lookup lost NPC name or original JSON context");
            var settings=row("settings");settings.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;settings.dim="minecraft:overworld";
            settings.blockBefore="{Name:'test:device'}";settings.blockAfter=settings.blockBefore;settings.beBefore="{Settings:{Speed:4}}";settings.beAfter="{Settings:{Speed:9}}";
            database.appendBatch("settings-test",List.of(settings));
            var settingsInsert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_blocks")&&body.contains("Settings")).findFirst().orElseThrow();
            var settingsEncoded=com.google.gson.JsonParser.parseString(settingsInsert.substring(settingsInsert.indexOf('\n')+1).trim()).getAsJsonObject();
            namedQuery.type=settings.type;lookupResponse.set(settingsEncoded+"\n");var settingsRead=database.queryReverse(namedQuery).getFirst();
            check(BlockChangeDetails.changes(settingsRead).stream().anyMatch(line->line.contains("Settings.Speed: 4 → 9")),"HTTP history reduced specific setting to touch");
            System.out.println("Target/settings HTTP checks passed: offline victim survives feed truncation, NPC names preserve extra JSON, exact setting values survive detail lookup.");
            for(var kind:CreateContraptionKind.values())if(kind!=CreateContraptionKind.UNKNOWN)for(String mode:List.of("container","depot","base-out","base-in")){
                boolean depot=mode.equals("depot"),base=mode.startsWith("base-"),load=mode.equals("base-in");if(base&&kind!=CreateContraptionKind.MINECART)continue;
                UUID construction=UUID.randomUUID(),transfer=UUID.randomUUID(),player=UUID.randomUUID();
                var cargo=row("linked cargo");cargo.type=ActionType.CART_CONTENT_CHANGE;cargo.dim="minecraft:overworld";cargo.source=kind.source(construction,depot?"depot_swap":"cargo:mechanism=minecraft:dropper@3,0,0");
                var b=NbtSerde.fromSnbt("{Kind:'item',LocalPos:3L,Slot:0,External:1b,TransferIndex:0,Item:{id:'minecraft:diamond',count:2}}");b.putUUID("Transfer",transfer);if(!depot)b.putString("TransferKind","automation");
                if(base){b.putBoolean("BaseCart",true);b.putLong("LocalPos",0);cargo.source=kind.source(construction,"cargo");}
                var a=b.copy();a.getCompound("Item").putInt("count",1);cargo.beBefore=b.toString();cargo.beAfter=a.toString();
                if(load){b.remove("Item");cargo.beBefore=b.toString();}
                var manifest=row("linked recipient");manifest.type=ActionType.CART_TRANSFER;manifest.dim=cargo.dim;manifest.source=kind.source(construction,depot?"depot_swap":"automation_transfer");
                var mb=NbtSerde.fromSnbt(depot?"{Format:1,Kind:'menu_items',Complete:1b,Changes:1,Slots:[{Slot:0}]}":"{Format:1,Kind:'automation',Mounted:1b,Complete:1b,Changes:1,Machine:'minecraft:dropper',MachineDim:'minecraft:overworld',MachinePos:7L,Slots:[{Kind:'item',Slot:27,Pos:8L,Dimension:'minecraft:overworld',Side:'west',Size:54,Limit:99,DirectContainer:1b,DoubleChest:{Pos:9L},Block:{Name:'minecraft:chest'},BlockEntity:'minecraft:chest',Handler:'net.neoforged.neoforge.items.wrapper.InvWrapper'}]}");
                mb.putUUID("Transfer",transfer);if(depot)mb.putUUID("Player",player);
                if(base){mb.remove("Mounted");mb.putString("Machine","minecraft:hopper");var address=mb.getList("Slots",10).getCompound(0);address.remove("DoubleChest");address.putInt("Slot",0);address.putInt("Size",5);address.putString("Side",load?"east":"up");address.getCompound("Block").putString("Name","minecraft:hopper");address.putString("BlockEntity","minecraft:hopper");}
                var ma=mb.copy();ma.getList("Slots",10).getCompound(0).put("Item",NbtSerde.fromSnbt("{id:'minecraft:diamond',count:1,components:{'minecraft:custom_name':'\"linked diamond\"'}}"));
                if(load){mb.getList("Slots",10).getCompound(0).put("Item",NbtSerde.fromSnbt("{id:'minecraft:diamond',count:2}"));ma.getList("Slots",10).getCompound(0).put("Item",NbtSerde.fromSnbt("{id:'minecraft:diamond',count:1}"));}
                manifest.beBefore=mb.toString();manifest.beAfter=ma.toString();
                database.appendBatch("linked-"+kind+"-"+mode,List.of(cargo,manifest));
                var insert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_compat")&&body.contains(construction.toString())).findFirst().orElseThrow();
                String payload=insert.substring(insert.indexOf('\n')+1);lookupResponse.set(payload);
                var linkedQuery=new LogQuery();linkedQuery.dim="*";linkedQuery.requireDetails=true;linkedQuery.sourcePrefix=kind.prefix()+construction+":";var read=database.queryReverse(linkedQuery);
                check(read.size()==2,"HTTP omitted linked endpoint");
                for(var entry:read){var recorded=NbtSerde.fromSnbt(entry.beAfter);check(recorded.getUUID("Transfer").equals(transfer),"HTTP lost linked transfer ID");
                    if(entry.type==ActionType.CART_TRANSFER){check(entry.beBefore.equals(manifest.beBefore)&&entry.beAfter.equals(manifest.beAfter),"HTTP discarded direct/depot snapshots");
                        check(CartLogDetails.describe(null,entry).stream().anyMatch(line->line.getString().contains(depot?"Слот игрока: 0":base?"Хранилище:":"физический слот 0")),"HTTP details lost addressed recipient");}
                    else{check(entry.beBefore.equals(cargo.beBefore)&&entry.beAfter.equals(cargo.beAfter),"HTTP discarded linked cargo");if(base)check(recorded.getBoolean("BaseCart")&&CartLogDetails.describe(null,entry).stream().anyMatch(line->line.getString().contains("Груз конструкции: вагонетка")),"HTTP lost base cart identity");}
                }
            }
            System.out.println("Linked Container/depot/base cart HTTP checks passed: paired snapshots, both base transfer directions, direct addresses and player slots persist with optional details disabled.");
            for(var kind:CreateContraptionKind.values())if(kind!=CreateContraptionKind.UNKNOWN){
                UUID construction=UUID.randomUUID(),transfer=UUID.randomUUID(),drop=UUID.randomUUID();
                var manifest=row("world funnel");manifest.type=ActionType.CART_TRANSFER;manifest.dim="minecraft:overworld";manifest.source=kind.source(construction,"automation_transfer");
                var worldBefore=NbtSerde.fromSnbt("{Format:1,Kind:'automation',Complete:1b,Mounted:1b,Changes:1,Machine:'create:andesite_funnel',MachineDim:'minecraft:overworld',MachinePos:7L,Slots:[],Entities:[{Dimension:'minecraft:overworld',Pos:7L,EntityType:'minecraft:item',Item:{id:'minecraft:diamond',count:5,components:{'minecraft:custom_name':'\"World source\"'}},EntityNBT:{id:'minecraft:item',Item:{id:'minecraft:diamond',count:5},Pos:[1d,2d,3d]}}]}");
                worldBefore.putUUID("Transfer",transfer);var endpoint=worldBefore.getList("Entities",10).getCompound(0);endpoint.putUUID("WorldEntity",drop);endpoint.getCompound("EntityNBT").putUUID("UUID",drop);
                var worldAfter=worldBefore.copy();worldAfter.getList("Entities",10).getCompound(0).getCompound("Item").putInt("count",2);manifest.beBefore=worldBefore.toString();manifest.beAfter=worldAfter.toString();
                database.appendBatch("world-funnel-"+kind,List.of(manifest));
                var insert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_compat")&&body.contains(construction.toString())).findFirst().orElseThrow();lookupResponse.set(insert.substring(insert.indexOf('\n')+1));
                var worldQuery=new LogQuery();worldQuery.dim="*";worldQuery.requireDetails=true;worldQuery.sourcePrefix=kind.prefix()+construction+":";
                var read=database.queryReverse(worldQuery).getFirst();check(manifest.beBefore.equals(read.beBefore)&&manifest.beAfter.equals(read.beAfter),"HTTP discarded sparse world item endpoints");
                var details=CartLogDetails.describe(null,read);check(details.stream().anyMatch(line->line.getString().contains(drop.toString()))&&details.stream().anyMatch(line->line.getString().contains("minecraft:diamond · 5")&&line.getString().contains("minecraft:diamond · 2")),"HTTP world endpoint identity/components altered");
                for(var type:List.of(ActionType.ENTITY_SPAWN,ActionType.ENTITY_DEATH)){
                    var effect=row("delayed drop "+type);effect.type=type;effect.dim=manifest.dim;effect.source=kind.source(construction,"delayed_drop");effect.entityUuid=drop;effect.entityType="minecraft:item";
                    var nbt=endpoint.getCompound("EntityNBT").copy();var origin=new net.minecraft.nbt.CompoundTag();origin.putString("Source",effect.source);origin.putUUID("Owner",UUID.randomUUID());origin.putString("OwnerName","DelayedOwner");var forge=new net.minecraft.nbt.CompoundTag();forge.put("AvilixCreateOrigin",origin);nbt.put("ForgeData",forge);effect.entityNbt=nbt.toString();
                    database.appendBatch("delayed-drop-"+kind+type,List.of(effect));
                    var body=requests.values().stream().filter(value->value.startsWith("INSERT")&&value.substring(0,value.indexOf('\n')).contains("_entities")&&value.contains(effect.extra)&&value.contains(construction.toString())).findFirst().orElseThrow();lookupResponse.set(body.substring(body.indexOf('\n')+1));worldQuery.type=type;
                    var loaded=database.queryReverse(worldQuery).getFirst();check(effect.entityNbt.equals(loaded.entityNbt)&&effect.source.equals(loaded.source),"HTTP lost delayed item origin/removal snapshot");
                }
            }
            {
                var structural=row("native controller disassembly");structural.type=ActionType.CART_DISASSEMBLE;structural.dim="minecraft:overworld";
                var structuralBefore=CreateStructuralPlanTest.manifest(false);var structuralAfter=CreateStructuralPlanTest.manifest(true);var construction=structuralBefore.getList("Bodies",10).getCompound(0).getUUID("Construction");
                structural.source=CreateContraptionKind.BEARING.source(construction,"structural_disassemble");structural.beBefore=structuralBefore.toString();structural.beAfter=structuralAfter.toString();
                database.appendBatch("structural-disassembly",List.of(structural));
                var payload=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_compat")&&body.contains(structural.source)).findFirst().orElseThrow();lookupResponse.set(payload.substring(payload.indexOf('\n')+1));
                var structuralQuery=new LogQuery();structuralQuery.dim="*";structuralQuery.constructionId=construction;structuralQuery.type=structural.type;structuralQuery.requireDetails=true;var read=database.queryReverse(structuralQuery).getFirst();
                check(read.beBefore.equals(structural.beBefore)&&read.beAfter.equals(structural.beAfter),"HTTP lost structural world/controller/effect snapshots");
                check(com.roften.avilixlogger.compat.create.CreateStructuralRollbackPlan.prepare(NbtSerde.fromSnbt(read.beBefore),NbtSerde.fromSnbt(read.beAfter)).effects().size()==1,"HTTP structural scene cannot be prepared");
                check(CartLogDetails.describe(null,read).stream().anyMatch(line->line.getString().contains("Running:1b")&&line.getString().contains("Running:0b")),"HTTP structural controller difference missing");
                System.out.println("CREATE_STRUCTURAL_HTTP_OK: full payload/world/controller/effects survive detail lookup and remain recoverable");
            }
            for(var kind:List.of(CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER)){
                UUID construction=UUID.randomUUID();
                var linked=row("paired cargo "+kind);linked.type=ActionType.CART_TRANSFER;linked.dim="minecraft:overworld";linked.source=kind.source(construction,"automation_transfer");
                var linkedBefore=CreateLinkedTransferPlanTest.manifest(kind,false);var linkedAfter=CreateLinkedTransferPlanTest.manifest(kind,true);linked.beBefore=linkedBefore.toString();linked.beAfter=linkedAfter.toString();
                database.appendBatch("paired-cargo-"+kind,List.of(linked));
                var payload=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_compat")&&body.contains(linked.source)).findFirst().orElseThrow();lookupResponse.set(payload.substring(payload.indexOf('\n')+1));
                var queryLinked=new LogQuery();queryLinked.dim="*";queryLinked.constructionId=construction;queryLinked.type=linked.type;queryLinked.requireDetails=true;
                check(queryLinked.copy().constructionId.equals(construction),"logical ID lost when copying query");
                var linkedRead=database.queryReverse(queryLinked).getFirst();
                check(linkedRead.beBefore.equals(linked.beBefore)&&linkedRead.beAfter.equals(linked.beAfter),"HTTP lost complete peer snapshots");
                check(com.roften.avilixlogger.compat.create.CreateTransferRollbackPlan.prepare(NbtSerde.fromSnbt(linkedRead.beBefore),NbtSerde.fromSnbt(linkedRead.beAfter)).cargo().size()==2,"HTTP payload is not jointly recoverable");
                var request=selects.getLast();for(var candidate:CreateContraptionKind.values())if(candidate!=CreateContraptionKind.UNKNOWN)check(request.contains(candidate.prefix()+construction+":"),"logical query dropped a construction kind");
                for(var type:List.of(ActionType.CART_ASSEMBLE,ActionType.CART_LOAD,ActionType.CART_DISASSEMBLE,ActionType.CART_UNLOAD,ActionType.CART_REMOVE)){
                    var life=row("lifecycle "+kind+type);life.type=type;life.dim="minecraft:overworld";life.source=kind.source(construction,"lifecycle");
                    var snapshot=NbtSerde.fromSnbt("{Format:2,Kind:'lifecycle',Form:'entity',Links:[],Entity:{Contraption:{Type:'create:bearing'}}}");snapshot.putUUID("Construction",construction);snapshot.putString("ConstructionKind",kind.name());snapshot.putUUID("StorageEntity",UUID.randomUUID());life.beAfter=snapshot.toString();
                    database.appendBatch("life-"+kind+type,List.of(life));
                    var insert=requests.values().stream().filter(body->body.startsWith("INSERT")&&body.substring(0,body.indexOf('\n')).contains("_compat")&&body.contains(life.extra)).findFirst().orElseThrow();lookupResponse.set(insert.substring(insert.indexOf('\n')+1));queryLinked.type=type;
                    var read=database.queryReverse(queryLinked).getFirst();check(life.beAfter.equals(read.beAfter),"HTTP discarded lifecycle full snapshot "+type);
                    check(CartLogDetails.describe(null,read).stream().anyMatch(c->c.getString().startsWith("Форма конструкции:")),"HTTP lifecycle details lost");
                }
            }
            System.out.println("CREATE_LINKED_LIFECYCLE_HTTP_OK: full peer plans and lifecycle snapshots survive disabled optional details; required all-kind ID queries");
            System.out.println("Create world endpoint HTTP checks passed: item UUID/components, before/after absorption and persistent delayed drop spawn/removal for four kinds.");
            lookupResponse.set(null);selects.clear();
            var search=new LogQuery();search.dim="*";search.sinceTs=1760000000000L;search.untilTs=1790000000000L;
            search.textFilter="O'Reilly needle";search.trainNameFilter="Express";search.owner="Alice";search.blockIdFilter="stone";search.planeOnly=true;search.planeNameFilter="aircraft";
            database.queryReverse(search);
            check(!selects.isEmpty()&&selects.stream().allMatch(sql->sql.contains("ts_ms >= 1760000000000")&&sql.contains("ts_ms <= 1790000000000")&&sql.contains("positionCaseInsensitiveUTF8")&&sql.contains("express")&&sql.contains("alice")&&sql.contains("stone")&&sql.contains("needle")&&sql.contains("action IN (")),"GUI/feed/split query dropped a date/search/category criterion");
            check(selects.getFirst().contains("target_id")&&!selects.getFirst().contains("entity_nbt"),"ordinary GUI search fetched heavy snapshots");
            search.textFilter="сломал";selects.clear();database.queryReverse(search);
            check(selects.stream().allMatch(sql->sql.contains("OR action = "+ActionType.BLOCK_BREAK.ordinal())),"display action text search was not pushed into SQL");
            search.textFilter="Незерак";search.textAliases=List.of("minecraft:netherrack");search.blockIdFilter="Незерак";search.blockAliases=search.textAliases;selects.clear();database.queryReverse(search);
            check(selects.stream().allMatch(sql->sql.contains("minecraft:netherrack")&&sql.contains("multiSearchAnyCaseInsensitiveUTF8")&&sql.indexOf("minecraft:netherrack")<sql.lastIndexOf("LIMIT")),"localized aliases not applied before LIMIT on every query path");
            search.textFilter="сломал";search.textAliases=List.of();search.blockIdFilter="stone";search.blockAliases=List.of();
            var cartGui=com.roften.avilixlogger.net.CartHistoryQuery.build(new com.roften.avilixlogger.net.C2SCartHistoryPayload(1,autoCart.toString(),99,"MaxKuz",true));selects.clear();database.queryReverse(cartGui);
            check(!selects.isEmpty()&&selects.stream().allMatch(sql->sql.contains("maxkuz")&&sql.contains(autoCart.toString())&&sql.contains("id < 99")&&sql.indexOf("maxkuz")<sql.lastIndexOf("LIMIT")),"cart GUI actor/ID/cursor not applied in SQL before LIMIT");
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
