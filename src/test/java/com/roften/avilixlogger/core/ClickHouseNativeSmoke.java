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
        var mechanism=new net.minecraft.nbt.CompoundTag();mechanism.putString("Kind","turret");mechanism.putString("Block","scguns:shotgun_turret");mechanism.putString("Dimension",block.dim);
        mechanism.putInt("X",-3);mechanism.putInt("Y",70);mechanism.putInt("Z",9);mechanism.putUUID("Owner",UUID.randomUUID());mechanism.putString("OwnerName","NativeTurretOwner");
        var turretWeapon=new net.minecraft.nbt.CompoundTag();turretWeapon.putString("id","scguns:shotgun_turret");turretWeapon.putInt("count",1);
        var turretShot=new WeaponCombatTrace.Shot(UUID.randomUUID(),System.currentTimeMillis(),null,"Турель",null,"scguns:shotgun_turret","scguns:shotgun_turret",
                new WeaponCombatTrace.Pose(block.dim,-2.125,70.25,9.875,29.25f,-23.5f,70.25),turretWeapon,mechanism);
        var turret=WeaponCombatAudit.entry(turretShot,ActionType.WEAPON_KILL,"kill",null,null);turret.source=weaponPrefix+"turret";turret.materializeSnapshots();weapons.add(turret);
        var setting=com.roften.avilixlogger.compat.blockpack.BlockPackGiftAudit.capture(new com.roften.avilixlogger.compat.blockpack.BlockPackGiftAudit.Actor(UUID.randomUUID(),"NativeGiftActor",block.dim,7,80,9),true,false);setting.materializeSnapshots();

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
            verifySetting(db,setting);
            System.out.println("CLICKHOUSE_NATIVE_OK: old split schema migration, exact details, GUI feed, acknowledged insert, restart; player v1 and turret v2 shot/hit/kill/reload/player-death evidence survives detail pruning and exact ID lookup");
        } finally {
            if (db != null) db.shutdown();
            LoggerConfig.SPEC.acceptConfig(null);
        }
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
