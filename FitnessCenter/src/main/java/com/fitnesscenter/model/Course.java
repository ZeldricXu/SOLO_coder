package com.fitnesscenter.model;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "courses")
public class Course {

    @Id
    @Column(name = "course_id")
    private String courseId;

    @Column(name = "course_name", nullable = false)
    private String courseName;

    @Column(name = "course_type")
    private String courseType;

    @Column(name = "course_coach")
    private String courseCoach;

    @Column(name = "course_time")
    private Instant courseTime;

    @Column(name = "course_duration")
    private Integer courseDuration;

    @Column(name = "course_capacity")
    private Integer courseCapacity;

    @Column(name = "course_available")
    private Integer courseAvailable;

    @Column(name = "course_status")
    private String courseStatus;

    @Column(name = "gym_id")
    private String gymId;

    public Course() {}

    public String getCourseId() {
        return courseId;
    }

    public void setCourseId(String courseId) {
        this.courseId = courseId;
    }

    public String getCourseName() {
        return courseName;
    }

    public void setCourseName(String courseName) {
        this.courseName = courseName;
    }

    public String getCourseType() {
        return courseType;
    }

    public void setCourseType(String courseType) {
        this.courseType = courseType;
    }

    public String getCourseCoach() {
        return courseCoach;
    }

    public void setCourseCoach(String courseCoach) {
        this.courseCoach = courseCoach;
    }

    public Instant getCourseTime() {
        return courseTime;
    }

    public void setCourseTime(Instant courseTime) {
        this.courseTime = courseTime;
    }

    public Integer getCourseDuration() {
        return courseDuration;
    }

    public void setCourseDuration(Integer courseDuration) {
        this.courseDuration = courseDuration;
    }

    public Integer getCourseCapacity() {
        return courseCapacity;
    }

    public void setCourseCapacity(Integer courseCapacity) {
        this.courseCapacity = courseCapacity;
    }

    public Integer getCourseAvailable() {
        return courseAvailable;
    }

    public void setCourseAvailable(Integer courseAvailable) {
        this.courseAvailable = courseAvailable;
    }

    public String getCourseStatus() {
        return courseStatus;
    }

    public void setCourseStatus(String courseStatus) {
        this.courseStatus = courseStatus;
    }

    public String getGymId() {
        return gymId;
    }

    public void setGymId(String gymId) {
        this.gymId = gymId;
    }
}
