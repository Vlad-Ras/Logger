import java.util.List;

public final class ProfileSummaryTest {
    private static ProfileSummary.Frame frame(String type,String method){return new ProfileSummary.Frame(type,method);}
    private static void expect(ProfileSummary.Ownership wanted,ProfileSummary.Frame... frames){
        if(ProfileSummary.ownership(List.of(frames))!=wanted)throw new AssertionError("Incorrect attribution: "+List.of(frames));
    }
    public static void main(String[] args) {
        var wrapper=frame("net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity","avilixlogger$withSource");
        expect(ProfileSummary.Ownership.WRAPPED_SIMULATION,frame("some.machine.ModBlockEntity","tick"),wrapper);
        expect(ProfileSummary.Ownership.LOGGER_SELF,wrapper);
        expect(ProfileSummary.Ownership.WRAPPED_SIMULATION,frame("some.machine.ModBlockEntity","tick"),frame("net.minecraft.ServerLevel","wrapMethod$123$avilixlogger$withSource"));
        expect(ProfileSummary.Ownership.LOGGER_CALLED,frame("some.machine.ModBlockEntity","saveAdditional"),frame("com.roften.avilixlogger.core.NbtSerde","snapshotBlockEntity"),wrapper);
        expect(ProfileSummary.Ownership.LOGGER_CALLED,frame("java.lang.StringBuilder","append"),frame("com.roften.avilixlogger.core.LogEntry","materializeSnapshots"));
        expect(ProfileSummary.Ownership.LOGGER_SELF,frame("com.roften.avilixlogger.core.CauseContext","peek"),wrapper);
        expect(ProfileSummary.Ownership.UNATTRIBUTED,frame("some.machine.ModBlockEntity","tick"));
        System.out.println("PROFILE_OWNERSHIP_OK: Logger execution, callbacks initiated by Logger, native simulation below wrappers and unrelated samples remain distinct");
    }
}
