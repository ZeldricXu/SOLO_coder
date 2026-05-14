package com.fitnesscenter.service;

import com.fitnesscenter.builder.TestDataBuilder;
import com.fitnesscenter.model.Member;
import com.fitnesscenter.reminder.ReminderService;
import com.fitnesscenter.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("提醒服务测试")
class ReminderServiceTest {

    @Mock
    private MemberRepository memberRepository;

    @InjectMocks
    private ReminderService reminderService;

    private Member activeMember;
    private Member inactiveMember;
    private Member regularMember;

    private static final int ACTIVE_THRESHOLD = 3;
    private static final int ACTIVE_FREQUENCY_DAYS = 7;
    private static final int INACTIVE_FREQUENCY_DAYS = 2;

    @BeforeEach
    void setUp() {
        reminderService.resetReminderStats();
        activeMember = TestDataBuilder.buildActiveMember();
        inactiveMember = TestDataBuilder.buildInactiveMember();
        regularMember = TestDataBuilder.buildRegularMember();
    }

    @Test
    @DisplayName("测试活跃会员判断 - 训练次数>=3")
    void testActiveMemberDetection() {
        Member highActiveMember = TestDataBuilder.buildMemberWithCustomStats("MEMBER_001", 5, 5, 1500);

        ReminderService.ReminderResult result = reminderService.shouldSendReminder(highActiveMember);

        assertEquals("active", result.frequencyType, "活跃会员应该被识别为active类型");
        assertEquals(ACTIVE_FREQUENCY_DAYS, result.frequencyDays, "活跃会员应该使用7天频率");
    }

    @Test
    @DisplayName("测试不活跃会员判断 - 训练次数<3")
    void testInactiveMemberDetection() {
        Member lowActiveMember = TestDataBuilder.buildMemberWithCustomStats("MEMBER_002", 1, 1, 300);

        ReminderService.ReminderResult result = reminderService.shouldSendReminder(lowActiveMember);

        assertEquals("inactive", result.frequencyType, "不活跃会员应该被识别为inactive类型");
        assertEquals(INACTIVE_FREQUENCY_DAYS, result.frequencyDays, "不活跃会员应该使用2天频率");
    }

    @Test
    @DisplayName("测试刚好等于阈值的会员判断")
    void testThresholdMemberDetection() {
        Member thresholdMember = TestDataBuilder.buildMemberWithCustomStats("MEMBER_003", 3, ACTIVE_THRESHOLD, 900);

        ReminderService.ReminderResult result = reminderService.shouldSendReminder(thresholdMember);

        assertEquals("active", result.frequencyType, "刚好等于阈值的会员应该被识别为active");
    }

    @Test
    @DisplayName("测试发送提醒成功")
    void testSendReminderSuccess() {
        boolean sent = reminderService.sendReminder(activeMember, "active");

        assertTrue(sent, "提醒应该发送成功");
        assertEquals(1, reminderService.getReminderCount(activeMember.getMemberId()), "提醒计数应该增加");
    }

    @Test
    @DisplayName("测试活跃会员使用低频提醒")
    void testActiveMemberLowFrequency() {
        reminderService.shouldSendReminder(activeMember);

        assertEquals(1, reminderService.getActiveMemberReminders(), "活跃会员提醒计数应该增加");
        assertEquals(0, reminderService.getInactiveMemberReminders(), "不活跃会员提醒计数不应该增加");
    }

    @Test
    @DisplayName("测试不活跃会员使用高频提醒")
    void testInactiveMemberHighFrequency() {
        reminderService.shouldSendReminder(inactiveMember);

        assertEquals(0, reminderService.getActiveMemberReminders(), "活跃会员提醒计数不应该增加");
        assertEquals(1, reminderService.getInactiveMemberReminders(), "不活跃会员提醒计数应该增加");
    }

    @Test
    @DisplayName("测试训练记录收集 - 有数据的会员")
    void testCollectTrainingRecordsWithData() {
        Member memberWithData = TestDataBuilder.buildMemberWithCustomStats("MEMBER_004", 5, 5, 1500);
        when(memberRepository.findByMemberId("MEMBER_004")).thenReturn(java.util.Optional.of(memberWithData));

        List<String> records = reminderService.collectTrainingRecordsToRemind("MEMBER_004");

        assertFalse(records.isEmpty(), "应该收集到训练记录");
        assertTrue(records.stream().anyMatch(r -> r.contains("1500")), "应该包含热量信息");
        assertTrue(records.stream().anyMatch(r -> r.contains("5")), "应该包含训练次数信息");
    }

    @Test
    @DisplayName("测试训练记录收集 - 无数据的会员")
    void testCollectTrainingRecordsWithNoData() {
        Member memberWithNoData = TestDataBuilder.buildMemberWithCustomStats("MEMBER_005", 0, 0, 0);
        when(memberRepository.findByMemberId("MEMBER_005")).thenReturn(java.util.Optional.of(memberWithNoData));

        List<String> records = reminderService.collectTrainingRecordsToRemind("MEMBER_005");

        assertTrue(records.isEmpty(), "应该没有训练记录");
    }

    @Test
    @DisplayName("测试训练记录收集 - 不存在的会员")
    void testCollectTrainingRecordsForNonExistentMember() {
        when(memberRepository.findByMemberId("NON_EXISTENT")).thenReturn(java.util.Optional.empty());

        List<String> records = reminderService.collectTrainingRecordsToRemind("NON_EXISTENT");

        assertTrue(records.isEmpty(), "不存在的会员应该返回空列表");
    }

