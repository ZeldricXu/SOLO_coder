package com.monitoring.anomaly.cache;

import java.util.Arrays;

public final class EvictingCircularBuffer {

    private static final int DEFAULT_CAPACITY = 2048;

    private final double[] buffer;
    private int head;
    private int size;
    private double sum;

    public EvictingCircularBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public EvictingCircularBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        this.buffer = new double[capacity];
        this.head = 0;
        this.size = 0;
        this.sum = 0.0;
    }

    public synchronized void add(double value) {
        int capacity = buffer.length;
        if (size == capacity) {
            sum -= buffer[head];
        } else {
            size++;
        }
        buffer[head] = value;
        sum += value;
        head = (head + 1) % capacity;
    }

    public synchronized double getMean() {
        return size > 0 ? sum / size : 0.0;
    }

    public synchronized double getVariance(double mean) {
        if (size < 2) {
            return 0.0;
        }
        double sumSquaredDiff = 0.0;
        int capacity = buffer.length;
        int start = size == capacity ? head : 0;
        int count = size;
        for (int i = 0; i < count; i++) {
            int idx = (start + i) % capacity;
            double diff = buffer[idx] - mean;
            sumSquaredDiff += diff * diff;
        }
        return sumSquaredDiff / size;
    }

    public synchronized double getStdDev(double mean) {
        return Math.sqrt(getVariance(mean));
    }

    public synchronized double[] getRecentValues(int count) {
        int actualCount = Math.min(count, size);
        double[] result = new double[actualCount];
        int capacity = buffer.length;
        int start = (head - actualCount + capacity) % capacity;
        for (int i = 0; i < actualCount; i++) {
            result[i] = buffer[(start + i) % capacity];
        }
        return result;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized boolean isEmpty() {
        return size == 0;
    }

    public synchronized void clear() {
        head = 0;
        size = 0;
        sum = 0.0;
        Arrays.fill(buffer, 0.0);
    }

    public synchronized void fillFrom(List<Double> values) {
        clear();
        int count = Math.min(values.size(), buffer.length);
        int start = values.size() - count;
        for (int i = start; i < values.size(); i++) {
            add(values.get(i));
        }
    }
}
