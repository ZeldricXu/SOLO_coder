package com.medical.appointment.dto;

public class VisitRequest {
    private String appointmentId;
    private String visitRecord;
    private String visitDiagnosis;
    private String visitPrescription;

    public VisitRequest() {}

    public String getAppointmentId() {
        return appointmentId;
    }

    public void setAppointmentId(String appointmentId) {
        this.appointmentId = appointmentId;
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
