package com.proteinviewer.surface;

import com.proteinviewer.render.SurfaceMesh;
import java.util.*;

public class SurfaceSmoother {

    public static SurfaceMesh smooth(SurfaceMesh mesh, int iterations) {
        if (iterations <= 0 || mesh.getVertices().isEmpty()) return mesh;

        List<Float> srcVertices = mesh.getVertices();
        int vertexCount = srcVertices.size() / 3;

        double[][] positions = new double[vertexCount][3];
        for (int i = 0; i < vertexCount; i++) {
            positions[i][0] = srcVertices.get(i * 3);
            positions[i][1] = srcVertices.get(i * 3 + 1);
            positions[i][2] = srcVertices.get(i * 3 + 2);
        }

        List<Set<Integer>> adjacency = new ArrayList<>();
        for (int i = 0; i < vertexCount; i++) {
            adjacency.add(new HashSet<>());
        }

        List<Integer> indices = mesh.getIndices();
        for (int i = 0; i < indices.size(); i += 3) {
            int a = indices.get(i);
            int b = indices.get(i + 1);
            int c = indices.get(i + 2);
            adjacency.get(a).add(b); adjacency.get(a).add(c);
            adjacency.get(b).add(a); adjacency.get(b).add(c);
            adjacency.get(c).add(a); adjacency.get(c).add(b);
        }

        double[][] current = positions;
        for (int iter = 0; iter < iterations; iter++) {
            double[][] next = new double[vertexCount][3];
            for (int i = 0; i < vertexCount; i++) {
                Set<Integer> neighbors = adjacency.get(i);
                if (neighbors.isEmpty()) {
                    next[i][0] = current[i][0];
                    next[i][1] = current[i][1];
                    next[i][2] = current[i][2];
                } else {
                    double avgX = 0, avgY = 0, avgZ = 0;
                    for (int n : neighbors) {
                        avgX += current[n][0];
                        avgY += current[n][1];
                        avgZ += current[n][2];
                    }
                    int count = neighbors.size();
                    avgX /= count; avgY /= count; avgZ /= count;
                    next[i][0] = current[i][0] + 0.5 * (avgX - current[i][0]);
                    next[i][1] = current[i][1] + 0.5 * (avgY - current[i][1]);
                    next[i][2] = current[i][2] + 0.5 * (avgZ - current[i][2]);
                }
            }
            current = next;
        }

        List<Float> smoothedVertices = new ArrayList<>(srcVertices.size());
        for (int i = 0; i < vertexCount; i++) {
            smoothedVertices.add((float) current[i][0]);
            smoothedVertices.add((float) current[i][1]);
            smoothedVertices.add((float) current[i][2]);
        }

        return new SurfaceMesh(smoothedVertices, mesh.getIndices(), mesh.getPotentials(),
                mesh.getMinPotential(), mesh.getMaxPotential(), mesh.getGridResolution());
    }
}
