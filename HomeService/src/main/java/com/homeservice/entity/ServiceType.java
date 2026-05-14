package com.homeservice.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "service_types")
public class ServiceType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "type_code", unique = true, nullable = false)
    private String typeCode;

    @Column(name = "type_name", nullable = false)
    private String typeName;

    @Column(name = "description")
    private String description;

    @Column(name = "base_price", nullable = false)
    private Double basePrice;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "supported_regions")
    private String supportedRegions;

    @Column(name = "min_duration")
    private Integer minDuration;

    @Column(name = "max_duration")
    private Integer maxDuration;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public ServiceType() {}

    public ServiceType(String typeCode, String typeName, String description, Double basePrice) {
        this.typeCode = typeCode;
        this.typeName = typeName;
        this.description = description;
        this.basePrice = basePrice;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTypeCode() { return typeCode; }
    public void setTypeCode(String typeCode) { this.typeCode = typeCode; }
    public String getTypeName() { return typeName; }
    public void setTypeName(String typeName) { this.typeName = typeName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Double getBasePrice() { return basePrice; }
    public void setBasePrice(Double basePrice) { this.basePrice = basePrice; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public String getSupportedRegions() { return supportedRegions; }
    public void setSupportedRegions(String supportedRegions) { this.supportedRegions = supportedRegions; }
    public Integer getMinDuration() { return minDuration; }
    public void setMinDuration(Integer minDuration) { this.minDuration = minDuration; }
    public Integer getMaxDuration() { return maxDuration; }
    public void setMaxDuration(Integer maxDuration) { this.maxDuration = maxDuration; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
