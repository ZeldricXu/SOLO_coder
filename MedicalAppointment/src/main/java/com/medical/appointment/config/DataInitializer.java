package com.medical.appointment.config;

import com.medical.appointment.entity.*;
import com.medical.appointment.repository.*;
import com.medical.appointment.util.IdGenerator;
import com.medical.appointment.util.ScheduleStatus;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Configuration
@Profile("!test")
public class DataInitializer implements CommandLineRunner {
    
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final DoctorRepository doctorRepository;
    private final ScheduleRepository scheduleRepository;
    private final PatientRepository patientRepository;
    
    public DataInitializer(HospitalRepository hospitalRepository,
                          DepartmentRepository departmentRepository,
                          DoctorRepository doctorRepository,
                          ScheduleRepository scheduleRepository,
                          PatientRepository patientRepository) {
        this.hospitalRepository = hospitalRepository;
        this.departmentRepository = departmentRepository;
        this.doctorRepository = doctorRepository;
        this.scheduleRepository = scheduleRepository;
        this.patientRepository = patientRepository;
    }
    
    @Override
    public void run(String... args) {
        initializeHospitals();
        initializeDepartments();
        initializeDoctors();
        initializeSchedules();
        initializePatients();
    }
    
    private void initializeHospitals() {
        if (hospitalRepository.count() == 0) {
            Hospital hospital1 = new Hospital();
            hospital1.setHospitalId(IdGenerator.generateHospitalId());
            hospital1.setHospitalName("中心医院");
            hospital1.setHospitalType("general");
            hospital1.setHospitalAddress("北京市朝阳区医院路100号");
            hospital1.setHospitalLevel("level_3");
            hospital1.setHospitalStatus("active");
            hospital1.setCreatedAt(LocalDateTime.now());
            hospitalRepository.save(hospital1);
            
            Hospital hospital2 = new Hospital();
            hospital2.setHospitalId(IdGenerator.generateHospitalId());
            hospital2.setHospitalName("人民医院");
            hospital2.setHospitalType("specialized");
            hospital2.setHospitalAddress("北京市海淀区健康街200号");
            hospital2.setHospitalLevel("level_2");
            hospital2.setHospitalStatus("active");
            hospital2.setCreatedAt(LocalDateTime.now());
            hospitalRepository.save(hospital2);
        }
    }
    
    private void initializeDepartments() {
        if (departmentRepository.count() == 0) {
            Hospital hospital1 = hospitalRepository.findAll().get(0);
            
            Department dept1 = new Department();
            dept1.setDepartmentId(IdGenerator.generateDepartmentId());
            dept1.setHospitalId(hospital1.getHospitalId());
            dept1.setDepartmentName("内科");
            dept1.setDepartmentType("internal");
            dept1.setDepartmentStatus("active");
            departmentRepository.save(dept1);
            
            Department dept2 = new Department();
            dept2.setDepartmentId(IdGenerator.generateDepartmentId());
            dept2.setHospitalId(hospital1.getHospitalId());
            dept2.setDepartmentName("外科");
            dept2.setDepartmentType("surgical");
            dept2.setDepartmentStatus("active");
            departmentRepository.save(dept2);
            
            Department dept3 = new Department();
            dept3.setDepartmentId(IdGenerator.generateDepartmentId());
            dept3.setHospitalId(hospital1.getHospitalId());
            dept3.setDepartmentName("儿科");
            dept3.setDepartmentType("pediatric");
            dept3.setDepartmentStatus("active");
            departmentRepository.save(dept3);
        }
    }
    
    private void initializeDoctors() {
        if (doctorRepository.count() == 0) {
            Department dept1 = departmentRepository.findAll().get(0);
            Department dept2 = departmentRepository.findAll().get(1);
            Department dept3 = departmentRepository.findAll().get(2);
            
            Doctor doctor1 = new Doctor();
            doctor1.setDoctorId(IdGenerator.generateDoctorId());
            doctor1.setDoctorName("张医生");
            doctor1.setDoctorTitle("主任医师");
            doctor1.setDepartmentId(dept1.getDepartmentId());
            doctor1.setDoctorRating(4.8);
            doctor1.setDoctorStatus("active");
            doctor1.setCreatedAt(LocalDateTime.now());
            doctorRepository.save(doctor1);
            
            Doctor doctor2 = new Doctor();
            doctor2.setDoctorId(IdGenerator.generateDoctorId());
            doctor2.setDoctorName("李医生");
            doctor2.setDoctorTitle("主治医师");
            doctor2.setDepartmentId(dept1.getDepartmentId());
            doctor2.setDoctorRating(4.5);
            doctor2.setDoctorStatus("active");
            doctor2.setCreatedAt(LocalDateTime.now());
            doctorRepository.save(doctor2);
            
            Doctor doctor3 = new Doctor();
            doctor3.setDoctorId(IdGenerator.generateDoctorId());
            doctor3.setDoctorName("王医生");
            doctor3.setDoctorTitle("副主任医师");
            doctor3.setDepartmentId(dept2.getDepartmentId());
            doctor3.setDoctorRating(4.6);
            doctor3.setDoctorStatus("active");
            doctor3.setCreatedAt(LocalDateTime.now());
            doctorRepository.save(doctor3);
            
            Doctor doctor4 = new Doctor();
            doctor4.setDoctorId(IdGenerator.generateDoctorId());
            doctor4.setDoctorName("赵医生");
            doctor4.setDoctorTitle("主治医师");
            doctor4.setDepartmentId(dept3.getDepartmentId());
            doctor4.setDoctorRating(4.7);
            doctor4.setDoctorStatus("active");
            doctor4.setCreatedAt(LocalDateTime.now());
            doctorRepository.save(doctor4);
        }
    }
    
