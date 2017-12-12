package ru.maklas.mrudp;

import java.util.Locale;

/**
 * Created by amaklakov on 12.09.2017.
 */
public class SimpleProfiler {

    private static long time;

    public static void start(){
        time = System.nanoTime();
    }


    public static String getTimeAsString(){
        return Double.toString(getTime());
    }

    public static String getTimeAsString(int numbersAfterComma){
        return floatFormatted((float)getTime(), numbersAfterComma);
    }

    public static double getTime(){
        long diff = System.nanoTime() - time;
        return ((double)(diff))/1000000000d;
    }

    public static long getNano(){
        return System.nanoTime() - time;
    }


    public static double getMS(){
        return (System.nanoTime() - time)/1000000d;
    }





    public static String floatFormatted(float f, int numbersAfterComma){
        return String.format(Locale.ENGLISH, "%.0"+ numbersAfterComma + "f", f);
    }

}
