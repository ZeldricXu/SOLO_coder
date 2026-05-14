package com.eventticket.repository;

import com.eventticket.entity.Participant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ParticipantRepository extends JpaRepository<Participant, String> {
    Optional<Participant> findByParticipantPhone(String participantPhone);
    Optional<Participant> findByParticipantIdNumber(String participantIdNumber);
}
