package com.cdcsync.test.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class TestDataFactory {

    private static final Random RANDOM = new Random(42);

    private TestDataFactory() {
    }

    public static float[] createRandomVector(int dimension) {
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) {
            vector[i] = RANDOM.nextFloat() * 2 - 1;
        }
        return vector;
    }

    public static List<float[]> createRandomVectors(int count, int dimension) {
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            vectors.add(createRandomVector(dimension));
        }
        return vectors;
    }

    public static float[] createUnitVector(int dimension, int axis) {
        float[] vector = new float[dimension];
        if (axis >= 0 && axis < dimension) {
            vector[axis] = 1.0f;
        }
        return vector;
    }

    public static float[] createZeroVector(int dimension) {
        return new float[dimension];
    }

    public static String createValidSelectSql() {
        return "SELECT id, name, email FROM users WHERE status = 'active' ORDER BY created_at DESC";
    }

    public static String createSelectSqlWithFilter() {
        return "SELECT u.id, u.name FROM users u WHERE u.age > 18 AND u.country = 'CN'";
    }

    public static String createSelectSqlWithJoin() {
        return "SELECT o.id, u.name, o.total FROM orders o JOIN users u ON o.user_id = u.id";
    }

    public static String createInvalidSql() {
        return "SELECT * FROM WHERE id = 1";
    }

    public static String createNonSelectSql() {
        return "INSERT INTO users (name) VALUES ('test')";
    }
}
