package com.roften.avilixlogger.core;

import java.util.List;

/** Stable query presets; these IDs do not change the twelve capture switches. */
public final class ModLogFilters {
    public record Module(int preset,String key,String title,String sourcePrefix,boolean large) {}
    public static final List<Module> MODULES=List.of(
        new Module(13,"ae2","Applied Energistics 2","ae2:",true),
        new Module(14,"opac","Open Parties and Claims","mod:openpartiesandclaims:",true),
        new Module(15,"scguns","Scorched Guns","mod:scguns:",true),
        new Module(16,"corpse","Corpse","mod:corpse:",false),
        new Module(17,"accessories","Accessories","mod:accessories:",false),
        new Module(18,"ftbteams","FTB Teams","mod:ftbteams:",false),
        new Module(19,"createcobblestone","Create Cobblestone","mod:createcobblestone:",false),
        new Module(20,"vibrantvaults","Create: Vibrant Vaults","mod:create_vibrant_vaults:",false),
        new Module(21,"enchantmentindustry","Create: Enchantment Industry","mod:create_enchantment_industry:",true),
        new Module(22,"dieselgenerators","Create Diesel Generators","mod:createdieselgenerators:",true),
        new Module(23,"blockpack","BlockPack","mod:bf_blockpack:",true),
        new Module(24,"chipped","Chipped","mod:chipped:",false),
        new Module(25,"supplementaries","Supplementaries","mod:supplementaries:",true)
    );
    private ModLogFilters() {}
    public static Module find(int preset){return preset>=13&&preset<13+MODULES.size()?MODULES.get(preset-13):null;}
}
