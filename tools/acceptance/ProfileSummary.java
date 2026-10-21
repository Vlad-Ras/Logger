import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Java 21 source launcher: streams JFR without retaining event stacks or loading the recording into heap. */
public final class ProfileSummary {
    private long events,allocationBytes,loggerAllocationBytes,allocationSamples,loggerAllocationSamples,heapAfterGc=-1,maxHeapAfterGc=-1,gcCount,gcPauseNanos,maxGcPauseNanos,executionSamples,loggerExecutionSamples,tickSamples;
    private double cpuSum,tickSumNanos,maxTickNanos;private long cpuSamples;
    private Instant first,last,lastHeapTime;
    private final Map<String,Long> allocatedClasses=new HashMap<>();
    private static boolean loggerStack(RecordedEvent event){
        var stack=event.getStackTrace();if(stack==null)return false;
        for(var frame:stack.getFrames())if(frame.getMethod().getType().getName().startsWith("com.roften.avilixlogger."))return true;
        return false;
    }
    private static long weight(RecordedEvent event){return event.hasField("weight")?event.getLong("weight"):0;}
    private void accept(RecordedEvent event){
        events++;var start=event.getStartTime();var end=event.getEndTime();if(first==null||start.isBefore(first))first=start;if(last==null||end.isAfter(last))last=end;
        switch(event.getEventType().getName()){
            case "jdk.ObjectAllocationSample"->{long bytes=weight(event);allocationBytes+=bytes;allocationSamples++;if(loggerStack(event)){loggerAllocationBytes+=bytes;loggerAllocationSamples++;String name=event.getClass("objectClass").getName();if(allocatedClasses.size()>=4096&&!allocatedClasses.containsKey(name))name="[other classes]";allocatedClasses.merge(name,bytes,Long::sum);}}
            case "jdk.ExecutionSample"->{executionSamples++;if(loggerStack(event))loggerExecutionSamples++;}
            case "jdk.GCHeapSummary"->{if(event.getString("when").equals("After GC")){long used=event.getLong("heapUsed");if(lastHeapTime==null||start.isAfter(lastHeapTime)){heapAfterGc=used;lastHeapTime=start;}maxHeapAfterGc=Math.max(maxHeapAfterGc,used);}}
            case "jdk.GCPhasePause"->{long nanos=event.getDuration().toNanos();gcCount++;gcPauseNanos+=nanos;maxGcPauseNanos=Math.max(maxGcPauseNanos,nanos);}
            case "jdk.CPULoad"->{cpuSum+=event.getFloat("jvmUser")+event.getFloat("jvmSystem");cpuSamples++;}
            case "minecraft.ServerTickTime"->{
                // Minecraft versions expose this numeric field with a JFR Timespan annotation.
                for(var field:event.getEventType().getFields()){
                    var unit=field.getAnnotation(jdk.jfr.Timespan.class);if(unit==null||!field.getName().startsWith("averageTick"))continue;
                    Object value=event.getValue(field.getName());if(!(value instanceof Number number))continue;
                    double multiplier=switch(unit.value()){case "NANOSECONDS"->1;case "MICROSECONDS"->1000;case "MILLISECONDS"->1000000;case "SECONDS"->1000000000;default->Double.NaN;};
                    double nanos=number.doubleValue()*multiplier;if(Double.isFinite(nanos)){tickSamples++;tickSumNanos+=nanos;maxTickNanos=Math.max(maxTickNanos,nanos);}
                }
            }
            default->{}
        }
    }
    private static String quote(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r").replace("\t","\\t")+"\"";}
    private static String number(double value){return Double.isFinite(value)?Double.toString(value):"null";}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("Usage: java ProfileSummary.java recording.jfr");
        var summary=new ProfileSummary();var file=Path.of(args[0]);try(var recording=new RecordingFile(file)){while(recording.hasMoreEvents())summary.accept(recording.readEvent());}
        double seconds=summary.first==null?0:Duration.between(summary.first,summary.last).toNanos()/1e9;
        var out=new LinkedHashMap<String,String>();out.put("schema","1");out.put("recording",quote(file.getFileName().toString()));out.put("observed_seconds",number(seconds));out.put("events",Long.toString(summary.events));
        out.put("allocation_sample_weight_bytes",Long.toString(summary.allocationBytes));out.put("allocation_samples",Long.toString(summary.allocationSamples));out.put("logger_stack_allocation_sample_weight_bytes",Long.toString(summary.loggerAllocationBytes));out.put("logger_stack_allocation_samples",Long.toString(summary.loggerAllocationSamples));
        out.put("execution_samples",Long.toString(summary.executionSamples));out.put("logger_stack_execution_samples",Long.toString(summary.loggerExecutionSamples));
        out.put("last_heap_after_gc_bytes",summary.heapAfterGc<0?"null":Long.toString(summary.heapAfterGc));out.put("max_heap_after_gc_bytes",summary.maxHeapAfterGc<0?"null":Long.toString(summary.maxHeapAfterGc));
        out.put("gc_pauses",Long.toString(summary.gcCount));out.put("gc_pause_total_ms",number(summary.gcPauseNanos/1e6));out.put("gc_pause_max_ms",number(summary.maxGcPauseNanos/1e6));out.put("jvm_cpu_fraction_mean",summary.cpuSamples==0?"null":number(summary.cpuSum/summary.cpuSamples));
        out.put("server_tick_samples",Long.toString(summary.tickSamples));out.put("server_tick_reported_average_ms",summary.tickSamples==0?"null":number(summary.tickSumNanos/summary.tickSamples/1e6));out.put("server_tick_max_reported_average_ms",summary.tickSamples==0?"null":number(summary.maxTickNanos/1e6));
        out.put("logger_stack_top_allocated_classes","["+String.join(",",summary.allocatedClasses.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(20).map(entry->"{\"class\":"+quote(entry.getKey())+",\"sample_weight_bytes\":"+entry.getValue()+"}").toList())+"]");
        System.out.println("{"+String.join(",\n",out.entrySet().stream().map(entry->quote(entry.getKey())+":"+entry.getValue()).toList())+"}");
    }
}
