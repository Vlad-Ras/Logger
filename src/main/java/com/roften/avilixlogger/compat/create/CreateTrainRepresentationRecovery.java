package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.lang.ref.WeakReference;
import java.util.*;

/** Recover absent dimensional bodies of an unchanged live carriage, retaining its single cargo owner. */
public final class CreateTrainRepresentationRecovery {
    private CreateTrainRepresentationRecovery() {}
    private record Body(ResourceKey<Level> dimension,CompoundTag nbt,StagedContraptionRestore staged) {}
    /** Detached preparation. No train, level, registry or chunk is consulted on this worker. */
    public static final class Prepared {
        final UUID train,carriage;final int index;
        final byte[] witness;
        final List<Body> bodies=new ArrayList<>();
        public Prepared(CompoundTag snapshot) {
            var state=CreateTrainRepresentationState.prepare(snapshot);train=state.train();carriage=state.carriage();index=state.index();witness=fingerprint(state);
            var nativeData=state.nativeCarriage();var entity=nativeData.getCompound("Entity");
            if(!entity.getString("id").equals("create:carriage_contraption")||!entity.hasUUID("UUID")||!entity.hasUUID("TrainId")||!entity.getUUID("TrainId").equals(state.train())||!entity.contains("CarriageIndex",3)||entity.getInt("CarriageIndex")!=state.index())throw new IllegalStateException("Payload не принадлежит вагону");
            if(!nativeData.getCompound("Passengers").isEmpty()||!entity.getList("Passengers",10).isEmpty()||!entity.getCompound("Contraption").getList("SubContraptions",10).isEmpty())throw new IllegalStateException("Пассажиры и вложенные конструкции требуют совместного восстановления");
            long bytes=0;
            for(var raw:nativeData.getList("EntityPositioning",10)) {
                var position=(CompoundTag)raw;var anchor=position.getList("PositionAnchor",6);
                if(anchor.size()!=3)throw new IllegalStateException("Нет позиции представления вагона");
                for(int i=0;i<3;i++)if(!Double.isFinite(anchor.getDouble(i))||Math.abs(anchor.getDouble(i))>30000000)throw new IllegalStateException("Некорректная позиция вагона");
                var dimension=dimension(state.palette().getList("DimensionPalette",10).getCompound(position.getInt("Dim")).getString("Id"));
                var body=entity.copy();body.remove("Passengers");body.put("Pos",anchor.copy());
                // Native Carriage owns the cargo. These stale entity copies must not be decoded into it.
                body.getCompound("Contraption").remove("items");body.getCompound("Contraption").remove("fluids");
                bytes+=body.sizeInBytes();if(bytes>128L*1024*1024)throw new IllegalStateException("Подготовка представлений превышает 128 MiB");
                var address=new CompoundTag();address.putUUID("UUID",body.getUUID("UUID"));address.put("Pos",anchor.copy());
                bodies.add(new Body(dimension,address,new StagedContraptionRestore(body)));
            }
        }
    }
    private static ResourceKey<Level> dimension(String id){return ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(id));}
    /** Entity motion is owned by the live train, cargo is compared from every native positioning entry. */
    private static CompoundTag canonical(CreateTrainRepresentationState.Prepared state) {
        var tag=state.nativeCarriage().copy();var entity=tag.getCompound("Entity");var payload=entity.getCompound("Contraption").copy();
        payload.remove("items");payload.remove("fluids");tag.put("Entity",payload);
        var positions=new TreeMap<String,CompoundTag>();
        for(var raw:tag.getList("EntityPositioning",10)) {
            var position=((CompoundTag)raw).copy();String dim=state.palette().getList("DimensionPalette",10).getCompound(position.getInt("Dim")).getString("Id");
            position.remove("Dim");position.remove("TicksSinceLastExchange");positions.put(dim,position);
        }
        var dimensions=new CompoundTag();positions.forEach(dimensions::put);tag.put("EntityPositioning",dimensions);return tag;
    }
    private static byte[] fingerprint(CreateTrainRepresentationState.Prepared state) {
        try{var digest=java.security.MessageDigest.getInstance("SHA-256");
            try(var out=new java.io.DataOutputStream(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(),digest))){writeCanonical(canonical(state),out);}
            return digest.digest();
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException failure){throw new IllegalStateException("Не удалось проверить payload вагона",failure);}
    }
    private static void writeCanonical(Tag tag,java.io.DataOutput out)throws java.io.IOException {
        out.writeByte(tag.getId());
        if(tag instanceof CompoundTag compound){var keys=new TreeSet<>(compound.getAllKeys());out.writeInt(keys.size());for(var key:keys){out.writeUTF(key);writeCanonical(compound.get(key),out);}}
        else if(tag instanceof ListTag list){out.writeByte(list.getElementType());out.writeInt(list.size());for(var child:list)writeCanonical(child,out);}
        else tag.write(out);
    }
    public static final class Job {
        final MinecraftServer server;final Prepared prepared;final Train train;final Carriage carriage;final int version;
        final Object graph;final long epoch;final Map<ResourceKey<Level>,CarriageContraptionEntity> existing=new HashMap<>();
        final List<Body> missing=new ArrayList<>();int index,spawned;boolean checked,committed,compensating;
        public Job(MinecraftServer server,Prepared prepared) {
            if(!server.isSameThread())throw new IllegalStateException("Job требует серверный поток");
            this.server=server;this.prepared=prepared;train=Create.RAILWAYS.trains.get(prepared.train);
            if(train==null||prepared.index>=train.carriages.size())throw new IllegalStateException("Состав отсутствует или изменён");
            carriage=train.carriages.get(prepared.index);
            if(!((ContraptionIdentity)carriage).avilixlogger$identity().equals(prepared.carriage))throw new IllegalStateException("Вагон заменён");
            graph=train.graph;epoch=graph instanceof CreateTrackEpoch access?access.avilixlogger$epoch():0;version=carriage.storage.getVersion();
            verifyPayload();
            for(var body:prepared.bodies) {
                var dimensional=carriage.getDimensionalIfPresent(body.dimension());
                if(dimensional==null)throw new IllegalStateException("Представление больше не принадлежит вагону");
                var entity=dimensional.entity.get();
                if(entity!=null&&!entity.isRemoved()) {
                    if(!entity.getPassengers().isEmpty()||entity.getVehicle()!=null||!CreateLifecycleAudit.links(entity).isEmpty())throw new IllegalStateException("Представление получило внешние связи");
                    existing.put(body.dimension(),entity);
                }else {
                    var level=level(body);var position=body.nbt().getList("Pos",6);var pos=BlockPos.containing(position.getDouble(0),position.getDouble(1),position.getDouble(2));
                    if(!level.hasChunkAt(pos))throw new IllegalStateException("Сначала загрузите целевой чанк вагона: "+body.dimension().location());
                    if(level.getEntity(body.nbt().getUUID("UUID"))!=null)throw new IllegalStateException("UUID представления уже занят");
                    missing.add(body);
                }
            }
            if(missing.isEmpty())throw new IllegalStateException("Все представления вагона уже присутствуют");
            CartRestoreLocks.construction(train.id);CartRestoreLocks.resource(CreateTrainRegistry.key(train.id));CartRestoreLocks.resource(CreateTrainRegistry.RAILWAY_MUTATION);
            CartStorageAudit.lockMenus(train.id,server);
            existing.values().forEach(entity->{CartAuditContext.LOCKED_ENTITIES.add(entity.getUUID());CartRestoreLocks.keepLoaded((ServerLevel)entity.level(),entity.blockPosition());});
            for(var body:missing){var p=body.nbt().getList("Pos",6);CartRestoreLocks.keepLoaded(level(body),BlockPos.containing(p.getDouble(0),p.getDouble(1),p.getDouble(2)));CartAuditContext.LOCKED_ENTITIES.add(body.nbt().getUUID("UUID"));body.staged().sharedCarriage(carriage);}
        }
        private ServerLevel level(Body body){var level=server.getLevel(body.dimension());if(level==null)throw new IllegalStateException("Измерение недоступно");return level;}
        private void unchanged() {
            if(Create.RAILWAYS.trains.get(train.id)!=train||train.carriages.size()<=prepared.index||train.carriages.get(prepared.index)!=carriage||carriage.train!=train||carriage.storage.getVersion()!=version)throw new IllegalStateException("Состав или груз изменён во время восстановления");
            if(train.graph!=graph||graph instanceof com.simibubi.create.content.trains.graph.TrackGraph g&&(Create.RAILWAYS.trackNetworks.get(g.id)!=g||((CreateTrackEpoch)g).avilixlogger$epoch()!=epoch))throw new IllegalStateException("Путевой граф изменён во время восстановления");
            for(var body:missing){var dimensional=carriage.getDimensionalIfPresent(body.dimension());var entity=dimensional==null?null:dimensional.entity.get();
                if(dimensional==null||entity!=null&&!entity.isRemoved()&&entity!=body.staged().entity)throw new IllegalStateException("Представление заменено во время восстановления");
            }
            for(var entry:existing.entrySet())if(entry.getValue().getCarriage()!=carriage||!entry.getValue().trainId.equals(train.id)||entry.getValue().carriageIndex!=prepared.index||entry.getValue().isRemoved()||server.getLevel(entry.getKey())==null||server.getLevel(entry.getKey()).getEntity(entry.getValue().getUUID())!=entry.getValue()||carriage.getDimensionalIfPresent(entry.getKey())==null||carriage.getDimensionalIfPresent(entry.getKey()).entity.get()!=entry.getValue())throw new IllegalStateException("Существующее представление заменено или выгружено");
        }
        private void verifyPayload(){
            var actual=CreateTrainRepresentationState.prepare(CreateTrainRepresentationState.capture(carriage,server.overworld(),prepared.index));
            if(!java.security.MessageDigest.isEqual(fingerprint(actual),prepared.witness))throw new IllegalStateException("Позиции, конструкция или груз вагона изменились после снимка");
        }
        /** One bounded construction or publication step; caller retains reservations through compensation. */
        public boolean step() {
            if(compensating)return compensate();if(committed)return true;unchanged();
            if(index<missing.size()) {
                var body=missing.get(index);if(body.staged().step(level(body))) {
                    var entity=body.staged().entity;
                    if(!(entity instanceof CarriageContraptionEntity ce)||ce.getCarriage()!=carriage||ce.getContraption().getStorage()!=carriage.storage||!ce.getUUID().equals(body.nbt().getUUID("UUID")))throw new IllegalStateException("Потеряна общая связь вагона и груза");
                    index++;
                }return false;
            }
            if(!checked) {
                verifyPayload();for(var body:missing){var entity=body.staged().entity;var level=level(body);
                    if(level.getEntity(entity.getUUID())!=null||!level.getEntitiesOfClass(com.simibubi.create.content.contraptions.AbstractContraptionEntity.class,entity.getBoundingBox().inflate(.01)).isEmpty())throw new IllegalStateException("Место или UUID вагона заняты");
                    var dimensional=carriage.getDimensionalIfPresent(body.dimension());var found=dimensional==null?null:dimensional.entity.get();if(found!=null&&!found.isRemoved())throw new IllegalStateException("Представление уже восстановлено другим действием");
                }checked=true;return false;
            }
            if(spawned<missing.size()) {
                var body=missing.get(spawned);var entity=(CarriageContraptionEntity)body.staged().entity;var level=level(body);
                if(level.getEntity(entity.getUUID())!=null)throw new IllegalStateException("UUID представления занят во время публикации");
                // Count before calling mods: even an add callback that throws must be compensated.
                var dimensional=carriage.getDimensionalIfPresent(body.dimension());var found=dimensional==null?null:dimensional.entity.get();
                if(dimensional==null||found!=null&&!found.isRemoved())throw new IllegalStateException("Представление уже появилось во время публикации");
                spawned++;dimensional.entity=new WeakReference<>(entity);
                if(!level.addFreshEntity(entity))throw new IllegalStateException("Мир отклонил представление вагона");
                carriage.getDimensionalIfPresent(body.dimension()).alignEntity(entity);carriage.getDimensionalIfPresent(body.dimension()).updateRenderedCutoff();entity.syncCarriage();return false;
            }
            for(var body:missing){var entity=body.staged().entity;if(entity.isRemoved()||level(body).getEntity(entity.getUUID())!=entity||carriage.getDimensionalIfPresent(body.dimension()).entity.get()!=entity||entity.getContraption().getStorage()!=carriage.storage)throw new IllegalStateException("Представление не закрепилось в мире");}
            verifyPayload();committed=true;return true;
        }
        public int count(){return missing.size();}
        public boolean committed(){return committed;}
        public void cancel(){if(!committed)compensating=true;}
        private boolean compensate() {
            if(spawned>0){var body=missing.get(spawned-1);var entity=body.staged().entity;
                if(entity!=null&&!entity.isRemoved())((CartEntityAccess)entity).avilixlogger$discardForRollback();
                var dimensional=carriage.getDimensionalIfPresent(body.dimension());if(dimensional!=null&&dimensional.entity.get()==entity)dimensional.entity=new WeakReference<>(null);
                spawned--;return false;
            }return true;
        }
    }
}
