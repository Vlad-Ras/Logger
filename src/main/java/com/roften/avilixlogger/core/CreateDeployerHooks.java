package com.roften.avilixlogger.core;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
/** Optional bridge; only an active deployer action captures world endpoints. */
public final class CreateDeployerHooks {
    public interface Observer {boolean before(Level level,BlockPos pos);}
    private static Observer observer;
    private CreateDeployerHooks(){}
    public static void install(Observer value){observer=value;}
    public static boolean before(Level level,BlockPos pos){return observer!=null&&observer.before(level,pos);}
}
