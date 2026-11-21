package com.roften.avilixlogger.core;

import com.roften.avilixlogger.mixin.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Raw UUID lookup through vanilla's serialized I/O queue; never instantiates unrelated entities. */
public final class EntitySavedIndex {
    public interface Reader {CompletableFuture<Optional<CompoundTag>> read(ChunkPos pos);}
    public record Source(String dimension,Path folder,Reader reader) {}
    public record Location(String dimension,ChunkPos chunk,UUID root) {}
    public record Result(Map<UUID,List<Location>> found) {
        public List<Location> locations(UUID id){return found.getOrDefault(id,List.of());}
    }
    public static final class Progress {public final AtomicInteger scanned=new AtomicInteger(),total=new AtomicInteger();}
    private EntitySavedIndex() {}
    public static List<Source> sources(MinecraftServer server) {
        var result=new ArrayList<Source>();
        for(var level:server.getAllLevels()) {
            var manager=((RecoveryEntityLevelAccessor)level).avilixlogger$entityManager();
            var storage=((RecoveryEntityManagerAccessor<?>)manager).avilixlogger$entityStorage();
            if(!(storage instanceof RecoveryEntityStorageAccessor access))throw new IllegalStateException("Неподдерживаемое хранилище сущностей: "+storage.getClass().getName());
            var regions=access.avilixlogger$entityRegionStorage();
            result.add(new Source(level.dimension().location().toString(),DimensionType.getStorageFolder(level.dimension(),server.getWorldPath(LevelResource.ROOT)).resolve("entities"),regions::read));
        }
        return List.copyOf(result);
    }
    public static Result scan(List<Source> sources,Set<UUID> wanted,Progress progress,BooleanSupplier cancelled) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(2);
        var found=new HashMap<UUID,List<Location>>();
        for(var source:sources) {
            for(var chunk:chunks(source.folder())) {
                progress.total.incrementAndGet();check(cancelled,deadline);
                var tag=source.reader().read(chunk).get(Math.max(1,Math.min(30_000,TimeUnit.NANOSECONDS.toMillis(deadline-System.nanoTime()))),TimeUnit.MILLISECONDS);
                if(tag.isPresent()) {
                    var raw=tag.get().get("Entities");
                    if(!(raw instanceof ListTag entities)||!entities.isEmpty()&&entities.getElementType()!=Tag.TAG_COMPOUND)
                        throw new IOException("Не прочитан список сущностей: "+source.dimension()+" "+chunk);
                    for(var node:(ListTag)raw) {
                        var root=(CompoundTag)node;
                        if(!root.hasUUID("UUID"))throw new IOException("Сохранённая сущность без UUID: "+source.dimension()+" "+chunk);
                        scanTree(root,root.getUUID("UUID"),source.dimension(),chunk,wanted,found,0,new int[1]);
                    }
                } else if(occupied(source.folder(),chunk))throw new IOException("Не прочитан занятый чанк сущностей: "+source.dimension()+" "+chunk);
                progress.scanned.incrementAndGet();
            }
        }
        var immutable=new HashMap<UUID,List<Location>>();found.forEach((id,locations)->immutable.put(id,List.copyOf(locations)));
        return new Result(Map.copyOf(immutable));
    }
    private static void scanTree(CompoundTag tag,UUID root,String dimension,ChunkPos chunk,Set<UUID> wanted,
                                 Map<UUID,List<Location>> found,int depth,int[] count) throws IOException {
        if(depth>32||++count[0]>4096||!tag.hasUUID("UUID"))throw new IOException("Неоднозначное дерево сохранённых сущностей: "+dimension+" "+chunk);
        UUID id=tag.getUUID("UUID");
        if(wanted.contains(id)) {
            var locations=found.computeIfAbsent(id,ignored->new ArrayList<>());
            if(locations.size()>=2)throw new IOException("UUID многократно сохранён в мире: "+id);
            locations.add(new Location(dimension,chunk,root));
        }
        if(tag.contains("Passengers")) {
            if(!(tag.get("Passengers") instanceof ListTag children)||!children.isEmpty()&&children.getElementType()!=Tag.TAG_COMPOUND)
                throw new IOException("Повреждённые пассажиры в сохранённом чанке: "+dimension+" "+chunk);
            for(var child:tag.getList("Passengers",Tag.TAG_COMPOUND))scanTree((CompoundTag)child,root,dimension,chunk,wanted,found,depth+1,count);
        }
    }
    static List<ChunkPos> chunks(Path folder) throws IOException {
        if(!Files.exists(folder))return List.of();
        var chunks=new ArrayList<ChunkPos>();
        var pattern=java.util.regex.Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
        try(var files=Files.list(folder)) {
            for(var file:files.filter(path->path.getFileName().toString().endsWith(".mca")).sorted().toList()) {
                var match=pattern.matcher(file.getFileName().toString());
                if(!match.matches())throw new IOException("Неизвестное имя региона сущностей: "+file);
                int rx=Integer.parseInt(match.group(1)),rz=Integer.parseInt(match.group(2));
                var header=ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN);
                try(var channel=FileChannel.open(file,StandardOpenOption.READ)) {
                    // Vanilla creates a zero-length region when checking an empty region.
                    if(channel.size()==0)continue;
                    if(channel.size()<8192)throw new EOFException("Неполный заголовок региона сущностей: "+file);
                    while(header.hasRemaining())if(channel.read(header)<0)throw new EOFException("Неполный заголовок региона сущностей: "+file);
                }
                header.flip();
                long sectors=(Files.size(file)+4095)/4096;
                for(int i=0;i<1024;i++) {int location=header.getInt();if(location!=0) {
                    int offset=location>>>8,count=location&255;
                    if(offset<2||count==0||(long)offset+count>sectors)throw new IOException("Неверный сектор региона сущностей: "+file+" slot="+i);
                    long x=(long)rx*32+(i&31),z=(long)rz*32+(i>>5);
                    if(Math.abs(x)>1_875_000||Math.abs(z)>1_875_000)throw new IOException("Регион сущностей вне границ мира: "+file);
                    chunks.add(new ChunkPos((int)x,(int)z));
                    if(chunks.size()>1_000_000)throw new IOException("Слишком много чанков для проверки UUID; мир не изменён");
                }}
            }
        }
        return chunks;
    }
    private static boolean occupied(Path folder,ChunkPos chunk) throws IOException {
        var file=folder.resolve("r."+(chunk.x>>5)+"."+(chunk.z>>5)+".mca");
        if(!Files.exists(file))return false;
        try(var channel=FileChannel.open(file,StandardOpenOption.READ)) {
            if(channel.size()==0)return false;
            var slot=ByteBuffer.allocate(4);channel.position(((chunk.x&31)+(chunk.z&31)*32)*4L);
            while(slot.hasRemaining())if(channel.read(slot)<0)throw new EOFException("Неполный заголовок региона сущностей: "+file);
            return slot.flip().getInt()!=0;
        }
    }
    private static void check(BooleanSupplier cancelled,long deadline) throws IOException {
        if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new CancellationException();
        if(System.nanoTime()>=deadline)throw new IOException("Проверка UUID в сохранённых чанках превысила 2 минуты; мир не изменён");
    }
}
