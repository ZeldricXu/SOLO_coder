package com.cardgame.common.utils;

import java.util.Random;

public class SeededRandom {
    private final Random random;
    private final long seed;

    public SeededRandom(long seed) {
        this.seed = seed;
        this.random = new Random(seed);
    }

    public long getSeed() {
        return seed;
    }

    public int nextInt() {
        return random.nextInt();
    }

    public int nextInt(int bound) {
        return random.nextInt(bound);
    }

    public int nextInt(int min, int max) {
        return random.nextInt(max - min + 1) + min;
    }

    public long nextLong() {
        return random.nextLong();
    }

    public boolean nextBoolean() {
        return random.nextBoolean();
    }

    public double nextDouble() {
        return random.nextDouble();
    }

    public float nextFloat() {
        return random.nextFloat();
    }

    public <T> T pickRandom(T[] array) {
        if (array == null || array.length == 0) {
            return null;
        }
        return array[nextInt(array.length)];
    }

    public <T> T pickRandom(java.util.List<T> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(nextInt(list.size()));
    }

    public void shuffle(java.util.List<?> list) {
        java.util.Collections.shuffle(list, random);
    }
}
