package com.roften.avilixlogger.core;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.roften.avilixlogger.LoggerConfig;
import net.neoforged.fml.config.IConfigSpec;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Runs against an isolated, real ClickHouse instance; no AuthCore bypass or Minecraft world. */
public final class ClickHouseNativeSmoke {
    private ClickHouseNativeSmoke() {}
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        String endpoint = System.getenv("AVILIX_CI_CLICKHOUSE_URL");
        if (endpoint == null || endpoint.isBlank())
            throw new IllegalStateException("Real ClickHouse URL required; mock HTTP is not an acceptance test");
        String user = System.getenv().getOrDefault("AVILIX_CI_CLICKHOUSE_USER", "avilix_ci");
        String password = System.getenv().getOrDefault("AVILIX_CI_CLICKHOUSE_PASSWORD", "");
        var config = CommentedConfig.inMemory();
        LoggerConfig.SPEC.correct(config);
        config.set(List.of("clickhouse", "url"), endpoint);
        config.set(List.of("clickhouse", "user"), user);
        config.set(List.of("clickhouse", "password"), password);
        config.set(List.of("clickhouse", "database"), "avilixlogger_ci");
        config.set(List.of("clickhouse", "schemaMode"), "split");
        config.set(List.of("clickhouse", "storeRollbackDetails"), true);
        config.set(List.of("clickhouse", "storeNonRollbackDetails"), false);
        config.set(List.of("clickhouse", "detailMode"), "minimal");
        var ctor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                .getDeclaredConstructor(CommentedConfig.class, Path.class, net.neoforged.fml.config.ModConfig.class);
        ctor.setAccessible(true);
        LoggerConfig.SPEC.acceptConfig((IConfigSpec.ILoadedConfig)ctor.newInstance(config, null, null));
        String prefix = "avilix_native_ci:" + UUID.randomUUID() + ":";
        var block = AsyncPipelineTest.row("native-ClickHouse-block");
        block.dim = "minecraft:overworld";
        block.type = ActionType.BLOCK_PLACE;
        block.source = prefix + "block";
        block.x = 12345;
        block.y = 80;
        block.z = -12345;
        block.blockBefore = "minecraft:air";
        block.blockAfter = "minecraft:stone";
        block.beBefore = "{CI:0}";
        block.beAfter = "{CI:1}";
        var item = AsyncPipelineTest.row("native-ClickHouse-item");
        item.dim = block.dim;
        item.type = ActionType.CONTAINER_PUT;
        item.source = prefix + "item";
        item.x = block.x;
        item.y = block.y;
        item.z = block.z;
        item.itemStackNbt = "{id:\"minecraft:diamond\",count:3}";
        item.count = 3;
        var weapons=new java.util.ArrayList<LogEntry>();
        String weaponPrefix="mod:scguns:native:"+UUID.randomUUID()+":";
        for(var type:List.of(ActionType.PROJECTILE_SHOOT,ActionType.PROJECTILE_HIT,ActionType.WEAPON_KILL,ActionType.WEAPON_RELOAD,ActionType.PLAYER_DEATH)){
            var weapon=AsyncPipelineTest.row("native-weapon-"+type);weapon.type=type;weapon.dim=block.dim;weapon.source=weaponPrefix+type;
            weapon.actorUuid=UUID.randomUUID();weapon.actorName="NativeWeaponActor";
            weapon.beAfter="{Kind:'WeaponCombat',Version:1,Provider:'scguns',Phase:'test',ActorPose:{Dimension:'minecraft:overworld',X:-2.125d,Y:70.25d,Z:9.875d,Yaw:29.25f,Pitch:-23.5f,EyeY:71.87d}}";
            weapons.add(weapon);
        }
        var mechanism=new net.minecraft.nbt.CompoundTag();mechanism.putString("Kind","turret");mechanism.putString("Block","scguns:shotgun_turret");mechanism.putString("Dimension",block.dim);
        mechanism.putInt("X",-3);mechanism.putInt("Y",70);mechanism.putInt("Z",9);mechanism.putUUID("Owner",UUID.randomUUID());mechanism.putString("OwnerName","NativeTurretOwner");
        var turretWeapon=new net.minecraft.nbt.CompoundTag();turretWeapon.putString("id","scguns:shotgun_turret");turretWeapon.putInt("count",1);
        var turretShot=new WeaponCombatTrace.Shot(UUID.randomUUID(),System.currentTimeMillis(),null,"Турель",null,"scguns:shotgun_turret","scguns:shotgun_turret",
                new WeaponCombatTrace.Pose(block.dim,-2.125,70.25,9.875,29.25f,-23.5f,70.25),turretWeapon,mechanism);
        var turret=WeaponCombatAudit.entry(turretShot,ActionType.WEAPON_KILL,"kill",null,null);turret.source=weaponPrefix+"turret";turret.materializeSnapshots();weapons.add(turret);
        var setting=com.roften.avilixlogger.compat.blockpack.BlockPackGiftAudit.capture(new com.roften.avilixlogger.compat.blockpack.BlockPackGiftAudit.Actor(UUID.randomUUID(),"NativeGiftActor",block.dim,7,80,9),true,false);setting.materializeSnapshots();
        var interaction=AsyncPipelineTest.row("native-block-interaction");interaction.type=ActionType.BLOCK_INTERACT;interaction.dim=block.dim;interaction.source="player:native_wrench_setting";
        interaction.actorUuid=UUID.randomUUID();interaction.actorName="NativeStateActor";interaction.x=-37;interaction.y=103;interaction.z=61;
        interaction.blockBefore="{Name:'minecraft:oak_trapdoor',Properties:{open:'false'}}";interaction.blockAfter="{Name:'minecraft:oak_trapdoor',Properties:{open:'true'}}";

