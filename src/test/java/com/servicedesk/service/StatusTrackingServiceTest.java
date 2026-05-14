package com.servicedesk.service;

import com.servicedesk.dto.TicketResponseRequest;
import com.servicedesk.entity.ResponseRecord;
import com.servicedesk.entity.StatusLog;
import com.servicedesk.entity.Ticket;
import com.servicedesk.repository.ResponseRecordRepository;
import com.servicedesk.repository.TicketRepository;
import com.servicedesk.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("状态流转服务测试")
class StatusTrackingServiceTest {

    @Mock
    private StatusLogRepository statusLogRepository;

    @Mock
    private TicketRepository ticketRepository;

    @InjectMocks
    private StatusTrackingService statusTrackingService;

    @BeforeEach
    void setUp() {
    }

    @Test
    @DisplayName("测试状态流转完整链路 - 正常工单流程")
    void testCompleteStatusFlow() {
        Ticket ticket = TestDataBuilder.createTicket("ticket_001", StatusTrackingService.STATUS_CREATED);

        when(ticketRepository.findById("ticket_001")).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(statusLogRepository.save(any(StatusLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Ticket updated1 = statusTrackingService.updateTicketStatus("ticket_001", StatusTrackingService.STATUS_ASSIGNED, "system");
        assertEquals(StatusTrackingService.STATUS_ASSIGNED, updated1.getTicketStatus());

        Ticket updated2 = statusTrackingService.updateTicketStatus("ticket_001", StatusTrackingService.STATUS_IN_PROGRESS, "agent_001");
        assertEquals(StatusTrackingService.STATUS_IN_PROGRESS, updated2.getTicketStatus());

        Ticket updated3 = statusTrackingService.updateTicketStatus("ticket_001", StatusTrackingService.STATUS_RESOLVED, "agent_001");
        assertEquals(StatusTrackingService.STATUS_RESOLVED, updated3.getTicketStatus());

        Ticket updated4 = statusTrackingService.updateTicketStatus("ticket_001", StatusTrackingService.STATUS_CLOSED, "system");
        assertEquals(StatusTrackingService.STATUS_CLOSED, updated4.getTicketStatus());

        verify(statusLogRepository, times(4)).save(any(StatusLog.class));
    }

    @Test
    @DisplayName("测试状态流转完整链路 - 工单转派流程")
    void testTransferStatusFlow() {
        Ticket ticket = TestDataBuilder.createTicketWithAssignee("ticket_001", StatusTrackingService.STATUS_ASSIGNED, "agent_001");

        when(ticketRepository.findById("ticket_001")).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(statusLogRepository.save(any(StatusLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Ticket updated1 = statusTrackingService.updateTicketStatus("ticket_001", StatusTrackingService.STATUS_TRANSFERRED, "agent_001");
        assertEquals(StatusTrackingService.STATUS_TRANSFERRED, updated1.getTicketStatus());

        Ticket updated2 = statusTrackingService.updateTicketStatus("ticket_001", StatusTrackingService.STATUS_ASSIGNED, "system");
        assertEquals(StatusTrackingService.STATUS_ASSIGNED, updated2.getTicketStatus());

        verify(statusLogRepository, times(2)).save(any(StatusLog.class));
    }

    @Test
    @DisplayName("测试异常状态处理 - 状态回滚场景")
    void testInvalidStatusTransition() {
        assertTrue(statusTrackingService.isValidStatusTransition(
                StatusTrackingService.STATUS_CREATED,
                StatusTrackingService.STATUS_ASSIGNED
        ));

        assertTrue(statusTrackingService.isValidStatusTransition(
                StatusTrackingService.STATUS_ASSIGNED,
                StatusTrackingService.STATUS_IN_PROGRESS
        ));

        assertFalse(statusTrackingService.isValidStatusTransition(
                StatusTrackingService.STATUS_CREATED,
                StatusTrackingService.STATUS_RESOLVED
        ));

        assertFalse(statusTrackingService.isValidStatusTransition(
                StatusTrackingService.STATUS_RESOLVED,
                StatusTrackingService.STATUS_IN_PROGRESS
        ));

        assertFalse(statusTrackingService.isValidStatusTransition(
                StatusTrackingService.STATUS_CLOSED,
                StatusTrackingService.STATUS_ASSIGNED
        ));
    }

    @Test
    @DisplayName("测试异常状态处理 - 已关闭工单不可转换")
    void testClosedTicketNoTransitions() {
        assertFalse(statusTrackingService.isValidStatusTransition(
                StatusTrackingService.STATUS_CLOSED,
                StatusTrackingService.STATUS_CREATED
        ));
        assertFalse(statusTrackingService.isValidStatusTransition(
                StatusTrackingService.STATUS_CLOSED,
                StatusTrackingService.STATUS_ASSIGNED
        ));
        assertFalse(statusTrackingService.isValidStatusTransition(
                StatusTrackingService.STATUS_CLOSED,
                StatusTrackingService.STATUS_RESOLVED
        ));
    }

    @Test
    @DisplayName("测试状态变更日志记录完整性")
    void testStatusLogRecording() {
        Ticket ticket = TestDataBuilder.createTicket("ticket_001", StatusTrackingService.STATUS_CREATED);

        when(ticketRepository.findById("ticket_001")).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(statusLogRepository.save(any(StatusLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        statusTrackingService.updateTicketStatus("ticket_001", StatusTrackingService.STATUS_ASSIGNED, "system");

        verify(statusLogRepository, times(1)).save(argThat(log ->
                log.getTicketId().equals("ticket_001") &&
                log.getFromStatus().equals(StatusTrackingService.STATUS_CREATED) &&
                log.getToStatus().equals(StatusTrackingService.STATUS_ASSIGNED) &&
                log.getChangedBy().equals("system") &&
                log.getChangedAt() != null
        ));
    }

    @Test
    @DisplayName("测试状态变更日志记录完整性 - 多次变更")
    void testMultipleStatusLogs() {
        Ticket ticket = TestDataBuilder.createTicket("ticket_001", StatusTrackingService.STATUS_CREATED);

        when(ticketRepository.findById("ticket_001")).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any(Ticket.class))).thenAnswer(invocation -> {
            Ticket arg = invocation.getArgument(0);
            ticket.setTicketStatus(arg.getTicketStatus());
            return ticket;
        });
        when(statusLogRepository.save(any(StatusLog.class))).thenAnswer(invocation -> invocation.getArgument(0));

        statusTrackingService.updateTicketStatus("ticket_001", StatusTrackingService.STATUS_ASSIGNED, "system");
        statusTrackingService.updateTicketStatus("ticket_001", StatusTrackingService.STATUS_IN_PROGRESS, "agent_001");
        statusTrackingService.updateTicketStatus("ticket_001", StatusTrackingService.STATUS_RESOLVED, "agent_001");

        verify(statusLogRepository, times(3)).save(any(StatusLog.class));
    }

    @Test
    @DisplayName("测试相同状态更新不记录日志")
    void testSameStatusDoesNotCreateLog() {
        Ticket ticket = TestDataBuilder.createTicket("ticket_001", StatusTrackingService.STATUS_ASSIGNED);

        when(ticketRepository.findById("ticket_001")).thenReturn(Optional.of(ticket));

        statusTrackingService.updateTicketStatus("ticket_001", StatusTrackingService.STATUS_ASSIGNED, "system");

        verify(statusLogRepository, never()).save(any(StatusLog.class));
    }

    @Test
    @DisplayName("测试不存在工单抛出异常")
    void testNonExistentTicketThrowsException() {
        when(ticketRepository.findById("ticket_999")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () ->
                statusTrackingService.updateTicketStatus("ticket_999", StatusTrackingService.STATUS_ASSIGNED, "system")
        );
    }

    @Test
    @DisplayName("测试获取状态历史记录")
    void testGetStatusHistory() {
        StatusLog log1 = TestDataBuilder.createStatusLog("ticket_001",
                StatusTrackingService.STATUS_CREATED, StatusTrackingService.STATUS_ASSIGNED, "system");
        StatusLog log2 = TestDataBuilder.createStatusLog("ticket_001",
                StatusTrackingService.STATUS_ASSIGNED, StatusTrackingService.STATUS_IN_PROGRESS, "agent_001");
        StatusLog log3 = TestDataBuilder.createStatusLog("ticket_001",
                StatusTrackingService.STATUS_IN_PROGRESS, StatusTrackingService.STATUS_RESOLVED, "agent_001");

        List<StatusLog> logs = Arrays.asList(log1, log2, log3);
        when(statusLogRepository.findByTicketIdOrderByChangedAtAsc("ticket_001")).thenReturn(logs);

        List<StatusLog> result = statusTrackingService.getStatusHistory("ticket_001");

        assertEquals(3, result.size());
        assertEquals(StatusTrackingService.STATUS_CREATED, result.get(0).getFromStatus());
        assertEquals(StatusTrackingService.STATUS_RESOLVED, result.get(2).getToStatus());
    }

    @Test
    @DisplayName("测试空状态值验证")
    void testNullStatusValidation() {
        assertFalse(statusTrackingService.isValidStatusTransition(null, StatusTrackingService.STATUS_ASSIGNED));
        assertFalse(statusTrackingService.isValidStatusTransition(StatusTrackingService.STATUS_CREATED, null));
        assertFalse(statusTrackingService.isValidStatusTransition(null, null));
    }
}
