package com.movie.controller;

import com.movie.dto.ApiResponse;
import com.movie.dto.TicketCreateRequest;
import com.movie.dto.TicketCreateResponse;
import com.movie.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketApiController {

    @Autowired
    private TicketService ticketService;

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createTicket(
            @Valid @RequestBody TicketCreateRequest request) {
        
        TicketCreateResponse response = ticketService.createTicket(request);
        
        Map<String, Object> data = new HashMap<>();
        data.put("ticket_id", response.getTicketId());
        data.put("status", response.getTicketStatus());
        data.put("amount", response.getTicketAmount());
        
        return ApiResponse.success(data);
    }
}
