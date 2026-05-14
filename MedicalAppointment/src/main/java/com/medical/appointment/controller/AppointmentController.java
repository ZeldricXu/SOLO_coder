package com.medical.appointment.controller;

import com.medical.appointment.dto.ApiResponse;
import com.medical.appointment.dto.AppointmentRequest;
import com.medical.appointment.dto.AppointmentResult;
import com.medical.appointment.entity.Appointment;
import com.medical.appointment.service.AppointmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/appointments")
public class AppointmentController {
    
    private final AppointmentService appointmentService;
    
    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }
    
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createAppointment(
            @RequestBody AppointmentRequest request) {
        try {
            AppointmentResult result = appointmentService.createAppointment(
                    request.getPatientId(), request.getScheduleId());
            Map<String, Object> data = new HashMap<>();
            data.put("appointment_id", result.getAppointmentId());
            data.put("number", result.getNumber());
            data.put("patient_name", result.getPatientName());
            data.put("doctor_name", result.getDoctorName());
            data.put("department_name", result.getDepartmentName());
            data.put("hospital_name", result.getHospitalName());
            data.put("status", result.getAppointmentStatus());
            data.put("appointment_time", result.getAppointmentTime());
            return ResponseEntity.ok(ApiResponse.success("预约成功", data));
        } catch (Exception e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.badRequest("预约失败: " + e.getMessage()));
        }
    }
    
    @GetMapping
    public ResponseEntity<ApiResponse<List<Appointment>>> getAllAppointments() {
        List<Appointment> appointments = appointmentService.getAllAppointments();
        return ResponseEntity.ok(ApiResponse.success(appointments));
    }
    
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AppointmentResult>> getAppointmentById(@PathVariable String id) {
        try {
            AppointmentResult result = appointmentService.getAppointmentResult(id);
            return ResponseEntity.ok(ApiResponse.success(result));
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(ApiResponse.notFound("挂号不存在: " + e.getMessage()));
        }
    }
    
    @GetMapping("/number/{number}")
    public ResponseEntity<ApiResponse<Appointment>> getAppointmentByNumber(@PathVariable String number) {
        return appointmentService.getAppointmentByNumber(number)
                .map(appointment -> ResponseEntity.ok(ApiResponse.success(appointment)))
                .orElse(ResponseEntity.status(404)
                        .body(ApiResponse.notFound("挂号不存在")));
    }
    
    @GetMapping("/patient/{patientId}")
    public ResponseEntity<ApiResponse<List<Appointment>>> getAppointmentsByPatient(
            @PathVariable String patientId) {
        List<Appointment> appointments = appointmentService.getAppointmentsByPatient(patientId);
        return ResponseEntity.ok(ApiResponse.success(appointments));
    }
    
    @GetMapping("/patient/{patientId}/status/{status}")
    public ResponseEntity<ApiResponse<List<Appointment>>> getAppointmentsByPatientAndStatus(
            @PathVariable String patientId, @PathVariable String status) {
        List<Appointment> appointments = appointmentService.getAppointmentsByPatientAndStatus(patientId, status);
        return ResponseEntity.ok(ApiResponse.success(appointments));
    }
    
    @GetMapping("/doctor/{doctorId}")
    public ResponseEntity<ApiResponse<List<Appointment>>> getAppointmentsByDoctor(
            @PathVariable String doctorId) {
        List<Appointment> appointments = appointmentService.getAppointmentsByDoctor(doctorId);
        return ResponseEntity.ok(ApiResponse.success(appointments));
    }
    
    @GetMapping("/schedule/{scheduleId}")
    public ResponseEntity<ApiResponse<List<Appointment>>> getAppointmentsBySchedule(
            @PathVariable String scheduleId) {
        List<Appointment> appointments = appointmentService.getAppointmentsBySchedule(scheduleId);
        return ResponseEntity.ok(ApiResponse.success(appointments));
    }
}
