package com.travelbooking.controller;

import com.travelbooking.dto.ApiResponse;
import com.travelbooking.model.Team;
import com.travelbooking.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @GetMapping
    public ApiResponse<List<Team>> getAllTeams() {
        return ApiResponse.success(teamService.getAllTeams());
    }

    @GetMapping("/{id}")
    public ApiResponse<Team> getTeamById(@PathVariable String id) {
        return teamService.getTeamById(id)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "团队不存在"));
    }

    @GetMapping("/available")
    public ApiResponse<List<Team>> getAvailableTeams() {
        return ApiResponse.success(teamService.getAvailableTeams());
    }

    @PostMapping
    public ApiResponse<Team> createTeam(@RequestBody Team team) {
        Team created = teamService.createTeam(team);
        return ApiResponse.success(created);
    }

    @PutMapping("/{id}")
    public ApiResponse<Team> updateTeam(@PathVariable String id, @RequestBody Team team) {
        Team updated = teamService.updateTeam(id, team);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteTeam(@PathVariable String id) {
        teamService.deleteTeam(id);
        return ApiResponse.success(null);
    }
}
