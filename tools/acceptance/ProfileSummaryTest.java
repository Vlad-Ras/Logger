import java.util.List;

public final class ProfileSummaryTest {
    private static ProfileSummary.Frame frame(String type,String method){return new ProfileSummary.Frame(type,method);}
    private static void expect(ProfileSummary.Ownership wanted,ProfileSummary.Frame... frames){
        if(ProfileSummary.ownership(List.of(frames))!=wanted)throw new AssertionError("Incorrect attribution: "+List.of(frames));
    }
    @jdk.jfr.Name("avilixlogger.ProfileFixtureMarker")
    static final class Marker extends jdk.jfr.Event {}
    private static void roundTrip() throws Exception {
        var file=java.nio.file.Files.createTempFile("logger-profile-fixture-",".jfr");
        var thread=Thread.currentThread();String name=thread.getName();
        try {
            thread.setName("Server thread");
            try(var recording=new jdk.jfr.Recording()) {
                recording.enable(Marker.class);recording.enable("jdk.ExecutionSample").withPeriod(java.time.Duration.ofMillis(1));recording.start();
                new Marker().commit();long until=System.nanoTime()+50_000_000L;while(System.nanoTime()<until)Thread.onSpinWait();new Marker().commit();
                recording.stop();recording.dump(file);
            }
            var bytes=new java.io.ByteArrayOutputStream();var previous=System.out;
            try(var capture=new java.io.PrintStream(bytes,true,java.nio.charset.StandardCharsets.UTF_8)) {
                System.setOut(capture);ProfileSummary.main(new String[]{file.toString()});
            }finally{System.setOut(previous);}
            String json=bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
            if(!json.contains("\"schema\":2")||!json.contains("server_execution_samples_wrapped_simulation")||!json.contains("\"server_tick_reported_average_ms\":null"))throw new AssertionError("Real JFR was not summarized with explicit missing Minecraft metrics");
        }finally{thread.setName(name);java.nio.file.Files.deleteIfExists(file);}
    }
    public static void main(String[] args) throws Exception {
        var wrapper=frame("net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity","avilixlogger$withSource");
        expect(ProfileSummary.Ownership.WRAPPED_SIMULATION,frame("some.machine.ModBlockEntity","tick"),wrapper);
        expect(ProfileSummary.Ownership.LOGGER_SELF,wrapper);
        expect(ProfileSummary.Ownership.WRAPPED_SIMULATION,frame("some.machine.ModBlockEntity","tick"),frame("net.minecraft.ServerLevel","wrapMethod$123$avilixlogger$withSource"));
        expect(ProfileSummary.Ownership.LOGGER_CALLED,frame("some.machine.ModBlockEntity","saveAdditional"),frame("com.roften.avilixlogger.core.NbtSerde","snapshotBlockEntity"),wrapper);
        expect(ProfileSummary.Ownership.LOGGER_CALLED,frame("java.lang.StringBuilder","append"),frame("com.roften.avilixlogger.core.LogEntry","materializeSnapshots"));
        expect(ProfileSummary.Ownership.LOGGER_SELF,frame("com.roften.avilixlogger.core.CauseContext","peek"),wrapper);
        expect(ProfileSummary.Ownership.UNATTRIBUTED,frame("some.machine.ModBlockEntity","tick"));
        roundTrip();
        System.out.println("PROFILE_OWNERSHIP_OK: Logger execution, callbacks initiated by Logger, native simulation below wrappers and unrelated samples remain distinct");
    }
}
