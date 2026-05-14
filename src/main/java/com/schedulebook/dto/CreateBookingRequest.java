package com.schedulebook.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalTime;

public class CreateBookingRequest {
    
    @NotBlank(message = "用户ID不能为空")
    private String userId;
    
    @NotBlank(message = "资源类型不能为空")
    private String resourceType;
    
    @NotNull(message = "预约日期不能为空")
    private LocalDate bookingDate;
    
    @NotNull(message = "预约时间不能为空")
    private LocalTime bookingTime;
    
    private Integer bookingDuration;
    
    private String resourceId;
    
    public CreateBookingRequest() {
    }
    
    public String getUserId() {
        return userId;
    }
    
    public void setUserId(String userId) {
        this.userId = userId;
    }
    
    public String getResourceType() {
        return resourceType;
    }
    
    public void setResourceType(String resourceType) {
        this.resourceType = resourceType;
    }
    
    public LocalDate getBookingDate() {
        return bookingDate;
    }
    
    public void setBookingDate(LocalDate bookingDate) {
        this.bookingDate = bookingDate;
    }
    
    public LocalTime getBookingTime() {
        return bookingTime;
    }
    
    public void setBookingTime(LocalTime bookingTime) {
        this.bookingTime = bookingTime;
    }
    
    public Integer getBookingDuration() {
        return bookingDuration;
    }
    
    public void setBookingDuration(Integer bookingDuration) {
        this.bookingDuration = bookingDuration;
    }
    
    public String getResourceId() {
        return resourceId;
    }
    
    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }
}
