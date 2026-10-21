package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import java.util.*;

/** One native controller call, including final BE/storage state after unmount. */
public final class CreateStructuralAudit implements CreateStructuralHooks.Observer {
    private static final ThreadLocal<Scope> CURRENT=new ThreadLocal<>();
    private record Address(ServerLevel level,BlockPos pos){}
    private CreateStructuralAudit(){}
    public static void install(){CreateStructuralHooks.install(new CreateStructuralAudit());}
    public static boolean covered(AbstractContraptionEntity entity){var scope=CURRENT.get();return scope!=null&&scope.bodies.containsKey(entity.getUUID());}
    public static boolean controllerLocked(BlockEntity be){
        if(CartRestoreLocks.blockLocked(be.getLevel(),be.getBlockPos()))return true;
        return be instanceof CreateControllerAccess access&&access.avilixlogger$controlled().stream().anyMatch(CreateCargoAudit::restoreLocked);
    }
    public static boolean coupled(){var scope=CURRENT.get();return scope!=null&&!scope.carts.isEmpty();}
    public static boolean cartCovered(com.simibubi.create.content.contraptions.minecart.capability.MinecartController controller){var scope=CURRENT.get();return scope!=null&&scope.carts.containsKey(controller);}
    public static Scope beginCoupled(com.simibubi.create.content.contraptions.minecart.capability.MinecartController controller){
        if(CURRENT.get()!=null||!CreateCartAudit.enabled()||!controller.hasContraptionCoupling(true)&&!controller.hasContraptionCoupling(false))return null;
        try{return captureCoupled(controller,true);}catch(RuntimeException failure){CreateCartAudit.failed("coupled structural before",failure);return null;}
    }
    public static Scope captureCoupled(com.simibubi.create.content.contraptions.minecart.capability.MinecartController controller){return captureCoupled(controller,false);}
    private static Scope captureCoupled(com.simibubi.create.content.contraptions.minecart.capability.MinecartController controller,boolean publish){
        if(CURRENT.get()!=null||controller.cart()==null||!(controller.cart().level() instanceof ServerLevel level)||!level.getServer().isSameThread())throw new IllegalStateException("Нет серверного scope расцепления");
        var scope=new Scope(publish);var targets=new HashSet<UUID>();for(boolean main:new boolean[]{true,false})if(controller.hasContraptionCoupling(main)){targets.add(controller.cart().getUUID());targets.add(controller.getCoupledCart(main));}
        for(var member:CreateCartRegistry.graph(controller)){var cart=member.cart();scope.carts.put(member,CreateCartRegistry.snapshot(member));scope.charge(scope.carts.get(member));var body=CreateCartAudit.passenger(cart);if(body!=null)scope.participants.add(body);if(cart.getPassengers().size()>1)scope.complete=false;}
        for(var member:scope.carts.keySet())if(targets.contains(member.cart().getUUID())){var body=CreateCartAudit.passenger(member.cart());if(body==null&&!member.cart().getPassengers().isEmpty())scope.complete=false;if(body!=null){if(member.cart().getPassengers().getFirst()!=body)scope.complete=false;scope.body(body);}}
        if(scope.bodies.isEmpty())throw new IllegalStateException("Нет разобранных тел сцепки");var first=scope.bodies.values().iterator().next().entity;scope.previousSource=MutationSourceResolver.enter(CreateContraptionSources.kind(first).source(CreateContraptionSources.id(first),"structural_disassemble"));CURRENT.set(scope);return scope;
    }
    public static Scope beginTrain(com.simibubi.create.content.trains.entity.Train train){
        // Create refuses a train with an unloaded carriage or two portal representations.
        // Skip the expensive world snapshot for an operation that cannot begin.
        if(!train.canDisassemble())return null;
        if(CURRENT.get()!=null||!CreateCartAudit.enabled())return null;
        try{return captureTrain(train,true);}catch(RuntimeException failure){CreateCartAudit.failed("train before disassembly",failure);return null;}
    }
    public static Scope captureTrain(com.simibubi.create.content.trains.entity.Train train){return captureTrain(train,false);}
    private static Scope captureTrain(com.simibubi.create.content.trains.entity.Train train,boolean publish){
        if(CURRENT.get()!=null)throw new IllegalStateException("Уже захватывается структурная операция");
        var first=train.carriages.stream().map(c->c.anyAvailableEntity()).filter(Objects::nonNull).findFirst().orElseThrow(()->new IllegalStateException("Все представления поезда выгружены"));
        if(!(first.level() instanceof ServerLevel level)||!level.getServer().isSameThread())throw new IllegalStateException("Нет серверного потока поезда");
        var scope=new Scope(publish);scope.train=train;scope.trainBefore=CreateTrainRegistry.snapshot(train,level);scope.charge(scope.trainBefore);
        for(var carriage:train.carriages){if(carriage.presentInMultipleDimensions())scope.complete=false;carriage.forEachPresentEntity(scope::body);
            var entity=carriage.anyAvailableEntity();if(entity==null){scope.complete=false;continue;}
            if(entity.getControllingPlayer().isPresent())scope.complete=false;
            for(var bogey:carriage.bogeys){if(bogey==null)continue;var pos=bogey.getAnchorPosition();if(pos!=null)scope.block((ServerLevel)entity.level(),BlockPos.containing(pos));}
        }
        if(scope.bodies.isEmpty())throw new IllegalStateException("Нет загруженных вагонов");
        if(scope.trainBefore.getCompound("Native").getList("Carriages",10).stream().map(raw->(CompoundTag)raw).anyMatch(c->!c.getCompound("Passengers").isEmpty()))scope.complete=false;
        if(scope.trainBefore.contains("Station",10)){var station=scope.trainBefore.getCompound("Station");var stationLevel=level.getServer().getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,net.minecraft.resources.ResourceLocation.parse(station.getString("Dimension"))));if(stationLevel==null)throw new IllegalStateException("Измерение станции недоступно");scope.block(stationLevel,BlockPos.of(station.getLong("Pos")));}
        scope.previousSource=MutationSourceResolver.enter(CreateContraptionSources.kind(first).source(CreateContraptionSources.id(first),"structural_disassemble"));CURRENT.set(scope);return scope;
    }
    public static Scope begin(BlockEntity controller){
        if(CURRENT.get()!=null||!CreateCartAudit.enabled())return null;
        try{return controller instanceof CreateControllerAccess access&&access.avilixlogger$controlled().stream().anyMatch(Entity::isAlive)?capture(controller,true):null;}
        catch(RuntimeException failure){CreateCartAudit.failed("structural before controller",failure);return null;}
    }
    /** Detached fixture exercises the same capture, without authorizing publication. */
    public static Scope capture(BlockEntity controller){return capture(controller,false);}
    public static Scope captureRemoval(BlockEntity controller){var scope=capture(controller,false);scope.externalRemoval=true;return scope;}
    @Override public CreateStructuralHooks.Capture removal(Level world,BlockPos pos,net.minecraft.world.level.block.state.BlockState replacement){
        if(CURRENT.get()!=null||CartAuditContext.restoring()||!(world instanceof ServerLevel level)||!level.getServer().isSameThread())return null;
        if(!CreateCartAudit.enabled())return null;
        var controller=removalController(level,pos,replacement);if(controller==null)return null;
        var scope=begin(controller);if(scope!=null)scope.externalRemoval=true;return scope;
    }
    private static BlockEntity removalController(ServerLevel level,BlockPos pos,net.minecraft.world.level.block.state.BlockState replacement){
        // An active native controller holds live entity references and therefore is
        // already instantiated in its loaded chunk. Ordinary blocks need no state /
        // hasBlockEntity / getBlock probes, nor lazy block-entity creation here.
        if(!level.hasChunkAt(pos))return null;
        var controller=level.getChunkAt(pos).getBlockEntities().get(pos);
        if(!(controller instanceof CreateControllerAccess access)||access.avilixlogger$controlled().stream().noneMatch(Entity::isAlive))return null;
        if(controller.getBlockState().getBlock()==replacement.getBlock())return null;
        return controller;
    }
    private static Scope capture(BlockEntity controller,boolean publish){
        if(CURRENT.get()!=null||!(controller.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread()||!(controller instanceof CreateControllerAccess access))throw new IllegalStateException("Нет серверного scope контроллера");
        var scope=new Scope(publish);
        scope.controller(level,controller);
        if(scope.bodies.isEmpty())throw new IllegalStateException("Контроллер не содержит конструкцию");
        if(access.avilixlogger$runtime().getBoolean("Removing"))scope.complete=false;
        var first=scope.bodies.values().iterator().next().entity;
        scope.previousSource=MutationSourceResolver.enter(CreateContraptionSources.kind(first).source(CreateContraptionSources.id(first),"structural_disassemble"));CURRENT.set(scope);return scope;
    }
    public boolean before(Level level,BlockPos pos){
        var scope=CURRENT.get();if(scope==null||CartAuditContext.restoring()||!(level instanceof ServerLevel server)||!server.getServer().isSameThread())return false;
        try{scope.block(server,pos);return scope.blocks.containsKey(new Address(server,pos));}catch(RuntimeException failure){scope.complete=false;CreateCartAudit.failed("structural world snapshot",failure);return false;}
    }
    public static void join(EntityJoinLevelEvent event){
        var scope=CURRENT.get();if(scope==null||event.isCanceled()||event.getEntity().level().isClientSide||event.loadedFromDisk())return;
        var entity=event.getEntity();if(scope.spawned.size()>=512){scope.complete=false;return;}scope.spawned.add(entity);
    }
    public static CompoundTag world(ServerLevel level,BlockPos pos){
        if(!level.hasChunkAt(pos))throw new IllegalStateException("Чанк структурного снимка не загружен");
        var tag=new CompoundTag();tag.putString("Dimension",level.dimension().location().toString());tag.putLong("Pos",pos.asLong());tag.put("State",NbtUtils.writeBlockState(level.getBlockState(pos)));
        var be=level.getBlockEntity(pos);var snapshot=NbtSerde.snapshotBlockEntity(level,be);if(be!=null&&snapshot==null)throw new IllegalStateException("Нет полного NBT блока");
        if(snapshot!=null)tag.put("BE",snapshot);if(be instanceof CreateKineticAccess kinetic)tag.put("KineticRuntime",kinetic.avilixlogger$kineticRuntime());if(be instanceof CreateControllerAccess controller){var runtime=controller.avilixlogger$runtime();if(be instanceof CreateControllerRuntimeAccess extra)runtime.put("Extra",extra.avilixlogger$extraRuntime());tag.put("Runtime",runtime);}var slots=ContainerSlotSnapshot.snapshotTag(level,pos);if(slots!=null)tag.put("Slots",slots);return tag;
    }
    public static CompoundTag world(ServerLevel level,BlockPos pos,CompoundTag template){
        var tag=world(level,pos);if(template.contains("KineticNetworks",9)){var networks=new ListTag();for(var raw:template.getList("KineticNetworks",10)){var network=(CompoundTag)raw;networks.add(CreateKineticRegistry.snapshot(level,CreateKineticRegistry.validate(network)));}tag.put("KineticNetworks",networks);}if(template.contains("ElevatorColumn",10))tag.put("ElevatorColumn",CreateElevatorRegistry.snapshot(level,CreateElevatorRegistry.validate(template.getCompound("ElevatorColumn"))));return tag;
    }
    private record Body(AbstractContraptionEntity entity,CompoundTag before){}
    public static final class Scope implements CreateStructuralHooks.Capture {
        final UUID operation=UUID.randomUUID();final boolean publish;final Map<UUID,Body> bodies=new LinkedHashMap<>();
        final Set<String> networks=new HashSet<>();final Map<com.simibubi.create.content.contraptions.minecart.capability.MinecartController,CompoundTag> carts=new LinkedHashMap<>();final Set<AbstractContraptionEntity> participants=new LinkedHashSet<>();ListTag cartsAfter;
        final Map<Address,CompoundTag> blocks=new LinkedHashMap<>();final Set<Address> controllers=new LinkedHashSet<>();final List<Entity> spawned=new ArrayList<>();
        com.simibubi.create.content.trains.entity.Train train;CompoundTag trainBefore,trainAfter;
        boolean complete=true,success,closed,externalRemoval;String previousSource;CompoundTag before,after;List<LogEntry> rows=List.of();long bytes;
        Scope(boolean publish){this.publish=publish;}
        void body(AbstractContraptionEntity entity){
            if(entity==null||entity.isRemoved()||entity.getContraption()==null||bodies.containsKey(entity.getUUID()))return;
            if(bodies.size()>=64)throw new IllegalStateException("Больше 64 связанных конструкций");
            var tag=CreateLifecycleAudit.snapshot(entity,"entity");
            if(entity instanceof CreateOrientationAccess&&(entity instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity||!carts.isEmpty())){var box=entity.getBoundingBox();var bounds=new ListTag();for(double value:new double[]{box.minX,box.minY,box.minZ,box.maxX,box.maxY,box.maxZ})bounds.add(DoubleTag.valueOf(value));tag.put("Bounds",bounds);tag.put("Orientation",((CreateOrientationAccess)entity).avilixlogger$orientation());}
            charge(tag);bodies.put(entity.getUUID(),new Body(entity,tag));participants.add(entity);
            for(var passenger:entity.getPassengers()){if(passenger instanceof AbstractContraptionEntity child)body(child);else complete=false;}
            // An external cart/train requires its own registry/controller transaction.
            if(entity.getVehicle()!=null&&!(entity.getVehicle() instanceof AbstractContraptionEntity)&&carts.keySet().stream().noneMatch(c->c.cart()==entity.getVehicle()))complete=false;
        }
        void controller(ServerLevel level,BlockEntity controller){
            var pending=new ArrayDeque<BlockEntity>();pending.add(controller);
            while(!pending.isEmpty()){
                var current=pending.removeFirst();var address=new Address(level,current.getBlockPos().immutable());
                if(controllers.contains(address))continue;
                if(controllers.size()>=64||!(current instanceof CreateControllerAccess access))throw new IllegalStateException("Неполный граф контроллеров");
                block(level,current.getBlockPos());controllers.add(address);
                if(current instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic&&kinetic.network!=null&&networks.add(CreateKineticRegistry.key(level,kinetic.network))){
                    var registry=CreateKineticRegistry.snapshot(level,kinetic.network);charge(registry);var captured=new ListTag();captured.add(registry);blocks.get(address).put("KineticNetworks",captured);
                    var network=CreateKineticRegistry.networks(level).get(kinetic.network);
                    if(network==null)throw new IllegalStateException("Кинетический реестр контроллера отсутствует");
                    for(var member:network.members.keySet()){if(member.getLevel()!=level||member.isRemoved())throw new IllegalStateException("Внешний участник кинетической сети");block(level,member.getBlockPos());}
                }
                for(var body:access.avilixlogger$controlled()){
                    body(body);
                    if(body.getContraption() instanceof com.simibubi.create.content.contraptions.elevator.ElevatorContraption elevator){
                        var registry=CreateElevatorRegistry.snapshot(level,elevator.getGlobalColumn());charge(registry);blocks.get(address).put("ElevatorColumn",registry);
                        var column=com.simibubi.create.content.contraptions.elevator.ElevatorColumn.get(level,elevator.getGlobalColumn());
                        if(column==null||!column.isActive())throw new IllegalStateException("Активная колонка лифта отсутствует");
                        for(var contact:column.getContacts())block(level,contact);
                    }
                }
                var data=blocks.get(address).getCompound("BE");var peers=new ArrayList<BlockPos>();
                if(data.contains("MirrorParent"))peers.add(NbtUtils.readBlockPos(data,"MirrorParent").orElseThrow(()->new IllegalStateException("Повреждён адрес родительского шкива")));
                for(var raw:data.getList("MirrorChildren",10))peers.add(NbtUtils.readBlockPos((CompoundTag)raw,"Pos").orElseThrow(()->new IllegalStateException("Повреждён адрес ведомого шкива")));
                for(var pos:peers){if(!level.hasChunkAt(pos))throw new IllegalStateException("Связанный контроллер выгружен");
                    var peer=level.getBlockEntity(pos);if(peer==null||peer.getType()!=current.getType())throw new IllegalStateException("Связанный контроллер заменён");pending.add(peer);}
            }
        }
        void block(ServerLevel level,BlockPos pos){var address=new Address(level,pos.immutable());if(blocks.containsKey(address))return;
            if(blocks.size()>=8192){complete=false;return;}var tag=world(level,pos);charge(tag);blocks.put(address,tag);}
        void charge(CompoundTag tag){bytes+=tag.sizeInBytes();if(bytes>128L*1024*1024)throw new IllegalStateException("Снимок разборки превышает 128 MiB");}
        public void success(){success=true;}
        public CompoundTag before(){if(!closed)throw new IllegalStateException("Разборка ещё идёт");return before.copy();}
        public CompoundTag after(){if(!closed)throw new IllegalStateException("Разборка ещё идёт");return after.copy();}
        public List<LogEntry> rows(){return rows;}
        public void close(){if(closed)return;closed=true;CURRENT.remove();
            try{finish();}catch(RuntimeException failure){CreateCartAudit.failed("structural after controller",failure);try{incomplete(failure);}catch(RuntimeException fallback){CreateCartAudit.failed("structural incomplete marker",fallback);}}
            finally{MutationSourceResolver.restore(previousSource);}
        }
        private void incomplete(RuntimeException failure){
            complete=false;var oldBodies=new ListTag();var newBodies=new ListTag();
            for(var body:bodies.values()){
                var tag=new CompoundTag();for(var name:List.of("Construction","ConstructionKind","StorageEntity","Dimension"))tag.put(name,body.before.get(name).copy());
                tag.putString("Form","entity");oldBodies.add(tag);var after=tag.copy();after.putString("Form",body.entity.isRemoved()?"blocks":"entity");newBodies.add(after);
            }
            before=envelope(oldBodies,new ListTag(),new ListTag(),true);after=envelope(newBodies,new ListTag(),new ListTag(),false);
            var message=Objects.toString(failure.getMessage(),failure.getClass().getSimpleName());after.putString("Failure",message.substring(0,Math.min(512,message.length())));
            var result=new ArrayList<LogEntry>();var ids=new HashSet<UUID>();
            for(var participant:participants)if(ids.add(CreateContraptionSources.id(participant))){var row=CreateCargoAudit.row(participant,success?ActionType.CART_DISASSEMBLE:ActionType.CART_TRANSFER,"structural_disassemble",before,after,"operation="+operation+"; complete=false; snapshot failure");result.add(row);if(publish)CreateCargoAudit.deliver(participant,row);}
            rows=List.copyOf(result);
        }
        private void finish(){
            if(!carts.isEmpty()){cartsAfter=new ListTag();for(var controller:carts.keySet()){var tag=CreateCartRegistry.snapshot(controller);charge(tag);cartsAfter.add(tag);}}
            if(train!=null){trainAfter=CreateTrainRegistry.after(trainBefore,train);charge(trainAfter);}
            var oldBodies=new ListTag();var newBodies=new ListTag();boolean changed=false;
            for(var body:bodies.values()){
                oldBodies.add(body.before);var now=CreateLifecycleAudit.snapshot(body.entity,body.entity.isRemoved()?"blocks":"entity");charge(now);newBodies.add(now);
                changed|=body.entity.isRemoved();if(!body.entity.isRemoved())complete=false;
            }
            if(!changed&&success){before=envelope(oldBodies,new ListTag(),new ListTag(),true);after=before.copy();return;}
            var oldWorld=new ListTag();var newWorld=new ListTag();
            for(var entry:blocks.entrySet()){
                var address=entry.getKey();
                if(address.level.getBlockEntity(address.pos) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic&&kinetic.network!=null&&!networks.contains(CreateKineticRegistry.key(address.level,kinetic.network)))complete=false;
                var now=world(address.level,address.pos,entry.getValue());charge(now);
                var old=entry.getValue().copy();if(controllers.contains(address)){
                    old.putBoolean("Controller",true);now.putBoolean("Controller",true);
                    if(externalRemoval&&!old.getCompound("State").getString("Name").equals(now.getCompound("State").getString("Name"))){
                        old.putBoolean("ControllerRemoved",true);now.putBoolean("ControllerRemoved",true);
                        if(now.contains("Runtime",10))complete=false;
                    }
                }oldWorld.add(old);newWorld.add(now);
            }
            var effects=new ListTag();
            for(var entity:spawned){
                if(entity.isRemoved()){complete=false;continue;}
                String type=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
                if(!Set.of("minecraft:item","create:super_glue").contains(type))complete=false;
                var nbt=CreateCartAudit.save(entity);charge(nbt);var effect=new CompoundTag();effect.putString("Dimension",entity.level().dimension().location().toString());effect.putString("EntityType",type);effect.putUUID("Entity",entity.getUUID());effect.put("NBT",nbt);effects.add(effect);
            }
            before=envelope(oldBodies,oldWorld,new ListTag(),true);after=envelope(newBodies,newWorld,effects,false);
            long histories=participants.stream().map(CreateContraptionSources::id).distinct().count();
            if(before.sizeInBytes()+after.sizeInBytes()>128L*1024*1024/Math.max(1,histories))throw new IllegalStateException("Общий размер снимков всех историй превышает 128 MiB");
            var result=new ArrayList<LogEntry>();var ids=new HashSet<UUID>();
            for(var participant:participants)if(ids.add(CreateContraptionSources.id(participant))){
                var row=CreateCargoAudit.row(participant,success?ActionType.CART_DISASSEMBLE:ActionType.CART_TRANSFER,"structural_disassemble",before,after,"operation="+operation+"; bodies="+bodies.size()+"; world="+blocks.size()+"; complete="+after.getBoolean("Complete")+"; role="+(bodies.containsKey(participant.getUUID())?"disassembled_body":"surviving_coupling_peer"));
                result.add(row);if(publish)CreateCargoAudit.deliver(participant,row);
            }rows=List.copyOf(result);
        }
        private CompoundTag envelope(ListTag entities,ListTag world,ListTag effects,boolean previous){
            var tag=new CompoundTag();tag.putInt("Format",3);tag.putString("Kind","structural");tag.putUUID("Operation",operation);tag.putBoolean("Complete",complete&&success);tag.put("Bodies",entities);tag.put("World",world);tag.put("Effects",effects);if(!carts.isEmpty()){var values=new ListTag();if(previous||cartsAfter==null)for(var value:carts.values())values.add(value.copy());else values=cartsAfter.copy();tag.put("Carts",values);}if(trainBefore!=null)tag.put("Train",(previous?trainBefore:trainAfter==null?trainBefore:trainAfter).copy());return tag;
        }
    }
    public static void clear(){CURRENT.remove();}
}
