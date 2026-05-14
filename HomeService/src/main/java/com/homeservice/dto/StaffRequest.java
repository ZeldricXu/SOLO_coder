package com.homeservice.dto;

public class StaffRequest {
    private String staffName;
    private String staffType;
    private String staffPhone;
    private String staffRegion;
    private Double staffPrice;

    public StaffRequest() {}

    public String getStaffName() { return staffName; }
    public void setStaffName(String staffName) { this.staffName = staffName; }
    public String getStaffType() { return staffType; }
    public void setStaffType(String staffType) { this.staffType = staffType; }
    public String getStaffPhone() { return staffPhone; }
    public void setStaffPhone(String staffPhone) { this.staffPhone = staffPhone; }
    public String getStaffRegion() { return staffRegion; }
    public void setStaffRegion(String staffRegion) { this.staffRegion = staffRegion; }
    public Double getStaffPrice() { return staffPrice; }
    public void setStaffPrice(Double staffPrice) { this.staffPrice = staffPrice; }
}
