package ru.maklas.mrudp;

class NTPInterractionData {

    public long t0;
    public long t1;
    public long t2;
    public long t3;

    public NTPInterractionData(long t0, long t1, long t2, long t3) {
        this.t0 = t0;
        this.t1 = t1;
        this.t2 = t2;
        this.t3 = t3;
    }

    public double calculateOffset(){
        return ((t1 - t0) + (t2 - t3)) / 2.0;
    }

    public float calculateDelay(){
        return (t3 - t0) - (t2 - t1);
    }
}
