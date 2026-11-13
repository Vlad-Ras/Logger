package com.roften.avilixlogger.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.roften.avilixlogger.core.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import java.util.*;
import java.util.concurrent.*;
import static net.minecraft.commands.Commands.*;

/** Entry ID is authoritative. Queries and chunk readiness never block the server thread. */
public final class EntryPositionCommand {
    private static final ThreadPoolExecutor QUERIES=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{
        var thread=new Thread(r,"AvilixLogger-entry-position");thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.AbortPolicy());
    private static final Map<UUID,UUID> REQUESTS=new HashMap<>();
    private static final Map<UUID,Pending> LOADING=new HashMap<>();
    private static final TicketType<UUID> TICKET=TicketType.create("avilixlogger_entry_position",Comparator.comparing(UUID::toString),220);
    private EntryPositionCommand() {}
    public record Feedback(com.roften.avilixlogger.net.S2CEntryTeleportPayload.State state,Component message) {}
    private static boolean allowed(CommandSourceStack source) {
        return com.roften.avilixlogger.auth.AuthCoreBridge.isAuthorized(source.getServer()) && source.getEntity() instanceof ServerPlayer && PermissionUtil.hasAny(source,2,"avilixlogger.command.lookup","avilixlogger.command.admin","avilixlogger.gui");
    }
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return literal("entrypos").requires(EntryPositionCommand::allowed)
            .then(argument("entryId",LongArgumentType.longArg(1,Long.MAX_VALUE-1))
                .executes(c->request(c.getSource(),LongArgumentType.getLong(c,"entryId"))));
    }
    private static int request(CommandSourceStack source,long id) {
        return request(source,id,result->{if(result.state()==com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.FAILURE)source.sendFailure(result.message());else source.sendSystemMessage(result.message());});
    }
    public static int request(CommandSourceStack source,long id,java.util.function.Consumer<Feedback> replies) {
        if(!allowed(source)||id<=0||id==Long.MAX_VALUE){replies.accept(new Feedback(com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.FAILURE,Component.literal("Нет прав на переход или неверный номер записи.")));return 0;}
        var player=(ServerPlayer)source.getEntity();var server=source.getServer();var uuid=player.getUUID();var token=UUID.randomUUID();
        var connection=player.connection;REQUESTS.put(uuid,token);
        var old=LOADING.remove(uuid);if(old!=null)old.release();
        var storage=LoggerRuntime.storage(player.serverLevel());
        try {QUERIES.execute(()->{
            EntryPositionEvidence.Position result=null;
            try {result=EntryPositionResolver.resolve(storage,id);}
            catch(RuntimeException error){AdaptiveLogDiagnostics.adapterFailure("entry-position-query");}
            var row=result;server.execute(()->{
                if(!token.equals(REQUESTS.get(uuid)))return;
                var current=server.getPlayerList().getPlayer(uuid);
                if(current==null||current.connection!=connection||!allowed(source)){REQUESTS.remove(uuid,token);return;}
                if(row==null){REQUESTS.remove(uuid,token);replies.accept(new Feedback(com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.FAILURE,Component.literal("Запись #"+id+" не найдена или не содержит подтверждённой позиции.")));return;}
                var dimension=ResourceLocation.tryParse(row.dimension());
                var level=dimension==null?null:server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,dimension));
                if(level==null){REQUESTS.remove(uuid,token);replies.accept(new Feedback(com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.FAILURE,Component.literal("Измерение записи недоступно.")));return;}
                long x=row.x(),z=row.z();boolean chunk=row.chunk();
                if(x < -29_999_984L||x>29_999_984L||z < -29_999_984L||z>29_999_984L){
                    REQUESTS.remove(uuid,token);replies.accept(new Feedback(com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.FAILURE,Component.literal("Позиция записи находится за границей мира.")));return;
                }
                var pending=new Pending(source,current,level,id,(int)x,row.y(),(int)z,chunk,token,replies);
                LOADING.put(uuid,pending);level.getChunkSource().addRegionTicket(TICKET,pending.chunk,1,token);
                replies.accept(new Feedback(com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.PREPARING,Component.literal("Подготавливаю переход к записи #"+id+"…")));pending.poll();
            });
        });} catch(RejectedExecutionException busy){REQUESTS.remove(uuid,token);replies.accept(new Feedback(com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.FAILURE,Component.literal("Очередь переходов занята. Повторите позже.")));return 0;}
        return 1;
    }
    private static final class Pending {
        private final CommandSourceStack source;private final ServerPlayer player;private final ServerLevel level;
        private final java.util.function.Consumer<Feedback> replies;
        private final long id;private final int x,y,z;private final boolean surface;private final UUID token;private final ChunkPos chunk;private final int started;
        private Pending(CommandSourceStack source,ServerPlayer player,ServerLevel level,long id,int x,int y,int z,boolean surface,UUID token,java.util.function.Consumer<Feedback> replies){
            this.replies=replies;
            this.source=source;this.player=player;this.level=level;this.id=id;this.x=x;this.y=y;this.z=z;this.surface=surface;this.token=token;
            chunk=new ChunkPos(x>>4,z>>4);started=level.getServer().getTickCount();
        }
        private void poll() {
            var uuid=player.getUUID();var server=level.getServer();
            if(!token.equals(REQUESTS.get(uuid))||server.getPlayerList().getPlayer(uuid)!=player||!allowed(source)){finish();return;}
            if(level.getChunkSource().getChunkNow(chunk.x,chunk.z)==null) {
                if(server.getTickCount()-started>=200){replies.accept(new Feedback(com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.FAILURE,Component.literal("Чанк не подготовлен за 200 тиков; переход отменён.")));finish();return;}
                ServerTickScheduler.schedule(server,1,this::poll);return;
            }
            try {
                int destinationY = surface ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z) : y;
                player.teleportTo(level,x+0.5,destinationY,z+0.5,player.getYRot(),player.getXRot());
                boolean moved=player.serverLevel()==level&&Math.abs(player.getX()-(x+0.5))<0.01
                        &&Math.abs(player.getY()-destinationY)<0.01&&Math.abs(player.getZ()-(z+0.5))<0.01;
                replies.accept(new Feedback(moved?com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.SUCCESS:com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.FAILURE,
                        Component.literal(moved?"Запись #"+id+": "+level.dimension().location()+" · "+x+" "+destinationY+" "+z:"Переход отменён: сервер не подтвердил новое положение игрока.")));
            } finally {finish();}
        }
        private void release(){level.getChunkSource().removeRegionTicket(TICKET,chunk,1,token);}
        private void finish(){release();REQUESTS.remove(player.getUUID(),token);LOADING.remove(player.getUUID(),this);}
    }
    public static void stop(){QUERIES.getQueue().clear();for(var pending:List.copyOf(LOADING.values()))pending.release();LOADING.clear();REQUESTS.clear();}
}
