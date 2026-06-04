package com.proteinviewer.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "annotations")
public class Annotation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long structureId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String label;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String shapeData;

    @Column(nullable = false)
    private Double positionX;

    @Column(nullable = false)
    private Double positionY;

    @Column(nullable = false)
    private Double positionZ;

    private String color;

    private Boolean visible = true;

    private Long createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Version
    private Integer version;

    public Annotation() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStructureId() { return structureId; }
    public void setStructureId(Long structureId) { this.structureId = structureId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getShapeData() { return shapeData; }
    public void setShapeData(String shapeData) { this.shapeData = shapeData; }
    public Double getPositionX() { return positionX; }
    public void setPositionX(Double positionX) { this.positionX = positionX; }
    public Double getPositionY() { return positionY; }
    public void setPositionY(Double positionY) { this.positionY = positionY; }
    public Double getPositionZ() { return positionZ; }
    public void setPositionZ(Double positionZ) { this.positionZ = positionZ; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public Boolean getVisible() { return visible; }
    public void setVisible(Boolean visible) { this.visible = visible; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (version == null) {
            version = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final Annotation r = new Annotation();
        public Builder id(Long v) { r.id = v; return this; }
        public Builder structureId(Long v) { r.structureId = v; return this; }
        public Builder type(String v) { r.type = v; return this; }
        public Builder label(String v) { r.label = v; return this; }
        public Builder description(String v) { r.description = v; return this; }
        public Builder shapeData(String v) { r.shapeData = v; return this; }
        public Builder positionX(Double v) { r.positionX = v; return this; }
        public Builder positionY(Double v) { r.positionY = v; return this; }
        public Builder positionZ(Double v) { r.positionZ = v; return this; }
        public Builder color(String v) { r.color = v; return this; }
        public Builder visible(Boolean v) { r.visible = v; return this; }
        public Builder createdBy(Long v) { r.createdBy = v; return this; }
        public Builder createdAt(LocalDateTime v) { r.createdAt = v; return this; }
        public Builder updatedAt(LocalDateTime v) { r.updatedAt = v; return this; }
        public Builder version(Integer v) { r.version = v; return this; }
        public Annotation build() { return r; }
    }
}
