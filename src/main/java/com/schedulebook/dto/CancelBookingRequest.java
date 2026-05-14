package com.schedulebook.dto;

import javax.validation.constraints.NotBlank;

public class CancelBookingRequest {
    
    @NotBlank(message = "预约ID不能为空")
    private String bookingId;
    
    @NotBlank(message = "取消原因不能为空")
    private String cancelReason;
    
    private String cancelBy;
    
    public CancelBookingRequest() {
    }
    
    public String getBookingId() {
        return bookingId;
    }
    
    public void setBookingId(String bookingId) {
        this.bookingId = bookingId;
    }
    
    public String getCancelReason() {
        return cancelReason;
    }
    
    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }
    
    public String getCancelBy() {
        return cancelBy;
    }
    
    public void setCancelBy(String cancelBy) {
        this.cancelBy = cancelBy;
    }
}