    private void initializeSchedules() {
        if (scheduleRepository.count() == 0) {
            Doctor doctor1 = doctorRepository.findAll().get(0);
            Doctor doctor2 = doctorRepository.findAll().get(1);
            Doctor doctor3 = doctorRepository.findAll().get(2);
            Doctor doctor4 = doctorRepository.findAll().get(3);
            LocalDate today = LocalDate.now();
            LocalDate tomorrow = today.plusDays(1);
            
            Schedule schedule1 = new Schedule();
            schedule1.setScheduleId(IdGenerator.generateScheduleId());
            schedule1.setDepartmentId(doctor1.getDepartmentId());
            schedule1.setDoctorId(doctor1.getDoctorId());
            schedule1.setScheduleDate(today);
            schedule1.setScheduleTime("morning");
            schedule1.setScheduleQuota(50);
            schedule1.setScheduleAvailable(30);
            schedule1.setScheduleStatus(ScheduleStatus.AVAILABLE);
            scheduleRepository.save(schedule1);
            
            Schedule schedule2 = new Schedule();
            schedule2.setScheduleId(IdGenerator.generateScheduleId());
            schedule2.setDepartmentId(doctor1.getDepartmentId());
            schedule2.setDoctorId(doctor1.getDoctorId());
            schedule2.setScheduleDate(today);
            schedule2.setScheduleTime("afternoon");
            schedule2.setScheduleQuota(40);
            schedule2.setScheduleAvailable(0);
            schedule2.setScheduleStatus(ScheduleStatus.FULL);
            scheduleRepository.save(schedule2);
            
            Schedule schedule3 = new Schedule();
            schedule3.setScheduleId(IdGenerator.generateScheduleId());
            schedule3.setDepartmentId(doctor2.getDepartmentId());
            schedule3.setDoctorId(doctor2.getDoctorId());
            schedule3.setScheduleDate(tomorrow);
            schedule3.setScheduleTime("morning");
            schedule3.setScheduleQuota(30);
            schedule3.setScheduleAvailable(30);
            schedule3.setScheduleStatus(ScheduleStatus.AVAILABLE);
            scheduleRepository.save(schedule3);
            
            Schedule schedule4 = new Schedule();
            schedule4.setScheduleId(IdGenerator.generateScheduleId());
            schedule4.setDepartmentId(doctor3.getDepartmentId());
            schedule4.setDoctorId(doctor3.getDoctorId());
            schedule4.setScheduleDate(today);
            schedule4.setScheduleTime("morning");
            schedule4.setScheduleQuota(25);
            schedule4.setScheduleAvailable(15);
            schedule4.setScheduleStatus(ScheduleStatus.AVAILABLE);
            scheduleRepository.save(schedule4);
            
            Schedule schedule5 = new Schedule();
            schedule5.setScheduleId(IdGenerator.generateScheduleId());
            schedule5.setDepartmentId(doctor4.getDepartmentId());
            schedule5.setDoctorId(doctor4.getDoctorId());
            schedule5.setScheduleDate(tomorrow);
            schedule5.setScheduleTime("afternoon");
            schedule5.setScheduleQuota(20);
            schedule5.setScheduleAvailable(20);
            schedule5.setScheduleStatus(ScheduleStatus.AVAILABLE);
            scheduleRepository.save(schedule5);
        }
    }
    
    private void initializePatients() {
        if (patientRepository.count() == 0) {
            Patient patient1 = new Patient();
            patient1.setPatientId(IdGenerator.generatePatientId());
            patient1.setPatientName("张三");
            patient1.setPatientPhone("13800138001");
            patient1.setPatientIdNumber("110101199001011234");
            patient1.setPatientStatus("active");
            patient1.setRegisteredAt(LocalDateTime.now());
            patientRepository.save(patient1);
            
            Patient patient2 = new Patient();
            patient2.setPatientId(IdGenerator.generatePatientId());
            patient2.setPatientName("李四");
            patient2.setPatientPhone("13800138002");
            patient2.setPatientIdNumber("110101199002025678");
            patient2.setPatientStatus("active");
            patient2.setRegisteredAt(LocalDateTime.now());
            patientRepository.save(patient2);
            
            Patient patient3 = new Patient();
            patient3.setPatientId(IdGenerator.generatePatientId());
            patient3.setPatientName("王五");
            patient3.setPatientPhone("13800138003");
            patient3.setPatientIdNumber("110101199003039012");
            patient3.setPatientStatus("frozen");
            patient3.setRegisteredAt(LocalDateTime.now());
            patientRepository.save(patient3);
        }
    }
}
