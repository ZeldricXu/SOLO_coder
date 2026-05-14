package com.medical.appointment.repository;

import com.medical.appointment.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, String> {
    List<Department> findByHospitalId(String hospitalId);
    List<Department> findByHospitalIdAndDepartmentStatus(String hospitalId, String status);
    List<Department> findByDepartmentType(String type);
}
