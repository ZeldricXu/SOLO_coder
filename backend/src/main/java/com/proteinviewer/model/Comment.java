package com.proteinviewer.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "comments")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long structureId;

    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private Double anchorX;

    @Column(nullable = false)
    private Double anchorY;

    @Column(nullable = false)
    private Double anchorZ;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public Comment() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getStructureId() { return structureId; }
    public void setStructureId(Long structureId) { this.structureId = structureId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Double getAnchorX() { return anchorX; }
    public void setAnchorX(Double anchorX) { this.anchorX = anchorX; }
    public Double getAnchorY() { return anchorY; }
    public void setAnchorY(Double anchorY) { this.anchorY = anchorY; }
    public Double getAnchorZ() { return anchorZ; }
    public void setAnchorZ(Double anchorZ) { this.anchorZ = anchorZ; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public static Builder builder() { return new Builder(); }
    public static class Builder {
        private final Comment r = new Comment();
        public Builder id(Long v) { r.id = v; return this; }
        public Builder structureId(Long v) { r.structureId = v; return this; }
        public Builder content(String v) { r.content = v; return this; }
        public Builder anchorX(Double v) { r.anchorX = v; return this; }
        public Builder anchorY(Double v) { r.anchorY = v; return this; }
        public Builder anchorZ(Double v) { r.anchorZ = v; return this; }
        public Builder userId(Long v) { r.userId = v; return this; }
        public Builder createdAt(LocalDateTime v) { r.createdAt = v; return this; }
        public Comment build() { return r; }
    }
}
