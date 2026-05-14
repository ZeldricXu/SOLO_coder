package com.eventticket.controller;

import com.eventticket.dto.ApiResponse;
import com.eventticket.entity.TicketHistory;
import com.eventticket.service.TicketHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/history")
public class HistoryController {

    @Autowired
    private TicketHistoryService ticketHistoryService;

    @GetMapping("/ticket/{ticketId}")
    public ApiResponse<List<TicketHistory>> getTicketHistory(@PathVariable String ticketId) {
        List<TicketHistory> history = ticketHistoryService.getTicketHistory(ticketId);
        return ApiResponse.success(history);
    }
}
