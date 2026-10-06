package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import com.electronwill.nightconfig.core.CommentedConfig;
import com.sun.net.httpserver.HttpServer;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.roften.avilixlogger.core.AsyncPipelineTest.*;

/** HTTP contract test: a failure after the feed insert must retry identical data and tokens. */
final class ClickHouseDeliveryTest {
    static void run() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, String> requests = new java.util.concurrent.ConcurrentHashMap<>();
        AtomicBoolean failed = new AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> handlerFailure = new java.util.concurrent.atomic.AtomicReference<>();
        http.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int status = 200;
            try {
                if (body.startsWith("INSERT")) {
                    String query = exchange.getRequestURI().getRawQuery();
                    check(query.contains("async_insert=0") && query.contains("insert_deduplication_token="), "insert lacks durable acknowledgment/deduplication");
                    String previous = requests.putIfAbsent(query, body);
                    check(previous == null || previous.equals(body), "partial batch retry changed payload");
                    if (body.contains("_containers") && failed.compareAndSet(false, true)) status = 503;
                }
            } catch (Throwable failure) { handlerFailure.set(failure); status = 500; }
            byte[] response = (body.startsWith("SELECT") ? "0\n" : status == 200 ? "" : "simulated failure").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length == 0 ? -1 : response.length);
            if (response.length > 0) exchange.getResponseBody().write(response);
            exchange.close();
        });
        http.start();
        ClickHouseLogStorage database = null;
        try {
            CommentedConfig config = CommentedConfig.inMemory();
            LoggerConfig.SPEC.correct(config);
            config.set(List.of("clickhouse", "url"), "http://127.0.0.1:" + http.getAddress().getPort());
            // FML seals ILoadedConfig. Use its real wrapper for this headless test;
            // no file/container is needed because the already-correct config is never saved.
            var constructor = Class.forName("net.neoforged.fml.config.LoadedConfig")
                    .getDeclaredConstructor(CommentedConfig.class, Path.class, ModConfig.class);
            constructor.setAccessible(true);
            LoggerConfig.SPEC.acceptConfig((IConfigSpec.ILoadedConfig) constructor.newInstance(config, null, null));
            database = new ClickHouseLogStorage();
            LogEntry first = row("delta"), second = row("delta");
            first.type = second.type = ActionType.CONTAINER_PUT;
            first.dim = second.dim = "minecraft:overworld";
            first.itemStackNbt = second.itemStackNbt = "{id:'minecraft:stone',count:1}";
            first.count = 2; second.count = 3;
            List<LogEntry> batch = List.of(first, second);
            try { database.appendBatch("retry-test", batch); throw new AssertionError("failed detail insert was acknowledged"); }
            catch (java.io.IOException expected) {}
            database.appendBatch("retry-test", batch);
            check(handlerFailure.get() == null, "HTTP contract violation: " + handlerFailure.get());
            check(failed.get() && requests.size() == 2, "split feed/detail retry was not exercised");
            check(first.count == 2 && second.count == 3, "coalescing mutated original item counts");
        } finally {
            if (database != null) database.shutdown();
            http.stop(0);
            LoggerConfig.SPEC.acceptConfig(null);
        }
    }
}
