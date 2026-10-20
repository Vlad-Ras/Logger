package com.roften.avilixlogger.compat.create;
import com.simibubi.create.content.trains.entity.Train;
import java.util.UUID;
public interface CreateRailwayAccess {
    int avilixlogger$moving(UUID id);
    int avilixlogger$waiting(UUID id);
    void avilixlogger$install(Train train,int moving,int waiting);
    void avilixlogger$remove(UUID id);
}
