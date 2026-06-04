package com.proteinviewer.dto;

import java.util.List;

public class ElectrostaticSurfaceDto {
    private Long structureId;
    private List<Float> vertices;
    private List<Integer> indices;
    private List<Float> potentials;
    private double minPotential;
    private double maxPotential;
    private int gridResolution;

    public ElectrostaticSurfaceDto() {}

    public Long getStructureId() { return structureId; }
    public void setStructureId(Long structureId) { this.structureId = structureId; }
    public List<Float> getVertices() { return vertices; }
    public void setVertices(List<Float> vertices) { this.vertices = vertices; }
    public List<Integer> getIndices() { return indices; }
    public void setIndices(List<Integer> indices) { this.indices = indices; }
    public List<Float> getPotentials() { return potentials; }
    public void setPotentials(List<Float> potentials) { this.potentials = potentials; }
    public double getMinPotential() { return minPotential; }
    public void setMinPotential(double minPotential) { this.minPotential = minPotential; }
    public double getMaxPotential() { return maxPotential; }
    public void setMaxPotential(double maxPotential) { this.maxPotential = maxPotential; }
    public int getGridResolution() { return gridResolution; }
    public void setGridResolution(int gridResolution) { this.gridResolution = gridResolution; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final ElectrostaticSurfaceDto r = new ElectrostaticSurfaceDto();
        public Builder structureId(Long v) { r.structureId = v; return this; }
        public Builder vertices(List<Float> v) { r.vertices = v; return this; }
        public Builder indices(List<Integer> v) { r.indices = v; return this; }
        public Builder potentials(List<Float> v) { r.potentials = v; return this; }
        public Builder minPotential(double v) { r.minPotential = v; return this; }
        public Builder maxPotential(double v) { r.maxPotential = v; return this; }
        public Builder gridResolution(int v) { r.gridResolution = v; return this; }
        public ElectrostaticSurfaceDto build() { return r; }
    }
}
