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

        ClickHouseLogStorage db = null;
        try {
            db = new ClickHouseLogStorage();
            db.appendBatch("native-" + UUID.randomUUID(), List.of(block, item));
            db.appendBatch("native-weapons-"+UUID.randomUUID(),weapons);
            verifyWeapons(db,weapons);
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
            System.out.println("CLICKHOUSE_NATIVE_OK: schema, exact details, GUI feed, acknowledged insert, restart; weapon shot/hit/kill/reload/player-death poses survive detail pruning and exact ID lookup");
        } finally {
            if (db != null) db.shutdown();
            LoggerConfig.SPEC.acceptConfig(null);
        }
    }

    private static void verifyWeapons(ClickHouseLogStorage db,List<LogEntry> weapons){
        for(var weapon:weapons){
            var q=new LogQuery();q.requireDetails=true;q.sourcePrefix="mod:scguns:";q.exactId=weapon.id;q.untilTs=Long.MAX_VALUE;q.limit=1;
            var rows=db.query(q);check(rows.size()==1 && rows.getFirst().id==weapon.id && weapon.beAfter.equals(rows.getFirst().beAfter),"native weapon evidence lost for "+weapon.type);
            var pose=WeaponCombatTrace.pose(rows.getFirst(),"source");check(pose!=null && pose.x()==-2.125 && pose.yaw()==29.25f && pose.pitch()==-23.5f,"native precision/orientation lost");
        }
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
