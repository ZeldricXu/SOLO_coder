package com.schedulebook.model;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "resources")
public class Resource {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "resource_id", unique = true, nullable = false, length = 50)
    private String resourceId;
    
    @Column(name = "resource_name", nullable = false, length = 100)
    private String resourceName;
    
    @Column(name = "resource_type", nullable = false, length = 50)
    private String resourceType;
    
    @Column(name = "resource_capacity")
    private Integer resourceCapacity;
    
    @Column(name = "resource_status", nullable = false, length = 50)
    private String resourceStatus = "available";
    
    @Column(name = "resource_location", length = 100)
    private String resourceLocation;
    
    @Column(name = "available_hours", length = 255)
    private String availableHours;
    
    @Column(name = "priority")
    private Integer priority = 0;
    
    @Column(name = "current_occupancy")
    private Integer currentOccupancy = 0;
    
    public Resource() {
    }
    
    // Getters and Setters
    public Long getId() {
        return id;
    }
    
    public void setId(Long id) {
        this.id = id;
    }
    
    public String getResourceId() {
        return resourceId;
    }
    
    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }
    
    public String getResourceName() {
        return resourceName;
    }
    
    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }
    
    public String getResourceType() {
        return resourceType;
    }
    
    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }
    
    public Integer getResourceCapacity() {
        return resourceCapacity;
    }
    
    public void setResourceCapacity(Integer resourceCapacity) {
        this.resourceCapacity = resourceCapacity;
    }
    
    public String getResourceStatus() {
        return resourceStatus;
    }
    
    public void setResourceStatus(String resourceStatus) {
        this.resourceStatus = resourceStatus;
    }
    
    public String getResourceLocation() {
        return resourceLocation;
    }
    
    public void setResourceLocation(String resourceLocation) {
        this.resourceLocation = resourceLocation;
    }
    
    public String getAvailableHours() {
        return availableHours;
    }
    
    public void setAvailableHours(String availableHours) {
        this.availableHours = availableHours;
    }
    
    public Integer getPriority() {
        return priority;
    }
    
    public void setPriority(Integer priority) {
        this.priority = priority;
    }
    
    public Integer getCurrentOccupancy() {
        return currentOccupancy;
    }
    
    public void setCurrentOccupancy(Integer currentOccupancy) {
        this.currentOccupancy = currentOccupancy;
    }
}
