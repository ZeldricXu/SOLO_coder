package com.medical.appointment.repository;

import com.medical.appointment.entity.Hospital;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HospitalRepository extends JpaRepository<Hospital, String> {
    List<Hospital> findByHospitalStatus(String status);
    List<Hospital> findByHospitalType(String type);
}
