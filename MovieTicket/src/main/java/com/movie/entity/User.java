package com.movie.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "user_id", length = 50)
    private String userId;

    @Column(name = "user_name", length = 100)
    private String userName;

    @Column(name = "user_phone", length = 20)
    private String userPhone;

    @Column(name = "user_status", length = 20)
    private String userStatus;

    @Column(name = "user_level", length = 20)
    private String userLevel;

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;

    public User() {
    }

    public User(String userId, String userName, String userPhone, String userStatus, LocalDateTime registeredAt) {
        this.userId = userId;
        this.userName = userName;
        this.userPhone = userPhone;
        this.userStatus = userStatus;
        this.registeredAt = registeredAt;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getUserPhone() {
        return userPhone;
    }

    public void setUserPhone(String userPhone) {
        this.userPhone = userPhone;
    }

    public String getUserStatus() {
        return userStatus;
    }

    public void setUserStatus(String userStatus) {
        this.userStatus = userStatus;
    }

    public String getUserLevel() {
        return userLevel;
    }

    public void setUserLevel(String userLevel) {
        this.userLevel = userLevel;
    }

    public LocalDateTime getRegisteredAt() {
        return registeredAt;
    }

    public void setRegisteredAt(LocalDateTime registeredAt) {
        this.registeredAt = registeredAt;
    }
}
