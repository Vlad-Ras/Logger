package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Write-ahead undo record. A scene cannot mutate the world until its record is durable. */
public final class RecoveryJournal {
    private static final int MAGIC=0x41565231, VERSION=2;
    private static final long MAX_BYTES=8L*1024*1024*1024;
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(0,1,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task->{var thread=new Thread(task,"avilixlogger-recovery-journal");thread.setDaemon(true);return thread;});
    public record Block(String dimension, BlockPos pos, RollbackEngine.ReplayCheckpoint before,
                        RollbackEngine.ReplayCheckpoint after) {}
    public record EntityImage(String dimension, UUID uuid, String type, CompoundTag before, CompoundTag after, List<Long> events) {}
    public record SceneData(List<Block> blocks, List<EntityImage> entities, List<Long> events) {}
    public record Handle(Path path, CompletableFuture<Void> written) {
        public boolean done() {return written.isDone();}
        public void await() {written.join();}
        public void commit(List<Long> events) {
            if(!done())throw new IllegalStateException("Журнал ещё записывается");
            io(()->{commitFile(path,events);return null;}).join();
        }
        /** Called only once all changes/compensation have been flushed, or before any mutation. */
        public void retire() {
            if(!done())throw new IllegalStateException("Журнал восстановления ещё записывается");
            io(()->{if(Files.deleteIfExists(path))forceDirectory(path.getParent());return null;}).join();
        }
    }
    private RecoveryJournal() {}

