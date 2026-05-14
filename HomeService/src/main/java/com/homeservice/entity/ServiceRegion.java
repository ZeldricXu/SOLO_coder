package com.homeservice.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "service_regions")
public class ServiceRegion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "region_code", unique = true, nullable = false)
    private String regionCode;

    @Column(name = "region_name", nullable = false)
    private String regionName;

    @Column(name = "province")
    private String province;

    @Column(name = "city")
    private String city;

    @Column(name = "district")
    private String district;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

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

    public ServiceRegion() {}

    public ServiceRegion(String regionCode, String regionName, String province, String city, String district) {
        this.regionCode = regionCode;
        this.regionName = regionName;
        this.province = province;
        this.city = city;
        this.district = district;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRegionCode() { return regionCode; }
    public void setRegionCode(String regionCode) { this.regionCode = regionCode; }
    public String getRegionName() { return regionName; }
    public void setRegionName(String regionName) { this.regionName = regionName; }
    public String getProvince() { return province; }
    public void setProvince(String province) { this.province = province; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getDistrict() { return district; }
    public void setDistrict(String district) { this.district = district; }
    public Boolean getIsActive() { return isActive; }
    public void setIsActive(Boolean isActive) { this.isActive = isActive; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
