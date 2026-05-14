package com.schedulebook.service;

import com.schedulebook.model.Booking;
import com.schedulebook.model.Reminder;
import com.schedulebook.repository.ReminderRepository;
import com.schedulebook.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("提醒模块测试 - 多重提醒机制")
class ReminderServiceTest {

    @Mock
    private ReminderRepository reminderRepository;

    @Mock
    private IdGeneratorService idGeneratorService;

    @InjectMocks
    private ReminderService reminderService;

    private Booking testBooking;

    @BeforeEach
    void setUp() {
        testBooking = TestDataBuilder.buildBooking();
    }

    @Test
    @DisplayName("测试多重提醒 - 提前一天、一小时提醒的正确性")
    void testMultipleReminders_TimingCorrectness() {
        when(idGeneratorService.generateReminderId()).thenReturn("reminder_001", "reminder_002", "reminder_003");
        when(reminderRepository.save(any(Reminder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<Reminder> reminders = reminderService.createMultipleReminders(testBooking);

        assertNotNull(reminders, "提醒列表不应该为空");
        assertEquals(3, reminders.size(), "60分钟预约应该有3个提醒");

        Reminder dayBefore = reminders.stream()
                .filter(r -> "before_day".equals(r.getReminderType()))
                .findFirst()
                .orElse(null);
        assertNotNull(dayBefore, "应该有提前一天的提醒");
        assertEquals(testBooking.getBookingTime().minusHours(24), dayBefore.getReminderTime(),
                "提前一天的提醒时间应该正确");

        Reminder hourBefore = reminders.stream()
                .filter(r -> "before_hour".equals(r.getReminderType()))
                .findFirst()
                .orElse(null);
        assertNotNull(hourBefore, "应该有提前一小时的提醒");
        assertEquals(testBooking.getBookingTime().minusHours(1), hourBefore.getReminderTime(),
                "提前一小时的提醒时间应该正确");
    }

    @ParameterizedTest
    @DisplayName("测试提醒时间间隔根据预约时长动态计算")
    @CsvSource({
            "30, 2",
            "60, 3",
            "120, 4",
            "240, 5"
    })
    void testReminderInterval_DynamicCalculation(int durationMinutes, int expectedCount) {
        int actualCount = reminderService.getReminderCountByDuration(durationMinutes);
        assertEquals(expectedCount, actualCount, 
                "时长" + durationMinutes + "分钟的预约应该有" + expectedCount + "个提醒");
    }

    @Test
    @DisplayName("测试30分钟预约 - 只有提前1天和提前1小时提醒")
    void testShortDurationBooking_TwoReminders() {
        Booking shortBooking = TestDataBuilder.buildBookingWithDuration(30);
        
        List<ReminderService.ReminderConfig> configs = reminderService.calculateReminderTimes(shortBooking);

        assertEquals(2, configs.size(), "30分钟预约应该只有2个提醒");
        assertTrue(configs.stream().anyMatch(c -> "before_day".equals(c.type)), 
                "应该有提前一天的提醒");
        assertTrue(configs.stream().anyMatch(c -> "before_hour".equals(c.type)), 
                "应该有提前一小时的提醒");
    }

    @Test
    @DisplayName("测试120分钟预约 - 额外增加提前2小时提醒")
    void testMediumDurationBooking_FourReminders() {
        Booking mediumBooking = TestDataBuilder.buildBookingWithDuration(120);
        
        List<ReminderService.ReminderConfig> configs = reminderService.calculateReminderTimes(mediumBooking);

        assertEquals(4, configs.size(), "120分钟预约应该有4个提醒");
        assertTrue(configs.stream().anyMatch(c -> "before_2hour".equals(c.type)), 
                "应该有提前2小时的提醒");
        assertTrue(configs.stream().anyMatch(c -> "email".equals(c.channel)), 
                "长时间预约应该有邮件提醒渠道");
    }

    @Test
    @DisplayName("测试240分钟预约 - 额外增加提前4小时提醒")
    void testLongDurationBooking_FiveReminders() {
        Booking longBooking = TestDataBuilder.buildBookingWithDuration(240);
        
        List<ReminderService.ReminderConfig> configs = reminderService.calculateReminderTimes(longBooking);

        assertEquals(5, configs.size(), "240分钟预约应该有5个提醒");
        assertTrue(configs.stream().anyMatch(c -> "before_4hour".equals(c.type)), 
                "应该有提前4小时的提醒");
    }

    @Test
    @DisplayName("测试提醒遗漏时的补充通知机制")
    void testMissedReminder_SupplementaryNotification() {
        Reminder pendingReminder = TestDataBuilder.buildReminder();
        pendingReminder.setReminderStatus("pending");
        pendingReminder.setReminderTime(LocalTime.now().minusHours(1));

        when(reminderRepository.findByBookingIdAndReminderStatus(
                testBooking.getBookingId(), "pending"))
                .thenReturn(Collections.singletonList(pendingReminder));
        when(reminderRepository.save(any(Reminder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<Reminder> sentReminders = reminderService.handleMissedReminders(testBooking.getBookingId());

        assertEquals(1, sentReminders.size(), "应该补充发送1个遗漏的提醒");
        assertEquals("sent", sentReminders.get(0).getReminderStatus(), 
                "补充发送的提醒状态应该是已发送");
        assertNotNull(sentReminders.get(0).getSentAt(), 
                "补充发送的提醒应该有发送时间");
    }

    @Test
    @DisplayName("测试未遗漏的提醒不被重复发送")
    void testNonMissedReminder_NotSentAgain() {
        Reminder futureReminder = TestDataBuilder.buildReminder();
        futureReminder.setReminderStatus("pending");
        futureReminder.setReminderTime(LocalTime.now().plusHours(1));

        when(reminderRepository.findByBookingIdAndReminderStatus(
                testBooking.getBookingId(), "pending"))
                .thenReturn(Collections.singletonList(futureReminder));

        List<Reminder> sentReminders = reminderService.handleMissedReminders(testBooking.getBookingId());

        assertEquals(0, sentReminders.size(), "未来的提醒不应该被发送");
    }

    @Test
    @DisplayName("测试不同预约时长下的提醒频率差异")
    void testReminderFrequency_BasedOnDuration() {
        int shortDurationCount = reminderService.getReminderCountByDuration(30);
        int mediumDurationCount = reminderService.getReminderCountByDuration(120);
        int longDurationCount = reminderService.getReminderCountByDuration(240);

        assertTrue(shortDurationCount < mediumDurationCount, 
                "短时间预约提醒数应该少于中等时长预约");
        assertTrue(mediumDurationCount < longDurationCount, 
                "中等时长预约提醒数应该少于长时间预约");
    }

    @Test
    @DisplayName("测试取消预约时提醒配置被取消")
    void testCancelReminders_OnBookingCancellation() {
        List<Reminder> pendingReminders = TestDataBuilder.buildMultipleReminders(
                testBooking.getBookingId(), 3);

        when(reminderRepository.findByBookingIdAndReminderStatus(
                testBooking.getBookingId(), "pending"))
                .thenReturn(pendingReminders);
        when(reminderRepository.save(any(Reminder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        reminderService.cancelReminders(testBooking.getBookingId());

        verify(reminderRepository, times(3)).save(any(Reminder.class));
    }

    @Test
    @DisplayName("测试发送提醒 - 状态更新正确")
    void testSendReminder_StatusUpdate() {
        Reminder pendingReminder = TestDataBuilder.buildReminder();
        pendingReminder.setReminderStatus("pending");
        
        when(reminderRepository.findByReminderId("reminder_001"))
                .thenReturn(Optional.of(pendingReminder));
        when(reminderRepository.save(any(Reminder.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertDoesNotThrow(() -> reminderService.sendReminder("reminder_001"));
        
        verify(reminderRepository).save(argThat(reminder -> 
                "sent".equals(reminder.getReminderStatus()) && 
                reminder.getSentAt() != null));
    }

    @Test
    @DisplayName("测试已发送的提醒不被重复发送")
    void testSendReminder_AlreadySentNotResent() {
        Reminder sentReminder = TestDataBuilder.buildReminder();
        sentReminder.setReminderStatus("sent");
        
        when(reminderRepository.findByReminderId("reminder_001"))
                .thenReturn(Optional.of(sentReminder));

        assertDoesNotThrow(() -> reminderService.sendReminder("reminder_001"));
        
        verify(reminderRepository, never()).save(any(Reminder.class));
    }

    @Test
    @DisplayName("测试提醒类型包含提前30分钟")
    void testReminderTypes_Include30MinuteReminder() {
        Booking booking60Min = TestDataBuilder.buildBookingWithDuration(60);
        
        List<ReminderService.ReminderConfig> configs = reminderService.calculateReminderTimes(booking60Min);

        assertTrue(configs.stream().anyMatch(c -> "before_30min".equals(c.type)), 
                "60分钟及以上的预约应该有提前30分钟的提醒");
    }

    @Test
    @DisplayName("测试提醒渠道多样性 - SMS和Email")
    void testReminderChannels_Diversity() {
        Booking longBooking = TestDataBuilder.buildBookingWithDuration(240);
        
        List<ReminderService.ReminderConfig> configs = reminderService.calculateReminderTimes(longBooking);

        long smsCount = configs.stream().filter(c -> "sms".equals(c.channel)).count();
        long emailCount = configs.stream().filter(c -> "email".equals(c.channel)).count();

        assertTrue(smsCount > 0, "应该有SMS渠道的提醒");
        assertTrue(emailCount > 0, "长时间预约应该有Email渠道的提醒");
    }

    @Test
    @DisplayName("测试提醒时间计算 - 提前一天")
    void testReminderTimeCalculation_DayBefore() {
        LocalTime bookingTime = LocalTime.of(10, 0);
        testBooking.setBookingTime(bookingTime);
        
        List<ReminderService.ReminderConfig> configs = reminderService.calculateReminderTimes(testBooking);
        
        ReminderService.ReminderConfig dayBefore = configs.stream()
                .filter(c -> "before_day".equals(c.type))
                .findFirst()
                .orElse(null);
        
        assertNotNull(dayBefore);
        assertEquals(bookingTime.minusHours(24), dayBefore.time, 
                "提前一天的提醒时间应该是预约时间减去24小时");
    }

    @Test
    @DisplayName("测试提醒时间计算 - 提前一小时")
    void testReminderTimeCalculation_HourBefore() {
        LocalTime bookingTime = LocalTime.of(14, 30);
        testBooking.setBookingTime(bookingTime);
        
        List<ReminderService.ReminderConfig> configs = reminderService.calculateReminderTimes(testBooking);
        
        ReminderService.ReminderConfig hourBefore = configs.stream()
                .filter(c -> "before_hour".equals(c.type))
                .findFirst()
                .orElse(null);
        
        assertNotNull(hourBefore);
        assertEquals(bookingTime.minusHours(1), hourBefore.time, 
                "提前一小时的提醒时间应该是预约时间减去1小时");
    }
}