    @Test
    @DisplayName("测试批量发送提醒 - 多个会员")
    void testSendMultipleReminders() {
        Member member1 = TestDataBuilder.buildMemberWithCustomStats("MEMBER_1", 5, 5, 1500);
        Member member2 = TestDataBuilder.buildMemberWithCustomStats("MEMBER_2", 1, 1, 300);
        Member member3 = TestDataBuilder.buildMemberWithCustomStats("MEMBER_3", 3, 3, 900);

        when(memberRepository.findByMemberStatus("active")).thenReturn(Arrays.asList(member1, member2, member3));

        List<ReminderService.ReminderResult> results = reminderService.sendTrainingReminders();

        assertEquals(3, results.size(), "应该发送3个提醒");
        assertEquals(2, reminderService.getActiveMemberReminders(), "应该有2个活跃会员提醒");
        assertEquals(1, reminderService.getInactiveMemberReminders(), "应该有1个不活跃会员提醒");
        assertEquals(3, reminderService.getTotalRemindersSent(), "应该总共发送3个提醒");
    }

    @Test
    @DisplayName("测试批量发送提醒 - 无会员")
    void testSendRemindersWithNoMembers() {
        when(memberRepository.findByMemberStatus("active")).thenReturn(Collections.emptyList());

        List<ReminderService.ReminderResult> results = reminderService.sendTrainingReminders();

        assertTrue(results.isEmpty(), "应该没有提醒被发送");
        assertEquals(0, reminderService.getTotalRemindersSent(), "总提醒数应该为0");
    }

    @Test
    @DisplayName("测试提醒发送机制 - 多次发送同一会员")
    void testSendMultipleRemindersToSameMember() {
        for (int i = 0; i < 3; i++) {
            reminderService.sendReminder(activeMember, "active");
        }

        assertEquals(3, reminderService.getReminderCount(activeMember.getMemberId()), "同一会员应该发送3次提醒");
        assertEquals(3, reminderService.getTotalRemindersSent(), "总提醒数应该为3");
    }

    @Test
    @DisplayName("测试提醒结果包含正确信息")
    void testReminderResultContainsCorrectInfo() {
        Member member = TestDataBuilder.buildMemberWithCustomStats("MEMBER_006", 5, 5, 1500);

        ReminderService.ReminderResult result = reminderService.shouldSendReminder(member);

        assertEquals("MEMBER_006", result.memberId, "会员ID应该正确");
        assertTrue(result.shouldSend, "应该发送提醒");
        assertEquals(5, result.trainingCount, "训练次数应该正确");
        assertNotNull(result.reminderDate, "提醒日期不应该为空");
    }

    @Test
    @DisplayName("测试重置提醒统计")
    void testResetReminderStats() {
        reminderService.sendReminder(activeMember, "active");
        reminderService.sendReminder(inactiveMember, "inactive");

        assertEquals(1, reminderService.getTotalRemindersSent());
        assertEquals(1, reminderService.getActiveMemberReminders());
        assertEquals(1, reminderService.getInactiveMemberReminders());

        reminderService.resetReminderStats();

        assertEquals(0, reminderService.getTotalRemindersSent(), "总提醒数应该重置为0");
        assertEquals(0, reminderService.getActiveMemberReminders(), "活跃会员提醒数应该重置为0");
        assertEquals(0, reminderService.getInactiveMemberReminders(), "不活跃会员提醒数应该重置为0");
        assertEquals(0, reminderService.getReminderCount(activeMember.getMemberId()), "会员提醒计数应该重置为0");
    }

    @Test
    @DisplayName("测试活跃和不活跃会员混合提醒")
    void testMixedMemberTypesReminders() {
        Member active1 = TestDataBuilder.buildMemberWithCustomStats("ACTIVE_1", 5, 5, 1500);
        Member active2 = TestDataBuilder.buildMemberWithCustomStats("ACTIVE_2", 4, 4, 1200);
        Member inactive1 = TestDataBuilder.buildMemberWithCustomStats("INACTIVE_1", 1, 1, 300);
        Member inactive2 = TestDataBuilder.buildMemberWithCustomStats("INACTIVE_2", 0, 0, 0);

        when(memberRepository.findByMemberStatus("active")).thenReturn(Arrays.asList(active1, active2, inactive1, inactive2));

        List<ReminderService.ReminderResult> results = reminderService.sendTrainingReminders();

        assertEquals(4, results.size(), "应该发送4个提醒");
        assertEquals(2, results.stream().filter(r -> "active".equals(r.frequencyType)).count(), "应该有2个活跃会员");
        assertEquals(2, results.stream().filter(r -> "inactive".equals(r.frequencyType)).count(), "应该有2个不活跃会员");
        assertEquals(2, reminderService.getActiveMemberReminders());
        assertEquals(2, reminderService.getInactiveMemberReminders());
    }

    @Test
    @DisplayName("测试训练记录收集包含预订信息")
    void testCollectRecordsIncludesBookingInfo() {
        Member memberWithBookings = TestDataBuilder.buildMemberWithCustomStats("MEMBER_007", 3, 2, 600);
        when(memberRepository.findByMemberId("MEMBER_007")).thenReturn(java.util.Optional.of(memberWithBookings));

        List<String> records = reminderService.collectTrainingRecordsToRemind("MEMBER_007");

        assertTrue(records.stream().anyMatch(r -> r.contains("Booking count")), "应该包含预订信息");
        assertTrue(records.stream().anyMatch(r -> r.contains("3")), "应该包含预订次数");
    }

    @Test
    @DisplayName("测试不存在的会员提醒计数")
    void testGetReminderCountForNonExistentMember() {
        int count = reminderService.getReminderCount("NON_EXISTENT_MEMBER");
        assertEquals(0, count, "不存在的会员提醒计数应该为0");
    }
}
