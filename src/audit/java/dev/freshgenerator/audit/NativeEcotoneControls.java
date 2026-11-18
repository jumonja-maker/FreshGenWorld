package dev.freshgenerator.audit;

import java.util.concurrent.atomic.LongAdder;

/** External observer counters only; never packaged in the playable mod. */
public final class NativeEcotoneControls {
    private NativeEcotoneControls() { }
    private static final LongAdder BYPASSED=new LongAdder(),FORCED=new LongAdder();
    private static final LongAdder PALE_OUT=new LongAdder(),PALE_IN=new LongAdder();
    public static void countBypassed(){BYPASSED.increment();}
    public static long bypasses(){return BYPASSED.sum();}
    public static long forcedCalls(){return FORCED.sum();}
    public static long paleOut(){return PALE_OUT.sum();}
    public static long paleIn(){return PALE_IN.sum();}
    public static void paletteGuard(String own,String donor,boolean allowed){
        if(allowed)return;
        if("minecraft:pale_garden".equals(own)&&!own.equals(donor))PALE_OUT.increment();
        if("minecraft:pale_garden".equals(donor)&&!donor.equals(own))PALE_IN.increment();
    }
    public static String forcedPalette(){
        String value=System.getenv("FG_ECOTONE_FORCE_PALETTE");
        if(!"minecraft:pale_garden".equals(value)&&!"minecraft:forest".equals(value))return null;
        FORCED.increment();return value;
    }
}
