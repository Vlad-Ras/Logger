package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.industry.IndustrialAudit;
import com.roften.avilixlogger.compat.industry.IndustrialState;
import com.roften.avilixlogger.core.CaptureHandlers;
import com.roften.avilixlogger.core.CaptureTypePolicy;
import com.roften.avilixlogger.core.HotPathMetrics;
import com.roften.avilixlogger.core.LoggerServerData;
import com.roften.avilixlogger.net.S2CCaptureTypesPayload;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.items.ItemStackHandler;

/** Exercises the native NBT and network APIs without enabling production capture. */
public final class HandlerCaptureSmoke {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }

    public static final class BulkFermenterBlockEntity extends BlockEntity implements IndustrialState {
        public final ItemStackHandler inventory = new ItemStackHandler(9);
        private final IndustrialAudit.Data data = new IndustrialAudit.Data();

        BulkFermenterBlockEntity(BlockPos pos) { super(BlockEntityType.CHEST, pos, Blocks.CHEST.defaultBlockState()); }
        public IndustrialAudit.Data avilixlogger$industrial() { return data; }
    }

    public static void run(ServerLevel world) throws Exception {
        var previous = CaptureHandlers.disabled();
        long previousMask = CaptureTypePolicy.mask();
        try {
            var handler = CaptureHandlers.at(100);
            var keys = Set.of(handler.key(), "future.handler");
            var tag = new CompoundTag();
            tag.putLong("guiCaptureDisabledMask", 1L << 38);
            var settings = LoggerServerData.load(tag, world.registryAccess());
            check(settings.disabledHandlers().isEmpty() && settings.captureMask() == (1L << 38), "Legacy mask changed");
            settings.setCaptureSettings(settings.captureMask(), keys);
            var saved = settings.save(new CompoundTag(), world.registryAccess());
            var restored = LoggerServerData.load(saved, world.registryAccess());
            check(restored.disabledHandlers().equals(keys) && restored.captureMask() == (1L << 38), "World settings lost");
            check(!handler.enabled() && CaptureHandlers.at(101).enabled(), "Handler switch affects neighbour");

            var spawn = CaptureHandlers.ENTITY_SPAWN;
            var leave = CaptureHandlers.ENTITY_LEAVE;
            var toss = CaptureHandlers.CORE_LOGGEREVENTHANDLERS_ONITEMTOSS;
            settings.setCaptureSettings(0, Set.of(spawn.key(), leave.key(), toss.key()));
            var events = new com.roften.avilixlogger.core.LoggerEventHandlers();
            // Disabled callbacks must stop before looking up the world or capturing a snapshot.
            events.onEntitySpawn(null);
            events.onEntityLeave(null);
            events.onItemToss(null);
            settings.setCaptureSettings(restored.captureMask(), keys);
            check(spawn.enabled() && leave.enabled() && toss.enabled(), "Callbacks did not resume after re-enabling");

            var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), world.registryAccess());
            try {
                var payload = new S2CCaptureTypesPayload(settings.captureMask(), settings.captureRevision(),
                        true, true, "Настройки", List.copyOf(keys));
                S2CCaptureTypesPayload.STREAM_CODEC.encode(buffer, payload);
                check(S2CCaptureTypesPayload.STREAM_CODEC.decode(buffer).equals(payload), "Wire lost settings");
            } finally { buffer.release(); }

            var machine = new BulkFermenterBlockEntity(world.getSharedSpawnPos());
            machine.setLevel(world);
            machine.inventory.setStackInSlot(0, new ItemStack(Items.DIAMOND, 5));
            Class<?> raw = Class.forName("com.roften.avilixlogger.compat.industry.IndustrialAudit$PhysicalContents");
            var read = raw.getDeclaredMethod("read", BlockEntity.class);
            read.setAccessible(true);
            var same = raw.getDeclaredMethod("sameAs", raw);
            same.setAccessible(true);
            var encode = raw.getDeclaredMethod("encode", BlockEntity.class);
            encode.setAccessible(true);

            HotPathMetrics.start();
            for (int i = 0; i < 5000; i++) {
                var before = read.invoke(null, machine);
                var remainder = machine.inventory.insertItem(0, new ItemStack(Items.STONE), false);
                var after = read.invoke(null, machine);
                check(remainder.is(Items.STONE) && (boolean) same.invoke(before, after), "Refused insert changed inventory");
            }
            check(HotPathMetrics.snapshotCount() == 0, "Refused inserts encoded NBT");
            var before = read.invoke(null, machine);
            machine.inventory.extractItem(0, 2, false);
            var after = read.invoke(null, machine);
            check(!(boolean) same.invoke(before, after), "Real extraction was ignored");
            var first = (CompoundTag) encode.invoke(before, machine);
            var last = (CompoundTag) encode.invoke(after, machine);
            check(HotPathMetrics.snapshotCount() == 2, "Positive encoding control inactive");
            check(first.getList("StoredItems", 10).getCompound(0).getCompound("Stack").getInt("count") == 5
                    && last.getList("StoredItems", 10).getCompound(0).getCompound("Stack").getInt("count") == 3,
                    "Detached before/after evidence changed");
            System.out.println("HANDLER_CAPTURE_NATIVE_OK: saved settings, wire, unchanged inventory, exact extraction evidence");
        } finally {
            HotPathMetrics.stop();
            CaptureHandlers.install(previous);
            CaptureTypePolicy.install(previousMask);
        }
    }
}
