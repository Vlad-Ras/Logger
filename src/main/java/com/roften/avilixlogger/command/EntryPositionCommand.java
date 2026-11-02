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
import net.minecraft.world.level.block.Blocks;
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
    private static boolean allowed(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer && PermissionUtil.hasAny(source,2,"avilixlogger.command.lookup","avilixlogger.command.admin","avilixlogger.gui");
    }
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return literal("entrypos").requires(EntryPositionCommand::allowed)
            .then(argument("entryId",LongArgumentType.longArg(1,Long.MAX_VALUE-1))
                .executes(c->request(c.getSource(),LongArgumentType.getLong(c,"entryId"))));
    }
    private static int request(CommandSourceStack source,long id) {
        if(!allowed(source))return 0;
        var player=(ServerPlayer)source.getEntity();var server=source.getServer();var uuid=player.getUUID();var token=UUID.randomUUID();
        var connection=player.connection;REQUESTS.put(uuid,token);
        var old=LOADING.remove(uuid);if(old!=null)old.release();
        var storage=LoggerRuntime.storage(player.serverLevel());var query=new LogQuery();query.dim="*";query.sinceTs=0;
        query.untilTs=Long.MAX_VALUE;query.exactId=id;query.limit=1;query.requireDetails=true;query.debugSource="entry-position";
        try {QUERIES.execute(()->{
            EntryPositionEvidence.Position result=null;
            try {var rows=storage.query(query);if(rows!=null&&rows.size()==1&&rows.getFirst().id==id)result=EntryPositionEvidence.read(rows.getFirst());}
            catch(RuntimeException error){AdaptiveLogDiagnostics.adapterFailure("entry-position-query");}
            var row=result;server.execute(()->{
                if(!token.equals(REQUESTS.get(uuid)))return;
                var current=server.getPlayerList().getPlayer(uuid);
                if(current==null||current.connection!=connection||!allowed(source)){REQUESTS.remove(uuid,token);return;}
                if(row==null){REQUESTS.remove(uuid,token);source.sendFailure(Component.literal("Запись #"+id+" не найдена или не содержит подтверждённой позиции."));return;}
                var dimension=ResourceLocation.tryParse(row.dimension());
                var level=dimension==null?null:server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,dimension));
                if(level==null){REQUESTS.remove(uuid,token);source.sendFailure(Component.literal("Измерение записи недоступно."));return;}
                long x=row.x(),z=row.z();boolean chunk=row.chunk();
                if(x < -29_999_984L||x>29_999_984L||z < -29_999_984L||z>29_999_984L||!level.getWorldBorder().isWithinBounds(new BlockPos((int)x,0,(int)z))){
                    REQUESTS.remove(uuid,token);source.sendFailure(Component.literal("Позиция записи находится за границей мира."));return;
                }
                var pending=new Pending(source,current,level,id,(int)x,row.y(),(int)z,chunk,token);
                LOADING.put(uuid,pending);level.getChunkSource().addRegionTicket(TICKET,pending.chunk,1,token);
                source.sendSystemMessage(Component.literal("Подготавливаю переход к записи #"+id+"…"));pending.poll();
            });
        });} catch(RejectedExecutionException busy){REQUESTS.remove(uuid,token);source.sendFailure(Component.literal("Очередь переходов занята. Повторите позже."));return 0;}
        return 1;
    }
    private static final class Pending {
        private final CommandSourceStack source;private final ServerPlayer player;private final ServerLevel level;
        private final long id;private final int x,y,z;private final boolean surface;private final UUID token;private final ChunkPos chunk;private final int started;
        private Pending(CommandSourceStack source,ServerPlayer player,ServerLevel level,long id,int x,int y,int z,boolean surface,UUID token){
            this.source=source;this.player=player;this.level=level;this.id=id;this.x=x;this.y=y;this.z=z;this.surface=surface;this.token=token;
            chunk=new ChunkPos(x>>4,z>>4);started=level.getServer().getTickCount();
        }
        private void poll() {
            var uuid=player.getUUID();var server=level.getServer();
            if(!token.equals(REQUESTS.get(uuid))||server.getPlayerList().getPlayer(uuid)!=player||!allowed(source)){finish();return;}
            if(level.getChunkSource().getChunkNow(chunk.x,chunk.z)==null) {
                if(server.getTickCount()-started>=200){source.sendFailure(Component.literal("Чанк не подготовлен за 200 тиков; переход отменён."));finish();return;}
                ServerTickScheduler.schedule(server,1,this::poll);return;
            }
            try {
                int start=surface?level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z):y+1;
                BlockPos destination=null;
                for(int offset=0;offset<=32&&destination==null;offset++) {
                    int candidate=start-offset;
                    if(candidate<=level.getMinBuildHeight()||candidate+1>=level.getMaxBuildHeight())continue;
                    var feet=new BlockPos(x,candidate,z);var floor=feet.below();
                    var ground=level.getBlockState(floor);
                    if(ground.is(Blocks.MAGMA_BLOCK)||ground.is(Blocks.CACTUS)||ground.is(Blocks.CAMPFIRE)||ground.is(Blocks.SOUL_CAMPFIRE))continue;
                    if(!ground.isFaceSturdy(level,floor,Direction.UP))continue;
                    if(level.getBlockState(feet).getCollisionShape(level,feet).isEmpty()&&level.getBlockState(feet.above()).getCollisionShape(level,feet.above()).isEmpty()
                        &&level.getFluidState(feet).isEmpty()&&level.getFluidState(feet.above()).isEmpty()
                        &&!level.getBlockState(feet).is(Blocks.FIRE)&&!level.getBlockState(feet).is(Blocks.SOUL_FIRE)&&!level.getBlockState(feet).is(Blocks.POWDER_SNOW))destination=feet;
                }
                if(destination==null){source.sendFailure(Component.literal("В пределах 32 блоков не найдено безопасного места для перехода."));return;}
                player.teleportTo(level,x+0.5,destination.getY(),z+0.5,player.getYRot(),player.getXRot());
                source.sendSystemMessage(Component.literal("Запись #"+id+": "+level.dimension().location()+" · "+x+" "+destination.getY()+" "+z));
            } finally {finish();}
        }
        private void release(){level.getChunkSource().removeRegionTicket(TICKET,chunk,1,token);}
        private void finish(){release();REQUESTS.remove(player.getUUID(),token);LOADING.remove(player.getUUID(),this);}
    }
    public static void stop(){QUERIES.getQueue().clear();for(var pending:List.copyOf(LOADING.values()))pending.release();LOADING.clear();REQUESTS.clear();}
}
