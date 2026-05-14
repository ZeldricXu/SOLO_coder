package com.recruitment.service;

import com.recruitment.common.enums.CandidateStatus;
import com.recruitment.common.util.IdGenerator;
import com.recruitment.model.Candidate;
import com.recruitment.repository.CandidateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateService {
    private final CandidateRepository candidateRepository;

    @Transactional
    public Candidate createOrGetCandidate(String name, String phone, String email,
                                          String education, String experience) {
        Optional<Candidate> existing = candidateRepository.findByCandidatePhone(phone);
        if (existing.isPresent()) {
            Candidate candidate = existing.get();
            if (name != null && !name.isEmpty()) {
                candidate.setCandidateName(name);
            }
            if (email != null && !email.isEmpty()) {
                candidate.setCandidateEmail(email);
            }
            if (education != null && !education.isEmpty()) {
                candidate.setCandidateEducation(education);
            }
            if (experience != null && !experience.isEmpty()) {
                candidate.setCandidateExperience(experience);
            }
            return candidateRepository.save(candidate);
        }
        String candidateId = IdGenerator.generateCandidateId();
        Candidate candidate = Candidate.builder()
                .candidateId(candidateId)
                .candidateName(name)
                .candidatePhone(phone)
                .candidateEmail(email)
                .candidateEducation(education)
                .candidateExperience(experience)
                .candidateStatus(CandidateStatus.REGISTERED)
                .build();
        Candidate saved = candidateRepository.save(candidate);
        log.info("Candidate: 创建候选人成功, candidateId: {}", candidateId);
        return saved;
    }

    @Transactional
    public Candidate updateCandidateStatus(String candidateId, CandidateStatus newStatus) {
        Candidate candidate = getCandidate(candidateId);
        candidate.setCandidateStatus(newStatus);
        Candidate saved = candidateRepository.save(candidate);
        log.info("Candidate: 更新候选人状态, candidateId: {}, status: {}", candidateId, newStatus);
        return saved;
    }

    public Candidate getCandidate(String candidateId) {
        return candidateRepository.findByCandidateId(candidateId)
                .orElseThrow(() -> new RuntimeException("候选人不存在: " + candidateId));
    }

    public Optional<Candidate> findCandidateByPhone(String phone) {
        return candidateRepository.findByCandidatePhone(phone);
    }

    public List<Candidate> getAllCandidates() {
        return candidateRepository.findAll();
    }

    public List<Candidate> getCandidatesByStatus(CandidateStatus status) {
        return candidateRepository.findByCandidateStatus(status);
    }

    public List<Candidate> searchCandidatesByName(String name) {
        return candidateRepository.findByCandidateNameContaining(name);
    }

    @Transactional
    public Candidate updateCandidate(String candidateId, String name, String email,
                                     String education, String experience) {
        Candidate candidate = getCandidate(candidateId);
        if (name != null && !name.isEmpty()) {
            candidate.setCandidateName(name);
        }
        if (email != null && !email.isEmpty()) {
            candidate.setCandidateEmail(email);
        }
        if (education != null && !education.isEmpty()) {
            candidate.setCandidateEducation(education);
        }
        if (experience != null && !experience.isEmpty()) {
            candidate.setCandidateExperience(experience);
        }
        return candidateRepository.save(candidate);
    }
}