        var buyer=new ItemTransactionAudit.Party(UUID.randomUUID(),"NativeReceiptBuyer","minecraft:player",block.dim,-3,80,4);
        var seller=new ItemTransactionAudit.Party(UUID.randomUUID(),"NativeReceiptSeller","bf_blockpack:wandering_trader",block.dim,19,75,-22);
        var receipts=List.of(ItemTransactionAudit.gift(buyer,ItemTransactionAuditTest.stack("bf_blockpack:guide_book",1),1,null,true),ItemTransactionAudit.trade(buyer,seller,ItemTransactionAuditTest.stack("minecraft:emerald",3),ItemTransactionAuditTest.stack("minecraft:diamond",1),ItemTransactionAuditTest.stack("bf_blockpack:workbench",2),2));for(var receipt:receipts)receipt.materializeSnapshots();
        var conversion=AsyncPipelineTest.row("native-conversion");conversion.type=ActionType.ITEM_TRANSFORM;conversion.dim=block.dim;conversion.source="mod:chipped:workbench";
        conversion.playerInvBefore="{Items:[{Slot:0,id:'minecraft:stone',count:2}]}";conversion.playerInvAfter="{Items:[{Slot:0,id:'chipped:carved_stone',count:2}]}";
        var textEdit=SupplementariesTextAuditTest.record();
        var display=SupplementariesAutomationAuditTest.record();
        var meTransfers=List.of(Ae2TransferDetailsTest.record("i",Long.MAX_VALUE), Ae2TransferDetailsTest.record("f",1234567890123L), Ae2TransferDetailsTest.record("g",918273645L), Ae2TransferDetailsTest.device(), Ae2TransferDetailsTest.remote());
        for(var transfer:meTransfers)transfer.materializeSnapshots();
        var meConfigurations=List.of(Ae2ConfigurationDetailsTest.record(ActionType.ME_FILTER_CHANGE), Ae2ConfigurationDetailsTest.record(ActionType.ME_UPGRADE_CHANGE), Ae2ConfigurationDetailsTest.record(ActionType.ME_CELL_CHANGE), Ae2ConfigurationDetailsTest.record(ActionType.ME_SETTING_CHANGE));
        for(var change:meConfigurations)change.materializeSnapshots();
        List<LogEntry> pressureRows=List.of();
        ClickHouseLogStorage db = null;
        try {
            db = new ClickHouseLogStorage();
            db.appendBatch("native-" + UUID.randomUUID(), List.of(block, item));
            db.shutdown();db=null;
            // Reproduce an existing split installation that predates weapon evidence columns.
            // This fixture touches only the explicitly named isolated CI database on loopback.
            seedOldWeaponSchema(endpoint,user,password);
            db = new ClickHouseLogStorage();
            db.appendBatch("native-weapons-"+UUID.randomUUID(),weapons);
            db.appendBatch("native-setting-"+UUID.randomUUID(),List.of(setting));verifySetting(db,setting);
            db.appendBatch("native-interaction-"+UUID.randomUUID(),List.of(interaction));verifyInteraction(db,interaction);
            verifyReceipts(db,receipts);verifyConversion(db,conversion);verifyTextEdit(db,textEdit);verifyDisplay(db,display);
            verifyWeapons(db,weapons); verifyMeTransfers(db,meTransfers); verifyMeConfigurations(db,meConfigurations);
            pressureRows=journalPressure(db);verifyJournalPressure(db,pressureRows);
            check(block.id > 0 && item.id > 0 && block.id != item.id, "IDs missing after insertion");

            var detail = new LogQuery();
            detail.dim = block.dim;
            detail.sourcePrefix = prefix;
            detail.sinceTs = block.ts - 1000;
            detail.untilTs = System.currentTimeMillis() + 10000;
            detail.limit = 100;
            detail.requireDetails = true;
            verifyDetails(db.queryReverse(detail), block, item);

            var feed = detail.copy();
            feed.requireDetails = false;
            check(db.queryReverse(feed).stream().anyMatch(e -> e.id == block.id),
                    "Native ClickHouse lightweight GUI feed cannot find acknowledged block");

            db.shutdown();
            db = new ClickHouseLogStorage();
            verifyDetails(db.queryReverse(detail), block, item);
            verifyWeapons(db,weapons);
            verifySetting(db,setting);
            verifyInteraction(db,interaction);
            verifyReceipts(db,receipts);verifyConversion(db,conversion);verifyTextEdit(db,textEdit);verifyDisplay(db,display);
            verifyJournalPressure(db,pressureRows); verifyMeTransfers(db,meTransfers); verifyMeConfigurations(db,meConfigurations);
            System.out.println("CLICKHOUSE_NATIVE_OK: old split schema migration, exact details, GUI feed, acknowledged insert, restart; player v1 and turret v2 shot/hit/kill/reload/player-death evidence survives detail pruning and exact ID lookup");
        } finally {
            if (db != null) db.shutdown();
            LoggerConfig.SPEC.acceptConfig(null);
        }
    }

    private static List<LogEntry> journalPressure(ClickHouseLogStorage database)throws Exception{
        var directory=java.nio.file.Files.createTempDirectory("avilix-real-journal-pressure");PendingLogStorage pending=null;Thread producer=null;
        var rows=new java.util.ArrayList<LogEntry>();for(int i=0;i<3;i++){var row=SupplementariesAutomationAuditTest.record();row.extra="native journal pressure "+i;rows.add(row);}
        try{
            pending=new PendingLogStorage(directory,2,1024*1024,1000,60_000);var target=pending;
            var task=new java.util.concurrent.FutureTask<Void>(()->{for(var row:rows)target.append(row);return null;});
            producer=new Thread(task,"real-clickhouse-pressure-producer");producer.setDaemon(true);producer.start();task.get(5,java.util.concurrent.TimeUnit.SECONDS);
            pending.shutdown();pending=null;
            // A final partial batch is durable even when the sender is stopped. Replay real files.
            pending=new PendingLogStorage(directory,2,1024*1024,1000,60_000);pending.setDelegate(database);
            check(pending.awaitVisible(System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15)),"real journal replay did not become visible");
            check(!new DiskLogJournal(directory).hasPending(),"real acknowledgment did not remove journal files");return List.copyOf(rows);
        }finally{
            if(pending!=null)pending.shutdown();if(producer!=null)producer.join(5000);
            try(var paths=java.nio.file.Files.walk(directory)){for(var file:paths.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(file);}
        }
    }
    private static void verifyJournalPressure(ClickHouseLogStorage db,List<LogEntry> rows){
        check(rows.size()==3,"real pressure fixture did not submit all rows");
        for(var expected:rows){var q=new LogQuery();q.type=expected.type;q.exactId=expected.id;q.untilTs=Long.MAX_VALUE;q.requireDetails=true;q.limit=2;var read=db.query(q);
            check(read.size()==1&&read.getFirst().id==expected.id&&expected.beBefore.equals(read.getFirst().beBefore)&&expected.beAfter.equals(read.getFirst().beAfter)&&expected.extra.equals(read.getFirst().extra)&&read.getFirst().actorUuid==null&&expected.actorName.equals(read.getFirst().actorName)&&expected.source.equals(read.getFirst().source)&&expected.dim.equals(read.getFirst().dim)&&expected.x==read.getFirst().x&&expected.y==read.getFirst().y&&expected.z==read.getFirst().z&&expected.blockBefore.equals(read.getFirst().blockBefore)&&expected.blockAfter.equals(read.getFirst().blockAfter),"real journal pressure/replay lost or duplicated payload/attribution by exact ID");
            q.requireDetails=false;check(db.query(q).size()==1,"real journal replay duplicated/lost exact ID in GUI feed");}
        System.out.println("JOURNAL_PRESSURE_CLICKHOUSE_OK: saturated partial batch, durable shutdown/restart replay, acknowledged visibility, exact IDs/full payload/actor survive real ClickHouse and database reopen");
    }
    private static void verifyMeTransfers(ClickHouseLogStorage db,List<LogEntry> rows) throws Exception {
        if(rows.getFirst().id==0)db.appendBatch("native-me-"+UUID.randomUUID(),rows);
        for(var expected:rows){
            var q=new LogQuery();q.type=expected.type;q.exactId=expected.id;q.untilTs=Long.MAX_VALUE;q.requireDetails=true;q.limit=2;
            var details=db.query(q);check(details.size()==1,"ME transfer missing/duplicated by exact ID");var read=details.getFirst();
            check(expected.beAfter.equals(read.beAfter)&&java.util.Objects.equals(expected.itemStackNbt,read.itemStackNbt)&&expected.extra.equals(read.extra)
                    &&Ae2TransferDetails.context(read)!=null&&Ae2TransferDetails.summary(read).amount()==Ae2TransferDetails.summary(expected).amount()
                    &&java.util.Objects.equals(expected.actorUuid,read.actorUuid)&&java.util.Objects.equals(expected.actorName,read.actorName)
                    &&expected.source.equals(read.source)&&expected.dim.equals(read.dim)&&expected.x==read.x&&expected.y==read.y&&expected.z==read.z,"ME full resource key/long/source/address lost in minimal details");
            q.requireDetails=false;var feed=db.query(q);check(feed.size()==1&&Ae2TransferDetails.summary(feed.getFirst())!=null
                    &&Ae2TransferDetails.summary(feed.getFirst()).equals(Ae2TransferDetails.summary(expected)),"ME feed lost exact type/resource/long amount");
            check(Ae2TransferDetails.summary(feed.getFirst()).amountLabel().contains(Long.toString(Ae2TransferDetails.summary(expected).amount())),"ME feed rendered truncated amount");
        }
        System.out.println("AE2_TRANSFER_CLICKHOUSE_OK: minimal-mode exact full key/components, long amount, typed feed, source/address and ID survive database reopen");
        check(rows.stream().anyMatch(r -> Ae2TransferDetails.context(r).getString("Domain").equals("device-slot")), "physical slot fixture missing");
        System.out.println("AE2_DEVICE_CLICKHOUSE_OK: physical slot components/domain/count, exact ID, source/address and feed survive minimal mode and reopened connection");
        check(rows.stream().anyMatch(r -> r.source.startsWith("ae2:PatternAccessTermMenu:terminal=")), "remote fixture missing");
        System.out.println("AE2_REMOTE_CLICKHOUSE_OK: full pattern/components/delta, actor, provider coordinates, original terminal/source/slot and exact ID/feed survive minimal mode and reopened connection");
    }
    private static void verifyMeConfigurations(ClickHouseLogStorage db,List<LogEntry> rows)throws Exception{
        if(rows.getFirst().id==0)db.appendBatch("native-me-config-"+UUID.randomUUID(),rows);
        for(var expected:rows){
            var q=new LogQuery();q.type=expected.type;q.exactId=expected.id;q.untilTs=Long.MAX_VALUE;q.requireDetails=true;q.limit=2;
            var detail=db.query(q);check(detail.size()==1,"ME configuration missing/duplicated exact ID");var read=detail.getFirst();
            check(expected.beBefore.equals(read.beBefore)&&expected.beAfter.equals(read.beAfter)&&java.util.Objects.equals(expected.itemStackNbt,read.itemStackNbt)
                    &&expected.extra.equals(read.extra)&&Ae2ConfigurationDetails.context(read)!=null&&expected.actorUuid.equals(read.actorUuid)&&expected.actorName.equals(read.actorName)
                    &&expected.dim.equals(read.dim)&&expected.source.equals(read.source)&&expected.x==read.x&&expected.y==read.y&&expected.z==read.z,"ME configuration before/after/components/metadata lost in minimal details");
            q.requireDetails=false;var feed=db.query(q);check(feed.size()==1&&expected.extra.equals(feed.getFirst().extra)&&Ae2ConfigurationDetails.label(feed.getFirst()).equals(Ae2ConfigurationDetails.label(expected)),"ME configuration feed label lost");
        }
        System.out.println("AE2_CONFIGURATION_CLICKHOUSE_OK: minimal mode retains both full filter/upgrade/cell/setting states, components, exact long, actor/address, feed/ID and reopened connection");
    }
    private static void seedOldWeaponSchema(String endpoint,String user,String password)throws Exception{
        var url=java.net.URI.create(endpoint).toURL();
        if(!java.util.Set.of("127.0.0.1","localhost","[::1]").contains(url.getHost()))
            throw new IllegalArgumentException("Schema migration fixture requires isolated loopback ClickHouse");
        for(String table:List.of("entities","items","players")){
            var connection=(java.net.HttpURLConnection)url.openConnection(java.net.Proxy.NO_PROXY);
            connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Authorization","Basic "+java.util.Base64.getEncoder().encodeToString((user+":"+password).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            try{
                try(var output=connection.getOutputStream()){
                    output.write(("ALTER TABLE avilixlogger_ci.avilixlogger_"+table+" DROP COLUMN IF EXISTS be_after").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                check(connection.getResponseCode()==200,"Cannot prepare old "+table+" schema");
                try(var input=connection.getInputStream()){input.readAllBytes();}
            }finally{connection.disconnect();}
        }
    }

    private static void verifyWeapons(ClickHouseLogStorage db,List<LogEntry> weapons){
        for(var weapon:weapons){
            var q=new LogQuery();q.requireDetails=true;q.sourcePrefix="mod:scguns:";q.exactId=weapon.id;q.untilTs=Long.MAX_VALUE;q.limit=1;
            var rows=db.query(q);check(rows.size()==1 && rows.getFirst().id==weapon.id && weapon.beAfter.equals(rows.getFirst().beAfter),"native weapon evidence lost for "+weapon.type);
            var pose=WeaponCombatTrace.pose(rows.getFirst(),"source");check(pose!=null && pose.x()==-2.125 && pose.yaw()==29.25f && pose.pitch()==-23.5f,"native precision/orientation lost");
            if(weapon.actorUuid==null){
                var shot=WeaponCombatTrace.Shot.read(WeaponCombatTrace.context(rows.getFirst()).getCompound("Shot"));
                check(rows.getFirst().actorUuid==null&&shot!=null&&shot.fromMechanism()&&shot.actor()==null&&shot.mechanism().getString("OwnerName").equals("NativeTurretOwner"),"native turret owner/actor semantics changed");
            }
        }
    }

    private static void verifyTextEdit(ClickHouseLogStorage db,LogEntry text)throws Exception{
        if(text.id==0)db.appendBatch("native-supplementaries-text-"+UUID.randomUUID(),List.of(text));
        var q=new LogQuery();q.type=text.type;q.requireDetails=true;q.exactId=text.id;q.untilTs=Long.MAX_VALUE;q.limit=1;var rows=db.query(q);
        check(rows.size()==1,"native text holder edit missing by exact ID");var row=rows.getFirst();check(text.actorUuid.equals(row.actorUuid)&&text.actorName.equals(row.actorName)&&row.x==-37&&row.y==103&&row.z==61&&text.beBefore.equals(row.beBefore)&&text.beAfter.equals(row.beAfter),"native text holder edit lost author, position or full before/after");
        q.requireDetails=false;check(db.query(q).stream().anyMatch(e->e.id==text.id),"native text holder edit missing from feed");
        System.out.println("SUPPLEMENTARIES_TEXT_CLICKHOUSE_OK: exact author, block position, both full holder states and ID/feed survive minimal mode and reopened connection");
    }
    private static void verifyDisplay(ClickHouseLogStorage db,LogEntry display)throws Exception{
        if(display.id==0)db.appendBatch("native-supplementaries-display-"+UUID.randomUUID(),List.of(display));
        var q=new LogQuery();q.type=display.type;q.requireDetails=true;q.exactId=display.id;q.untilTs=Long.MAX_VALUE;q.limit=1;var rows=db.query(q);
        check(rows.size()==1,"native display missing by exact ID");var row=rows.getFirst();
        check(row.actorUuid==null&&display.actorName.equals(row.actorName)&&row.x==64&&row.y==104&&row.z==45&&display.source.equals(row.source)&&display.extra.equals(row.extra)&&display.beBefore.equals(row.beBefore)&&display.beAfter.equals(row.beAfter),"native display lost system actor, target/mechanism, completion or book data");
        q.requireDetails=false;check(db.query(q).stream().anyMatch(e->e.id==display.id),"native display missing from feed");
        System.out.println("SUPPLEMENTARIES_DISPLAY_CLICKHOUSE_OK: system actor, exact target/mechanism, failed completion flag and complete raw/filtered book components survive minimal mode, ID/feed and reopen");
    }
    private static void verifyConversion(ClickHouseLogStorage db,LogEntry conversion)throws Exception{
        if(conversion.id==0)db.appendBatch("native-conversion-"+UUID.randomUUID(),List.of(conversion));
        var q=new LogQuery();q.type=ActionType.ITEM_TRANSFORM;q.requireDetails=true;q.exactId=conversion.id;q.untilTs=Long.MAX_VALUE;q.limit=1;var rows=db.query(q);
        check(rows.size()==1&&conversion.playerInvBefore.equals(rows.getFirst().playerInvBefore)&&conversion.playerInvAfter.equals(rows.getFirst().playerInvAfter),"native conversion inventory receipt lost before/after");
        q.requireDetails=false;check(db.query(q).stream().anyMatch(e->e.id==conversion.id),"native conversion missing from feed");
        System.out.println("ITEM_CONVERSION_CLICKHOUSE_OK: exact inventory values and ID/feed survive minimal mode and reopened connection");
    }
    private static void verifyReceipts(ClickHouseLogStorage db,List<LogEntry> receipts)throws Exception{
        if(receipts.getFirst().id==0)db.appendBatch("native-item-receipts-"+UUID.randomUUID(),receipts);
        for(var receipt:receipts){var q=new LogQuery();q.type=receipt.type;q.requireDetails=true;q.exactId=receipt.id;q.untilTs=Long.MAX_VALUE;q.limit=1;var rows=db.query(q);check(rows.size()==1,"native receipt missing by exact ID");var row=rows.getFirst();
            check(java.util.Objects.equals(row.actorUuid,receipt.actorUuid)&&row.count==receipt.count&&row.beAfter.equals(receipt.beAfter)&&row.itemStackNbt.equals(receipt.itemStackNbt)&&row.x==receipt.x&&row.z==receipt.z&&!ItemTransactionAudit.describe(row).isEmpty(),"native receipt lost actor/recipient, costs/delivery, position or details");q.requireDetails=false;check(db.query(q).stream().anyMatch(e->e.id==receipt.id),"native receipt missing in item feed");}
        System.out.println("ITEM_RECEIPT_CLICKHOUSE_OK: gift/trade metadata, quantities, exact target, actor, costs, feed and details survive minimal mode and reopened connection");
    }
    private static void verifyInteraction(ClickHouseLogStorage db,LogEntry interaction){
        var q=new LogQuery();q.requireDetails=true;q.exactId=interaction.id;q.limit=1;q.untilTs=Long.MAX_VALUE;
        var rows=db.query(q);check(rows.size()==1,"native restorable interaction missing by exact ID");var row=rows.getFirst();
        check(interaction.actorUuid.equals(row.actorUuid)&&row.x==-37&&row.y==103&&row.z==61&&interaction.blockBefore.equals(row.blockBefore)&&interaction.blockAfter.equals(row.blockAfter),"native interaction lost actor, position or state values");
        check(BlockChangeDetails.changes(row).stream().anyMatch(s->s.contains("open")),"native interaction property unavailable in details");
        q.requireDetails=false;check(db.query(q).stream().anyMatch(e->e.id==interaction.id),"native interaction absent from feed");
        System.out.println("BLOCK_INTERACTION_CLICKHOUSE_OK: minimal mode preserves restorable states, actor, coordinates, exact ID/feed and property details");
    }
    private static void verifySetting(ClickHouseLogStorage db,LogEntry setting){
        var q=new LogQuery();q.requireDetails=true;q.exactId=setting.id;q.limit=1;q.untilTs=Long.MAX_VALUE;
        var rows=db.query(q);check(rows.size()==1&&setting.actorUuid.equals(rows.getFirst().actorUuid)&&setting.beBefore.equals(rows.getFirst().beBefore)&&setting.beAfter.equals(rows.getFirst().beAfter),"native setting before/after or actor lost with optional details disabled");
        System.out.println("GUI_SETTING_CLICKHOUSE_OK: global gift setting and actor survive optional detail pruning and exact ID lookup");
    }

    private static void verifyDetails(List<LogEntry> rows, LogEntry block, LogEntry item) {
        var restored = rows.stream().filter(e -> e.id == block.id).findFirst().orElseThrow(
                () -> new AssertionError("Native ClickHouse did not return block ID after flush"));
        check(block.blockBefore.equals(restored.blockBefore) && block.blockAfter.equals(restored.blockAfter)
                        && block.beBefore.equals(restored.beBefore) && block.beAfter.equals(restored.beAfter),
                "Native ClickHouse dropped block/BE rollback payload");
        var inventory = rows.stream().filter(e -> e.id == item.id).findFirst().orElseThrow(
                () -> new AssertionError("Native ClickHouse did not return inventory event after flush"));
        check(item.itemStackNbt.equals(inventory.itemStackNbt) && item.count == inventory.count,
                "Native ClickHouse dropped item count/components");
    }
}
