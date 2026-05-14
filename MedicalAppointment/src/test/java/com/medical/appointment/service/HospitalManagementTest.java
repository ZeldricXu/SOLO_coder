package com.medical.appointment.service;

import com.medical.appointment.builder.TestDataBuilder;
import com.medical.appointment.entity.Department;
import com.medical.appointment.entity.Doctor;
import com.medical.appointment.entity.Hospital;
import com.medical.appointment.repository.HospitalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("HospitalService 单元测试 - 医院管理模块")
@ExtendWith(MockitoExtension.class)
class HospitalManagementTest {

    @Mock
    private HospitalRepository hospitalRepository;

    @InjectMocks
    private HospitalService hospitalService;

    private TestDataBuilder.TestHospitalSetup setup;

    @BeforeEach
    void setUp() {
        TestDataBuilder.resetCounter();
        setup = TestDataBuilder.createCompleteHospitalSetup();
    }

    @Nested
    @DisplayName("医院录入测试")
    class HospitalEntryTests {

        @Test
        @DisplayName("应该成功录入医院信息")
        void shouldCreateHospitalSuccessfully() {
            Hospital newHospital = TestDataBuilder.createTestHospital("people");
            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));

            Hospital result = hospitalService.createHospital(newHospital);

            assertNotNull(result, "创建的医院不应为空");
            assertNotNull(result.getHospitalId(), "医院ID不应为空");
            assertTrue(result.getHospitalId().startsWith("hospital_"), "医院ID格式不正确");
            assertEquals(newHospital.getHospitalName(), result.getHospitalName());
            assertEquals(newHospital.getHospitalType(), result.getHospitalType());
            assertEquals(newHospital.getHospitalAddress(), result.getHospitalAddress());
            assertEquals(newHospital.getHospitalLevel(), result.getHospitalLevel());
            assertEquals("active", result.getHospitalStatus(), "新建医院状态应该为active");
            assertNotNull(result.getCreatedAt(), "创建时间不应为空");

