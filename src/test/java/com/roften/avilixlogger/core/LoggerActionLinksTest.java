package com.roften.avilixlogger.core;

import java.util.List;

public final class LoggerActionLinksTest {
    public static void main(String[] args){run();System.out.println("LOGGER_ACTION_LINKS_OK");}
    static void run(){
        String id="51000000-0000-0000-0000-000000000001";
        for(String name:List.of("cart_confirm","cart_recover_confirm")){
            var action=LoggerActionCatalog.find(name);
            var result=LoggerActionCatalog.linkedForm(LoggerActionCatalog.ACTIONS,"/"+action.command(List.of(id,"42")));
            check(result!=null&&result.action().id().equals(name)&&result.values().equals(List.of(id,"42")),"confirmation changed action/parameters");
        }
        check(LoggerActionCatalog.linkedForm(LoggerActionCatalog.ACTIONS,"/log rollback --confirm").action().id().equals("rollback_confirm"),"confirmation became a preview");
        var history=LoggerActionCatalog.linkedForm(LoggerActionCatalog.ACTIONS,"/log backpack history BP-123");
        check(history.action().id().equals("backpack_history")&&history.values().equals(List.of("BP-123","")),"optional history cursor lost");
        check(LoggerActionCatalog.linkedForm(LoggerActionCatalog.ACTIONS,"/log preset run theft").values().equals(List.of("theft","")),"optional preset filters lost");
        check(LoggerActionCatalog.linkedForm(LoggerActionCatalog.ACTIONS,"/execute in minecraft:overworld run tp @s 1 2 3")==null,"non-logger command routed into a logger form");
        var filtered=List.of(LoggerActionCatalog.find("inspect"));
        check(LoggerActionCatalog.linkedForm(filtered,"/log cart confirm "+id+" 42")==null,"hidden form became available through a link");
    }
    private static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
}
