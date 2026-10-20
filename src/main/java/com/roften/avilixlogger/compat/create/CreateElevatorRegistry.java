package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.CartRestoreLocks;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn.ColumnCoords;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import java.util.HashSet;

/** Server-thread column registry endpoint, captured together with its controller. */
public final class CreateElevatorRegistry {
    private CreateElevatorRegistry() {}
    public static String key(LevelAccessor level,ColumnCoords coords) {
        if(!(level instanceof ServerLevel server))return "";
        return server.dimension().location()+"/elevator/"+coords.x()+"/"+coords.z()+"/"+coords.side().getName();
    }
    public static boolean locked(LevelAccessor level,ColumnCoords coords) {return CartRestoreLocks.resourceLocked(key(level,coords));}
    public static CompoundTag snapshot(ServerLevel level,ColumnCoords coords) {
        var tag=new CompoundTag();tag.put("Coords",coords.write());
        var column=ElevatorColumn.get(level,coords);tag.putBoolean("Exists",column!=null);
        if(column!=null)tag.put("State",((CreateElevatorColumnAccess)column).avilixlogger$column());
        return tag;
    }
    public static ColumnCoords validate(CompoundTag tag) {
        if(!tag.contains("Coords",10)||!tag.contains("Exists",1))throw new IllegalStateException("Нет полного снимка колонки лифта");
        var coords=ColumnCoords.read(tag.getCompound("Coords"));
        if(coords==null||coords.side().getAxis().isVertical()||!coords.write().equals(tag.getCompound("Coords")))throw new IllegalStateException("Повреждён адрес колонки лифта");
        if(tag.getBoolean("Exists")) {
            var state=tag.getCompound("State");
            if(!state.contains("Contacts",11)||!state.contains("Active",1)||!state.contains("TargetAvailable",1)||!state.contains("TargetY",3)||!state.contains("NamesVersion",3))throw new IllegalStateException("Неполное состояние колонки лифта");
            var seen=new HashSet<Integer>();int previous=Integer.MIN_VALUE;
            if(state.getIntArray("Contacts").length>8192)throw new IllegalStateException("Слишком много контактов лифта");
            for(int y:state.getIntArray("Contacts")){if(!seen.add(y)||y<previous)throw new IllegalStateException("Повторные или неупорядоченные контакты лифта");previous=y;}
        }else if(tag.contains("State"))throw new IllegalStateException("Отсутствующая колонка содержит состояние");
        return coords;
    }
    public static void reserve(ServerLevel level,CompoundTag tag) {CartRestoreLocks.resource(key(level,validate(tag)));}
    public static void restore(ServerLevel level,CompoundTag tag) {
        var coords=validate(tag);var columns=ElevatorColumn.LOADED_COLUMNS.get(level);
        if(!tag.getBoolean("Exists")){columns.remove(coords);return;}
        var column=columns.computeIfAbsent(coords,c->new ElevatorColumn(level,c));
        ((CreateElevatorColumnAccess)column).avilixlogger$column(tag.getCompound("State"));
        column.markDirty();
        if(!snapshot(level,coords).equals(tag))throw new IllegalStateException("Колонка лифта не восстановлена полностью");
    }
}
