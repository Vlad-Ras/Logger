package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import net.minecraft.commands.CommandSourceStack;
import java.util.*;


final class LoggerActionCatalogSmoke {
    static void run(){
        var dispatcher=new CommandDispatcher<CommandSourceStack>();
        com.roften.avilixlogger.command.LoggerCommands.register(dispatcher);
        var ids=new HashSet<String>();
        for(var action:LoggerActionCatalog.ACTIONS){
            check(ids.add(action.id()),"duplicate GUI action");
            var values=action.fields().stream().map(f->f.initial().isBlank()?"fixture":f.initial()).toList();
            String command=action.command(values);check(command.startsWith("log"),"GUI escaped logger command root");
            com.mojang.brigadier.tree.CommandNode<CommandSourceStack> node=dispatcher.getRoot();
            for(String word:command.split(" ")){
                var child=node.getChild(word);
                if(child==null)child=node.getChildren().stream().filter(ArgumentCommandNode.class::isInstance).findFirst().orElse(null);
                check(child!=null,"GUI action has no registered command: "+action.id()+" at "+word);
                node=child;
                if(child instanceof ArgumentCommandNode<?,?> argument&&argument.getType() instanceof com.mojang.brigadier.arguments.StringArgumentType type&&type.getType()==com.mojang.brigadier.arguments.StringArgumentType.StringType.GREEDY_PHRASE)break;
            }
            check(node.getCommand()!=null,"GUI action stops before executable node: "+action.id());
            if(!values.isEmpty()){
                var invalid=new ArrayList<>(values);invalid.set(0,"name\nstop");
                try{action.command(invalid);throw new AssertionError("multiline GUI value accepted");}catch(IllegalArgumentException expected){}
            }
        }
        System.out.println("LOGGER_GUI_ACTIONS_OK: "+ids.size()+" forms resolve to registered command nodes; field boundaries checked");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}

}
