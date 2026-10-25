package com.roften.avilixlogger.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.roften.avilixlogger.auth.AuthCoreBridge;
import com.roften.avilixlogger.core.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import java.util.concurrent.*;
import static net.minecraft.commands.Commands.*;

/** Query in a bounded worker, validate the stored evidence and permissions again on the server. */
public final class CombatPositionCommand {
    private static final ThreadPoolExecutor QUERIES=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{
        var t=new Thread(r,"AvilixLogger-combat-position");t.setDaemon(true);return t;
    },new ThreadPoolExecutor.AbortPolicy());
    private CombatPositionCommand() {}
    private static boolean allowed(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer && AuthCoreBridge.isAuthorized(source.getServer())
                && PermissionUtil.hasAny(source,2,"avilixlogger.command.lookup","avilixlogger.command.admin","avilixlogger.gui");
    }
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        var id=argument("entryId",LongArgumentType.longArg(1,Long.MAX_VALUE-1));
        for(String side:new String[]{"source","view","target"})id.then(literal(side).executes(c->request(c.getSource(),LongArgumentType.getLong(c,"entryId"),side)));
        return literal("combatpos").requires(CombatPositionCommand::allowed).then(id);
    }
    private static int request(CommandSourceStack source,long id,String side) {
        if(!allowed(source))return 0;
        var player=(ServerPlayer)source.getEntity();var server=source.getServer();var uuid=player.getUUID();var connection=player.connection;
        var storage=LoggerRuntime.storage(player.serverLevel());var query=new LogQuery();query.requireDetails=true;
        query.exactId=id;query.limit=1;query.untilTs=Long.MAX_VALUE;query.sourcePrefix="mod:scguns:";query.debugSource="combat-position";
        try {QUERIES.execute(()->{
            WeaponCombatTrace.Pose pose=null;
            try {var rows=storage.query(query);if(rows.size()==1 && rows.getFirst().id==id)pose=WeaponCombatTrace.pose(rows.getFirst(),side);}
            catch(RuntimeException failure){WeaponCombatAudit.failure();}
            var captured=pose;
            server.execute(()->{
                var current=server.getPlayerList().getPlayer(uuid);
                if(current==null || current.connection!=connection || !allowed(current.createCommandSourceStack()))return;
                if(captured==null){current.sendSystemMessage(Component.literal("В записи нет подтверждённой позиции для этого перехода."));return;}
                var level=server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(captured.dim())));
                if(level==null){current.sendSystemMessage(Component.literal("Измерение из записи сейчас недоступно."));return;}
                var destination=side.equals("view")?captured.viewpoint(current.getEyeHeight()):captured;
                current.teleportTo(level,destination.x(),destination.y(),destination.z(),destination.yaw(),destination.pitch());
                current.sendSystemMessage(Component.literal("Позиция из записи #"+id+": "+destination.display()));
            });
        });}catch(RejectedExecutionException busy){source.sendFailure(Component.literal("Очередь переходов занята. Повторите запрос позже."));return 0;}
        return 1;
    }
    public static void stop(){QUERIES.getQueue().clear();}
}
