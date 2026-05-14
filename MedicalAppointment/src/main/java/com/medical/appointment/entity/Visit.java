package com.medical.appointment.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "visits")
public class Visit {
    
    @Id
    @Column(name = "visit_id")
    private String visitId;
    
    @Column(name = "appointment_id", nullable = false)
    private String appointmentId;
    
    @Column(name = "patient_id", nullable = false)
    private String patientId;
    
    @Column(name = "doctor_id", nullable = false)
    private String doctorId;
    
    @Column(name = "visit_time")
    private LocalDateTime visitTime;
    
    @Column(name = "visit_status")
    private String visitStatus;
    
    @Column(name = "visit_record")
    private String visitRecord;
    
    @Column(name = "visit_diagnosis")
    private String visitDiagnosis;
    
    @Column(name = "visit_prescription")
    private String visitPrescription;

    public Visit() {}

    public String getVisitId() {
        return visitId;
    }

    public void setVisitId(String visitId) {
        this.visitId = visitId;
    }

    public String getAppointmentId() {
        return appointmentId;
    }

    public void setAppointmentId(String appointmentId) {
        this.appointmentId = appointmentId;
    }

    public String getPatientId() {
        return patientId;
    }

    public void setPatientId(String patientId) {
        this.patientId = patientId;
    }

    public String getDoctorId() {
        return doctorId;
    }

    public void setDoctorId(String doctorId) {
        this.doctorId = doctorId;
    }

    public LocalDateTime getVisitTime() {
        return visitTime;
    }

    public void setVisitTime(LocalDateTime visitTime) {
        this.visitTime = visitTime;
    }

    public String getVisitStatus() {
        return visitStatus;
    }

    public void setVisitStatus(String visitStatus) {
        this.visitStatus = visitStatus;
    }

    public String getVisitRecord() {
        return visitRecord;
    }

    public void setVisitRecord(String visitRecord) {
        this.visitRecord = visitRecord;
    }

    public String getVisitDiagnosis() {
        return visitDiagnosis;
    }

    public void setVisitDiagnosis(String visitDiagnosis) {
        this.visitDiagnosis = visitDiagnosis;
    }

    public String getVisitPrescription() {
        return visitPrescription;
    }

    public void setVisitPrescription(String visitPrescription) {
        this.visitPrescription = visitPrescription;
    }
}
