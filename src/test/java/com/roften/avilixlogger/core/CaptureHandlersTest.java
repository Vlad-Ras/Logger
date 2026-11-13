package com.roften.avilixlogger.core;

import java.util.HashSet;
import java.util.Set;

public final class CaptureHandlersTest {
    static void run() {
        var previous = CaptureHandlers.disabled();
        long mask = CaptureTypePolicy.mask();
        try {
            var keys = new HashSet<String>();
            var titles = new HashSet<String>();
            for (var handler : CaptureHandlers.values()) {
                if (!keys.add(handler.key())) throw new AssertionError("Duplicate handler key");
                if (!titles.add(handler.title()) || handler.title().codePoints().noneMatch(c -> c >= 0x400 && c <= 0x4FF))
                    throw new AssertionError("Duplicate or untranslated handler title: " + handler.title());
                CaptureHandlers.install(Set.of(handler.key()));
                for (var other : CaptureHandlers.values()) {
                    if (other.enabled() == (other == handler)) throw new AssertionError("Crossed handler controls");
                }
                if (CaptureTypePolicy.mask() != mask) throw new AssertionError("Handler setting changed a legacy group");
            }
            if (keys.size() <= 64) throw new AssertionError("Fixture requires handlers beyond bit 64");
            CaptureHandlers.install(Set.of("future.provider.handler"));
            if (!CaptureHandlers.disabled().contains("future.provider.handler")) throw new AssertionError("Unknown saved key lost");
            if (CaptureHandlers.fromPreset(0) != null || CaptureHandlers.fromPreset(1000 + keys.size()) != null)
                throw new AssertionError("Invalid handler preset accepted");
        } finally {
            CaptureHandlers.install(previous);
        }
        System.out.println("CAPTURE_HANDLERS_OK: independent producers, stable keys and legacy groups");
    }
}
