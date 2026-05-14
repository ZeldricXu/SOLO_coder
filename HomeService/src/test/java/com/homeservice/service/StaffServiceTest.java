package com.homeservice.service;

import com.homeservice.builder.TestDataBuilder;
import com.homeservice.dto.StaffRequest;
import com.homeservice.entity.Staff;
import com.homeservice.enums.StaffStatus;
import com.homeservice.exception.BusinessException;
import com.homeservice.exception.ResourceNotFoundException;
import com.homeservice.repository.ReviewRepository;
import com.homeservice.repository.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("StaffService 人员管理服务测试")
class StaffServiceTest {

    @Mock
    private StaffRepository staffRepository;

    @Mock
    private ReviewRepository reviewRepository;

    @InjectMocks
    private StaffService staffService;

    private Staff testStaff;
    private StaffRequest testStaffRequest;

    @BeforeEach
    void setUp() {
        TestDataBuilder.resetCounters();
        testStaff = TestDataBuilder.createStaff();
        testStaffRequest = createStaffRequest();
    }

    private StaffRequest createStaffRequest() {
        StaffRequest request = new StaffRequest();
        request.setStaffName("测试人员");
        request.setStaffType("cleaning");
        request.setStaffPhone("13812345678");
        request.setStaffRegion("北京朝阳区");
        request.setStaffPrice(100.0);
        return request;
    }

    @Test
    @DisplayName("测试创建人员成功")
    void testCreateStaffSuccess() {
        when(staffRepository.save(any(Staff.class))).thenReturn(testStaff);

        Staff created = staffService.createStaff(testStaffRequest);

        assertNotNull(created);
        verify(staffRepository, times(1)).save(any(Staff.class));
    }

    @Test
    @DisplayName("测试获取所有人员")
    void testGetAllStaff() {
        Staff staff1 = TestDataBuilder.createStaff();
        Staff staff2 = TestDataBuilder.createStaff();
        List<Staff> staffList = Arrays.asList(staff1, staff2);

        when(staffRepository.findAll()).thenReturn(staffList);

        List<Staff> result = staffService.getAllStaff();

        assertEquals(2, result.size());
        verify(staffRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("测试根据ID获取人员成功")
    void testGetStaffByIdSuccess() {
        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));

        Staff found = staffService.getStaffById(testStaff.getStaffId());

        assertNotNull(found);
        assertEquals(testStaff.getStaffId(), found.getStaffId());
    }

    @Test
    @DisplayName("测试根据ID获取人员不存在抛出异常")
    void testGetStaffByIdNotFound() {
        when(staffRepository.findByStaffId("non_existent")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> {
            staffService.getStaffById("non_existent");
        });
    }

