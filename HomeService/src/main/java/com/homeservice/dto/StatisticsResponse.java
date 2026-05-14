package com.homeservice.dto;

public class StatisticsResponse {
    private Long totalStaff;
    private Long totalCustomers;
    private Long totalBookings;
    private Long completedBookings;
    private Long totalReviews;
    private Double averageRating;
    private Double totalRevenue;
    private String month;

    public StatisticsResponse() {}

    public Long getTotalStaff() { return totalStaff; }
    public void setTotalStaff(Long totalStaff) { this.totalStaff = totalStaff; }
    public Long getTotalCustomers() { return totalCustomers; }
    public void setTotalCustomers(Long totalCustomers) { this.totalCustomers = totalCustomers; }
    public Long getTotalBookings() { return totalBookings; }
    public void setTotalBookings(Long totalBookings) { this.totalBookings = totalBookings; }
    public Long getCompletedBookings() { return completedBookings; }
    public void setCompletedBookings(Long completedBookings) { this.completedBookings = completedBookings; }
    public Long getTotalReviews() { return totalReviews; }
    public void setTotalReviews(Long totalReviews) { this.totalReviews = totalReviews; }
    public Double getAverageRating() { return averageRating; }
    public void setAverageRating(Double averageRating) { this.averageRating = averageRating; }
    public Double getTotalRevenue() { return totalRevenue; }
    public void setTotalRevenue(Double totalRevenue) { this.totalRevenue = totalRevenue; }
    public String getMonth() { return month; }
    public void setMonth(String month) { this.month = month; }
}
