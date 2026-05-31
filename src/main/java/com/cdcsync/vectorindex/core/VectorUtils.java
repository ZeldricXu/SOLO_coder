package com.cdcsync.vectorindex.core;

public class VectorUtils {

    private VectorUtils() {
    }

    public static float cosineSimilarity(float[] v1, float[] v2) {
        float dotProduct = 0.0f;
        float norm1 = 0.0f;
        float norm2 = 0.0f;
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
            norm1 += v1[i] * v1[i];
            norm2 += v2[i] * v2[i];
        }
        if (norm1 == 0.0f || norm2 == 0.0f) {
            return 0.0f;
        }
        return (float) (dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2)));
    }

    public static float euclideanDistance(float[] v1, float[] v2) {
        float sum = 0.0f;
        for (int i = 0; i < v1.length; i++) {
            float diff = v1[i] - v2[i];
            sum += diff * diff;
        }
        return (float) Math.sqrt(sum);
    }

    public static float innerProduct(float[] v1, float[] v2) {
        float dotProduct = 0.0f;
        for (int i = 0; i < v1.length; i++) {
            dotProduct += v1[i] * v2[i];
        }
        return dotProduct;
    }

    public static float[] normalize(float[] vector) {
        float norm = 0.0f;
        for (float v : vector) {
            norm += v * v;
        }
        norm = (float) Math.sqrt(norm);
        if (norm == 0.0f) {
            return vector.clone();
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / norm;
        }
        return normalized;
    }

    public static float distance(float[] v1, float[] v2, String metricType) {
        return switch (metricType.toUpperCase()) {
            case "COSINE" -> 1.0f - cosineSimilarity(v1, v2);
            case "EUCLIDEAN", "L2" -> euclideanDistance(v1, v2);
            case "INNER_PRODUCT", "IP" -> -innerProduct(v1, v2);
            default -> euclideanDistance(v1, v2);
        };
    }

    public static void validateDimension(float[] vector, int expected) {
        if (vector.length != expected) {
            throw new IllegalArgumentException(
                "Vector dimension mismatch: expected " + expected + ", got " + vector.length
            );
        }
    }
}
