package com.fitnesscenter.model;

import javax.persistence.*;

@Entity
@Table(name = "statistics")
public class Statistic {

    @Id
    @Column(name = "stat_id")
    private String statId;

    @Column(name = "stat_month", unique = true)
    private String statMonth;

    @Column(name = "member_count")
    private Integer memberCount = 0;

    @Column(name = "booking_count")
    private Integer bookingCount = 0;

    @Column(name = "training_count")
    private Integer trainingCount = 0;

    @Column(name = "total_calories")
    private Integer totalCalories = 0;

    @Column(name = "plan_count")
    private Integer planCount = 0;

    public Statistic() {}

    public String getStatId() {
        return statId;
    }

    public void setStatId(String statId) {
        this.statId = statId;
    }

    public String getStatMonth() {
        return statMonth;
    }

    public void setStatMonth(String statMonth) {
        this.statMonth = statMonth;
    }

    public Integer getMemberCount() {
        return memberCount;
    }

    public void setMemberCount(Integer memberCount) {
        this.memberCount = memberCount;
    }

    public Integer getBookingCount() {
        return bookingCount;
    }

    public void setBookingCount(Integer bookingCount) {
        this.bookingCount = bookingCount;
    }

    public Integer getTrainingCount() {
        return trainingCount;
    }

    public void setTrainingCount(Integer trainingCount) {
        this.trainingCount = trainingCount;
    }

    public Integer getTotalCalories() {
        return totalCalories;
    }

    public void setTotalCalories(Integer totalCalories) {
        this.totalCalories = totalCalories;
    }

    public Integer getPlanCount() {
        return planCount;
    }

    public void setPlanCount(Integer planCount) {
        this.planCount = planCount;
    }
}
