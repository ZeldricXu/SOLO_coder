package com.proteinviewer.render;

import java.util.List;

public final class SurfaceMesh {
    private final List<Float> vertices;
    private final List<Integer> indices;
    private final List<Float> potentials;
    private final double minPotential;
    private final double maxPotential;
    private final int gridResolution;

    public SurfaceMesh(List<Float> vertices, List<Integer> indices, List<Float> potentials,
                       double minPotential, double maxPotential, int gridResolution) {
        this.vertices = vertices;
        this.indices = indices;
        this.potentials = potentials;
        this.minPotential = minPotential;
        this.maxPotential = maxPotential;
        this.gridResolution = gridResolution;
    }

    public List<Float> getVertices() { return vertices; }
    public List<Integer> getIndices() { return indices; }
    public List<Float> getPotentials() { return potentials; }
    public double getMinPotential() { return minPotential; }
    public double getMaxPotential() { return maxPotential; }
    public int getGridResolution() { return gridResolution; }
}
