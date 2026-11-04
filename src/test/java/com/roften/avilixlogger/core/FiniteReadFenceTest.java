package com.roften.avilixlogger.core;

import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Read visibility must include prior in-flight work and exclude a later live tail. */
public final class FiniteReadFenceTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void run() throws Exception {
        var queue=new WeightedQueue<Integer>(4,1024);
        queue.put(1,16);var first=queue.poll(1);long fence=queue.fence();
        queue.put(2,16);var later=queue.poll(1);
        check(!queue.awaitThrough(fence,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(20)),"fence skipped an in-flight predecessor");
        queue.complete(first);
        check(queue.awaitThrough(fence,System.nanoTime()+TimeUnit.SECONDS.toNanos(1)),"later live traffic extended the read fence");
        check(queue.statistics().outstanding()==1,"fence consumed a later event");
        queue.put(3,16);var third=queue.poll(1);long all=queue.fence();queue.complete(third);
        check(!queue.awaitThrough(all,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(20)),"out-of-order completion released an older in-flight event");
        queue.complete(later);check(queue.awaitThrough(all,System.nanoTime()+TimeUnit.SECONDS.toNanos(1)),"finite prefix did not drain");
        check(queue.statistics().bytes()==0&&queue.statistics().outstanding()==0,"read fence retained payloads");queue.close();

        var directory=Files.createTempDirectory("logger-fence-clock");
        try {
            var journal=new DiskLogJournal(directory);
            String future=String.format(java.util.Locale.ROOT,"%019d-000000-future",System.currentTimeMillis()+86_400_000);
            var row=AsyncPipelineTest.row("fence");row.id=1;row.type=ActionType.BLOCK_PLACE;
            journal.write(future,List.of(row));
            check(journal.initialize()==1,"startup backlog omitted from visibility fence");
            check(journal.newToken().compareTo(future)>0,"new batch overtook old backlog after a clock adjustment");
        } finally {try(var files=Files.list(directory)){for(var file:files.toList())Files.delete(file);}Files.delete(directory);}
        System.out.println("FINITE_READ_FENCE_OK: earlier in-flight work, concurrent later tail, out-of-order completion, payload release and restart/clock order");
    }
}