    public static Handle prepare(MinecraftServer server,List<Block> blocks) {return prepare(server,blocks,List.of(),List.of());}
    public static Handle prepare(MinecraftServer server,List<Block> blocks,List<EntityImage> entities,List<Long> events) {
        var scene=new SceneData(List.copyOf(blocks),List.copyOf(entities),List.copyOf(events));
        Path file=directory(server).resolve(UUID.randomUUID()+".scene");
        return new Handle(file,io(()->{write(file,scene);return null;}));
    }
    static Path directory(MinecraftServer server) {return server.getWorldPath(LevelResource.ROOT).resolve("data/avilixlogger-recovery");}
    interface IOCall<T> {T call() throws Exception;}
    static <T> CompletableFuture<T> io(IOCall<T> action) {
        return CompletableFuture.supplyAsync(()->{try{return action.call();}catch(Exception problem){throw new CompletionException(problem);}},IO);
    }
    static void write(Path file,List<Block> blocks) throws Exception {write(file,new SceneData(blocks,List.of(),List.of()));}
    static void write(Path file,SceneData scene) throws Exception {
        var root=new CompoundTag();root.putInt("Version",VERSION);root.putString("Kind","scene");
        var entries=new ListTag();
        for(var block:scene.blocks()) {
            var tag=new CompoundTag();tag.putString("Dimension",block.dimension());tag.putLong("Pos",block.pos().asLong());
            tag.put("Before",encode(block.before()));tag.put("After",encode(block.after()));entries.add(tag);
        }
        root.put("Blocks",entries);var entities=new ListTag();
        for(var entity:scene.entities()) {
            var tag=new CompoundTag();tag.putString("Dimension",entity.dimension());tag.putUUID("UUID",entity.uuid());tag.putString("Type",entity.type());
            if(entity.before()!=null)tag.put("Before",entity.before());if(entity.after()!=null)tag.put("After",entity.after());
            tag.putLongArray("Events",longs(entity.events()));entities.add(tag);
        }
        root.put("Entities",entities);root.putLongArray("Events",longs(scene.events()));writeDocument(file,root);
    }
    private static long[] longs(List<Long> ids){return ids.stream().mapToLong(Long::longValue).toArray();}
    private static void writeDocument(Path file,CompoundTag root) throws Exception {
        Path dir=file.getParent();
        if(!Files.exists(dir)){Files.createDirectories(dir);forceDirectory(dir.getParent());}
        if(Files.exists(file))throw new FileAlreadyExistsException(file.toString());
        Path temporary=dir.resolve(file.getFileName()+".tmp");
        var digest=MessageDigest.getInstance("SHA-256");
        try {
            try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                var checked=new DigestOutputStream(Channels.newOutputStream(channel),digest);
                var out=new DataOutputStream(new BufferedOutputStream(checked));
                out.writeInt(MAGIC);NbtIo.write(root,out);out.flush();
                checked.on(false);out.write(digest.digest());out.flush();
                if(channel.size()>MAX_BYTES)throw new IOException("Журнал восстановления превышает допустимый размер");
                channel.force(true);
            }
            Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE);
            forceDirectory(dir);
        } finally {Files.deleteIfExists(temporary);}
    }
    private static CompoundTag readDocument(Path file) throws Exception {
        long size=Files.size(file);
        long budget;
        try {budget=com.roften.avilixlogger.LoggerConfig.VALUES.rollbackMaxPreparedPayloadMiB.get()*1024L*1024L;}
        catch(IllegalStateException unloaded){budget=512L*1024*1024;}
        if(size<40||size>Math.min(MAX_BYTES,budget))throw new IOException("Недопустимый размер журнала восстановления: "+file);
        var digest=MessageDigest.getInstance("SHA-256");
        // Validate bytes before parsing lengths/NBT. Bit corruption must not drive allocation first.
        try(var raw=new BufferedInputStream(Files.newInputStream(file))) {
            var checked=new DigestInputStream(new LimitedInput(raw,size-32),digest);
            checked.transferTo(OutputStream.nullOutputStream());
            if(!MessageDigest.isEqual(digest.digest(),raw.readNBytes(32)))throw new IOException("Контрольная сумма журнала не совпала: "+file);
        }
        digest.reset();
        try(var raw=new BufferedInputStream(Files.newInputStream(file))) {
            var limited=new LimitedInput(raw,size-32);
            var checked=new DigestInputStream(limited,digest);
            var in=new DataInputStream(checked);
            if(in.readInt()!=MAGIC)throw new IOException("Неизвестный формат журнала восстановления: "+file);
            var root=NbtIo.read(in,NbtAccounter.create(Math.min(budget,size*128L)));
            if(root==null||in.read()!=-1)throw new IOException("Неполный журнал/лишние данные: "+file);
            if(!MessageDigest.isEqual(digest.digest(),raw.readNBytes(32)))throw new IOException("Контрольная сумма журнала не совпала: "+file);
            if(root.getInt("Version")<1||root.getInt("Version")>VERSION)throw new IOException("Неподдерживаемая версия журнала: "+file);
            return root;
        }
    }
    static List<Block> read(Path file) throws Exception {return readScene(file).blocks();}
    static SceneData readScene(Path file) throws Exception {
        var root=readDocument(file);
        if(root.getInt("Version")>=2&&!root.getString("Kind").equals("scene"))throw new IOException("Неверный вид журнала: "+file);
        var entries=compoundList(root,"Blocks",file);
        if(entries.size()>100_000)throw new IOException("Слишком много блоков в журнале: "+file);
        var blocks=new ArrayList<Block>(entries.size());var addresses=new HashSet<String>();
        for(var entry:entries) {
            var tag=(CompoundTag)entry;
            if(!tag.contains("Pos",Tag.TAG_LONG)||!tag.contains("Dimension",Tag.TAG_STRING))throw new IOException("Нет адреса объекта: "+file);
            String dim=tag.getString("Dimension");var pos=BlockPos.of(tag.getLong("Pos"));
            if(ResourceLocation.tryParse(dim)==null||!addresses.add(dim+"/"+pos.asLong()))throw new IOException("Некорректный/повторный адрес: "+file);
            blocks.add(new Block(dim,pos,decode(tag.getCompound("Before")),decode(tag.getCompound("After"))));
        }
        var entities=new ArrayList<EntityImage>();var owned=new HashSet<UUID>();
        List<Long> events=root.getInt("Version")==1?List.of():events(root,file);
        var eventSet=new HashSet<>(events);
        if(root.getInt("Version")>=2)for(var entry:compoundList(root,"Entities",file)) {
            var tag=(CompoundTag)entry;
            String dim=tag.getString("Dimension"),type=tag.getString("Type");
            if(ResourceLocation.tryParse(dim)==null||!tag.hasUUID("UUID"))throw new IOException("Нет адреса/UUID сущности: "+file);
            UUID id=tag.getUUID("UUID");
            CompoundTag before=optionalCompound(tag,"Before",file),after=optionalCompound(tag,"After",file);
            if(before==null&&after==null)throw new IOException("Нет ни одного снимка сущности: "+id);
            var all=new HashSet<UUID>();
            for(var image:Arrays.asList(before,after))if(image!=null)all.addAll(EntityRecoverySnapshot.require(image,type,id).ids());
            for(var uuid:all)if(!owned.add(uuid))throw new IOException("Пересечение деревьев сущностей в журнале: "+uuid);
            var keys=events(tag,file);
            if(keys.isEmpty()||!eventSet.containsAll(keys)||owned.size()>4096)throw new IOException("Неполные квитанции/слишком много UUID: "+file);
            entities.add(new EntityImage(dim,id,type,before,after,keys));
        }
        return new SceneData(List.copyOf(blocks),List.copyOf(entities),events);
    }
    private static ListTag compoundList(CompoundTag tag,String key,Path file) throws IOException {
        if(!(tag.get(key) instanceof ListTag list)||!list.isEmpty()&&list.getElementType()!=Tag.TAG_COMPOUND)
            throw new IOException("Неверный список "+key+": "+file);
        return tag.getList(key,Tag.TAG_COMPOUND);
    }
    private static CompoundTag optionalCompound(CompoundTag tag,String key,Path file) throws IOException {
        if(!tag.contains(key))return null;
        if(!tag.contains(key,Tag.TAG_COMPOUND))throw new IOException("Неверный снимок "+key+": "+file);
        return tag.getCompound(key);
    }
    private static List<Long> events(CompoundTag tag,Path file) throws IOException {
        if(!tag.contains("Events",Tag.TAG_LONG_ARRAY))throw new IOException("Нет квитанций событий: "+file);
        long[] raw=tag.getLongArray("Events");if(raw.length>100_000)throw new IOException("Слишком много квитанций: "+file);
        var ids=new LinkedHashSet<Long>();for(long id:raw)if(id<=0||!ids.add(id))throw new IOException("Неверная/повторная квитанция: "+file);
        return List.copyOf(ids);
    }
    static Set<Long> readCommitted(Path dir) throws Exception {
        var ids=new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        if(!Files.exists(dir))return Set.of();
        try(var files=Files.list(dir)) {
            for(var file:files.filter(path->path.getFileName().toString().endsWith(".receipt")).toList()) {
                var root=readDocument(file);
                if(root.getInt("Version")!=VERSION||!root.getString("Kind").equals("receipt"))throw new IOException("Неверная квитанция: "+file);
                for(long id:events(root,file))ids.add(id);
                if(ids.size()>1_000_000)throw new IOException("Превышен безопасный объём квитанций; требуется обслуживание журнала");
            }
        }
        return it.unimi.dsi.fastutil.longs.LongSets.unmodifiable(ids);
    }
    private static Path committedPath(Path file){return file.resolveSibling(file.getFileName().toString().replace(".scene",".committed"));}
    private static void commitFile(Path file,List<Long> ids) throws Exception {
        if(ids.isEmpty()){if(Files.deleteIfExists(file))forceDirectory(file.getParent());return;}
        Path committed=committedPath(file);Files.move(file,committed,StandardCopyOption.ATOMIC_MOVE);forceDirectory(file.getParent());
        sealReceipt(committed,ids);
    }
    private static void sealReceipt(Path committed,List<Long> ids) throws Exception {
        Path receipt=committed.resolveSibling(committed.getFileName().toString().replace(".committed",".receipt"));
        if(Files.exists(receipt)) {
            var old=readDocument(receipt);
            if(!old.getString("Kind").equals("receipt")||!new HashSet<>(events(old,receipt)).equals(new HashSet<>(ids)))throw new IOException("Квитанция не совпала с завершённой операцией: "+committed);
        } else {
            var root=new CompoundTag();root.putInt("Version",VERSION);root.putString("Kind","receipt");root.putLongArray("Events",longs(ids));writeDocument(receipt,root);
        }
        Files.delete(committed);forceDirectory(committed.getParent());
    }
    private static CompoundTag encode(RollbackEngine.ReplayCheckpoint value) {
        var tag=new CompoundTag();tag.put("State",value.state());
        if(value.be()!=null)tag.put("BlockEntity",value.be());if(value.slots()!=null)tag.put("Slots",value.slots());return tag;
    }
    private static RollbackEngine.ReplayCheckpoint decode(CompoundTag tag) throws IOException {
        if(!tag.contains("State",Tag.TAG_COMPOUND)||!tag.getCompound("State").contains("Name",Tag.TAG_STRING))
            throw new IOException("Журнал не содержит полного состояния блока");
        if(tag.contains("BlockEntity")&&!tag.contains("BlockEntity",Tag.TAG_COMPOUND)
                ||tag.contains("Slots")&&(!tag.contains("Slots",Tag.TAG_COMPOUND)||!ContainerSlotSnapshot.isValid(tag.getCompound("Slots"))))
            throw new IOException("Повреждённые NBT/слоты в журнале");
        return new RollbackEngine.ReplayCheckpoint(tag.getCompound("State"),tag.contains("BlockEntity")?tag.getCompound("BlockEntity"):null,
                tag.contains("Slots")?tag.getCompound("Slots"):null);
    }
    private static void forceDirectory(Path dir) throws IOException {
        // Failing here refuses mutation rather than silently claiming crash durability on this filesystem.
        try(var channel=FileChannel.open(dir,StandardOpenOption.READ)){channel.force(true);}
    }
    private static final class LimitedInput extends FilterInputStream {
        private long remaining;
        LimitedInput(InputStream in,long size){super(in);remaining=size;}
        @Override public int read() throws IOException {if(remaining==0)return -1;int value=super.read();if(value<0)throw new EOFException();remaining--;return value;}
        @Override public int read(byte[] data,int offset,int length) throws IOException {
            if(length==0)return 0;if(remaining==0)return -1;
            int count=super.read(data,offset,(int)Math.min(length,remaining));if(count<0)throw new EOFException();remaining-=count;return count;
        }
    }

    /** Before players/ticks: unfinished scenes always undo to the exact saved before-image. */
    public static void recoverOnStartup(MinecraftServer server) {
        if(!server.isSameThread())throw new IllegalStateException("Восстановление журнала запущено вне серверного потока");
        Path dir=directory(server);
        record Pending(Path file,SceneData scene) {}
        var pending=io(()->{
            if(!Files.exists(dir))return List.<Pending>of();
            try(var files=Files.list(dir)) {
                for(var file:files.filter(path->path.getFileName().toString().endsWith(".committed")).toList()) {
                    var scene=readScene(file);sealReceipt(file,scene.events());
                }
            }
            var receipts=readCommitted(dir);
            var result=new ArrayList<Pending>();
            try(var files=Files.list(dir)) {
                for(var file:files.filter(path->path.getFileName().toString().endsWith(".scene")).sorted().toList())result.add(new Pending(file,readScene(file)));
            }
            if(result.size()>1)throw new IOException("Найдено несколько незавершённых сцен; автоматическое совмещение небезопасно: "+dir);
            for(var scene:result)for(long id:scene.scene().events())if(receipts.contains(id))throw new IOException("Незавершённый журнал противоречит завершённой квитанции: "+scene.file());
            return List.copyOf(result);
        }).join();
        if(pending.isEmpty())return;
        if(CartAuditContext.rollbackActive)throw new IllegalStateException("Мир уже зарезервирован другой операцией");
        CartAuditContext.rollbackActive=true;
        boolean success=false,previous=CartAuditContext.restoring(true);
        try {
            for(var scene:pending) {
                for(var entity:scene.scene().entities()) {
                    RecoveryEntityBatch.level(server,entity.dimension());
                    for(var snapshot:Arrays.asList(entity.before(),entity.after()))if(snapshot!=null) {
                        String invalid=NbtSerde.validateEntitySnapshot(entity.type(),entity.uuid(),snapshot);
                        if(invalid!=null)throw new IllegalStateException(invalid+": "+entity.dimension()+" UUID="+entity.uuid());
                    }
                }
                var levels=new HashMap<String,ServerLevel>();var states=new ArrayList<BlockState>(scene.scene().blocks().size());
                // Validate all dimensions and before-images before changing any one block.
                for(var block:scene.scene().blocks()) {
                    var id=ResourceLocation.parse(block.dimension());
                    var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,id));
                    if(level==null)throw new IllegalStateException("Измерение незавершённого восстановления отсутствует: "+id);
                    levels.put(block.dimension(),level);
                    if(level.isOutsideBuildHeight(block.pos()))throw new IllegalStateException("Неверная высота объекта в журнале: "+block.pos());
                    var state=RollbackEngine.strictReplayState(level,block.before().state());
                    if(state==null||state.hasBlockEntity()&&(block.before().be()==null||!block.before().be().contains("id",Tag.TAG_STRING)))
                        throw new IllegalStateException("Недостаточный резервный снимок: "+id+" "+block.pos());
                    states.add(state);CartRestoreLocks.block(level,block.pos());
                    for(var side:Direction.values())CartRestoreLocks.block(level,block.pos().relative(side));
                }
                for(var block:scene.scene().blocks()) {
                    var level=levels.get(block.dimension());level.getChunk(block.pos());CartRestoreLocks.keepLoaded(level,block.pos());
                    for(var side:Direction.values()){var pos=block.pos().relative(side);level.getChunk(pos);CartRestoreLocks.keepLoaded(level,pos);}
                }
                for(int i=0;i<scene.scene().blocks().size();i++) {
                    var block=scene.scene().blocks().get(i);var level=levels.get(block.dimension());var state=states.get(i);
                    if(!level.getBlockState(block.pos()).equals(state))level.removeBlockEntity(block.pos());
                    level.setBlock(block.pos(),state,2|16|32|64);
                    if(!level.getBlockState(block.pos()).equals(state))throw new IllegalStateException("Компенсация состояния отклонена: "+block.pos());
                }
                for(var block:scene.scene().blocks()) {
                    var level=levels.get(block.dimension());var before=block.before();
                    if(before.be()!=null&&!NbtSerde.readBlockEntity(level,block.pos(),before.be())
                            ||before.slots()!=null&&!ContainerSlotSnapshot.apply(level,block.pos(),before.slots()))
                        throw new IllegalStateException("Компенсация NBT/слотов отклонена: "+block.pos());
                }
                for(var block:scene.scene().blocks())if(!RollbackEngine.sameCheckpoint(RollbackEngine.currentCheckpoint(levels.get(block.dimension()),block.pos()),block.before()))
                    throw new IllegalStateException("Проверка компенсированного объекта не прошла: "+block.dimension()+" "+block.pos());
                if(!scene.scene().entities().isEmpty())RecoveryEntityBatch.undoOnStartup(server,scene.scene().entities());
                RecoveryCheckpoint.flush(server);
                new Handle(scene.file(),CompletableFuture.completedFuture(null)).retire();
                com.roften.avilixlogger.AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Незавершённая сцена компенсирована до начала игровых тиков: {} объектов; {}",scene.scene().blocks().size()+scene.scene().entities().size(),scene.file());
            }
            success=true;
        } catch(Throwable failure) {
            throw new IllegalStateException("Не удалось надёжно завершить обработку незавершённого восстановления. Каталог журнала: "+dir+". Запуск остановлен до выяснения причины.",failure);
        } finally {
            CartAuditContext.restoring(previous);
            if(success){RecoveryEntityReservations.release();CartRestoreLocks.clear();CartAuditContext.rollbackActive=false;}
        }
    }
}
