package com.restaurant.mgmt.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "tables")
public class RestaurantTable {
    @Id
    @Column(name = "table_id")
    private String tableId;

    @Column(name = "table_number", nullable = false, unique = true)
    private String tableNumber;

    @Column(name = "table_type")
    private String tableType;

    @Column(name = "table_capacity")
    private int tableCapacity;

    @Column(name = "table_status")
    private String tableStatus;

    @Column(name = "reserve_time")
    private LocalDateTime reserveTime;

    @Column(name = "reserve_customer_name")
    private String reserveCustomerName;

    @Column(name = "reserve_customer_phone")
    private String reserveCustomerPhone;

    @Column(name = "reserve_remark")
    private String reserveRemark;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public RestaurantTable() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.tableStatus = "available";
    }

    public String getTableId() {
        return tableId;
    }

    public void setTableId(String tableId) {
        this.tableId = tableId;
    }

    public String getTableNumber() {
        return tableNumber;
    }

    public void setTableNumber(String tableNumber) {
        this.tableNumber = tableNumber;
    }

    public String getTableType() {
        return tableType;
    }

    public void setTableType(String tableType) {
        this.tableType = tableType;
    }

    public int getTableCapacity() {
        return tableCapacity;
    }

    public void setTableCapacity(int tableCapacity) {
        this.tableCapacity = tableCapacity;
    }

    public String getTableStatus() {
        return tableStatus;
    }

    public void setTableStatus(String tableStatus) {
        this.tableStatus = tableStatus;
    }

    public LocalDateTime getReserveTime() {
        return reserveTime;
    }

    public void setReserveTime(LocalDateTime reserveTime) {
        this.reserveTime = reserveTime;
    }

    public String getReserveCustomerName() {
        return reserveCustomerName;
    }

    public void setReserveCustomerName(String reserveCustomerName) {
        this.reserveCustomerName = reserveCustomerName;
    }

    public String getReserveCustomerPhone() {
        return reserveCustomerPhone;
    }

    public void setReserveCustomerPhone(String reserveCustomerPhone) {
        this.reserveCustomerPhone = reserveCustomerPhone;
    }

    public String getReserveRemark() {
        return reserveRemark;
    }

    public void setReserveRemark(String reserveRemark) {
        this.reserveRemark = reserveRemark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
