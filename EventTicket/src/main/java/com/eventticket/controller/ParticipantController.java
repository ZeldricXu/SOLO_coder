package com.eventticket.controller;

import com.eventticket.dto.ApiResponse;
import com.eventticket.entity.Participant;
import com.eventticket.service.ParticipantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@RestController
@RequestMapping("/api/v1/participants")
public class ParticipantController {

    @Autowired
    private ParticipantService participantService;

    @PostMapping
    public ApiResponse<Participant> createParticipant(@RequestBody Participant participant) {
        Participant createdParticipant = participantService.createParticipant(participant);
        return ApiResponse.success(createdParticipant);
    }

    @GetMapping("/{participantId}")
    public ApiResponse<Participant> getParticipantById(@PathVariable String participantId) {
        Optional<Participant> participant = participantService.getParticipantById(participantId);
        if (participant.isPresent()) {
            return ApiResponse.success(participant.get());
        }
        return ApiResponse.error(404, "参与者不存在");
    }

    @GetMapping("/phone/{phone}")
    public ApiResponse<Participant> getParticipantByPhone(@PathVariable String phone) {
        Optional<Participant> participant = participantService.getParticipantByPhone(phone);
        if (participant.isPresent()) {
            return ApiResponse.success(participant.get());
        }
        return ApiResponse.error(404, "参与者不存在");
    }

    @PutMapping("/{participantId}")
    public ApiResponse<Participant> updateParticipant(
            @PathVariable String participantId,
            @RequestBody Participant updatedParticipant) {
        Participant participant = participantService.updateParticipant(participantId, updatedParticipant);
        if (participant != null) {
            return ApiResponse.success(participant);
        }
        return ApiResponse.error(404, "参与者不存在");
    }
}
