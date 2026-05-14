package com.medical.appointment.builder;

import com.medical.appointment.entity.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    private static long counter = 0;

    public static synchronized long nextId() {
        return ++counter;
    }

    public static Hospital createTestHospital() {
        return createTestHospital("center");
    }

    public static Hospital createTestHospital(String type) {
        long id = nextId();
        Hospital hospital = new Hospital();
        hospital.setHospitalId("hospital_" + id);
        
        switch (type) {
            case "center":
                hospital.setHospitalName("中心医院");
                hospital.setHospitalType("general");
                hospital.setHospitalAddress("北京市朝阳区医院路100号");
                hospital.setHospitalLevel("level_3");
                break;
            case "people":
                hospital.setHospitalName("人民医院");
                hospital.setHospitalType("specialized");
                hospital.setHospitalAddress("北京市海淀区健康街200号");
                hospital.setHospitalLevel("level_2");
                break;
            case "child":
                hospital.setHospitalName("儿童医院");
                hospital.setHospitalType("pediatric");
                hospital.setHospitalAddress("北京市西城区儿童路50号");
                hospital.setHospitalLevel("level_3");
                break;
            default:
                hospital.setHospitalName("测试医院" + id);
                hospital.setHospitalType("general");
                hospital.setHospitalAddress("测试地址" + id + "号");
                hospital.setHospitalLevel("level_2");
        }
        
        hospital.setHospitalStatus("active");
        hospital.setCreatedAt(LocalDateTime.now().minusDays(id));
        return hospital;
    }

    public static Department createTestDepartment(String hospitalId) {
        return createTestDepartment(hospitalId, "internal");
    }

    public static Department createTestDepartment(String hospitalId, String type) {
        long id = nextId();
        Department department = new Department();
        department.setDepartmentId("dept_" + id);
        department.setHospitalId(hospitalId);
        
        switch (type) {
            case "internal":
                department.setDepartmentName("内科");
                department.setDepartmentType("internal");
                break;
            case "surgical":
                department.setDepartmentName("外科");
                department.setDepartmentType("surgical");
                break;
            case "pediatric":
                department.setDepartmentName("儿科");
                department.setDepartmentType("pediatric");
                break;
            case "gynecology":
                department.setDepartmentName("妇科");
                department.setDepartmentType("gynecology");
                break;
            case "emergency":
                department.setDepartmentName("急诊科");
                department.setDepartmentType("emergency");
                break;
            default:
                department.setDepartmentName("科室" + id);
                department.setDepartmentType("other");
        }
        
        department.setDepartmentStatus("active");
        return department;
    }

    public static Doctor createTestDoctor(String departmentId) {
        return createTestDoctor(departmentId, "senior");
    }

    public static Doctor createTestDoctor(String departmentId, String level) {
        long id = nextId();
        Doctor doctor = new Doctor();
        doctor.setDoctorId("doctor_" + id);
        
        String[] firstNames = {"张", "李", "王", "赵", "刘", "陈", "杨", "黄"};
        String firstName = firstNames[(int) (id % firstNames.length)];
        String[] lastNames = {"医生", "主任", "医师", "教授"};
        String lastName = lastNames[(int) (id % lastNames.length)];
        doctor.setDoctorName(firstName + lastName);
        
        switch (level) {
            case "chief":
                doctor.setDoctorTitle("主任医师");
                doctor.setDoctorRating(4.8 + (id % 2) * 0.1);
                break;
            case "senior":
                doctor.setDoctorTitle("副主任医师");
                doctor.setDoctorRating(4.5 + (id % 3) * 0.1);
                break;
            case "attending":
                doctor.setDoctorTitle("主治医师");
                doctor.setDoctorRating(4.2 + (id % 4) * 0.1);
                break;
            case "resident":
                doctor.setDoctorTitle("住院医师");
                doctor.setDoctorRating(4.0 + (id % 2) * 0.1);
                break;
            default:
                doctor.setDoctorTitle("医师");
                doctor.setDoctorRating(4.0);
        }
        
        doctor.setDepartmentId(departmentId);
        doctor.setDoctorStatus("active");
        doctor.setCreatedAt(LocalDateTime.now().minusDays(id));
        doctor.setAppointmentCount((int) (id % 100));
        doctor.setVisitCount((int) (id % 80));
        return doctor;
    }

    public static Patient createTestPatient() {
        return createTestPatient("normal");
    }

    public static Patient createTestPatient(String type) {
        long id = nextId();
        Patient patient = new Patient();
        patient.setPatientId("patient_" + id);
        
        String[] firstNames = {"张", "李", "王", "赵", "刘", "陈", "杨", "黄", "周", "吴"};
        String firstName = firstNames[(int) (id % firstNames.length)];
        String[] lastNames = {"三", "四", "五", "六", "七", "八", "九", "十", "小明", "小红"};
        String lastName = lastNames[(int) (id % lastNames.length)];
        patient.setPatientName(firstName + lastName);
        
        patient.setPatientPhone("138" + String.format("%08d", 10000000 + id));
        patient.setPatientIdNumber("110101" + String.format("%08d", 19900101 + id));
        
        switch (type) {
            case "vip":
                patient.setPatientStatus("active");
                break;
            case "frozen":
                patient.setPatientStatus("frozen");
                break;
            case "inactive":
                patient.setPatientStatus("inactive");
                break;
            default:
                patient.setPatientStatus("active");
        }
        
        patient.setRegisteredAt(LocalDateTime.now().minusDays(id));
        patient.setAppointmentCount((int) (id % 20));
        patient.setVisitCount((int) (id % 15));
        return patient;
    }

    public static Schedule createTestSchedule(String departmentId, String doctorId) {
        return createTestSchedule(departmentId, doctorId, "morning");
    }

    public static Schedule createTestSchedule(String departmentId, String doctorId, String time) {
        long id = nextId();
        Schedule schedule = new Schedule();
        schedule.setScheduleId("schedule_" + id);
        schedule.setDepartmentId(departmentId);
        schedule.setDoctorId(doctorId);
        schedule.setScheduleDate(LocalDate.now().plusDays(id % 7));
        
        switch (time) {
            case "morning":
                schedule.setScheduleTime("morning");
                schedule.setScheduleQuota(50);
                schedule.setScheduleAvailable(50);
                break;
            case "afternoon":
                schedule.setScheduleTime("afternoon");
                schedule.setScheduleQuota(40);
                schedule.setScheduleAvailable(40);
                break;
            case "evening":
                schedule.setScheduleTime("evening");
                schedule.setScheduleQuota(20);
                schedule.setScheduleAvailable(20);
                break;
            case "full":
                schedule.setScheduleTime("morning");
                schedule.setScheduleQuota(50);
                schedule.setScheduleAvailable(0);
                schedule.setScheduleStatus("full");
                return schedule;
            default:
                schedule.setScheduleTime("morning");
                schedule.setScheduleQuota(30);
                schedule.setScheduleAvailable(30);
        }
        
        schedule.setScheduleStatus("available");
        return schedule;
    }

    public static Appointment createTestAppointment(String patientId, String scheduleId, String doctorId) {
        return createTestAppointment(patientId, scheduleId, doctorId, "appointed");
    }

    public static Appointment createTestAppointment(String patientId, String scheduleId, 
                                                   String doctorId, String status) {
        long id = nextId();
        Appointment appointment = new Appointment();
        appointment.setAppointmentId("appoint_" + id);
        appointment.setPatientId(patientId);
        appointment.setScheduleId(scheduleId);
        appointment.setDoctorId(doctorId);
        appointment.setAppointmentNumber("GH" + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + String.format("%03d", id));
        appointment.setAppointmentStatus(status);
        appointment.setAppointmentTime(LocalDateTime.now().plusDays(1));
        appointment.setCreatedAt(LocalDateTime.now().minusHours(id % 24));
        appointment.setCancelReason(null);
        return appointment;
    }

    public static Visit createTestVisit(String appointmentId, String patientId, String doctorId) {
        return createTestVisit(appointmentId, patientId, doctorId, "completed");
    }

    public static Visit createTestVisit(String appointmentId, String patientId, 
                                       String doctorId, String status) {
        long id = nextId();
        Visit visit = new Visit();
        visit.setVisitId("visit_" + id);
        visit.setAppointmentId(appointmentId);
        visit.setPatientId(patientId);
        visit.setDoctorId(doctorId);
        visit.setVisitTime(LocalDateTime.now().minusHours(id % 48));
        visit.setVisitStatus(status);
        visit.setVisitRecord("患者主诉症状持续3天，伴有发热、咳嗽等症状。");
        visit.setVisitDiagnosis("上呼吸道感染");
        visit.setVisitPrescription("阿莫西林胶囊 0.5g * 24粒，每日3次，每次2粒；布洛芬缓释胶囊 0.3g * 12粒，发热时服用。");
        return visit;
    }

    public static List<Hospital> createTestHospitals(int count) {
        List<Hospital> hospitals = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            hospitals.add(createTestHospital());
        }
        return hospitals;
    }

    public static List<Department> createTestDepartments(String hospitalId, int count) {
        List<Department> departments = new ArrayList<>();
        String[] types = {"internal", "surgical", "pediatric", "gynecology", "emergency"};
        for (int i = 0; i < count; i++) {
            departments.add(createTestDepartment(hospitalId, types[i % types.length]));
        }
        return departments;
    }

    public static List<Doctor> createTestDoctors(String departmentId, int count) {
        List<Doctor> doctors = new ArrayList<>();
        String[] levels = {"chief", "senior", "attending", "resident"};
        for (int i = 0; i < count; i++) {
            doctors.add(createTestDoctor(departmentId, levels[i % levels.length]));
        }
        return doctors;
    }

    public static List<Schedule> createTestSchedules(String departmentId, String doctorId, int count) {
        List<Schedule> schedules = new ArrayList<>();
        String[] times = {"morning", "afternoon", "evening"};
        for (int i = 0; i < count; i++) {
            schedules.add(createTestSchedule(departmentId, doctorId, times[i % times.length]));
        }
        return schedules;
    }

    public static class TestHospitalSetup {
        public Hospital hospital;
        public List<Department> departments;
        public List<Doctor> doctors;
        public List<Schedule> schedules;
        public List<Patient> patients;
    }

    public static TestHospitalSetup createCompleteHospitalSetup() {
        TestHospitalSetup setup = new TestHospitalSetup();
        
        setup.hospital = createTestHospital();
        setup.departments = createTestDepartments(setup.hospital.getHospitalId(), 3);
        setup.doctors = new ArrayList<>();
        setup.schedules = new ArrayList<>();
        
        for (Department dept : setup.departments) {
            List<Doctor> deptDoctors = createTestDoctors(dept.getDepartmentId(), 2);
            setup.doctors.addAll(deptDoctors);
            
            for (Doctor doctor : deptDoctors) {
                setup.schedules.addAll(createTestSchedules(dept.getDepartmentId(), doctor.getDoctorId(), 2));
            }
        }
        
        setup.patients = new ArrayList<>();
        setup.patients.add(createTestPatient("vip"));
        setup.patients.add(createTestPatient("normal"));
        setup.patients.add(createTestPatient("frozen"));
        
        return setup;
    }

    public static void resetCounter() {
        counter = 0;
    }

    public static String randomId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
