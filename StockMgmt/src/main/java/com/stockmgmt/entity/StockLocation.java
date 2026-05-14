package com.stockmgmt.entity;

import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.GenericGenerator;
import org.hibernate.annotations.UpdateTimestamp;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "stock_location", indexes = {
    @Index(name = "idx_warehouse", columnList = "warehouse_id"),
    @Index(name = "idx_location_code", columnList = "location_code", unique = true)
})
public class StockLocation {

    @Id
    @GeneratedValue(generator = "system-uuid")
    @GenericGenerator(name = "system-uuid", strategy = "uuid")
    @Column(name = "location_id", length = 36)
    private String locationId;

    @Column(name = "warehouse_id", nullable = false, length = 50)
    private String warehouseId;

    @Column(name = "location_code", nullable = false, unique = true, length = 50)
    private String locationCode;

    @Column(name = "location_name", length = 100)
    private String locationName;

    @Column(name = "zone", length = 50)
    private String zone;

    @Column(name = "aisle", length = 20)
    private String aisle;

    @Column(name = "rack", length = 20)
    private String rack;

    @Column(name = "level", length = 20)
    private String level;

    @Column(name = "capacity")
    private Integer capacity;

    @Column(name = "status", length = 20)
    private String status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
