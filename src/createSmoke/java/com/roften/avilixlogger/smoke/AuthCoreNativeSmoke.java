package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.auth.AuthCoreBridge;
import net.minecraft.server.MinecraftServer;

/** Uses the supplied, normally obfuscated AuthCore JAR; never grants test authorization. */
final class AuthCoreNativeSmoke {
    static void run(MinecraftServer server) throws Exception {
        var key=net.neoforged.fml.loading.FMLPaths.CONFIGDIR.get().resolve("avilix_auth_core/server-private-key.pk8");
        check(!java.nio.file.Files.exists(key),"this negative fixture requires its own empty config directory");
        var api=Class.forName("ru.avilix.authcore.api.AvilixAuthApi");
        var method=api.getMethod("isAuthorized",MinecraftServer.class,String.class);
        check(method.getReturnType()==boolean.class,"AuthCore API signature changed");
        check(Boolean.FALSE.equals(method.invoke(null,server,"avilixlogger")),"missing-key server was authorized by the real API");
        check(!AuthCoreBridge.attach(server),"Logger accepted an unauthorized server");
        check("AvilixAuthCore denied this server".equals(AuthCoreBridge.failureDescription()),"bridge failed to link the actual API: "+AuthCoreBridge.failureDescription());
        check(!AuthCoreBridge.isAuthorizedForTick()&&!LoggerConfig.isEnabled(),"capture gate ignored actual AuthCore denial");
        int result=server.getCommands().getDispatcher().execute("avilixauth reload",server.createCommandSourceStack().withSuppressedOutput());
        check(result==0&&!AuthCoreBridge.isAuthorized(server),"reload bypassed missing-key denial");
        check(!LoggerConfig.isEnabled(),"reload enabled protected logging without authorization");
        System.out.println("AUTHCORE_API_NATIVE_OK: actual obfuscated AuthCore API links on NeoForge; absent key and native reload remain denied through Logger bridge and capture gates; positive authorization not exercised");
    }
    private static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
}
