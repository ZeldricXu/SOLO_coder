package com.homeservice.service;

import com.homeservice.builder.TestDataBuilder;
import com.homeservice.entity.Booking;
import com.homeservice.entity.Customer;
import com.homeservice.entity.Staff;
import com.homeservice.repository.CustomerRepository;
import com.homeservice.service.ReminderService;
import com.homeservice.service.ReminderService.CustomerActivityLevel;
import com.homeservice.service.ReminderService.ReminderRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReminderService 评价提醒机制测试")
class ReminderServiceTest {

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private ReminderService reminderService;

    private Staff testStaff;
    private Customer activeCustomer;
    private Customer inactiveCustomer;
    private Booking testBooking;

    @BeforeEach
    void setUp() {
        TestDataBuilder.resetCounters();
        reminderService.clearAllReminderRecords();
        
        testStaff = TestDataBuilder.createStaff();
        activeCustomer = TestDataBuilder.createVIPCustomer();
        inactiveCustomer = TestDataBuilder.createInactiveCustomer();
        testBooking = TestDataBuilder.createCompletedBooking(testStaff, activeCustomer);
    }

    @Test
    @DisplayName("测试服务完成后评价提醒触发的正确性")
    void testCreateReminderRecordAfterServiceComplete() {
        when(customerRepository.findByCustomerId(anyString())).thenReturn(Optional.of(activeCustomer));

        ReminderRecord record = reminderService.createReminderRecord(testBooking);

        assertNotNull(record, "提醒记录不应为空");
        assertEquals(testBooking.getBookingId(), record.getBookingId());
        assertEquals(testBooking.getCustomerId(), record.getCustomerId());
        assertEquals(testBooking.getStaffId(), record.getStaffId());
        assertEquals(0, record.getReminderCount());
        assertFalse(record.isReviewed());
        assertNotNull(record.getServiceCompletedAt());
    }

    @Test
    @DisplayName("测试首次提醒应该发送")
    void testFirstReminderShouldSend() {
        when(customerRepository.findByCustomerId(anyString())).thenReturn(Optional.of(activeCustomer));
        reminderService.createReminderRecord(testBooking);

        boolean shouldSend = reminderService.shouldSendReminder(testBooking.getBookingId());

        assertTrue(shouldSend, "首次提醒应该发送");
    }

    @Test
    @DisplayName("测试提醒发送机制的正确性")
    void testSendReminderSuccess() {
        when(customerRepository.findByCustomerId(anyString())).thenReturn(Optional.of(activeCustomer));
        reminderService.createReminderRecord(testBooking);
        int remindersBefore = reminderService.getTotalRemindersSent();

        boolean sent = reminderService.sendReminder(testBooking.getBookingId());

        assertTrue(sent, "提醒应该发送成功");
        assertEquals(remindersBefore + 1, reminderService.getTotalRemindersSent());

        ReminderRecord record = reminderService.getReminderRecord(testBooking.getBookingId());
        assertEquals(1, record.getReminderCount());
    }

    @Test
    @DisplayName("测试活跃客户低频提醒 - 24小时间隔")
    void testActiveCustomerLowFrequencyReminder() {
        when(customerRepository.findByCustomerId(activeCustomer.getCustomerId()))
            .thenReturn(Optional.of(activeCustomer));
        
        CustomerActivityLevel level = reminderService.determineCustomerActivityLevel(activeCustomer.getCustomerId());

        assertEquals(CustomerActivityLevel.ACTIVE, level);
        assertEquals(24, level.getReminderIntervalHours(), "活跃客户提醒间隔应为24小时");
    }

    @Test
    @DisplayName("测试不活跃客户高频提醒 - 6小时间隔")
    void testInactiveCustomerHighFrequencyReminder() {
        when(customerRepository.findByCustomerId(inactiveCustomer.getCustomerId()))
            .thenReturn(Optional.of(inactiveCustomer));
        
        CustomerActivityLevel level = reminderService.determineCustomerActivityLevel(inactiveCustomer.getCustomerId());

        assertEquals(CustomerActivityLevel.INACTIVE, level);
        assertEquals(6, level.getReminderIntervalHours(), "不活跃客户提醒间隔应为6小时");
    }

