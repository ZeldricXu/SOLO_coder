package com.travelbooking.service;

import com.travelbooking.model.Team;
import com.travelbooking.repository.TeamRepository;
import com.travelbooking.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class TeamService {

    private final TeamRepository teamRepository;

    @Transactional
    public Team createTeam(Team team) {
        if (team.getTeamId() == null || team.getTeamId().isEmpty()) {
            team.setTeamId(IdGenerator.generateTeamId());
        }
        if (team.getTeamStatus() == null) {
            team.setTeamStatus("available");
        }
        if (team.getCreatedAt() == null) {
            team.setCreatedAt(Instant.now());
        }
        return teamRepository.save(team);
    }

    @Transactional
    public Team assignTeam() {
        List<Team> teams = teamRepository.findByTeamStatus("available");
        if (teams.isEmpty()) {
            return null;
        }

        return teams.stream()
                .max(Comparator.comparing(Team::getTeamCapacity))
                .orElse(null);
    }

    public List<Team> getAllTeams() {
        return teamRepository.findAll();
    }

    public Optional<Team> getTeamById(String teamId) {
        return teamRepository.findById(teamId);
    }

    public List<Team> getAvailableTeams() {
        return teamRepository.findByTeamStatus("available");
    }

    @Transactional
    public Team updateTeam(String teamId, Team team) {
        Team existing = teamRepository.findById(teamId)
                .orElseThrow(() -> new RuntimeException("团队不存在"));

        if (team.getTeamName() != null) {
            existing.setTeamName(team.getTeamName());
        }
        if (team.getTeamStatus() != null) {
            existing.setTeamStatus(team.getTeamStatus());
        }
        if (team.getTeamCapacity() != null) {
            existing.setTeamCapacity(team.getTeamCapacity());
        }

        return teamRepository.save(existing);
    }

    public void deleteTeam(String teamId) {
        teamRepository.deleteById(teamId);
    }
}
