package com.proteinviewer.dto;

public class SnapshotDto {
    private String shortId;
    private Long structureId;
    private double cameraPositionX;
    private double cameraPositionY;
    private double cameraPositionZ;
    private double cameraTargetX;
    private double cameraTargetY;
    private double cameraTargetZ;
    private double cameraUpX;
    private double cameraUpY;
    private double cameraUpZ;
    private double cameraZoom;
    private String renderMode;
    private String colorScheme;
    private String annotationsFilter;
    private String createdAt;

    public SnapshotDto() {}

    public String getShortId() { return shortId; }
    public void setShortId(String shortId) { this.shortId = shortId; }
    public Long getStructureId() { return structureId; }
    public void setStructureId(Long structureId) { this.structureId = structureId; }
    public double getCameraPositionX() { return cameraPositionX; }
    public void setCameraPositionX(double cameraPositionX) { this.cameraPositionX = cameraPositionX; }
    public double getCameraPositionY() { return cameraPositionY; }
    public void setCameraPositionY(double cameraPositionY) { this.cameraPositionY = cameraPositionY; }
    public double getCameraPositionZ() { return cameraPositionZ; }
    public void setCameraPositionZ(double cameraPositionZ) { this.cameraPositionZ = cameraPositionZ; }
    public double getCameraTargetX() { return cameraTargetX; }
    public void setCameraTargetX(double cameraTargetX) { this.cameraTargetX = cameraTargetX; }
    public double getCameraTargetY() { return cameraTargetY; }
    public void setCameraTargetY(double cameraTargetY) { this.cameraTargetY = cameraTargetY; }
    public double getCameraTargetZ() { return cameraTargetZ; }
    public void setCameraTargetZ(double cameraTargetZ) { this.cameraTargetZ = cameraTargetZ; }
    public double getCameraUpX() { return cameraUpX; }
    public void setCameraUpX(double cameraUpX) { this.cameraUpX = cameraUpX; }
    public double getCameraUpY() { return cameraUpY; }
    public void setCameraUpY(double cameraUpY) { this.cameraUpY = cameraUpY; }
    public double getCameraUpZ() { return cameraUpZ; }
    public void setCameraUpZ(double cameraUpZ) { this.cameraUpZ = cameraUpZ; }
    public double getCameraZoom() { return cameraZoom; }
    public void setCameraZoom(double cameraZoom) { this.cameraZoom = cameraZoom; }
    public String getRenderMode() { return renderMode; }
    public void setRenderMode(String renderMode) { this.renderMode = renderMode; }
    public String getColorScheme() { return colorScheme; }
    public void setColorScheme(String colorScheme) { this.colorScheme = colorScheme; }
    public String getAnnotationsFilter() { return annotationsFilter; }
    public void setAnnotationsFilter(String annotationsFilter) { this.annotationsFilter = annotationsFilter; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final SnapshotDto r = new SnapshotDto();
        public Builder shortId(String v) { r.shortId = v; return this; }
        public Builder structureId(Long v) { r.structureId = v; return this; }
        public Builder cameraPositionX(double v) { r.cameraPositionX = v; return this; }
        public Builder cameraPositionY(double v) { r.cameraPositionY = v; return this; }
        public Builder cameraPositionZ(double v) { r.cameraPositionZ = v; return this; }
        public Builder cameraTargetX(double v) { r.cameraTargetX = v; return this; }
        public Builder cameraTargetY(double v) { r.cameraTargetY = v; return this; }
        public Builder cameraTargetZ(double v) { r.cameraTargetZ = v; return this; }
        public Builder cameraUpX(double v) { r.cameraUpX = v; return this; }
        public Builder cameraUpY(double v) { r.cameraUpY = v; return this; }
        public Builder cameraUpZ(double v) { r.cameraUpZ = v; return this; }
        public Builder cameraZoom(double v) { r.cameraZoom = v; return this; }
        public Builder renderMode(String v) { r.renderMode = v; return this; }
        public Builder colorScheme(String v) { r.colorScheme = v; return this; }
        public Builder annotationsFilter(String v) { r.annotationsFilter = v; return this; }
        public Builder createdAt(String v) { r.createdAt = v; return this; }
        public SnapshotDto build() { return r; }
    }
}
