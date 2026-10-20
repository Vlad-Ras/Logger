package com.roften.avilixlogger.core;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
/** Nullable optional bridge; ordinary setBlock calls allocate no audit state. */
public final class CreateStructuralHooks {
    public interface Capture extends AutoCloseable {void success();void close();}
    public interface Observer {
        boolean before(Level level,BlockPos pos);
        default Capture removal(Level level,BlockPos pos,net.minecraft.world.level.block.state.BlockState replacement){return null;}
    }
    private static Observer observer;
    private CreateStructuralHooks(){}
    public static void install(Observer value){observer=value;}
    public static boolean before(Level level,BlockPos pos){return observer!=null&&observer.before(level,pos);}
    public static Capture removal(Level level,BlockPos pos,net.minecraft.world.level.block.state.BlockState replacement){return observer==null?null:observer.removal(level,pos,replacement);}
}
