package com.medical.appointment.repository;

import com.medical.appointment.entity.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, String> {
    List<Doctor> findByDepartmentId(String departmentId);
    List<Doctor> findByDepartmentIdAndDoctorStatus(String departmentId, String status);
    List<Doctor> findByDoctorStatus(String status);
}
