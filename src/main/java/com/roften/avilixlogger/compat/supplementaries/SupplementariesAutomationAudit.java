package com.roften.avilixlogger.compat.supplementaries;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Synchronous native display-target observation. Only detached snapshots leave this scope. */
public final class SupplementariesAutomationAudit {
    public static final String SOURCE="mod:supplementaries:create_display_link";
    private static final ThreadLocal<Frame> CURRENT=new ThreadLocal<>(), FREE=new ThreadLocal<>();
    private SupplementariesAutomationAudit(){}

    public static Frame enter(int first,List<? extends Component> input){
        try{
            if(!SupplementariesTextAudit.enabled())return null;
            var frame=FREE.get();FREE.remove();if(frame==null)frame=new Frame();
            frame.previous=CURRENT.get();frame.first=first;frame.input=input;frame.closed=false;
            CURRENT.set(frame);return frame;
        }catch(RuntimeException|LinkageError failure){AdaptiveLogDiagnostics.adapterFailure("supplementaries-display-scope");return null;}
    }
    public static Frame current(){return CURRENT.get();}

    /** Mirrors only page replacement/extension, without invoking native reservation checks. */
    public static boolean bookMayChange(ItemStack book,int first,List<? extends Component> input){
        if(book==null||book.isEmpty()||(!book.is(Items.WRITABLE_BOOK)&&!book.is(Items.WRITTEN_BOOK)))return false;
        if(first<0)return true;
        int end=(int)Math.min(50,(long)first+input.size());
        if(end<=0)return false;
        if(book.is(Items.WRITABLE_BOOK))return true;
        var content=book.getOrDefault(DataComponents.WRITTEN_BOOK_CONTENT,WrittenBookContent.EMPTY);
        // withReplacedPages also resets resolved, even for an equal page or empty offset update.
        if(content.resolved())return true;
        var pages=content.pages();
        if(pages.size()<end)return true;
        for(int i=first;i<end;i++)if(pages.get(i).filtered().isPresent()||!Objects.equals(pages.get(i).raw(),input.get(i-first)))return true;
        return false;
    }
    public static final class Frame implements AutoCloseable {
        private Frame previous;
        private BlockEntity target;
        private BlockPos mechanism;
        private List<? extends Component> input;
        private int first;
        private CompoundTag before;
        private BlockState state;
        private boolean attempted,closed;
        private Frame(){}
        public void bind(BlockEntity entity,BlockPos origin){
            target=entity!=null&&entity.getLevel() instanceof ServerLevel level&&level.getServer().isSameThread()?entity:null;
            mechanism=target==null||origin==null?null:origin.immutable();
        }
        public void text(Object holder){
            if(attempted||target==null)return;
            try{if(holder instanceof TextHolderAuditAccess access&&!access.avilixlogger$matches(first,input))capture();}
            catch(RuntimeException|LinkageError failure){AdaptiveLogDiagnostics.adapterFailure("supplementaries-display-text");}
        }
        public void book(ItemStack item){
            if(attempted||target==null)return;
            try{if(bookMayChange(item,first,input))capture();}
            catch(RuntimeException|LinkageError failure){AdaptiveLogDiagnostics.adapterFailure("supplementaries-display-book");}
        }
        private void capture(){
            attempted=true;
            if(!SupplementariesTextAudit.enabled()||!(target!=null&&target.getLevel() instanceof ServerLevel level)
                    ||!WorldSnapshotAccess.readable(level,target.getBlockPos())||level.getBlockEntity(target.getBlockPos())!=target
                    ||!SupplementariesTextAudit.supported(target))return;
            String source=MutationSourceResolver.resolveExternalSource();
            if(!CaptureTypePolicy.allows(ActionType.BLOCK_ENTITY_NBT_CHANGE,source==null?SOURCE:source,null))return;
            boolean structural=CreateStructuralHooks.before(level,target.getBlockPos()),deployer=CreateDeployerHooks.before(level,target.getBlockPos());
            if(structural||deployer)return;
            state=target.getBlockState();before=NbtSerde.snapshotBlockEntity(level,target);
        }
        public void finish(boolean completed){
            if(closed)return;
            boolean released=false;
            try{
                if(before==null||!SupplementariesTextAudit.enabled()||!(target.getLevel() instanceof ServerLevel level))return;
                var pos=target.getBlockPos();
                if(!WorldSnapshotAccess.readable(level,pos)||level.getBlockEntity(pos)!=target){AdaptiveLogDiagnostics.adapterFailure("supplementaries-display-replaced");return;}
                var after=NbtSerde.snapshotBlockEntity(level,target);
                var row=pendingRow(level.dimension().location().toString(),pos,mechanism,state,target.getBlockState(),before,after,completed);
                close();released=true;if(row!=null)LoggerRuntime.storage(level).append(row);
            }catch(RuntimeException|LinkageError failure){AdaptiveLogDiagnostics.adapterFailure("supplementaries-display-after");}
            finally{if(!released)close();}
        }
        public void close(){
            if(closed)return;closed=true;
            if(previous==null)CURRENT.remove();else CURRENT.set(previous);
            previous=null;target=null;mechanism=null;input=null;before=null;state=null;first=0;attempted=false;
            if(FREE.get()==null)FREE.set(this);
        }
    }
    public static boolean changed(CompoundTag before,CompoundTag after){
        return before!=null&&after!=null&&(SupplementariesTextAudit.changed(before,after)
                ||!Objects.equals(before.get("Items"),after.get("Items"))||before.getInt("PageNumber")!=after.getInt("PageNumber"));
    }
    /** Detached kernel, not authorization. Caller transfers snapshot ownership. */
    public static LogEntry row(String dimension,BlockPos target,BlockPos mechanism,BlockState beforeState,BlockState afterState,CompoundTag before,CompoundTag after,boolean completed){
        if(target==null||mechanism==null||beforeState==null||afterState==null||!changed(before,after))return null;return captureRow(dimension,target,mechanism,beforeState,afterState,before,after,completed,false);
    }
    /** Candidate for the ordered event worker. All snapshot tags have detached from the world. */
    public static LogEntry pendingRow(String dimension,BlockPos target,BlockPos mechanism,BlockState beforeState,BlockState afterState,CompoundTag before,CompoundTag after,boolean completed){
        return captureRow(dimension,target,mechanism,beforeState,afterState,before,after,completed,true);
    }
    private static LogEntry captureRow(String dimension,BlockPos target,BlockPos mechanism,BlockState beforeState,BlockState afterState,CompoundTag before,CompoundTag after,boolean completed,boolean deferred){
        if(target==null||mechanism==null||beforeState==null||afterState==null||before==null||after==null)return null;
        var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;row.dim=dimension;
        row.x=target.getX();row.y=target.getY();row.z=target.getZ();row.actorName="SYSTEM[create:display_link]";row.source=SOURCE;
        row.extra="supplementaries_display;mechanism=create:display_link@"+mechanism.getX()+","+mechanism.getY()+","+mechanism.getZ()+";dimension="+dimension+";completed="+completed;
        row.deferBlockBefore(beforeState);row.deferBlockAfter(afterState);
        row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,before);row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,after);if(deferred)row.deferSnapshotDelta(SnapshotDeltaPolicy.SUPPLEMENTARIES_DISPLAY);return row;
    }
}
