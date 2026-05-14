package com.movie.controller;

import com.movie.dto.ApiResponse;
import com.movie.entity.Ticket;
import com.movie.service.TicketService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/tickets")
public class TicketController {

    @Autowired
    private TicketService ticketService;

    @GetMapping
    public ApiResponse<List<Ticket>> list() {
        return ApiResponse.success(ticketService.getAllTickets());
    }

    @GetMapping("/{ticketId}")
    public ApiResponse<Ticket> get(@PathVariable String ticketId) {
        return ApiResponse.success(ticketService.getTicketOrThrow(ticketId));
    }

    @GetMapping("/user/{userId}")
    public ApiResponse<List<Ticket>> getByUser(@PathVariable String userId) {
        return ApiResponse.success(ticketService.getTicketsByUser(userId));
    }

    @PostMapping("/{ticketId}/cancel")
    public ApiResponse<Ticket> cancel(@PathVariable String ticketId) {
        return ApiResponse.success(ticketService.cancelTicket(ticketId));
    }
}
