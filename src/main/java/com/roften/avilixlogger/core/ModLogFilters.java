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
    public static Module find(int preset){if(preset>=1300 && preset%100>3)return null;int id=preset>=1300?preset/100:preset;return id>=13&&id<13+MODULES.size()?MODULES.get(id-13):null;}
    public static int[] options(Module module) { return new int[]{module.preset(),module.preset()*100+1,module.preset()*100+2,module.preset()*100+3}; }
    public static String label(int preset) {
        var module=find(preset);if(module==null)return "";
        int sub=preset>=1300?preset%100:0;
        if(sub==0)return "Все события";
        if(module.preset()==13)return switch(sub){case 1->"Ресурсы";case 2->"Автокрафт";default->"Настройки";};
        if(module.preset()==14)return switch(sub){case 1->"Приват и снятие";case 2->"Права и настройки";default->"Кланы";};
        if(module.preset()==15)return switch(sub){case 1->"Выстрелы и попадания";case 2->"Убийства и смерти";default->"Перезарядка";};
        return switch(sub){case 1->"Содержимое / опыт";case 2->"Состояние / права";default->"Взаимодействия";};
    }
    public static java.util.EnumSet<ActionType> types(int preset) {
        var module=find(preset);int sub=preset>=1300?preset%100:0;if(module==null||sub==0)return null;
        if(module.preset()==13)return switch(sub){
            case 1->java.util.EnumSet.of(ActionType.ME_PUT,ActionType.ME_TAKE);
            case 2->java.util.EnumSet.of(ActionType.ME_CRAFT_REQUEST,ActionType.ME_CRAFT_CANCEL,ActionType.ME_CRAFT_FINISH);
            default->java.util.EnumSet.of(ActionType.ME_SETTING_CHANGE,ActionType.ME_FILTER_CHANGE,ActionType.ME_UPGRADE_CHANGE,ActionType.ME_CELL_CHANGE);
        };
        if(module.preset()==14)return switch(sub){case 1->java.util.EnumSet.of(ActionType.CLAIM_ACTION);case 2->java.util.EnumSet.of(ActionType.PROTECTION_CHANGE);default->java.util.EnumSet.of(ActionType.PARTY_CHANGE);};
        if(module.preset()==15)return switch(sub){case 1->java.util.EnumSet.of(ActionType.PROJECTILE_SHOOT,ActionType.PROJECTILE_HIT);case 2->java.util.EnumSet.of(ActionType.WEAPON_KILL,ActionType.PLAYER_KILL,ActionType.PLAYER_DEATH);default->java.util.EnumSet.of(ActionType.WEAPON_RELOAD);};
        return switch(sub){
            case 1->java.util.EnumSet.of(ActionType.MACHINE_CONTENT_CHANGE,ActionType.EXPERIENCE_CHANGE,ActionType.CORPSE_CONTENT_CHANGE,ActionType.CONTAINER_PUT,ActionType.CONTAINER_TAKE,ActionType.ITEM_TRANSFORM);
            case 2->java.util.EnumSet.of(ActionType.MACHINE_STATE_CHANGE,ActionType.PROTECTION_CHANGE,ActionType.PARTY_CHANGE,ActionType.CLAIM_ACTION,ActionType.BLOCK_ENTITY_NBT_CHANGE);
            default->{var types=java.util.EnumSet.allOf(ActionType.class);types.removeAll(types(module.preset()*100+1));types.removeAll(types(module.preset()*100+2));yield types;}
        };
    }
}