    @Test
    @DisplayName("测试客户不存在时默认视为不活跃")
    void testNonExistentCustomerDefaultToInactive() {
        when(customerRepository.findByCustomerId("non_existent")).thenReturn(Optional.empty());
        
        CustomerActivityLevel level = reminderService.determineCustomerActivityLevel("non_existent");

        assertEquals(CustomerActivityLevel.INACTIVE, level);
    }

    @Test
    @DisplayName("测试已评价的预订不应再发送提醒")
    void testReviewedBookingShouldNotSendReminder() {
        when(customerRepository.findByCustomerId(anyString())).thenReturn(Optional.of(activeCustomer));
        reminderService.createReminderRecord(testBooking);
        
        reminderService.markAsReviewed(testBooking.getBookingId());
        
        boolean shouldSend = reminderService.shouldSendReminder(testBooking.getBookingId());
        assertFalse(shouldSend, "已评价的预订不应再发送提醒");

        boolean sent = reminderService.sendReminder(testBooking.getBookingId());
        assertFalse(sent, "已评价的预订提醒发送应失败");
    }

    @Test
    @DisplayName("测试不存在的预订不应发送提醒")
    void testNonExistentBookingShouldNotSendReminder() {
        boolean shouldSend = reminderService.shouldSendReminder("non_existent_booking");
        assertFalse(shouldSend, "不存在的预订不应发送提醒");

        boolean sent = reminderService.sendReminder("non_existent_booking");
        assertFalse(sent, "不存在的预订提醒发送应失败");
    }

    @Test
    @DisplayName("测试最大提醒次数限制 - 最多发送3次")
    void testMaxReminderCountLimit() {
        when(customerRepository.findByCustomerId(anyString())).thenReturn(Optional.of(activeCustomer));
        reminderService.createReminderRecord(testBooking);

        boolean sent1 = reminderService.sendReminderImmediately(testBooking.getBookingId());
        boolean sent2 = reminderService.sendReminderImmediately(testBooking.getBookingId());
        boolean sent3 = reminderService.sendReminderImmediately(testBooking.getBookingId());
        boolean sent4 = reminderService.sendReminderImmediately(testBooking.getBookingId());

        assertTrue(sent1, "第1次提醒应该成功");
        assertTrue(sent2, "第2次提醒应该成功");
        assertTrue(sent3, "第3次提醒应该成功");
        assertFalse(sent4, "第4次提醒应该失败（超过最大次数）");

        ReminderRecord record = reminderService.getReminderRecord(testBooking.getBookingId());
        assertEquals(3, record.getReminderCount(), "提醒次数应为3次");
    }

    @Test
    @DisplayName("测试提醒触发后评价数据的完整收集")
    void testReminderAndReviewDataCollection() {
        when(customerRepository.findByCustomerId(anyString())).thenReturn(Optional.of(activeCustomer));
        reminderService.createReminderRecord(testBooking);

        reminderService.sendReminder(testBooking.getBookingId());
        reminderService.markAsReviewed(testBooking.getBookingId());

        ReminderRecord record = reminderService.getReminderRecord(testBooking.getBookingId());
        assertTrue(record.isReviewed(), "评价状态应为已评价");
        assertEquals(1, record.getReminderCount(), "应该有1次提醒记录");
        assertEquals(1, reminderService.getTotalRemindersSent(), "总提醒数应为1");
    }

    @Test
    @DisplayName("测试获取提醒记录")
    void testGetReminderRecord() {
        when(customerRepository.findByCustomerId(anyString())).thenReturn(Optional.of(activeCustomer));
        reminderService.createReminderRecord(testBooking);

        ReminderRecord record = reminderService.getReminderRecord(testBooking.getBookingId());
        assertNotNull(record);
        assertEquals(testBooking.getBookingId(), record.getBookingId());
    }

