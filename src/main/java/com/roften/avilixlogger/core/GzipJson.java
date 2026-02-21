package com.roften.avilixlogger.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Utility: compact serialization to store full log entries in SQL as a compressed blob.
 */
public final class GzipJson {

    public static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .create();

    private GzipJson() {}

    public static byte[] toGzippedJsonBytes(Object obj) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
            try (GZIPOutputStream gz = new GZIPOutputStream(baos);
                 OutputStreamWriter w = new OutputStreamWriter(gz, StandardCharsets.UTF_8)) {
                GSON.toJson(obj, w);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> T fromGzippedJsonBytes(byte[] bytes, Class<T> type) {
        try {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(bytes));
                 InputStreamReader r = new InputStreamReader(gz, StandardCharsets.UTF_8)) {
                return GSON.fromJson(r, type);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
