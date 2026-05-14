package com.homeservice.dto;

public class CustomerRequest {
    private String customerName;
    private String customerPhone;
    private String customerAddress;
    private String customerRegion;

    public CustomerRequest() {}

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public String getCustomerPhone() { return customerPhone; }
    public void setCustomerPhone(String customerPhone) { this.customerPhone = customerPhone; }
    public String getCustomerAddress() { return customerAddress; }
    public void setCustomerAddress(String customerAddress) { this.customerAddress = customerAddress; }
    public String getCustomerRegion() { return customerRegion; }
    public void setCustomerRegion(String customerRegion) { this.customerRegion = customerRegion; }
}
