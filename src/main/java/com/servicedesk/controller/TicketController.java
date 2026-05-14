package com.servicedesk.controller;

import com.servicedesk.dto.*;
import com.servicedesk.entity.*;
import com.servicedesk.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketManagementService ticketManagementService;
    private final ResponseService responseService;
    private final AssignmentService assignmentService;
    private final TransferService transferService;
    private final SatisfactionService satisfactionService;
    private final HistoryService historyService;
    private final StatisticsService statisticsService;

    @PostMapping("/create")
    public ApiResponse<CreateTicketResponse> createTicket(@Validated @RequestBody CreateTicketRequest request) {
        log.info("收到工单创建请求: {}", request.getTicketTitle());
        Ticket ticket = ticketManagementService.createTicket(request);
        CreateTicketResponse response = new CreateTicketResponse(ticket.getTicketId(), ticket.getTicketStatus());
        return ApiResponse.success(response, "工单创建成功");
    }

    @PostMapping("/response")
    public ApiResponse<TicketResponseResponse> ticketResponse(@Validated @RequestBody TicketResponseRequest request) {
        log.info("收到工单响应请求: {}", request.getTicketId());
        ResponseRecord responseRecord = responseService.recordResponse(request);
        TicketResponseResponse response = new TicketResponseResponse(responseRecord.getResponseId());
        return ApiResponse.success(response, "响应记录成功");
    }

    @GetMapping("/status")
    public ApiResponse<TicketStatusResponse> getTicketStatus(@RequestParam String ticket_id) {
        log.info("收到工单状态查询请求: {}", ticket_id);
        Optional<Ticket> ticketOpt = ticketManagementService.getTicketById(ticket_id);
        if (ticketOpt.isEmpty()) {
            return ApiResponse.error(404, "工单不存在");
        }
        TicketStatusResponse response = new TicketStatusResponse(ticketOpt.get());
        return ApiResponse.success(response);
    }

    @GetMapping("/{ticketId}")
    public ApiResponse<Ticket> getTicket(@PathVariable String ticketId) {
        log.info("获取工单详情: {}", ticketId);
        Optional<Ticket> ticketOpt = ticketManagementService.getTicketById(ticketId);
        if (ticketOpt.isEmpty()) {
            return ApiResponse.error(404, "工单不存在");
        }
        return ApiResponse.success(ticketOpt.get());
    }

    @PostMapping("/assign")
    public ApiResponse<Ticket> manualAssign(@Validated @RequestBody ManualAssignRequest request) {
        log.info("手动分配工单: {} -> {}", request.getTicketId(), request.getAgentId());
        try {
            Ticket ticket = assignmentService.manualAssignTicket(request.getTicketId(), request.getAgentId());
            return ApiResponse.success(ticket, "工单分配成功");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PostMapping("/transfer")
    public ApiResponse<TransferRecord> transferTicket(@Validated @RequestBody TransferTicketRequest request) {
        log.info("转派工单: {}", request.getTicketId());
        try {
            TransferRecord record = transferService.transferTicket(request);
            return ApiResponse.success(record, "工单转派成功");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PostMapping("/satisfaction")
    public ApiResponse<Satisfaction> submitSatisfaction(@Validated @RequestBody SatisfactionRequest request) {
        log.info("提交满意度评价: {}", request.getTicketId());
        try {
            Satisfaction satisfaction = satisfactionService.submitSatisfaction(request);
            return ApiResponse.success(satisfaction, "评价提交成功");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (IllegalStateException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/{ticketId}/history")
    public ApiResponse<Map<String, Object>> getTicketHistory(@PathVariable String ticketId) {
        log.info("获取工单历史: {}", ticketId);
        try {
            Map<String, Object> history = historyService.getTicketFullHistory(ticketId);
            return ApiResponse.success(history);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    @PostMapping("/{ticketId}/resolve")
    public ApiResponse<Ticket> resolveTicket(@PathVariable String ticketId) {
        log.info("解决工单: {}", ticketId);
        try {
            Ticket ticket = ticketManagementService.resolveTicket(ticketId);
            return ApiResponse.success(ticket, "工单已解决");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }

    @GetMapping
    public ApiResponse<List<Ticket>> getAllTickets() {
        log.info("获取所有工单");
        List<Ticket> tickets = ticketManagementService.getAllTickets();
        return ApiResponse.success(tickets);
    }

    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<Ticket>> getTicketsByCustomer(@PathVariable String customerId) {
        log.info("获取客户工单: {}", customerId);
        List<Ticket> tickets = ticketManagementService.getTicketsByCustomerId(customerId);
        return ApiResponse.success(tickets);
    }

    @GetMapping("/agent/{agentId}")
    public ApiResponse<List<Ticket>> getTicketsByAgent(@PathVariable String agentId) {
        log.info("获取客服工单: {}", agentId);
        List<Ticket> tickets = ticketManagementService.getTicketsByAssigneeId(agentId);
        return ApiResponse.success(tickets);
    }
}
