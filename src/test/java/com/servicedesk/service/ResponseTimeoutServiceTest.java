package com.servicedesk.service;

import com.servicedesk.config.ServiceDeskProperties;
import com.servicedesk.entity.Ticket;
import com.servicedesk.repository.TicketRepository;
import com.servicedesk.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("响应超时服务测试")
class ResponseTimeoutServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ResponseTimeoutService responseTimeoutService;

    private ServiceDeskProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ServiceDeskProperties();
        properties.getResponseTimeout().setDefaultTimeoutSeconds(300);
        properties.getResponseTimeout().setWarningRatio(0.7);
        properties.getResponseTimeout().getPriorityTimeouts().put("high", 120);
        properties.getResponseTimeout().getPriorityTimeouts().put("medium", 300);
        properties.getResponseTimeout().getPriorityTimeouts().put("low", 600);

        responseTimeoutService = new ResponseTimeoutService(ticketRepository, properties, eventPublisher);
        responseTimeoutService.clearAllWarnings();
    }

    @Test
    @DisplayName("测试工单分配后启动响应计时")
    void testResponseTimerStartsOnAssignment() {
        Ticket assignedTicket = TestDataBuilder.createTicketWithAssignee(
                "ticket_001",
                StatusTrackingService.STATUS_ASSIGNED,
                "agent_001"
        );

        long elapsed = responseTimeoutService.getElapsedSeconds(assignedTicket);
        assertTrue(elapsed >= 0, "已分配工单应有响应计时");
    }

    @Test
    @DisplayName("测试响应时间接近超时阈值时发送警告提醒")
    void testWarningSentWhenApproachingTimeout() {
        Ticket warningTicket = TestDataBuilder.createPendingResponseTicket(
                "ticket_001", "high", 90
        );

        List<Ticket> tickets = Collections.singletonList(warningTicket);
        when(ticketRepository.findByTicketStatus(StatusTrackingService.STATUS_ASSIGNED)).thenReturn(tickets);

        List<Ticket> result = responseTimeoutService.checkAndWarnTimeoutTickets();

        assertEquals(1, result.size(), "应检测到即将超时的工单");
        assertTrue(responseTimeoutService.isTicketWarned("ticket_001"), "工单应被标记为已警告");
    }

    @Test
    @DisplayName("测试不同工单紧急程度下的超时阈值差异 - 高优先级")
    void testHighPriorityTimeoutThreshold() {
        int highTimeout = responseTimeoutService.getTimeoutByPriority("high");
        assertEquals(120, highTimeout, "高优先级工单超时阈值应为120秒");

        int highWarning = responseTimeoutService.getWarningThresholdByPriority("high");
        assertEquals(84, highWarning, "高优先级工单警告阈值应为120 * 0.7 = 84秒");
    }

    @Test
    @DisplayName("测试不同工单紧急程度下的超时阈值差异 - 中优先级")
    void testMediumPriorityTimeoutThreshold() {
        int mediumTimeout = responseTimeoutService.getTimeoutByPriority("medium");
        assertEquals(300, mediumTimeout, "中优先级工单超时阈值应为300秒");

        int mediumWarning = responseTimeoutService.getWarningThresholdByPriority("medium");
        assertEquals(210, mediumWarning, "中优先级工单警告阈值应为300 * 0.7 = 210秒");
    }

    @Test
    @DisplayName("测试不同工单紧急程度下的超时阈值差异 - 低优先级")
    void testLowPriorityTimeoutThreshold() {
        int lowTimeout = responseTimeoutService.getTimeoutByPriority("low");
        assertEquals(600, lowTimeout, "低优先级工单超时阈值应为600秒");

        int lowWarning = responseTimeoutService.getWarningThresholdByPriority("low");
        assertEquals(420, lowWarning, "低优先级工单警告阈值应为600 * 0.7 = 420秒");
    }

    @Test
    @DisplayName("测试超时阈值配置动态加载")
    void testConfigurationLoading() {
        assertEquals(300, properties.getResponseTimeout().getDefaultTimeoutSeconds());
        assertEquals(0.7, properties.getResponseTimeout().getWarningRatio());
        assertEquals(120, (int) properties.getResponseTimeout().getPriorityTimeouts().get("high"));
        assertEquals(300, (int) properties.getResponseTimeout().getPriorityTimeouts().get("medium"));
        assertEquals(600, (int) properties.getResponseTimeout().getPriorityTimeouts().get("low"));
    }

    @Test
    @DisplayName("测试处理中的工单不触发超时检查")
    void testInProgressTicketNotChecked() {
        Ticket inProgressTicket = TestDataBuilder.createInProgressTicket("ticket_001");
        inProgressTicket.setAssignedAt(java.time.Instant.now().minus(java.time.Duration.ofHours(1)));

        boolean isWarning = responseTimeoutService.isResponseWarningTriggered(inProgressTicket);
        boolean isTimeout = responseTimeoutService.isResponseTimedOut(inProgressTicket);

        assertFalse(isWarning, "处理中的工单不应触发警告");
        assertFalse(isTimeout, "处理中的工单不应触发超时");
    }

    @Test
    @DisplayName("测试已解决工单不触发超时检查")
    void testResolvedTicketNotChecked() {
        Ticket resolvedTicket = TestDataBuilder.createResolvedTicket("ticket_001");

        boolean isWarning = responseTimeoutService.isResponseWarningTriggered(resolvedTicket);
        boolean isTimeout = responseTimeoutService.isResponseTimedOut(resolvedTicket);

        assertFalse(isWarning, "已解决工单不应触发警告");
        assertFalse(isTimeout, "已解决工单不应触发超时");
    }

    @Test
    @DisplayName("测试响应后清除超时警告状态")
    void testResponseClearsWarningStatus() {
        Ticket timeoutTicket = TestDataBuilder.createPendingResponseTicket(
                "ticket_001", "high", 200
        );

        List<Ticket> tickets = Collections.singletonList(timeoutTicket);
        when(ticketRepository.findByTicketStatus(StatusTrackingService.STATUS_ASSIGNED)).thenReturn(tickets);

        responseTimeoutService.checkAndWarnTimeoutTickets();
        assertTrue(responseTimeoutService.isTicketTimedOut("ticket_001"));

        responseTimeoutService.markAsResponded("ticket_001");
        assertFalse(responseTimeoutService.isTicketWarned("ticket_001"));
        assertFalse(responseTimeoutService.isTicketTimedOut("ticket_001"));
    }

    @Test
    @DisplayName("测试多个工单超时检查")
    void testMultipleTicketsTimeoutCheck() {
        Ticket warningTicket = TestDataBuilder.createPendingResponseTicket("ticket_001", "high", 90);
        Ticket timeoutTicket = TestDataBuilder.createPendingResponseTicket("ticket_002", "high", 150);
        Ticket normalTicket = TestDataBuilder.createPendingResponseTicket("ticket_003", "high", 30);

        List<Ticket> tickets = new ArrayList<>();
        tickets.add(warningTicket);
        tickets.add(timeoutTicket);
        tickets.add(normalTicket);

        when(ticketRepository.findByTicketStatus(StatusTrackingService.STATUS_ASSIGNED)).thenReturn(tickets);

        List<Ticket> result = responseTimeoutService.checkAndWarnTimeoutTickets();

        assertEquals(2, result.size(), "应检测到2个异常工单(1个警告,1个超时)");
    }

    @Test
    @DisplayName("测试未分配工单不触发计时")
    void testUnassignedTicketNoTimer() {
        Ticket unassignedTicket = TestDataBuilder.createTicket("ticket_001", StatusTrackingService.STATUS_CREATED);

        long elapsed = responseTimeoutService.getElapsedSeconds(unassignedTicket);
        assertEquals(0, elapsed, "未分配工单计时应为0");

        boolean isWarning = responseTimeoutService.isResponseWarningTriggered(unassignedTicket);
        assertFalse(isWarning, "未分配工单不应触发警告");
    }
}