    @Test
    @DisplayName("测试获取不存在的提醒记录返回null")
    void testGetNonExistentReminderRecord() {
        ReminderRecord record = reminderService.getReminderRecord("non_existent");
        assertNull(record);
    }

    @Test
    @DisplayName("测试重置提醒计数器")
    void testResetReminderCounter() {
        when(customerRepository.findByCustomerId(anyString())).thenReturn(Optional.of(activeCustomer));
        reminderService.createReminderRecord(testBooking);
        reminderService.sendReminderImmediately(testBooking.getBookingId());
        
        assertEquals(1, reminderService.getTotalRemindersSent());
        
        reminderService.resetReminderCounter();
        
        assertEquals(0, reminderService.getTotalRemindersSent());
    }

    @Test
    @DisplayName("测试清除所有提醒记录")
    void testClearAllReminderRecords() {
        when(customerRepository.findByCustomerId(anyString())).thenReturn(Optional.of(activeCustomer));
        
        Booking booking1 = TestDataBuilder.createCompletedBooking(testStaff, activeCustomer);
        Booking booking2 = TestDataBuilder.createCompletedBooking(testStaff, inactiveCustomer);
        
        reminderService.createReminderRecord(booking1);
        reminderService.createReminderRecord(booking2);
        reminderService.sendReminderImmediately(booking1.getBookingId());
        
        assertNotNull(reminderService.getReminderRecord(booking1.getBookingId()));
        assertNotNull(reminderService.getReminderRecord(booking2.getBookingId()));
        assertEquals(1, reminderService.getTotalRemindersSent());
        
        reminderService.clearAllReminderRecords();
        
        assertNull(reminderService.getReminderRecord(booking1.getBookingId()));
        assertNull(reminderService.getReminderRecord(booking2.getBookingId()));
        assertEquals(0, reminderService.getTotalRemindersSent());
    }

    @Test
    @DisplayName("测试发送立即提醒（忽略间隔检查）")
    void testSendReminderImmediately() {
        when(customerRepository.findByCustomerId(anyString())).thenReturn(Optional.of(activeCustomer));
        reminderService.createReminderRecord(testBooking);
        
        reminderService.sendReminderImmediately(testBooking.getBookingId());
        reminderService.sendReminderImmediately(testBooking.getBookingId());
        reminderService.sendReminderImmediately(testBooking.getBookingId());
        
        assertEquals(3, reminderService.getTotalRemindersSent());
    }

    @Test
    @DisplayName("测试客户活跃度阈值 - 5次预订以上为活跃")
    void testCustomerActivityThreshold() {
        Customer customerWith4Bookings = TestDataBuilder.createCustomer();
        customerWith4Bookings.setTotalBookings(4);
        
        Customer customerWith5Bookings = TestDataBuilder.createCustomer();
        customerWith5Bookings.setTotalBookings(5);
        
        Customer customerWith10Bookings = TestDataBuilder.createCustomer();
        customerWith10Bookings.setTotalBookings(10);

        when(customerRepository.findByCustomerId(customerWith4Bookings.getCustomerId()))
            .thenReturn(Optional.of(customerWith4Bookings));
        when(customerRepository.findByCustomerId(customerWith5Bookings.getCustomerId()))
            .thenReturn(Optional.of(customerWith5Bookings));
        when(customerRepository.findByCustomerId(customerWith10Bookings.getCustomerId()))
            .thenReturn(Optional.of(customerWith10Bookings));

        assertEquals(CustomerActivityLevel.INACTIVE, 
            reminderService.determineCustomerActivityLevel(customerWith4Bookings.getCustomerId()));
        assertEquals(CustomerActivityLevel.ACTIVE, 
            reminderService.determineCustomerActivityLevel(customerWith5Bookings.getCustomerId()));
        assertEquals(CustomerActivityLevel.ACTIVE, 
            reminderService.determineCustomerActivityLevel(customerWith10Bookings.getCustomerId()));
    }
}
