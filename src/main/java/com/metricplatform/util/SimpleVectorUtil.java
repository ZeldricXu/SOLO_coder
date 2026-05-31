package com.metricplatform.util;

import java.util.Random;

public class SimpleVectorUtil {

    private static final Random RANDOM = new Random(42);

    public static float[] generateRandomVector(int dimension) {
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = (RANDOM.nextFloat() - 0.5f) * 2;
        }
        normalize(vector);
        return vector;
    }

    public static float[] generateVectorFromText(String text, int dimension) {
        float[] vector = new float[dimension];
        byte[] bytes = text.getBytes();
        for (int i = 0; i < dimension; i++) {
            float sum = 0;
            for (int j = 0; j < bytes.length; j++) {
                sum += bytes[j] * Math.sin((j + i) * 0.1);
            }
            vector[i] = (float) Math.tanh(sum / 100.0);
        }
        normalize(vector);
        return vector;
    }

    public static void normalize(float[] vector) {
        float norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
    }

    public static float cosineSimilarity(float[] v1, float[] v2) {
        if (v1.length != v2.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }
        float dot = 0;
        float norm1 = 0;
        float norm2 = 0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }
        norm1 = (float) Math.sqrt(norm1);
        norm2 = (float) Math.sqrt(norm2);
        if (norm1 == 0 || norm2 == 0) {
            return 0;
        }
        return dot / (norm1 * norm2);
    }

    public static float euclideanDistance(float[] v1, float[] v2) {
        if (v1.length != v2.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }
        float sum = 0;
        for (int i = 0; i < v1.length; i++) {
            float diff = v1[i] - v2[i];
            sum += diff * diff;
        }
        return (float) Math.sqrt(sum);
    }

    public static float innerProduct(float[] v1, float[] v2) {
        if (v1.length != v2.length) {
            throw new IllegalArgumentException("向量维度不匹配");
        }
        float dot = 0;
        for (int i = 0; i < v1.length; i++) {
            dot += v1[i] * v2[i];
        }
        return dot;
    }
}
