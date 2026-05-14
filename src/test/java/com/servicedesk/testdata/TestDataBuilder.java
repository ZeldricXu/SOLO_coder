package com.servicedesk.testdata;

import com.servicedesk.dto.CreateTicketRequest;
import com.servicedesk.dto.TicketResponseRequest;
import com.servicedesk.dto.TransferTicketRequest;
import com.servicedesk.entity.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class TestDataBuilder {

    private TestDataBuilder() {}

    public static Agent createAgent(String agentId, String agentGroup) {
        Agent agent = new Agent();
        agent.setAgentId(agentId);
        agent.setAgentName("客服_" + agentId);
        agent.setAgentGroup(agentGroup);
        agent.setAgentStatus("online");
        agent.setCurrentTickets(0);
        agent.setMaxTickets(5);
        agent.setResponseAvgTime(60);
        return agent;
    }

    public static Agent createAgentWithLoad(String agentId, String agentGroup, int currentLoad, int maxLoad) {
        Agent agent = createAgent(agentId, agentGroup);
        agent.setCurrentTickets(currentLoad);
        agent.setMaxTickets(maxLoad);
        return agent;
    }

    public static Agent createHighLoadAgent(String agentId, String agentGroup) {
        Agent agent = createAgent(agentId, agentGroup);
        agent.setCurrentTickets(4);
        agent.setMaxTickets(5);
        return agent;
    }

    public static Agent createCriticalLoadAgent(String agentId, String agentGroup) {
        Agent agent = createAgent(agentId, agentGroup);
        agent.setCurrentTickets(5);
        agent.setMaxTickets(5);
        return agent;
    }

    public static Agent createOfflineAgent(String agentId, String agentGroup) {
        Agent agent = createAgent(agentId, agentGroup);
        agent.setAgentStatus("offline");
        return agent;
    }

    public static Ticket createTicket(String ticketId, String status) {
        Ticket ticket = new Ticket();
        ticket.setTicketId(ticketId);
        ticket.setTicketTitle("测试工单-" + ticketId);
        ticket.setTicketContent("这是一个测试工单内容");
        ticket.setTicketCategory("technical");
        ticket.setTicketPriority("medium");
        ticket.setTicketStatus(status);
        ticket.setCustomerId("customer_001");
        ticket.setCreatedAt(Instant.now());
        return ticket;
    }

    public static Ticket createTicketWithAssignee(String ticketId, String status, String assigneeId) {
        Ticket ticket = createTicket(ticketId, status);
        ticket.setAssigneeId(assigneeId);
        ticket.setAssignedAt(Instant.now());
        return ticket;
    }

    public static Ticket createTicketWithPriority(String ticketId, String priority) {
        Ticket ticket = createTicket(ticketId, StatusTrackingService.STATUS_ASSIGNED);
        ticket.setTicketPriority(priority);
        ticket.setAssigneeId("agent_001");
        ticket.setAssignedAt(Instant.now());
        return ticket;
    }

    public static Ticket createPendingResponseTicket(String ticketId, String priority, long secondsSinceAssignment) {
        Ticket ticket = createTicket(ticketId, StatusTrackingService.STATUS_ASSIGNED);
        ticket.setTicketPriority(priority);
        ticket.setAssigneeId("agent_001");
        ticket.setAssignedAt(Instant.now().minus(Duration.ofSeconds(secondsSinceAssignment)));
        return ticket;
    }

    public static Ticket createInProgressTicket(String ticketId) {
        Ticket ticket = createTicket(ticketId, StatusTrackingService.STATUS_IN_PROGRESS);
        ticket.setAssigneeId("agent_001");
        ticket.setAssignedAt(Instant.now());
        ticket.setFirstResponseAt(Instant.now());
        return ticket;
    }

    public static Ticket createResolvedTicket(String ticketId) {
        Ticket ticket = createTicket(ticketId, StatusTrackingService.STATUS_RESOLVED);
        ticket.setAssigneeId("agent_001");
        ticket.setAssignedAt(Instant.now().minus(Duration.ofMinutes(30)));
        ticket.setFirstResponseAt(Instant.now().minus(Duration.ofMinutes(25)));
        ticket.setResolvedAt(Instant.now());
        ticket.setResponseTimeSeconds(300L);
        ticket.setResolutionTimeSeconds(1800L);
        return ticket;
    }

    public static CreateTicketRequest createCreateTicketRequest(String title, String content, String category) {
        CreateTicketRequest request = new CreateTicketRequest();
        request.setTicketTitle(title);
        request.setTicketContent(content);
        request.setTicketCategory(category);
        request.setCustomerId("customer_" + UUID.randomUUID().toString().substring(0, 8));
        return request;
    }

    public static CreateTicketRequest createHighPriorityRequest() {
        CreateTicketRequest request = createCreateTicketRequest(
                "系统紧急故障",
                "生产环境服务器宕机，无法访问！紧急！",
                "technical"
        );
        return request;
    }

    public static CreateTicketRequest createMediumPriorityRequest() {
        CreateTicketRequest request = createCreateTicketRequest(
                "登录有问题",
                "用户登录时提示错误，影响使用",
                "technical"
        );
        return request;
    }

    public static CreateTicketRequest createLowPriorityRequest() {
        CreateTicketRequest request = createCreateTicketRequest(
                "建议添加新功能",
                "希望系统能够增加导出功能",
                "功能请求"
        );
        return request;
    }

    public static CreateTicketRequest createRequestWithExplicitPriority(String priority) {
        CreateTicketRequest request = createCreateTicketRequest("测试工单", "测试内容", "technical");
        request.setTicketPriority(priority);
        return request;
    }

    public static TicketResponseRequest createTicketResponseRequest(String ticketId, String content) {
        TicketResponseRequest request = new TicketResponseRequest();
        request.setTicketId(ticketId);
        request.setResponseContent(content);
        request.setAgentId("agent_001");
        request.setResponseType("reply");
        return request;
    }

    public static TransferTicketRequest createTransferRequest(String ticketId, String fromAgent, String toAgent) {
        TransferTicketRequest request = new TransferTicketRequest();
        request.setTicketId(ticketId);
        request.setToAgentId(toAgent);
        request.setFromAgentId(fromAgent);
        request.setTransferReason("需要专业技术支持");
        return request;
    }

    public static StatusLog createStatusLog(String ticketId, String fromStatus, String toStatus, String changedBy) {
        StatusLog log = new StatusLog();
        log.setStatusLogId("log_" + UUID.randomUUID().toString().substring(0, 8));
        log.setTicketId(ticketId);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setChangedAt(Instant.now());
        log.setChangedBy(changedBy);
        return log;
    }

    public static ResponseRecord createResponseRecord(String ticketId, String agentId) {
        ResponseRecord record = new ResponseRecord();
        record.setResponseId("response_" + UUID.randomUUID().toString().substring(0, 8));
        record.setTicketId(ticketId);
        record.setAgentId(agentId);
        record.setResponseContent("已收到您的请求，正在处理中");
        record.setResponseTime(Instant.now());
        record.setResponseType("reply");
        return record;
    }

    public static TransferRecord createTransferRecord(String ticketId, String fromAgent, String toAgent) {
        TransferRecord record = new TransferRecord();
        record.setTransferId("transfer_" + UUID.randomUUID().toString().substring(0, 8));
        record.setTicketId(ticketId);
        record.setFromAgent(fromAgent);
        record.setToAgent(toAgent);
        record.setTransferReason("技术升级");
        record.setTransferTime(Instant.now());
        return record;
    }

    public static Satisfaction createSatisfaction(String ticketId, int score) {
        Satisfaction satisfaction = new Satisfaction();
        satisfaction.setSatisfactionId("satisfaction_" + UUID.randomUUID().toString().substring(0, 8));
        satisfaction.setTicketId(ticketId);
        satisfaction.setCustomerId("customer_001");
        satisfaction.setSatisfactionScore(score);
        satisfaction.setSatisfactionComment("服务很好");
        satisfaction.setEvaluatedAt(Instant.now());
        return satisfaction;
    }
}
