package com.roften.avilixlogger.core;
/** Explicit mechanism metadata only. Actor is never an owner fallback. */
public final class CartOwnerMetadata {
    private static final java.util.regex.Pattern OWNER=java.util.regex.Pattern.compile("(?:^|;\\s*)owner=([^;]*)");
    private CartOwnerMetadata(){}
    public static String owner(String extra){if(extra==null)return "";var m=OWNER.matcher(extra);return m.find()?m.group(1).trim():"";}
}
