package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Bounded text and click ranges; no hover NBT or arbitrary component trees on the wire. */
public record S2CLoggerActionResultPayload(long request,String message,List<Link> links) implements CustomPacketPayload {
    private static final int MAX_TEXT=8000, MAX_LINKS=64;
    private static final java.util.Set<String> CLICKS=java.util.Set.of("RUN_COMMAND","SUGGEST_COMMAND","COPY_TO_CLIPBOARD");
    public record Link(int start,int end,String action,String value) {
        public Link {
            if(start<0||end<=start||!CLICKS.contains(action)||value.isEmpty()||value.length()>512)
                throw new IllegalArgumentException("Invalid GUI link");
        }
    }
    public S2CLoggerActionResultPayload(long request,String message){this(request,message,List.of());}
    public S2CLoggerActionResultPayload(long request,String message,String action,String value){
        this(request,message,action.isEmpty()?List.of():List.of(new Link(0,message.length(),action,value)));
    }
    public S2CLoggerActionResultPayload {
        links=List.copyOf(links);
        if(message.length()>MAX_TEXT||links.size()>MAX_LINKS)throw new IllegalArgumentException("Invalid GUI result");
        int previous=0;
        for(var link:links){
            if(link.start()<previous||link.end()>message.length())throw new IllegalArgumentException("Overlapping/outside GUI link");
            previous=link.end();
        }
    }
    public static S2CLoggerActionResultPayload from(long request,Component source){
        var text=new StringBuilder();var links=new ArrayList<Link>();
        source.<Boolean>visit((style,part)->{
            int length=Math.min(7900-text.length(),part.length());
            if(length>0&&length<part.length()&&Character.isHighSurrogate(part.charAt(length-1)))length--;
            int start=text.length();text.append(part,0,length);
            var click=style.getClickEvent();
            if(length>0&&click!=null&&CLICKS.contains(click.getAction().name())&&!click.getValue().isEmpty()&&click.getValue().length()<=512){
                var link=new Link(start,text.length(),click.getAction().name(),click.getValue());
                var last=links.isEmpty()?null:links.getLast();
                if(last!=null&&last.end()==start&&last.action().equals(link.action())&&last.value().equals(link.value()))
                    links.set(links.size()-1,new Link(last.start(),link.end(),link.action(),link.value()));
                else if(links.size()<MAX_LINKS)links.add(link);
            }
            if(length<part.length()){text.append("… (сообщение сокращено)");return Optional.of(true);}
            return Optional.empty();
        },Style.EMPTY);
        return new S2CLoggerActionResultPayload(request,text.toString(),links);
    }
    public Component component(){
        var text=Component.empty();int position=0;
        for(var link:links){
            if(position<link.start())text.append(Component.literal(message.substring(position,link.start())));
            text.append(Component.literal(message.substring(link.start(),link.end())).withStyle(s->s.withColor(net.minecraft.ChatFormatting.AQUA)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.valueOf(link.action()),link.value()))));
            position=link.end();
        }
        if(position<message.length())text.append(Component.literal(message.substring(position)));
        return text;
    }
    public static final Type<S2CLoggerActionResultPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID,"logger_action_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf,S2CLoggerActionResultPayload> STREAM_CODEC=StreamCodec.of(
        (b,p)->{
            b.writeLong(p.request);b.writeUtf(p.message,MAX_TEXT);b.writeVarInt(p.links.size());
            for(var link:p.links){b.writeVarInt(link.start());b.writeVarInt(link.end());b.writeUtf(link.action(),24);b.writeUtf(link.value(),512);}
        },b->{
            long request=b.readLong();String text=b.readUtf(MAX_TEXT);int count=b.readVarInt();
            if(count<0||count>MAX_LINKS)throw new IllegalArgumentException("Too many GUI links");
            var links=new ArrayList<Link>(count);
            for(int i=0;i<count;i++)links.add(new Link(b.readVarInt(),b.readVarInt(),b.readUtf(24),b.readUtf(512)));
            return new S2CLoggerActionResultPayload(request,text,links);
        });
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
