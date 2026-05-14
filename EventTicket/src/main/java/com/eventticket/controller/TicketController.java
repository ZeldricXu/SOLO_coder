package com.eventticket.controller;

import com.eventticket.dto.ApiResponse;
import com.eventticket.dto.TicketCreateRequest;
import com.eventticket.dto.TicketCreateResponse;
import com.eventticket.dto.TicketVerifyRequest;
import com.eventticket.dto.TicketVerifyResponse;
import com.eventticket.entity.Ticket;
import com.eventticket.service.TicketService;
import com.eventticket.service.VerificationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    @Autowired
    private TicketService ticketService;

    @Autowired
    private VerificationService verificationService;

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createTicket(@Valid @RequestBody TicketCreateRequest request) {
        try {
            TicketCreateResponse ticketResponse = ticketService.createTicket(request);
            
            Map<String, Object> data = new HashMap<>();
            data.put("ticket_id", ticketResponse.getTicketId());
            data.put("ticket_number", ticketResponse.getTicketNumber());
            data.put("status", ticketResponse.getStatus());
            data.put("ticket_price", ticketResponse.getTicketPrice());
            
            return ApiResponse.success(data);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PostMapping("/verify")
    public ApiResponse<Map<String, Object>> verifyTicket(@Valid @RequestBody TicketVerifyRequest request) {
        try {
            TicketVerifyResponse verifyResponse = verificationService.verifyTicket(request);
            
            Map<String, Object> data = new HashMap<>();
            data.put("verify_result", verifyResponse.getVerifyResult());
            if (verifyResponse.getEventName() != null) {
                data.put("event_name", verifyResponse.getEventName());
            }
            if (verifyResponse.getSeatNumber() != null) {
                data.put("seat_number", verifyResponse.getSeatNumber());
            }
            if (verifyResponse.getParticipantName() != null) {
                data.put("participant_name", verifyResponse.getParticipantName());
            }
            
            return ApiResponse.success(data);
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/{ticketId}")
    public ApiResponse<Ticket> getTicketById(@PathVariable String ticketId) {
        Optional<Ticket> ticket = ticketService.getTicketById(ticketId);
        if (ticket.isPresent()) {
            return ApiResponse.success(ticket.get());
        }
        return ApiResponse.error(404, "票务不存在");
    }

    @GetMapping("/event/{eventId}")
    public ApiResponse<List<Ticket>> getTicketsByEventId(@PathVariable String eventId) {
        List<Ticket> tickets = ticketService.getTicketsByEventId(eventId);
        return ApiResponse.success(tickets);
    }

    @GetMapping("/phone/{phone}")
    public ApiResponse<List<Ticket>> getTicketsByPhone(@PathVariable String phone) {
        List<Ticket> tickets = ticketService.getTicketsByParticipantPhone(phone);
        return ApiResponse.success(tickets);
    }

    @PostMapping("/{ticketId}/confirm-payment")
    public ApiResponse<Boolean> confirmPayment(@PathVariable String ticketId) {
        try {
            boolean confirmed = ticketService.confirmTicketPayment(ticketId);
            if (confirmed) {
                return ApiResponse.success(true);
            }
            return ApiResponse.error(400, "支付确认失败");
        } catch (RuntimeException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }
}
