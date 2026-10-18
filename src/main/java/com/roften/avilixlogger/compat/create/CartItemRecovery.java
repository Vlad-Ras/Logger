package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;
import java.nio.channels.FileChannel;
import java.util.*;
import java.util.concurrent.*;

/** Explicit administrator disaster recovery; does not apply CartRollbackPlan or edit world blocks. */
public final class CartItemRecovery {
    private record Preview(CartRecoveryPlan plan,UUID recipient,long expires,ItemStack stack,CompoundTag receiptItem){}
    private static final Map<UUID,Preview> PREVIEWS=new HashMap<>();
    private static final Map<UUID,Long> REQUESTS=new HashMap<>();
    private static long nextRequest;
    private static final Set<UUID> BUSY=new HashSet<>();
    private static final ThreadPoolExecutor IO=new ThreadPoolExecutor(1,1,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(8),r->{var t=new Thread(r,"avilixlogger-cart-recovery");t.setDaemon(true);return t;});
    private static long generation;
    private CartItemRecovery(){}
    public static void preview(CommandSourceStack source,UUID id,long entry){
        var actor=source.getPlayer();if(actor==null)return;long token=++nextRequest;REQUESTS.put(actor.getUUID(),token);
        var storage=LoggerRuntime.storage(source.getLevel());var registries=source.getServer().registryAccess();long request=generation;
        submit(source,()->{try{
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            if(!AsyncLogProcessor.awaitIdle(deadline)||!storage.awaitVisible(deadline))throw new IllegalStateException("Журнал ещё не подтверждён. Повторите запрос");
            var plan=CartRecoveryPlan.prepare(storage,id,entry);
            var stack=ItemStack.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE),plan.item()).getOrThrow();
            if(stack.isEmpty()||stack.getCount()!=1||!id.equals(CreateCartAudit.itemId(stack)))throw new IllegalStateException("Предмет не прочитан полностью");
            if(com.simibubi.create.content.contraptions.data.ContraptionPickupLimiting.isTooLargeForPickup(plan.item()))throw new IllegalStateException("Штуковина превышает размер предмета, разрешённый Create");
            var receiptItem=plan.item().copy();
            source.getServer().execute(()->{if(request!=generation||!authorized(actor)||!Objects.equals(REQUESTS.get(actor.getUUID()),token))return;REQUESTS.remove(actor.getUUID());try{
                absent(source,id);if(actor.getInventory().getFreeSlot()<0)throw new IllegalStateException("Освободите слот инвентаря");
                PREVIEWS.put(actor.getUUID(),new Preview(plan,actor.getUUID(),System.currentTimeMillis()+120000,stack,receiptItem));
                String message="Вернуть штуковину в предмет: "+id+"; entry="+plan.entry()+" ("+plan.side()+"), "+java.time.Instant.ofEpochMilli(plan.timestamp())+", блоков: "+plan.blocks()+". Получатель: "+actor.getGameProfile().getName()+". Мир и действия штуковины не откатываются. Используется полный снимок этого момента; более поздние изменения не добавляются. Проверены загруженные конструкции/дропы и онлайн-инвентари; проверьте, что оригинал не остался в выгруженном чанке или хранилище.";
                com.roften.avilixlogger.net.CartHistoryNetwork.confirm(source,message,"log cart confirm-recovery "+id+" "+plan.entry());
            }catch(Exception e){error(source,e);}});
        }catch(Exception e){error(source,e);}});
    }
    public static void confirm(CommandSourceStack source,UUID expectedId,long expectedEntry){
        var actor=source.getPlayer();if(actor==null)return;var p=PREVIEWS.get(actor.getUUID());
        if(p!=null&&(!p.plan.id().equals(expectedId)||p.plan.entry()!=expectedEntry)){com.roften.avilixlogger.net.CartHistoryNetwork.error(source,"Выбранный предпросмотр заменён. Запросите его снова");return;}
        PREVIEWS.remove(actor.getUUID());
        if(p==null||p.expires<System.currentTimeMillis()){com.roften.avilixlogger.net.CartHistoryNetwork.error(source,"Предпросмотр восстановления отсутствует или истёк");return;}
        if(!authorized(actor)){com.roften.avilixlogger.net.CartHistoryNetwork.error(source,"Нет авторизации или права восстановления");return;}
        if(!BUSY.add(p.plan.id())){com.roften.avilixlogger.net.CartHistoryNetwork.error(source,"Эта штуковина уже восстанавливается");return;}
        try{absent(source,p.plan.id());if(actor.getInventory().getFreeSlot()<0)throw new IllegalStateException("Нет свободного слота");}
        catch(Exception e){BUSY.remove(p.plan.id());error(source,e);return;}
        var claim=source.getServer().getWorldPath(LevelResource.ROOT).resolve("data/avilix-cart-recovery/"+p.plan.id()+".nbt");long request=generation;
        if(!submit(source,()->{try{
            reserve(claim,p.plan,p.recipient);
            source.getServer().execute(()->{try{
                if(request!=generation||!authorized(actor))throw new IllegalStateException("Сессия завершена; резерв сохранён, выдача не выполнена");
                absent(source,p.plan.id());int slot=actor.getInventory().getFreeSlot();if(slot<0)throw new IllegalStateException("Нет места; резерв сохранён");
                // One server-thread slot write. Never drop the recovered item on the ground.
                actor.getInventory().setItem(slot,p.stack);actor.getInventory().setChanged();actor.containerMenu.broadcastChanges();
                try{var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_ROLLBACK;row.actorUuid=actor.getUUID();row.actorName=actor.getGameProfile().getName();
                row.source=CartAuditContext.prefix(p.plan.id())+"recover_item:"+p.plan.entry();row.dim=actor.level().dimension().location().toString();row.x=actor.blockPosition().getX();row.y=actor.blockPosition().getY();row.z=actor.blockPosition().getZ();
                var receipt=new CompoundTag();receipt.putInt("Format",1);receipt.putString("Form","item");receipt.put("Item",p.receiptItem);receipt.putUUID("Holder",actor.getUUID());receipt.putString("Dimension",row.dim);
                row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,receipt);row.extra="item_recovery; entry="+p.plan.entry()+"; side="+p.plan.side()+"; world_changes=0";LogIdGenerator.ensure(row);LoggerRuntime.storage(actor.serverLevel()).append(row);
                }catch(Exception journalError){CreateCartAudit.failed("delivered recovery receipt",journalError);source.sendFailure(Component.literal("Предмет уже выдан; не удалось записать квитанцию. Повторная выдача заблокирована резервом."));}
                source.sendSystemMessage(Component.literal("Штуковина выдана в свободный слот. entry="+p.plan.entry()+". Блоки мира и действия не откатывались."));
            }catch(Exception e){error(source,e);}finally{BUSY.remove(p.plan.id());}});
        }catch(Exception e){source.getServer().execute(()->{BUSY.remove(p.plan.id());error(source,e);});}}))BUSY.remove(p.plan.id());
    }
    private static boolean authorized(ServerPlayer player){return LoggerConfig.isEnabled()&&player.getServer()!=null&&player.getServer().getPlayerList().getPlayer(player.getUUID())==player&&PermissionUtil.has(player.createCommandSourceStack(),"avilixlogger.command.rollback",2);}
    private static void absent(CommandSourceStack source,UUID id){
        if(CartRollbackCoordinator.busy()||RollbackCoordinator.hasActiveRollback()||CartAuditContext.rollbackActive)throw new IllegalStateException("Дождитесь завершения текущего отката");
        if(CreateCartAudit.loaded(id)!=null||!CartItemTracker.loaded(id).isEmpty())throw new IllegalStateException("Оригинал найден среди загруженных конструкций/предметов; копия не выдана");
        for(var player:source.getServer().getPlayerList().getPlayers()){
            for(var inv:List.of(player.getInventory(),player.getEnderChestInventory()))for(int i=0;i<inv.getContainerSize();i++)if(id.equals(CreateCartAudit.itemId(inv.getItem(i))))throw new IllegalStateException("Предмет найден у "+player.getGameProfile().getName());
            if(id.equals(CreateCartAudit.itemId(player.containerMenu.getCarried())))throw new IllegalStateException("Предмет находится на курсоре игрока");
        }
    }
    private static void reserve(Path path,CartRecoveryPlan plan,UUID target)throws Exception{
        Files.createDirectories(path.getParent());var tag=new CompoundTag();tag.putUUID("Id",plan.id());tag.putUUID("Recipient",target);tag.putLong("Entry",plan.entry());tag.putString("Side",plan.side());
        try(var channel=FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){
            var bytes=new java.io.ByteArrayOutputStream();NbtIo.writeCompressed(tag,bytes);var data=java.nio.ByteBuffer.wrap(bytes.toByteArray());while(data.hasRemaining())channel.write(data);channel.force(true);
        }catch(FileAlreadyExistsException e){throw new IllegalStateException("Восстановление этого ID уже выполнено или зарезервировано. Повторная выдача требует проверки администратором");}
    }
    private static boolean submit(CommandSourceStack source,Runnable action){if(!LoggerConfig.isEnabled()){source.sendFailure(Component.literal("Logger не авторизован"));return false;}try{IO.execute(action);return true;}catch(RejectedExecutionException e){source.sendFailure(Component.literal("Очередь восстановления занята"));return false;}}
    private static void error(CommandSourceStack source,Exception e){source.getServer().execute(()->com.roften.avilixlogger.net.CartHistoryNetwork.error(source,"Восстановление штуковины: "+e.getMessage()));}
    public static void cancel(CommandSourceStack source){if(source.getPlayer()!=null){PREVIEWS.remove(source.getPlayer().getUUID());REQUESTS.remove(source.getPlayer().getUUID());}}
    public static void logout(UUID actor){PREVIEWS.remove(actor);REQUESTS.remove(actor);}
    public static void stop(){generation++;PREVIEWS.clear();REQUESTS.clear();BUSY.clear();IO.getQueue().clear();}
}
