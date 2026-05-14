package com.hotelbooking.dto;

import jakarta.validation.constraints.NotBlank;

public class CheckInRequest {
    @NotBlank(message = "预订ID不能为空")
    private String bookingId;

    @NotBlank(message = "证件号码不能为空")
    private String customerIdNumber;

    private String customerIdType;

    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public String getCustomerIdNumber() { return customerIdNumber; }
    public void setCustomerIdNumber(String customerIdNumber) { this.customerIdNumber = customerIdNumber; }
    public String getCustomerIdType() { return customerIdType; }
    public void setCustomerIdType(String customerIdType) { this.customerIdType = customerIdType; }
}
