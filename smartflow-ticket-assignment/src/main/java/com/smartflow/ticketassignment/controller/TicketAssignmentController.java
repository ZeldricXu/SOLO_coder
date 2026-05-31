package com.smartflow.ticketassignment.controller;

import com.smartflow.common.base.Result;
import com.smartflow.common.dto.AssignmentRequest;
import com.smartflow.common.dto.AssignmentResult;
import com.smartflow.ticketassignment.service.TicketAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ticket-assignment")
@RequiredArgsConstructor
public class TicketAssignmentController {

    private final TicketAssignmentService assignmentService;

    @PostMapping("/assign")
    public Result<AssignmentResult> assignTicket(@RequestBody AssignmentRequest request) {
        AssignmentResult result = assignmentService.assignTicket(request);
        return Result.success(result);
    }

    @PostMapping("/reassign/{ticketId}")
    public Result<Boolean> reassignTicket(@PathVariable Long ticketId, @RequestParam(required = false) String reason) {
        boolean success = assignmentService.reassignTicket(ticketId, reason);
        return Result.success(success);
    }

    @GetMapping("/load-status")
    public Result<Map<String, Object>> getLoadStatus() {
        Map<String, Object> status = assignmentService.getEmployeeLoadStatus();
        return Result.success(status);
    }
}
