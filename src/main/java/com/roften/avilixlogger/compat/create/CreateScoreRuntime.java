package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.*;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.numbers.NumberFormatTypes;
import java.util.*;
/** A holder's finite scores, including absence, display overrides and objective identity. */
public final class CreateScoreRuntime {
    public static final String LOCK="create-score-objectives";
    private CreateScoreRuntime(){}
    public static CompoundTag capture(net.minecraft.world.entity.Entity player){
        var tag=new CompoundTag();tag.putString("Holder",player.getScoreboardName());var list=new ListTag();var board=player.level().getScoreboard();var ops=player.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        for(var objective:board.getObjectives().stream().sorted(Comparator.comparing(Objective::getName)).toList()){
            var row=new CompoundTag();row.putString("Name",objective.getName());row.putString("Criteria",objective.getCriteria().getName());row.putString("Render",objective.getRenderType().name());row.putBoolean("Auto",objective.displayAutoUpdate());
            row.put("Display",ComponentSerialization.CODEC.encodeStart(ops,objective.getDisplayName()).getOrThrow());if(objective.numberFormat()!=null)row.put("Format",NumberFormatTypes.CODEC.encodeStart(ops,objective.numberFormat()).getOrThrow());
            var score=board.getPlayerScoreInfo(player,objective);if(score!=null){if(!(score instanceof Score nativeScore))throw new IllegalStateException("Неизвестная реализация счётчика");row.put("Score",nativeScore.write(player.registryAccess()));}
            list.add(row);
        }
        tag.put("Objectives",list);var team=board.getPlayersTeam(player.getScoreboardName());if(team!=null)tag.put("Team",team(player,team));validate(tag);return tag;
    }
    private static CompoundTag team(net.minecraft.world.entity.Entity entity,PlayerTeam team){
        var tag=new CompoundTag();tag.putString("Name",team.getName());tag.putString("Color",team.getColor().getName());tag.putInt("Options",team.packOptions());
        tag.putString("Names",team.getNameTagVisibility().name());tag.putString("Deaths",team.getDeathMessageVisibility().name());tag.putString("Collision",team.getCollisionRule().name());
        var ops=entity.registryAccess().createSerializationContext(NbtOps.INSTANCE);
        tag.put("Display",ComponentSerialization.CODEC.encodeStart(ops,team.getDisplayName()).getOrThrow());tag.put("Prefix",ComponentSerialization.CODEC.encodeStart(ops,team.getPlayerPrefix()).getOrThrow());tag.put("Suffix",ComponentSerialization.CODEC.encodeStart(ops,team.getPlayerSuffix()).getOrThrow());return tag;
    }
    public static void validate(CompoundTag tag){
        if(tag.getString("Holder").isBlank()||!tag.contains("Objectives",9)||tag.getList("Objectives",10).size()>512||tag.sizeInBytes()>512*1024)throw new IllegalStateException("Неполный scoreboard получателя");
        CreatePlayerProgress.rows(tag,"Objectives",512);
        var names=new HashSet<String>();for(var raw:tag.getList("Objectives",10)){var row=(CompoundTag)raw;if(row.getString("Name").isBlank()||!names.add(row.getString("Name"))||row.getString("Criteria").isBlank()||!row.contains("Display")||!row.contains("Render",8)||!row.contains("Auto",1)||row.contains("Score")&&!row.contains("Score",10))throw new IllegalStateException("Некорректный scoreboard получателя");}
    }
    public static void preflight(net.minecraft.world.entity.Entity player,CompoundTag tag){
        validate(tag);var expected=tag.copy();for(var raw:expected.getList("Objectives",10))((CompoundTag)raw).remove("Score");var actual=capture(player);expected.remove("Team");actual.remove("Team");for(var raw:actual.getList("Objectives",10))((CompoundTag)raw).remove("Score");
        if(!actual.equals(expected))throw new IllegalStateException("Определения scoreboard получателя изменились");
        if(tag.contains("Team")){var saved=tag.getCompound("Team");var nativeTeam=player.level().getScoreboard().getPlayerTeam(saved.getString("Name"));if(nativeTeam==null||!team(player,nativeTeam).equals(saved))throw new IllegalStateException("Определение команды scoreboard изменилось");}
        for(var raw:tag.getList("Objectives",10)){var row=(CompoundTag)raw;if(row.contains("Score")){var score=Score.read(row.getCompound("Score"),player.registryAccess());if(!score.write(player.registryAccess()).equals(row.getCompound("Score")))throw new IllegalStateException("Scoreboard не прочитан полностью");}}
    }
    public static CompoundTag merge(CompoundTag current,CompoundTag from,CompoundTag to){return merge(current,from,to,false);}
    public static CompoundTag merge(CompoundTag current,CompoundTag from,CompoundTag to,boolean compensating){
        var out=current.copy();var start=new HashMap<String,CompoundTag>();var end=new HashMap<String,CompoundTag>();
        for(var raw:from.getList("Objectives",10)){var row=(CompoundTag)raw;start.put(row.getString("Name"),row);}
        for(var raw:to.getList("Objectives",10)){var row=(CompoundTag)raw;end.put(row.getString("Name"),row);}
        for(var raw:out.getList("Objectives",10)){var live=(CompoundTag)raw;var b=start.get(live.getString("Name"));var a=end.get(live.getString("Name"));
            if(b==null||a==null)throw new IllegalStateException("Определения scoreboard изменились");
            if(!Objects.equals(b.get("Score"),a.get("Score"))){if(!Objects.equals(live.get("Score"),b.get("Score"))&&(!compensating||!Objects.equals(live.get("Score"),a.get("Score"))))throw new IllegalStateException("Счётчик действия изменился");live.remove("Score");if(a.contains("Score"))live.put("Score",a.getCompound("Score").copy());}
        }
        if(!Objects.equals(from.get("Team"),to.get("Team"))){if(!Objects.equals(current.get("Team"),from.get("Team"))&&(!compensating||!Objects.equals(current.get("Team"),to.get("Team"))))throw new IllegalStateException("Команда участника изменилась");out.remove("Team");if(to.contains("Team"))out.put("Team",to.getCompound("Team").copy());}
        return out;
    }
    public static void reserve(net.minecraft.world.entity.Entity player){
        CartRestoreLocks.resource(LOCK);CartRestoreLocks.resource("create-score:"+player.getScoreboardName());
        for(var objective:player.level().getScoreboard().getObjectives())CartRestoreLocks.handler(player.level().getScoreboard().getPlayerScoreInfo(player,objective));
    }
    public static void restore(net.minecraft.world.entity.Entity player,CompoundTag tag){
        preflight(player,tag);var board=player.level().getScoreboard();
        if(tag.contains("Team"))board.addPlayerToTeam(player.getScoreboardName(),board.getPlayerTeam(tag.getCompound("Team").getString("Name")));else board.removePlayerFromTeam(player.getScoreboardName());
        for(var raw:tag.getList("Objectives",10)){var row=(CompoundTag)raw;var objective=board.getObjective(row.getString("Name"));
            if(!row.contains("Score")){board.resetSinglePlayerScore(player,objective);continue;}
            var source=Score.read(row.getCompound("Score"),player.registryAccess());var dest=board.getOrCreatePlayerScore(player,objective,true);dest.set(source.value());if(source.isLocked())dest.lock();else dest.unlock();dest.display(source.display());dest.numberFormatOverride(source.numberFormat());
        }
    }
}
