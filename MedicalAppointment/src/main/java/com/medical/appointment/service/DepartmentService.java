package com.medical.appointment.service;

import com.medical.appointment.config.DepartmentTypeConfig.DepartmentTypeInfo;
import com.medical.appointment.entity.Department;
import com.medical.appointment.entity.Hospital;
import com.medical.appointment.repository.DepartmentRepository;
import com.medical.appointment.repository.HospitalRepository;
import com.medical.appointment.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class DepartmentService {
    
    private static final Logger log = LoggerFactory.getLogger(DepartmentService.class);
    
    private final DepartmentRepository departmentRepository;
    private final HospitalRepository hospitalRepository;
    private final DepartmentTypeService departmentTypeService;
    
    public DepartmentService(DepartmentRepository departmentRepository,
                            HospitalRepository hospitalRepository,
                            DepartmentTypeService departmentTypeService) {
        this.departmentRepository = departmentRepository;
        this.hospitalRepository = hospitalRepository;
        this.departmentTypeService = departmentTypeService;
    }
    
    public Department createDepartment(Department department) {
        Hospital hospital = hospitalRepository.findById(department.getHospitalId())
                .orElseThrow(() -> new RuntimeException("医院不存在: " + department.getHospitalId()));
        
        if (department.getDepartmentType() != null) {
            departmentTypeService.validateTypeCode(department.getDepartmentType());
            
            String configuredName = departmentTypeService.getTypeNameByCode(department.getDepartmentType());
            if (department.getDepartmentName() == null || department.getDepartmentName().isEmpty()) {
                department.setDepartmentName(configuredName);
                log.info("使用配置的科室名称: {} - {}", department.getDepartmentType(), configuredName);
            }
        }
        
        department.setDepartmentId(IdGenerator.generateDepartmentId());
        if (department.getDepartmentStatus() == null) {
            department.setDepartmentStatus("active");
        }
        return departmentRepository.save(department);
    }
    
    public List<DepartmentTypeInfo> getAvailableDepartmentTypes() {
        return departmentTypeService.getAllEnabledTypes();
    }
    
    public Optional<DepartmentTypeInfo> getDepartmentTypeInfo(String typeCode) {
        return departmentTypeService.getTypeByCode(typeCode);
    }
    
    public Optional<Department> getDepartmentById(String departmentId) {
        return departmentRepository.findById(departmentId);
    }
    
    public List<Department> getAllDepartments() {
        return departmentRepository.findAll();
    }
    
    public List<Department> getDepartmentsByHospital(String hospitalId) {
        return departmentRepository.findByHospitalId(hospitalId);
    }
    
    public List<Department> getActiveDepartmentsByHospital(String hospitalId) {
        return departmentRepository.findByHospitalIdAndDepartmentStatus(hospitalId, "active");
    }
    
    public Department updateDepartment(String departmentId, Department departmentDetails) {
        return departmentRepository.findById(departmentId)
                .map(department -> {
                    if (departmentDetails.getDepartmentName() != null) {
                        department.setDepartmentName(departmentDetails.getDepartmentName());
                    }
                    if (departmentDetails.getDepartmentType() != null) {
                        department.setDepartmentType(departmentDetails.getDepartmentType());
                    }
                    if (departmentDetails.getDepartmentStatus() != null) {
                        department.setDepartmentStatus(departmentDetails.getDepartmentStatus());
                    }
                    return departmentRepository.save(department);
                })
                .orElseThrow(() -> new RuntimeException("科室不存在: " + departmentId));
    }
    
    public void deleteDepartment(String departmentId) {
        departmentRepository.deleteById(departmentId);
    }
}
