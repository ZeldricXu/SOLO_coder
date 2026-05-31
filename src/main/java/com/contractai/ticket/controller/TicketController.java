package com.contractai.ticket.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.contractai.common.result.ApiResponse;
import com.contractai.ticket.dto.TicketDTO;
import com.contractai.ticket.entity.AssignmentStrategy;
import com.contractai.ticket.entity.EmployeeWorkload;
import com.contractai.ticket.entity.Ticket;
import com.contractai.ticket.entity.TicketAssignmentLog;
import com.contractai.ticket.service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tickets")
@RequiredArgsConstructor
public class TicketController {

    private final TicketService ticketService;

    @PostMapping
    public ApiResponse<Ticket> createTicket(@RequestBody TicketDTO.TicketCreateDTO dto) {
        return ApiResponse.success(ticketService.createTicket(dto));
    }

    @PutMapping("/{id}")
    public ApiResponse<Ticket> updateTicket(@PathVariable Long id, @RequestBody TicketDTO.TicketUpdateDTO dto) {
        return ApiResponse.success(ticketService.updateTicket(id, dto));
    }

    @GetMapping
    public ApiResponse<Page<Ticket>> listTickets(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ticketType,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(ticketService.listTickets(page, size, status, ticketType, assigneeId, category, priority, keyword));
    }

    @GetMapping("/{id}")
    public ApiResponse<Ticket> getTicket(@PathVariable Long id) {
        return ApiResponse.success(ticketService.getTicket(id));
    }

    @PostMapping("/assign")
    public ApiResponse<Ticket> assignTicket(@RequestBody TicketDTO.TicketAssignDTO dto) {
        return ApiResponse.success(ticketService.assignTicket(dto));
    }

    @PostMapping("/auto-assign")
    public ApiResponse<TicketDTO.AssignmentResultDTO> autoAssignTicket(@RequestBody TicketDTO.TicketAutoAssignDTO dto) {
        return ApiResponse.success(ticketService.autoAssignTicket(dto));
    }

    @PostMapping("/batch-auto-assign")
    public ApiResponse<List<TicketDTO.AssignmentResultDTO>> batchAutoAssign(@RequestBody TicketDTO.BatchAssignDTO dto) {
        return ApiResponse.success(ticketService.batchAutoAssign(dto));
    }

    @PostMapping("/status")
    public ApiResponse<Ticket> updateTicketStatus(@RequestBody TicketDTO.TicketStatusUpdateDTO dto) {
        return ApiResponse.success(ticketService.updateTicketStatus(dto));
    }

    @GetMapping("/{id}/logs")
    public ApiResponse<List<TicketAssignmentLog>> getTicketLogs(@PathVariable Long id) {
        Ticket ticket = ticketService.getTicket(id);
        return ApiResponse.success(ticket.getAssignmentLogs());
    }

    @PostMapping("/strategies")
    public ApiResponse<AssignmentStrategy> createStrategy(@RequestBody TicketDTO.StrategyCreateDTO dto) {
        return ApiResponse.success(ticketService.createStrategy(dto));
    }

    @PutMapping("/strategies/{id}")
    public ApiResponse<AssignmentStrategy> updateStrategy(@PathVariable Long id, @RequestBody TicketDTO.StrategyUpdateDTO dto) {
        return ApiResponse.success(ticketService.updateStrategy(id, dto));
    }

    @GetMapping("/strategies")
    public ApiResponse<Page<AssignmentStrategy>> listStrategies(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String strategyType,
            @RequestParam(required = false) Boolean enabled) {
        return ApiResponse.success(ticketService.listStrategies(page, size, strategyType, enabled));
    }

    @GetMapping("/strategies/{id}")
    public ApiResponse<AssignmentStrategy> getStrategy(@PathVariable Long id) {
        return ApiResponse.success(ticketService.getStrategy(id));
    }

    @DeleteMapping("/strategies/{id}")
    public ApiResponse<Void> deleteStrategy(@PathVariable Long id) {
        ticketService.deleteStrategy(id);
        return ApiResponse.success();
    }

    @GetMapping("/workloads")
    public ApiResponse<Page<EmployeeWorkload>> listWorkloads(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(ticketService.listWorkloads(page, size));
    }

    @GetMapping("/workloads/{employeeId}")
    public ApiResponse<EmployeeWorkload> getEmployeeWorkload(@PathVariable Long employeeId) {
        return ApiResponse.success(ticketService.getEmployeeWorkload(employeeId));
    }

    @PostMapping("/workloads/recalculate")
    public ApiResponse<List<EmployeeWorkload>> recalculateWorkloads(@RequestBody TicketDTO.WorkloadRecalculateDTO dto) {
        return ApiResponse.success(ticketService.recalculateWorkloads(dto));
    }
}
