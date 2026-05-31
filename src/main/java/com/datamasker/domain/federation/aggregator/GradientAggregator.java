package com.datamasker.domain.federation.aggregator;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Component
public class GradientAggregator {

    public String aggregateGradients(List<String> encryptedGradients, int participantCount) {
        if (encryptedGradients == null || encryptedGradients.isEmpty()) {
            throw new IllegalArgumentException("No gradients to aggregate");
        }

        List<double[]> decodedGradients = new ArrayList<>();
        int dimension = -1;

        for (String encoded : encryptedGradients) {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decoded.split(",");
            double[] values = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                values[i] = Double.parseDouble(parts[i].trim());
            }
            if (dimension == -1) {
                dimension = values.length;
            }
            decodedGradients.add(values);
        }

        double[] averaged = new double[dimension];
        for (double[] gradient : decodedGradients) {
            for (int i = 0; i < dimension; i++) {
                averaged[i] += gradient[i];
            }
        }
        for (int i = 0; i < dimension; i++) {
            averaged[i] /= decodedGradients.size();
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < averaged.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(averaged[i]);
        }

        return Base64.getEncoder().encodeToString(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    public String computeModelHash(String aggregatedGradient) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(aggregatedGradient.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    public double computeConvergence(List<String> gradients) {
        if (gradients == null || gradients.size() < 2) {
            return 1.0;
        }

        List<double[]> decodedGradients = new ArrayList<>();
        for (String encoded : gradients) {
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            String[] parts = decoded.split(",");
            double[] values = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                values[i] = Double.parseDouble(parts[i].trim());
            }
            decodedGradients.add(values);
        }

        double totalDistance = 0.0;
        int pairCount = 0;

        for (int i = 0; i < decodedGradients.size(); i++) {
            for (int j = i + 1; j < decodedGradients.size(); j++) {
                double[] g1 = decodedGradients.get(i);
                double[] g2 = decodedGradients.get(j);
                double sumSqDiff = 0.0;
                for (int k = 0; k < g1.length; k++) {
                    double diff = g1[k] - g2[k];
                    sumSqDiff += diff * diff;
                }
                totalDistance += Math.sqrt(sumSqDiff);
                pairCount++;
            }
        }

        double variance = (pairCount > 0) ? totalDistance / pairCount : 0.0;
        return 1.0 / (1.0 + variance);
    }
}
