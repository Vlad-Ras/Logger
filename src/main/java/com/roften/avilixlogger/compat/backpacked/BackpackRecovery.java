package com.roften.avilixlogger.compat.backpacked;

import com.mrcrayfish.backpacked.BackpackHelper;
import com.mrcrayfish.backpacked.inventory.BackpackedInventoryAccess;
import com.mrcrayfish.backpacked.blockentity.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Conservative latest-loss recovery and relocation. Never rewinds distributed inventory contents. */
public final class BackpackRecovery {
    private BackpackRecovery() {}
    private record Preview(UUID id,UUID target,long loss,long expires,boolean recover,CompoundTag snapshot) {}
    private static final Map<String,Preview> PREVIEWS=new HashMap<>();
    public static void expirePreviews(long now){PreviewExpiry.prune(PREVIEWS,now,Preview::expires);}
    public static void logout(UUID actor){PREVIEWS.remove(actor.toString());}
    private static final Set<UUID> BUSY=new HashSet<>();
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"avilixlogger-backpack-recovery");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    static { IO.allowCoreThreadTimeOut(true); }
    private static String key(CommandSourceStack source){return source.getEntity()==null?"console":source.getEntity().getUUID().toString();}
    public static boolean query(CommandSourceStack source,Runnable action){
        try{IO.execute(action);return true;}catch(RejectedExecutionException e){source.sendFailure(Component.literal("Очередь запросов занята. Повторите позже."));return false;}
    }
    public static void prepare(CommandSourceStack source,String text,ServerPlayer target,boolean recover){
        var index=BackpackIndex.get(source.getServer());UUID id=index==null?null:index.resolve(text);
        if(id==null)try{id=UUID.fromString(text);}catch(IllegalArgumentException ignored){}
        if(!recover){if(id==null)source.sendFailure(Component.literal("Неизвестный ID рюкзака"));else preview(source,id,target,false);return;}
        if(id!=null&&index!=null&&index.record(id)!=null&&index.record(id).lost()){preview(source,id,target,true);return;}
        // A crash before the world's next autosave can leave the location index behind the durable log.
        // Recover from the latest exact audit row, never from an arbitrary older contents checkpoint.
        LogQuery q=new LogQuery();q.dim="*";q.types=BackpackIds.types();q.requireDetails=true;q.limit=id==null?25:1;
        if(id!=null)q.backpackId=id;
        else if(text.toUpperCase(Locale.ROOT).matches("BP-[0-9A-Z]{1,32}")){q.sourcePrefix="backpacked:bag:";q.extraTextFilter=text;}
        else{source.sendFailure(Component.literal("Неверный UUID или короткий ID рюкзака"));return;}
        UUID recipient=target.getUUID();var storage=LoggerRuntime.storage(source.getLevel());
        query(source,()->{try{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            if(!AsyncLogProcessor.awaitIdle(deadline)||!storage.awaitVisible(deadline))throw new IllegalStateException("Журнал ещё не подтверждён. Повторите позже.");
            var rows=storage.queryReverse(q);
            LogEntry latest=null;UUID resolved=null;
            for(LogEntry row:rows){UUID candidate=BackpackIds.fromSource(row.source);if(candidate==null)continue;
                if(q.backpackId==null){try{var extra=com.google.gson.JsonParser.parseString(row.extra).getAsJsonObject();if(!extra.has("shortId")||!extra.get("shortId").getAsString().equalsIgnoreCase(text))continue;}catch(RuntimeException ignored){continue;}}
                if(resolved!=null&&!resolved.equals(candidate))throw new IllegalStateException("Короткий ID неоднозначен. Используйте полный UUID.");
                resolved=candidate;if(latest==null)latest=row;
            }
            if(latest==null||latest.type!=ActionType.BACKPACK_DESTROY)throw new IllegalStateException("Последняя запись не подтверждает утрату. Используйте return для существующего рюкзака.");
            CompoundTag snapshot=NbtSerde.fromSnbt(latest.itemStackNbt);if(snapshot==null)throw new IllegalStateException("Полный снимок утраты отсутствует");
            LogEntry loss=latest;UUID bagId=resolved;
            source.getServer().execute(()->{try{
                if(!LoggerConfigGate())throw new IllegalStateException("Logger отключён");
                var liveIndex=BackpackIndex.get(source.getServer());if(liveIndex==null)throw new IllegalStateException("Индекс недоступен");
                var current=liveIndex.record(bagId);
                if(current!=null&&current.lastEventId>loss.id&&!current.lost())throw new IllegalStateException("После утраты рюкзак уже обнаружен. Повторная выдача запрещена.");
                ItemStack stack=decode(source.getLevel(),snapshot);if(!bagId.equals(BackpackIds.id(stack)))throw new IllegalStateException("ID снимка не соответствует истории");
                var record=liveIndex.identify(stack,null,null);liveIndex.snapshot(record,stack,source.getLevel().registryAccess());
                record.state="destroyed";record.lossId=loss.id;record.lastEventId=loss.id;record.updated=loss.ts;record.dim=loss.dim;record.x=loss.x;record.y=loss.y;record.z=loss.z;record.kind="item";record.reason="confirmed_history_loss";liveIndex.setDirty();
                var player=source.getServer().getPlayerList().getPlayer(recipient);if(player==null)throw new IllegalStateException("Получатель вышел с сервера");preview(source,bagId,player,true);
            }catch(Exception e){source.sendFailure(Component.literal(e.getMessage()));}});
        }catch(Exception e){source.getServer().execute(()->source.sendFailure(Component.literal("Снимок утраты недоступен: "+e.getMessage())));}});
    }
    public static void preview(CommandSourceStack source,UUID id,ServerPlayer target,boolean recover){
        var index=BackpackIndex.get(source.getServer());var r=index==null?null:index.record(id);
        if(r==null){source.sendFailure(Component.literal("Рюкзак не учтён"));return;}
        try{
            if(recover){
                if(!r.lost()||r.lossId<=0||r.snapshot==null)throw new IllegalStateException("Утрата не подтверждена. Для существующего рюкзака используйте return.");
                if(find(source,id,r)!=null)throw new IllegalStateException("Рюкзак найден: повторная выдача запрещена");
                decode(source.getLevel(),r.snapshot);
            }else if(find(source,id,r)==null)throw new IllegalStateException("По последнему адресу рюкзак не найден. Выгруженные чанки и офлайн-инвентари не просматриваются.");
            if(target.getInventory().getFreeSlot()<0)throw new IllegalStateException("В инвентаре получателя нет свободного места");
            PREVIEWS.put(key(source),new Preview(id,target.getUUID(),r.lossId,System.currentTimeMillis()+60_000,recover,r.snapshot==null?null:r.snapshot.copy()));
            source.sendSystemMessage(Component.literal((recover?"Восстановление ":"Возврат ")+r.shortId+" → "+target.getGameProfile().getName()+". "+(recover?"Полный снимок перед утратой, причина: "+r.reason:"Перенос существующего рюкзака")+". /log backpack confirm (60 с), cancel — отмена."));
        }catch(Exception e){source.sendFailure(Component.literal(e.getMessage()));}
    }
    public static void cancel(CommandSourceStack source){PREVIEWS.remove(key(source));source.sendSystemMessage(Component.literal("Операция отменена"));}
    public static void confirm(CommandSourceStack source){
        Preview p=PREVIEWS.remove(key(source));if(p==null||p.expires<System.currentTimeMillis()){source.sendFailure(Component.literal("Предпросмотр отсутствует или истёк"));return;}
        if(!BUSY.add(p.id)){source.sendFailure(Component.literal("Рюкзак уже обрабатывается"));return;}
        var index=BackpackIndex.get(source.getServer());var r=index==null?null:index.record(p.id);
        var target=source.getServer().getPlayerList().getPlayer(p.target);
        try{
            if(!LoggerConfigGate()||r==null||target==null||target.getInventory().getFreeSlot()<0)throw new IllegalStateException("Состояние или получатель изменились");
            if(!p.recover){relocate(source,p,r,target);BUSY.remove(p.id);return;}
            if(!r.lost()||r.lossId!=p.loss||!Objects.equals(r.snapshot,p.snapshot)||find(source,p.id,r)!=null)throw new IllegalStateException("Состояние рюкзака изменилось. Выполните новый предпросмотр.");
            // Durable one-use reservation before delivery. A crash leaves a reservation requiring review,
            // never an automatic second grant. Workers receive only detached data and filesystem paths.
            Path claim=source.getServer().getWorldPath(LevelResource.ROOT).resolve("data/avilix-backpack-recovery/"+p.id+"-"+p.loss+".nbt");
            CompoundTag reservation=new CompoundTag();reservation.putUUID("Id",p.id);reservation.putUUID("Target",p.target);reservation.putLong("Loss",p.loss);reservation.put("Snapshot",p.snapshot.copy());
            if(!query(source,()->{
                try{
                    reserve(claim,reservation);
                    source.getServer().execute(()->{
                        try{
                            var current=BackpackIndex.get(source.getServer());var live=current==null?null:current.record(p.id);
                            var recipient=source.getServer().getPlayerList().getPlayer(p.target);
                            if(!LoggerConfigGate()||live==null||!live.lost()||live.lossId!=p.loss||!Objects.equals(live.snapshot,p.snapshot)||recipient==null||find(source,p.id,live)!=null)throw new IllegalStateException("После резервирования состояние изменилось. Резерв сохранён для проверки, повторная выдача заблокирована.");
                            deliver(source,live,recipient,decode(recipient.serverLevel(),p.snapshot),ActionType.BACKPACK_RECOVER,"recovered_loss:"+p.loss);
                        }catch(Exception e){source.sendFailure(Component.literal(e.getMessage()));}finally{BUSY.remove(p.id);}
                    });
                }catch(Exception e){source.getServer().execute(()->{BUSY.remove(p.id);source.sendFailure(Component.literal("Восстановление не выполнено: "+e.getMessage()));});}
            }))BUSY.remove(p.id);
        }catch(Exception e){BUSY.remove(p.id);source.sendFailure(Component.literal(e.getMessage()));}
    }
    private static boolean LoggerConfigGate(){return com.roften.avilixlogger.LoggerConfig.isEnabled();}
    public static void reserve(Path path,CompoundTag reservation)throws IOException{
        Files.createDirectories(path.getParent());
        // CREATE_NEW is exclusive across JVMs; incomplete reservations also prevent duplicate recovery.
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();NbtIo.writeCompressed(reservation,bytes);
            java.nio.ByteBuffer data=java.nio.ByteBuffer.wrap(bytes.toByteArray());while(data.hasRemaining())channel.write(data);channel.force(true);
        }catch(FileAlreadyExistsException e){throw new IOException("Эта утрата уже восстановлена или зарезервирована: "+path.getFileName());}
    }
    public static ItemStack decode(ServerLevel level,CompoundTag tag){
        ItemStack stack=ItemStack.CODEC.parse(level.registryAccess().createSerializationContext(NbtOps.INSTANCE),tag).getOrThrow();
        if(!BackpackAudit.isBag(stack)||stack.getCount()!=1||BackpackIds.id(stack)==null)throw new IllegalArgumentException("Снимок рюкзака неполон или некорректен");return stack;
    }
    private record Located(ItemStack stack,Runnable remove,Runnable restore) {}
    private static Located find(CommandSourceStack source,UUID id,BackpackIndex.Record r){
        Located found=null;
        // On administrator request only, bounded by online players; no world or global chunk scans.
        for(ServerPlayer player:source.getServer().getPlayerList().getPlayers()){
            for(int i=0;i<player.getInventory().getContainerSize();i++){final int slot=i;var stack=player.getInventory().getItem(i);if(id.equals(BackpackIds.id(stack))){
                if(found!=null)throw new IllegalStateException("Найдено несколько копий одного ID. Автоматическая выдача запрещена.");
                ItemStack original=stack.copy();found=new Located(original,()->{player.closeContainer();player.getInventory().setItem(slot,ItemStack.EMPTY);},()->player.getInventory().setItem(slot,original.copy()));
            }}
            var cursor=player.containerMenu.getCarried();if(id.equals(BackpackIds.id(cursor)))throw new IllegalStateException("Рюкзак на курсоре игрока. Сначала завершите работу с GUI.");
            var bags=BackpackHelper.getBackpacks(player);
            for(int i=0;i<bags.size();i++){final int slot=i;ItemStack stack=bags.get(i);if(id.equals(BackpackIds.id(stack))){
                if(found!=null)throw new IllegalStateException("Найдено несколько копий одного ID");
                var inventory=((BackpackedInventoryAccess)player).backpacked$GetBackpackInventory(i);if(inventory!=null)inventory.saveItemsToStack();
                ItemStack original=stack.copy();found=new Located(original,()->{player.closeContainer();BackpackHelper.setBackpackStack(player,ItemStack.EMPTY,slot);},()->BackpackHelper.setBackpackStack(player,original.copy(),slot));
            }}
        }
        ServerLevel level=source.getServer().getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,ResourceLocation.parse(r.dim)));
        BlockPos pos=new BlockPos(r.x,r.y,r.z);
        if(level!=null&&r.entity!=null&&level.getEntity(r.entity) instanceof ItemEntity entity&&id.equals(BackpackIds.id(entity.getItem()))){
            if(found!=null)throw new IllegalStateException("Найдены несколько копий ID");ItemStack stack=entity.getItem().copy();
            found=new Located(stack,entity::discard,()->{var replacement=new ItemEntity(level,entity.getX(),entity.getY(),entity.getZ(),stack.copy());replacement.setUUID(entity.getUUID());level.addFreshEntity(replacement);});
        }
        if(level!=null&&level.hasChunkAt(pos)){
            var be=level.getBlockEntity(pos);Container container=null;ItemStack stack=ItemStack.EMPTY;
            if(be instanceof BackpackDockBlockEntity dock){stack=dock.getBackpackWithContents();}
            else if(be instanceof ShelfBlockEntity shelf){container=shelf.getContainer();stack=shelf.getBackpack();}
            else if(be instanceof Container c&&r.slot>=0&&r.slot<c.getContainerSize()){container=c;stack=c.getItem(r.slot);}
            if(id.equals(BackpackIds.id(stack))){
                if(found!=null)throw new IllegalStateException("Найдены несколько копий ID");ItemStack original=stack.copy();
                if(be instanceof BackpackDockBlockEntity dock){
                    // Direct state replacement avoids the dock's pop operation spawning another item.
                    CompoundTag before=dock.saveWithoutMetadata(level.registryAccess());CompoundTag empty=before.copy();empty.put("Backpack",new CompoundTag());
                    found=new Located(original,()->{closeMenus(source,dock.getInventory());dock.loadWithComponents(empty,level.registryAccess());dock.setChanged();level.sendBlockUpdated(pos,dock.getBlockState(),dock.getBlockState(),3);},()->{dock.loadWithComponents(before,level.registryAccess());dock.setChanged();});
                }else{Container c=container;int slot=be instanceof ShelfBlockEntity?0:r.slot;found=new Located(original,()->{closeMenus(source,c);c.setItem(slot,ItemStack.EMPTY);c.setChanged();},()->{c.setItem(slot,original.copy());c.setChanged();});}
            }
        }
        return found;
    }
    private static void closeMenus(CommandSourceStack source,Container c){if(c==null)return;for(ServerPlayer p:source.getServer().getPlayerList().getPlayers())if(p.containerMenu.slots.stream().anyMatch(s->s.container==c))p.closeContainer();}
    private static void relocate(CommandSourceStack source,Preview p,BackpackIndex.Record r,ServerPlayer target){
        Located located=find(source,p.id,r);if(located==null||located.stack.getCount()!=1)throw new IllegalStateException("Исходный рюкзак изменился");
        boolean old=BackpackAudit.restoring;BackpackAudit.restoring=true;
        try{located.remove.run();try{deliver(source,r,target,located.stack.copy(),ActionType.BACKPACK_RETURN,"admin_return");}catch(Exception e){located.restore.run();throw e;}}
        finally{BackpackAudit.restoring=old;}
    }
    private static void deliver(CommandSourceStack source,BackpackIndex.Record r,ServerPlayer target,ItemStack stack,ActionType type,String reason){
        if(target.getInventory().getFreeSlot()<0)throw new IllegalStateException("У получателя нет свободного слота; резерв сохранён, выдача не состоялась");
        target.closeContainer();int slot=target.getInventory().getFreeSlot();if(slot<0)throw new IllegalStateException("Нет свободного слота");
        target.getInventory().setItem(slot,stack);
        // Once granted, audit/broadcast failure must not restore the source alongside the new bag.
        try { target.getInventory().setChanged();target.inventoryMenu.broadcastChanges(); } catch (RuntimeException e) { com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error("Backpack delivered; inventory notification failed",e); }
        boolean old=BackpackAudit.restoring;BackpackAudit.restoring=false;
        try{BackpackAudit.lifecycle(new BackpackAudit.Ref(stack,target.serverLevel(),target.blockPosition(),target,"inventory",slot,null),source.getEntity() instanceof ServerPlayer actor?actor:null,type,reason);}
        catch(RuntimeException e){com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error("Backpack delivered; recovery audit failed",e);}
        finally{BackpackAudit.restoring=old;}
        source.sendSystemMessage(Component.literal(r.shortId+" выдан "+target.getGameProfile().getName()+" вместе с содержимым"));
    }
    public static void stop(){PREVIEWS.clear();BUSY.clear();}
}