    @Test
    @DisplayName("测试更新人员信息成功")
    void testUpdateStaffSuccess() {
        StaffRequest updateRequest = new StaffRequest();
        updateRequest.setStaffName("更新后的名字");
        updateRequest.setStaffType("nursing");
        updateRequest.setStaffPhone("13999999999");
        updateRequest.setStaffRegion("北京海淀区");
        updateRequest.setStaffPrice(150.0);

        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));
        when(staffRepository.save(any(Staff.class))).thenReturn(testStaff);

        Staff updated = staffService.updateStaff(testStaff.getStaffId(), updateRequest);

        assertNotNull(updated);
        verify(staffRepository, times(1)).save(any(Staff.class));
    }

    @Test
    @DisplayName("测试删除人员成功")
    void testDeleteStaffSuccess() {
        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));
        doNothing().when(staffRepository).delete(testStaff);

        staffService.deleteStaff(testStaff.getStaffId());

        verify(staffRepository, times(1)).delete(testStaff);
    }

    @Test
    @DisplayName("测试获取可用人员列表")
    void testGetAvailableStaff() {
        Staff available = TestDataBuilder.createAvailableStaff();
        Staff booked = TestDataBuilder.createBookedStaff();
        List<Staff> availableList = Arrays.asList(available);

        when(staffRepository.findByStaffStatus(StaffStatus.AVAILABLE)).thenReturn(availableList);

        List<Staff> result = staffService.getAvailableStaff();

        assertEquals(1, result.size());
        assertEquals(StaffStatus.AVAILABLE, result.get(0).getStaffStatus());
    }

    @Test
    @DisplayName("测试按类型获取人员")
    void testGetStaffByType() {
        Staff cleaningStaff = TestDataBuilder.createStaff("cleaning", "北京朝阳区", 100.0);
        List<Staff> cleaningList = Arrays.asList(cleaningStaff);

        when(staffRepository.findByStaffType("cleaning")).thenReturn(cleaningList);

        List<Staff> result = staffService.getStaffByType("cleaning");

        assertEquals(1, result.size());
        assertEquals("cleaning", result.get(0).getStaffType());
    }

    @Test
    @DisplayName("测试按区域获取人员")
    void testGetStaffByRegion() {
        Staff regionStaff = TestDataBuilder.createStaff("cleaning", "北京朝阳区", 100.0);
        List<Staff> regionList = Arrays.asList(regionStaff);

        when(staffRepository.findByStaffRegion("北京朝阳区")).thenReturn(regionList);

        List<Staff> result = staffService.getStaffByRegion("北京朝阳区");

        assertEquals(1, result.size());
        assertEquals("北京朝阳区", result.get(0).getStaffRegion());
    }

    @Test
    @DisplayName("测试人员状态流转 - 空闲->已预约")
    void testStaffStatusFlowAvailableToBooked() {
        testStaff.setStaffStatus(StaffStatus.AVAILABLE);
        
        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));
        when(staffRepository.save(any(Staff.class))).thenReturn(testStaff);

        Staff updated = staffService.updateStaffStatus(testStaff.getStaffId(), StaffStatus.BOOKED);

        assertEquals(StaffStatus.BOOKED, updated.getStaffStatus());
        verify(staffRepository, times(1)).save(any(Staff.class));
    }

    @Test
    @DisplayName("测试人员状态流转 - 已预约->空闲（服务完成）")
    void testStaffStatusFlowBookedToAvailable() {
        testStaff.setStaffStatus(StaffStatus.BOOKED);
        
        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));
        when(staffRepository.save(any(Staff.class))).thenReturn(testStaff);

        Staff updated = staffService.updateStaffStatus(testStaff.getStaffId(), StaffStatus.AVAILABLE);

        assertEquals(StaffStatus.AVAILABLE, updated.getStaffStatus());
    }

    @Test
    @DisplayName("测试人员状态流转 - 设置为不可用")
    void testStaffStatusFlowSetUnavailable() {
        testStaff.setStaffStatus(StaffStatus.AVAILABLE);
        
        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));
        when(staffRepository.save(any(Staff.class))).thenReturn(testStaff);

        Staff updated = staffService.updateStaffStatus(testStaff.getStaffId(), StaffStatus.UNAVAILABLE);

        assertEquals(StaffStatus.UNAVAILABLE, updated.getStaffStatus());
    }

    @Test
    @DisplayName("测试增加人员预订计数")
    void testIncrementBookingCount() {
        int initialBookings = testStaff.getTotalBookings();
        
        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));
        when(staffRepository.save(any(Staff.class))).thenReturn(testStaff);

        staffService.incrementBookingCount(testStaff.getStaffId());

        verify(staffRepository, times(1)).save(any(Staff.class));
    }

    @Test
    @DisplayName("测试增加人员评价计数")
    void testIncrementReviewCount() {
        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));
        when(staffRepository.save(any(Staff.class))).thenReturn(testStaff);

        staffService.incrementReviewCount(testStaff.getStaffId());

        verify(staffRepository, times(1)).save(any(Staff.class));
    }

    @Test
    @DisplayName("测试人员评分统计的正确性 - 计算平均评分")
    void testStaffRatingCalculation() {
        Double expectedAverage = 4.5;
        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));
        when(reviewRepository.getAverageRatingByStaffId(testStaff.getStaffId())).thenReturn(expectedAverage);
        when(staffRepository.save(any(Staff.class))).thenReturn(testStaff);

        staffService.updateStaffRating(testStaff.getStaffId(), 5.0);

        verify(reviewRepository, times(1)).getAverageRatingByStaffId(testStaff.getStaffId());
        verify(staffRepository, times(1)).save(any(Staff.class));
    }

    @Test
    @DisplayName("测试增加人员收入")
    void testAddStaffIncome() {
        double initialIncome = testStaff.getTotalIncome();
        double incomeToAdd = 180.0;

        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));
        when(staffRepository.save(any(Staff.class))).thenReturn(testStaff);

        staffService.addStaffIncome(testStaff.getStaffId(), incomeToAdd);

        verify(staffRepository, times(1)).save(any(Staff.class));
    }

    @Test
    @DisplayName("测试验证可用人员 - 状态为AVAILABLE")
    void testValidateStaffAvailabilityAvailable() {
        testStaff.setStaffStatus(StaffStatus.AVAILABLE);
        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));

        assertDoesNotThrow(() -> staffService.validateStaffAvailability(testStaff.getStaffId()));
    }

    @Test
    @DisplayName("测试验证不可用人员 - 状态为UNAVAILABLE时抛出异常")
    void testValidateStaffAvailabilityUnavailable() {
        testStaff.setStaffStatus(StaffStatus.UNAVAILABLE);
        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));

        BusinessException exception = assertThrows(BusinessException.class, 
            () -> staffService.validateStaffAvailability(testStaff.getStaffId()));
        
        assertEquals("Staff is unavailable", exception.getMessage());
    }

    @Test
    @DisplayName("测试验证已预约人员 - 状态为BOOKED时不抛出异常（可以预约冲突检查）")
    void testValidateStaffAvailabilityBooked() {
        testStaff.setStaffStatus(StaffStatus.BOOKED);
        when(staffRepository.findByStaffId(testStaff.getStaffId())).thenReturn(Optional.of(testStaff));

        assertDoesNotThrow(() -> staffService.validateStaffAvailability(testStaff.getStaffId()));
    }

    @Test
    @DisplayName("测试人员状态枚举值验证")
    void testStaffStatusEnumValues() {
        assertEquals("available", StaffStatus.AVAILABLE.getValue());
        assertEquals("booked", StaffStatus.BOOKED.getValue());
        assertEquals("unavailable", StaffStatus.UNAVAILABLE.getValue());
    }
}
