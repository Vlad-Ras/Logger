package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.nio.file.*;
import java.util.List;

final class RecoveryJournalTest {
    static void run() throws Exception {
        Path directory=Files.createTempDirectory("avilix-recovery-journal-");
        try {
            var air=new CompoundTag();air.putString("Name","minecraft:air");
            var chest=new CompoundTag();chest.putString("Name","minecraft:chest");
            var be=new CompoundTag();be.putString("id","minecraft:chest");be.putByteArray("Probe",new byte[8192]);
            var before=new RollbackEngine.ReplayCheckpoint(air,null,null);
            var after=new RollbackEngine.ReplayCheckpoint(chest,be,null);
            var block=new RecoveryJournal.Block("minecraft:overworld",new BlockPos(-17,180,48),before,after);
            Path file=directory.resolve("valid.scene");
            RecoveryJournal.write(file,List.of(block));
            check(RecoveryJournal.read(file).equals(List.of(block)),"durable NBT roundtrip changed before/after/address");
            byte[] data=Files.readAllBytes(file);data[data.length-1]^=1;
            Path broken=directory.resolve("corrupt.scene");Files.write(broken,data);
            refused(broken,"checksum corruption");
            Files.write(broken,java.util.Arrays.copyOf(data,data.length-17));refused(broken,"truncation");
            Path duplicate=directory.resolve("duplicate.scene");RecoveryJournal.write(duplicate,List.of(block,block));
            refused(duplicate,"duplicate address");
            var wrongItems=new net.minecraft.nbt.ListTag();wrongItems.add(net.minecraft.nbt.StringTag.valueOf("not an item compound"));
            var slots=new CompoundTag();slots.putInt("Size",27);slots.put("Items",wrongItems);
            check(!ContainerSlotSnapshot.isValid(slots),"malformed nonempty slots became a valid empty inventory");
            Path invalidSlots=directory.resolve("invalid-slots.scene");
            RecoveryJournal.write(invalidSlots,List.of(new RecoveryJournal.Block(block.dimension(),block.pos(),before,new RollbackEngine.ReplayCheckpoint(chest,be,slots))));
            refused(invalidSlots,"incorrect slot element type");
            Path invalidBlocks=directory.resolve("invalid-blocks.scene");
            recodeBlocks(file,invalidBlocks);refused(invalidBlocks,"incorrect scene element type");
            try(var paths=Files.list(directory)){check(paths.noneMatch(path->path.toString().endsWith(".tmp")),"temporary journal retained after publication");}
            System.out.println("RECOVERY_JOURNAL_IO_OK: durable exact before/after, checksum/truncation/duplicate/wrong-list refusal, atomic publication, no temporary files");
        } finally {
            try(var paths=Files.walk(directory)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    private static void recodeBlocks(Path original,Path result) throws Exception {
        var in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(Files.readAllBytes(original)));
        int header=in.readInt();var root=net.minecraft.nbt.NbtIo.read(in,net.minecraft.nbt.NbtAccounter.create(1024*1024));
        var list=new net.minecraft.nbt.ListTag();list.add(net.minecraft.nbt.StringTag.valueOf("not a block compound"));root.put("Blocks",list);
        var buffer=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(buffer);
        out.writeInt(header);net.minecraft.nbt.NbtIo.write(root,out);out.flush();
        var digest=java.security.MessageDigest.getInstance("SHA-256").digest(buffer.toByteArray());out.write(digest);out.flush();Files.write(result,buffer.toByteArray());
    }
    private static void refused(Path file,String why) throws Exception {
        try {RecoveryJournal.read(file);throw new AssertionError("Accepted "+why);}
        catch(java.io.IOException expected) {}
    }
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
}
