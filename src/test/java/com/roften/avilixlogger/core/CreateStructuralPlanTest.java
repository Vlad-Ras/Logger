package com.roften.avilixlogger.core;
import com.roften.avilixlogger.compat.create.CreateStructuralRollbackPlan;
import net.minecraft.nbt.*;
import java.util.*;

/** Detached plans, malformed graphs and history freshness without initializing a world. */
public final class CreateStructuralPlanTest {
    public static void run(){
        var before=manifest(false);var after=manifest(true);var plan=CreateStructuralRollbackPlan.prepare(before,after);
        check(plan.bodies().size()==1&&plan.world().size()==2&&plan.effects().size()==1,"scene endpoints lost");
        var frozen=plan.before().copy();before.getList("World",10).getCompound(0).getCompound("BE").putInt("Fault",1);check(plan.before().equals(frozen),"plan shares mutable inputs");before=manifest(false);
        final var validBefore=before;
        for(String fault:List.of("partial","operation","uuid","world","controller","effects","typed-list","train","elevator","mirror","removing","external","cycle","backlink","duplicate")){
            var b=before.copy();var a=after.copy();var body=b.getList("Bodies",10).getCompound(0);
            switch(fault){
                case "partial"->a.putBoolean("Complete",false);
                case "operation"->a.putUUID("Operation",UUID.randomUUID());
                case "uuid"->body.getCompound("Entity").remove("UUID");
                case "world"->a.getList("World",10).remove(1);
                case "controller"->b.getList("World",10).getCompound(0).remove("BE");
                case "mirror"->b.getList("World",10).getCompound(0).getCompound("BE").put("MirrorParent",new IntArrayTag(new int[]{0,1,0}));
                case "removing"->b.getList("World",10).getCompound(0).put("Runtime",NbtSerde.fromSnbt("{Removing:1b}"));
                case "effects"->a.getList("Effects",10).getCompound(0).getCompound("NBT").putUUID("UUID",UUID.randomUUID());
                case "typed-list"->{var bad=new ListTag();bad.add(StringTag.valueOf("lost drop"));a.put("Effects",bad);}
                case "train","elevator"->{body.getCompound("Entity").getCompound("Contraption").putString("Type",fault.equals("train")?"create:carriage":"create:elevator");}
                case "external","cycle","backlink","duplicate"->{var link=new CompoundTag();link.putString("Relation",fault.equals("backlink")?"passenger":"vehicle");link.putUUID("Entity",fault.equals("external")?UUID.randomUUID():body.getUUID("StorageEntity"));body.getList("Links",10).add(link);if(fault.equals("duplicate"))body.getList("Links",10).add(link.copy());}
            }
            reject(()->CreateStructuralRollbackPlan.prepare(b,a),"bad structural scene accepted: "+fault);
        }
        var row=new LogEntry();row.source=CreateContraptionKind.BEARING.source(plan.constructions().iterator().next(),"structural_disassemble");row.beBefore=before.toString();row.beAfter=after.toString();
        check(CartLogDetails.describe(null,row).stream().anyMatch(line->line.getString().contains("блоков/контроллеров: 2")),"structural detail missing without world");
        mirrorGraph();
        history(plan);
        check(CreateStructuralRollbackPlan.prepare(validBefore,after).operation().equals(plan.operation()),"detached round trip changed operation");
        System.out.println("CREATE_STRUCTURAL_PLAN_OK: full scenes, detached snapshots, malformed/missing endpoints, unsupported registries, graphs, detail formatting and later-history guards");
    }
    private static void mirrorGraph(){
        var b=manifest(false);var a=manifest(true);
        var childPos=new net.minecraft.core.BlockPos(2,0,0);var parentPos=net.minecraft.core.BlockPos.ZERO;
        for(var envelope:List.of(b,a)){
            var controller=envelope.getList("World",10).getCompound(0);controller.getCompound("State").putString("Name","create:rope_pulley");controller.getCompound("BE").putString("id","create:rope_pulley");
            var child=controller.copy();child.putLong("Pos",childPos.asLong());envelope.getList("World",10).add(child);
            if(envelope==b){var edge=new CompoundTag();edge.put("Pos",NbtUtils.writeBlockPos(childPos));var children=new ListTag();children.add(edge);controller.getCompound("BE").put("MirrorChildren",children);child.getCompound("BE").put("MirrorParent",NbtUtils.writeBlockPos(parentPos));}
        }
        check(CreateStructuralRollbackPlan.prepare(b,a).world().size()==3,"valid mirror graph rejected");
        for(String fault:List.of("missing","backlink","foreign-type","duplicate","cycle")){
            var old=b.copy();var now=a.copy();var parent=old.getList("World",10).getCompound(0).getCompound("BE");var child=old.getList("World",10).getCompound(2).getCompound("BE");
            switch(fault){
                case "missing"->{old.getList("World",10).remove(2);now.getList("World",10).remove(2);}
                case "backlink"->child.remove("MirrorParent");
                case "foreign-type"->child.putString("id","create:elevator_pulley");
                case "duplicate"->parent.getList("MirrorChildren",10).add(parent.getList("MirrorChildren",10).getCompound(0).copy());
                case "cycle"->{parent.put("MirrorParent",NbtUtils.writeBlockPos(childPos));var edge=new CompoundTag();edge.put("Pos",NbtUtils.writeBlockPos(parentPos));var children=new ListTag();children.add(edge);child.put("MirrorChildren",children);}
            }
            reject(()->CreateStructuralRollbackPlan.prepare(old,now),"bad mirror graph accepted: "+fault);
        }
    }
    private static void history(CreateStructuralRollbackPlan plan){
        var source=CreateContraptionKind.BEARING.source(plan.constructions().iterator().next(),"structural_disassemble");
        var same=new LogEntry();same.id=11;same.type=ActionType.CART_DISASSEMBLE;same.source=source;same.beAfter=plan.after().toString();verify(plan,List.of(same));
        var drop=new LogEntry();drop.id=12;drop.type=ActionType.ENTITY_SPAWN;drop.source=source;drop.entityUuid=plan.effects().getFirst().getUUID("Entity");verify(plan,List.of(drop,same));
        drop.entityUuid=UUID.randomUUID();reject(()->verify(plan,List.of(drop,same)),"foreign spawn accepted");
        for(var action:List.of(ActionType.CART_ROLLBACK,ActionType.CART_CONTENT_CHANGE,ActionType.CART_LOAD,ActionType.CART_STATUS,ActionType.ENTITY_DEATH)){
            var later=new LogEntry();later.id=13;later.type=action;later.source=source;later.beAfter=plan.after().toString();reject(()->verify(plan,List.of(later,same)),"later mutation accepted: "+action);
        }
    }
    private static void verify(CreateStructuralRollbackPlan plan,List<LogEntry> rows){
        LogStorage storage=new LogStorage(){public void append(LogEntry row){throw new UnsupportedOperationException();}public List<LogEntry> query(LogQuery q){return queryReverse(q);}public List<LogEntry> queryReverse(LogQuery q){return rows.stream().filter(row->q.constructionId.equals(CreateContraptionKind.id(row.source))).sorted(Comparator.<LogEntry>comparingLong(row->row.id).reversed()).toList();}public void shutdown(){}};
        try{var type=Class.forName("com.roften.avilixlogger.compat.create.CreateStructuralRollbackCoordinator");var method=type.getDeclaredMethod("verifyLatest",LogStorage.class,CreateStructuralRollbackPlan.class,long.class);method.setAccessible(true);method.invoke(null,storage,plan,10L);}
        catch(java.lang.reflect.InvocationTargetException failure){if(failure.getCause() instanceof RuntimeException cause)throw cause;throw new AssertionError(failure.getCause());}catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }
    public static CompoundTag manifest(boolean after){
        var tag=NbtSerde.fromSnbt("{Format:3,Kind:'structural',Complete:1b,Bodies:[],World:[],Effects:[]}");tag.putUUID("Operation",new UUID(43,1));
        var body=NbtSerde.fromSnbt("{ConstructionKind:'BEARING',Dimension:'minecraft:overworld',Links:[],Entity:{id:'create:controlled_contraption',Pos:[0d,1d,0d],ControllerRelative:[I;0,-1,0],Contraption:{Type:'create:bearing',Blocks:{Palette:[{Name:'minecraft:chest'}],BlockList:[{Pos:0L,State:0}]},items:[]}}}");body.putString("Form",after?"blocks":"entity");body.putUUID("Construction",new UUID(43,2));body.putUUID("StorageEntity",new UUID(43,3));body.getCompound("Entity").putUUID("UUID",new UUID(43,3));tag.getList("Bodies",10).add(body);
        var controller=NbtSerde.fromSnbt("{Dimension:'minecraft:overworld',Pos:0L,Controller:1b,State:{Name:'create:mechanical_bearing'},BE:{id:'create:mechanical_bearing'}}");controller.getCompound("BE").putBoolean("Running",!after);tag.getList("World",10).add(controller);
        tag.getList("World",10).add(NbtSerde.fromSnbt(after?"{Dimension:'minecraft:overworld',Pos:1L,State:{Name:'minecraft:chest'},BE:{id:'minecraft:chest',Items:[{Slot:5b,id:'minecraft:diamond',count:7}]}}":"{Dimension:'minecraft:overworld',Pos:1L,State:{Name:'minecraft:dirt'}}"));
        if(after){var effect=NbtSerde.fromSnbt("{Dimension:'minecraft:overworld',EntityType:'minecraft:item',NBT:{id:'minecraft:item',Pos:[0d,1d,0d],Item:{id:'minecraft:dirt',count:1}}}");effect.putUUID("Entity",new UUID(43,4));effect.getCompound("NBT").putUUID("UUID",new UUID(43,4));tag.getList("Effects",10).add(effect);}return tag;
    }
    private static void reject(Runnable action,String message){try{action.run();}catch(IllegalStateException|IllegalArgumentException expected){return;}throw new AssertionError(message);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
