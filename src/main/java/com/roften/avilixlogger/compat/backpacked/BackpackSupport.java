package com.roften.avilixlogger.compat.backpacked;

import net.neoforged.fml.ModList;

public final class BackpackSupport {
    private BackpackSupport() {}
    public static boolean supported(String version) {
        return version.startsWith("3.0.") && !version.contains("beta") && !version.contains("alpha");
    }
    public static boolean available() {
        return ModList.get().getModContainerById("backpacked")
                .map(c->supported(c.getModInfo().getVersion().toString())).orElse(false);
    }
}
