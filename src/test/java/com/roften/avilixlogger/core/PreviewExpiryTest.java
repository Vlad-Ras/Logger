package com.roften.avilixlogger.core;

import java.util.HashMap;
import java.util.Map;

/** Expired payloads are released without consuming current previews or active-job data. */
public final class PreviewExpiryTest {
    private record Preview(long expires,byte[] payload) {}
    public static void main(String[] args){run();System.out.println("PREVIEW_EXPIRY_OK");}
    static void run(){
        var previews=new HashMap<String,Preview>();
        var expired=new Preview(9,new byte[1024*1024]);
        var current=new Preview(10,new byte[1024*1024]);
        previews.put("expired",expired);previews.put("current",current);previews.put("replaced",expired);
        previews.put("replaced",new Preview(50,new byte[1]));
        PreviewExpiry.prune(previews,10,Preview::expires);
        if(previews.size()!=2||previews.get("current")!=current||!previews.containsKey("replaced"))throw new AssertionError("expired cleanup consumed a valid/replacement preview");
        PreviewExpiry.prune(previews,51,Preview::expires);
        if(!previews.isEmpty())throw new AssertionError("expired payloads retained");
        PreviewExpiry.prune(Map.of(),100,Preview::expires);
    }
}
