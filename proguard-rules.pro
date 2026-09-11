# Avilix Logger release obfuscation. Shrinking/optimization are deliberately disabled: NeoForge
# and optional mod integrations discover several handlers dynamically, while identifier
# obfuscation still protects the implementation and keeps release behaviour deterministic.
-dontshrink
-dontoptimize
-dontwarn

-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature,Exceptions,InnerClasses,EnclosingMethod,Record,PermittedSubclasses
-adaptclassstrings
-adaptresourcefilecontents META-INF/services/**

# Entrypoints resolved by NeoForge or by explicit reflection in the common network layer.
-keepnames class com.roften.avilixlogger.AvilixLoggerMod
-keep class com.roften.avilixlogger.client.ClientBootstrap {
    public static void init(...);
}
-keep class com.roften.avilixlogger.client.gui.LogViewerClientHooks {
    public static void open();
    public static void acceptPage(...);
    public static void acceptDetails(...);
    public static void acceptInspectTool(...);
    public static void acceptRollbackPreview(...);
}

# Mixin class/member names are referenced from JSON and @Shadow/@Inject metadata.
-keep class com.roften.avilixlogger.mixin.** { *; }
-keep class com.roften.avilixlogger.compat.**.mixin.** { *; }

# Preserve event subscriber signatures inspected by the NeoForge event bus.
-keepclassmembers,allowoptimization class * {
    @net.neoforged.bus.api.SubscribeEvent <methods>;
}

# Preserve public no-arg constructors used by ServiceLoader integrations.
-keepclassmembers class * implements com.roften.avilixlogger.api.LogSemanticAdapter {
    public <init>();
}

-repackageclasses 'com.roften.avilixlogger.o'
