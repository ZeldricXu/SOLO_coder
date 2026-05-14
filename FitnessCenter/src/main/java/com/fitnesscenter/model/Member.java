package com.fitnesscenter.model;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "members")
public class Member {

    @Id
    @Column(name = "member_id")
    private String memberId;

    @Column(name = "member_name", nullable = false)
    private String memberName;

    @Column(name = "member_phone", nullable = false)
    private String memberPhone;

    @Column(name = "member_type")
    private String memberType;

    @Column(name = "member_status")
    private String memberStatus;

    @Column(name = "member_level")
    private String memberLevel;

    @Column(name = "registered_at")
    private Instant registeredAt;

    @Column(name = "expire_at")
    private Instant expireAt;

    @Column(name = "booking_count")
    private Integer bookingCount = 0;

    @Column(name = "training_count")
    private Integer trainingCount = 0;

    @Column(name = "total_calories")
    private Integer totalCalories = 0;

    public Member() {}

    public String getMemberId() {
        return memberId;
    }

    public void setMemberId(String memberId) {
        this.memberId = memberId;
    }

    public String getMemberName() {
        return memberName;
    }

    public void setMemberName(String memberName) {
        this.memberName = memberName;
    }

    public String getMemberPhone() {
        return memberPhone;
    }

    public void setMemberPhone(String memberPhone) {
        this.memberPhone = memberPhone;
    }

    public String getMemberType() {
        return memberType;
    }

    public void setMemberType(String memberType) {
        this.memberType = memberType;
    }

    public String getMemberStatus() {
        return memberStatus;
    }

    public void setMemberStatus(String memberStatus) {
        this.memberStatus = memberStatus;
    }

    public String getMemberLevel() {
        return memberLevel;
    }

    public void setMemberLevel(String memberLevel) {
        this.memberLevel = memberLevel;
    }

    public Instant getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(Instant registeredAt) {
        this.registeredAt = registeredAt;
    }

    public Instant getExpireAt() {
        return expireAt;
    }

    public void setExpireAt(Instant expireAt) {
        this.expireAt = expireAt;
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
}
