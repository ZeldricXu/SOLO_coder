package com.eventticket.service;

import com.eventticket.entity.Participant;
import com.eventticket.repository.ParticipantRepository;
import com.eventticket.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class ParticipantService {

    @Autowired
    private ParticipantRepository participantRepository;

    @Transactional
    public Participant createParticipant(Participant participant) {
        participant.setParticipantId(IdGenerator.generateParticipantId());
        if (participant.getCreatedAt() == null) {
            participant.setCreatedAt(LocalDateTime.now());
        }
        return participantRepository.save(participant);
    }

    @Transactional
    public Participant findOrCreateParticipant(String name, String phone, String idType, String idNumber) {
        Optional<Participant> existingParticipant = participantRepository.findByParticipantPhone(phone);
        if (existingParticipant.isPresent()) {
            return existingParticipant.get();
        }

        Participant participant = new Participant();
        participant.setParticipantName(name);
        participant.setParticipantPhone(phone);
        participant.setParticipantIdType(idType);
        participant.setParticipantIdNumber(idNumber);
        participant.setParticipantId(IdGenerator.generateParticipantId());
        participant.setCreatedAt(LocalDateTime.now());
        
        return participantRepository.save(participant);
    }

    @Transactional(readOnly = true)
    public Optional<Participant> getParticipantById(String participantId) {
        return participantRepository.findById(participantId);
    }

    @Transactional(readOnly = true)
    public Optional<Participant> getParticipantByPhone(String phone) {
        return participantRepository.findByParticipantPhone(phone);
    }

    @Transactional
    public Participant updateParticipant(String participantId, Participant updatedParticipant) {
        return participantRepository.findById(participantId).map(participant -> {
            participant.setParticipantName(updatedParticipant.getParticipantName());
            participant.setParticipantPhone(updatedParticipant.getParticipantPhone());
            if (updatedParticipant.getParticipantIdType() != null) {
                participant.setParticipantIdType(updatedParticipant.getParticipantIdType());
            }
            if (updatedParticipant.getParticipantIdNumber() != null) {
                participant.setParticipantIdNumber(updatedParticipant.getParticipantIdNumber());
            }
            return participantRepository.save(participant);
        }).orElse(null);
    }
}
