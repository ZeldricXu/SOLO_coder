package com.proteinviewer.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "protein_structures")
public class ProteinStructure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String pdbId;

    @Column(length = 20)
    private String chainId;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String storageKey;

    private Long fileSize;

    private Integer atomCount;

    private Integer residueCount;

    private Integer bondCount;

    @Column(length = 10)
    private String depositionDate;

    @Column(length = 200)
    private String title;

    @Column(length = 50)
    private String organism;

    @Column(length = 50)
    private String technique;

    private Double resolution;

    @Column(nullable = false)
    private Long projectId;

    @Column(nullable = false)
    private Long uploadedBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    public ProteinStructure() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPdbId() { return pdbId; }
    public void setPdbId(String pdbId) { this.pdbId = pdbId; }
    public String getChainId() { return chainId; }
    public void setChainId(String chainId) { this.chainId = chainId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public Integer getAtomCount() { return atomCount; }
    public void setAtomCount(Integer atomCount) { this.atomCount = atomCount; }
    public Integer getResidueCount() { return residueCount; }
    public void setResidueCount(Integer residueCount) { this.residueCount = residueCount; }
    public Integer getBondCount() { return bondCount; }
    public void setBondCount(Integer bondCount) { this.bondCount = bondCount; }
    public String getDepositionDate() { return depositionDate; }
    public void setDepositionDate(String depositionDate) { this.depositionDate = depositionDate; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getOrganism() { return organism; }
    public void setOrganism(String organism) { this.organism = organism; }
    public String getTechnique() { return technique; }
    public void setTechnique(String technique) { this.technique = technique; }
    public Double getResolution() { return resolution; }
    public void setResolution(Double resolution) { this.resolution = resolution; }
    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }
    public Long getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(Long uploadedBy) { this.uploadedBy = uploadedBy; }
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
        private final ProteinStructure r = new ProteinStructure();
        public Builder id(Long v) { r.id = v; return this; }
        public Builder name(String v) { r.name = v; return this; }
        public Builder pdbId(String v) { r.pdbId = v; return this; }
        public Builder chainId(String v) { r.chainId = v; return this; }
        public Builder fileName(String v) { r.fileName = v; return this; }
        public Builder storageKey(String v) { r.storageKey = v; return this; }
        public Builder fileSize(Long v) { r.fileSize = v; return this; }
        public Builder atomCount(Integer v) { r.atomCount = v; return this; }
        public Builder residueCount(Integer v) { r.residueCount = v; return this; }
        public Builder bondCount(Integer v) { r.bondCount = v; return this; }
        public Builder depositionDate(String v) { r.depositionDate = v; return this; }
        public Builder title(String v) { r.title = v; return this; }
        public Builder organism(String v) { r.organism = v; return this; }
        public Builder technique(String v) { r.technique = v; return this; }
        public Builder resolution(Double v) { r.resolution = v; return this; }
        public Builder projectId(Long v) { r.projectId = v; return this; }
        public Builder uploadedBy(Long v) { r.uploadedBy = v; return this; }
        public Builder createdAt(LocalDateTime v) { r.createdAt = v; return this; }
        public ProteinStructure build() { return r; }
    }
}
