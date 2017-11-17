package ru.maklas.mrudp.impl;

import ru.maklas.mrudp.impl.RouterImpl;

import java.util.Random;

public class LaggyRouter extends RouterImpl {

    private final Random rand = new Random();
    private double losePercent;

    public LaggyRouter(double losePercent) {
        if (losePercent <0 || losePercent >100){
            throw new RuntimeException("Bad losePercent. Must be >0% && <100%!");
        }
        this.losePercent = losePercent / 100;
    }

    @Override
    protected boolean filter(RouterUDP source, RouterUDP destination, RouterImpl.Data data) {
        double random;
        synchronized (rand) {
            random = rand.nextDouble();
        }
        return random > losePercent;
    }

    public double getSuccessChance(int numberOfTries){
        double leftOvers = 1;
        double chance = 0;
        double successPercent = 1 - losePercent;
        for (int i = 0; i < numberOfTries; i++) {
            double reqSuccesses = leftOvers * successPercent;
            double respSuccesses = reqSuccesses * successPercent;
            chance += respSuccesses;
            leftOvers = 1-chance;
        }

        return chance * 100;
    }
}