            verify(hospitalRepository).save(any(Hospital.class));
        }

        @Test
        @DisplayName("应该正确生成医院ID")
        void shouldGenerateHospitalId() {
            Hospital hospital1 = TestDataBuilder.createTestHospital("center");
            Hospital hospital2 = TestDataBuilder.createTestHospital("people");
            
            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));

            Hospital result1 = hospitalService.createHospital(hospital1);
            Hospital result2 = hospitalService.createHospital(hospital2);

            assertNotEquals(result1.getHospitalId(), result2.getHospitalId(), "医院ID应该唯一");
            assertTrue(result1.getHospitalId().startsWith("hospital_"));
            assertTrue(result2.getHospitalId().startsWith("hospital_"));
        }

        @Test
        @DisplayName("应该设置默认创建时间")
        void shouldSetDefaultCreatedAt() {
            Hospital hospital = TestDataBuilder.createTestHospital();
            hospital.setCreatedAt(null);

            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> {
                Hospital saved = inv.getArgument(0);
                assertNotNull(saved.getCreatedAt(), "创建时间应该被设置");
                return saved;
            });

            hospitalService.createHospital(hospital);

            verify(hospitalRepository).save(any(Hospital.class));
        }

        @Test
        @DisplayName("应该设置默认状态为active")
        void shouldSetDefaultStatusToActive() {
            Hospital hospital = TestDataBuilder.createTestHospital();
            hospital.setHospitalStatus(null);

            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> {
                Hospital saved = inv.getArgument(0);
                assertEquals("active", saved.getHospitalStatus(), "默认状态应该为active");
                return saved;
            });

            hospitalService.createHospital(hospital);
        }
    }

    @Nested
    @DisplayName("医院查询测试")
    class HospitalQueryTests {

        @Test
        @DisplayName("应该成功查询医院信息")
        void shouldGetHospitalById() {
            when(hospitalRepository.findById(setup.hospital.getHospitalId()))
                    .thenReturn(Optional.of(setup.hospital));

            Optional<Hospital> result = hospitalService.getHospitalById(setup.hospital.getHospitalId());

            assertTrue(result.isPresent());
            assertEquals(setup.hospital.getHospitalId(), result.get().getHospitalId());
            assertEquals(setup.hospital.getHospitalName(), result.get().getHospitalName());
        }

        @Test
        @DisplayName("应该查询所有医院")
        void shouldGetAllHospitals() {
            List<Hospital> hospitals = TestDataBuilder.createTestHospitals(3);
            when(hospitalRepository.findAll()).thenReturn(hospitals);

            List<Hospital> result = hospitalService.getAllHospitals();

            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("应该查询活跃医院")
        void shouldGetActiveHospitals() {
            List<Hospital> activeHospitals = TestDataBuilder.createTestHospitals(2);
            when(hospitalRepository.findByHospitalStatus("active")).thenReturn(activeHospitals);

            List<Hospital> result = hospitalService.getActiveHospitals();

            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("查询不存在的医院应该返回空")
        void shouldReturnEmptyForNonExistentHospital() {
            when(hospitalRepository.findById("nonexistent")).thenReturn(Optional.empty());

            Optional<Hospital> result = hospitalService.getHospitalById("nonexistent");

            assertFalse(result.isPresent());
        }
    }

    @Nested
    @DisplayName("医院更新测试")
    class HospitalUpdateTests {

        @Test
        @DisplayName("应该成功更新医院信息")
        void shouldUpdateHospitalSuccessfully() {
            Hospital existingHospital = setup.hospital;
            Hospital updateDetails = new Hospital();
            updateDetails.setHospitalName("更新后的医院名称");
            updateDetails.setHospitalAddress("更新后的地址");

            when(hospitalRepository.findById(existingHospital.getHospitalId()))
                    .thenReturn(Optional.of(existingHospital));
            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));

            Hospital result = hospitalService.updateHospital(
                    existingHospital.getHospitalId(), updateDetails);

            assertEquals("更新后的医院名称", result.getHospitalName());
            assertEquals("更新后的地址", result.getHospitalAddress());
            verify(hospitalRepository).save(any(Hospital.class));
        }

        @Test
        @DisplayName("更新不存在的医院应该抛出异常")
        void shouldThrowExceptionWhenUpdatingNonExistentHospital() {
            when(hospitalRepository.findById("nonexistent")).thenReturn(Optional.empty());

            RuntimeException exception = assertThrows(RuntimeException.class, () -> {
                hospitalService.updateHospital("nonexistent", new Hospital());
            });

            assertTrue(exception.getMessage().contains("医院不存在"));
        }

        @Test
        @DisplayName("应该成功激活医院")
        void shouldActivateHospital() {
            Hospital hospital = TestDataBuilder.createTestHospital();
            hospital.setHospitalStatus("inactive");

            when(hospitalRepository.findById(hospital.getHospitalId()))
                    .thenReturn(Optional.of(hospital));
            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));

            Hospital result = hospitalService.activateHospital(hospital.getHospitalId());

            assertEquals("active", result.getHospitalStatus());
        }

        @Test
        @DisplayName("应该成功停用医院")
        void shouldDeactivateHospital() {
            Hospital hospital = TestDataBuilder.createTestHospital();
            hospital.setHospitalStatus("active");

            when(hospitalRepository.findById(hospital.getHospitalId()))
                    .thenReturn(Optional.of(hospital));
            when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));

            Hospital result = hospitalService.deactivateHospital(hospital.getHospitalId());

            assertEquals("inactive", result.getHospitalStatus());
        }
    }

    @Nested
    @DisplayName("医院删除测试")
    class HospitalDeleteTests {

        @Test
        @DisplayName("应该成功删除医院")
        void shouldDeleteHospitalSuccessfully() {
            String hospitalId = setup.hospital.getHospitalId();

            doNothing().when(hospitalRepository).deleteById(hospitalId);

            hospitalService.deleteHospital(hospitalId);

            verify(hospitalRepository).deleteById(hospitalId);
        }
    }

    @Nested
    @DisplayName("科室配置管理测试")
    class DepartmentConfigurationTests {

        @Test
        @DisplayName("应该正确加载所有科室")
        void shouldLoadAllDepartmentsCorrectly() {
            assertNotNull(setup.departments);
            assertEquals(3, setup.departments.size());
        }

        @Test
        @DisplayName("应该正确关联科室与医院")
        void shouldAssociateDepartmentWithHospital() {
            for (Department dept : setup.departments) {
                assertEquals(setup.hospital.getHospitalId(), dept.getHospitalId());
            }
        }

        @Test
        @DisplayName("应该正确设置科室状态")
        void shouldSetDepartmentStatus() {
            for (Department dept : setup.departments) {
                assertEquals("active", dept.getDepartmentStatus());
            }
        }
    }

    @Nested
    @DisplayName("科室类型动态加载测试")
    class DepartmentTypeDynamicLoadingTests {

        @Test
        @DisplayName("应该正确加载内科科室")
        void shouldLoadInternalMedicineDepartment() {
            Department internal = setup.departments.stream()
                    .filter(d -> "internal".equals(d.getDepartmentType()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(internal);
            assertEquals("内科", internal.getDepartmentName());
        }

        @Test
        @DisplayName("应该正确加载外科科室")
        void shouldLoadSurgicalDepartment() {
            Department surgical = setup.departments.stream()
                    .filter(d -> "surgical".equals(d.getDepartmentType()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(surgical);
            assertEquals("外科", surgical.getDepartmentName());
        }

        @Test
        @DisplayName("应该正确加载儿科科室")
        void shouldLoadPediatricDepartment() {
            Department pediatric = setup.departments.stream()
                    .filter(d -> "pediatric".equals(d.getDepartmentType()))
                    .findFirst()
                    .orElse(null);

            assertNotNull(pediatric);
            assertEquals("儿科", pediatric.getDepartmentName());
        }
    }

    @Nested
    @DisplayName("医生与排班配置测试")
    class DoctorAndScheduleConfigurationTests {

        @Test
        @DisplayName("应该正确配置医生")
        void shouldConfigureDoctorsCorrectly() {
            assertNotNull(setup.doctors);
            assertTrue(setup.doctors.size() >= 1);
        }

        @Test
        @DisplayName("应该正确关联医生与科室")
        void shouldAssociateDoctorWithDepartment() {
            for (Doctor doctor : setup.doctors) {
                assertNotNull(doctor.getDepartmentId());
            }
        }

        @Test
        @DisplayName("应该正确配置排班")
        void shouldConfigureSchedulesCorrectly() {
            assertNotNull(setup.schedules);
            assertTrue(setup.schedules.size() >= 1);
        }
    }
}
