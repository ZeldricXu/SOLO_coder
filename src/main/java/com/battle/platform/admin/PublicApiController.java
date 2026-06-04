package com.battle.platform.admin;

import com.battle.platform.battlefield.BattlefieldManager;
import com.battle.platform.leaderboard.LeaderboardService;
import com.battle.platform.matching.MatchingEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicApiController {

    private final LeaderboardService leaderboardService;
    private final MatchingEngine matchingEngine;
    private final BattlefieldManager battlefieldManager;

    @GetMapping("/leaderboard/score")
    public ResponseEntity<List<Map<String, Object>>> getScoreLeaderboard(
            @RequestParam(defaultValue = "100") int topN) {
        return ResponseEntity.ok(leaderboardService.getTopPlayersByScore(topN));
    }

    @GetMapping("/leaderboard/kills")
    public ResponseEntity<List<Map<String, Object>>> getKillsLeaderboard(
            @RequestParam(defaultValue = "100") int topN) {
        return ResponseEntity.ok(leaderboardService.getTopPlayersByKills(topN));
    }

    @GetMapping("/leaderboard/guild")
    public ResponseEntity<List<Map<String, Object>>> getGuildLeaderboard(
            @RequestParam(defaultValue = "50") int topN) {
        return ResponseEntity.ok(leaderboardService.getTopGuildsByScore(topN));
    }

    @GetMapping("/player/{playerId}/rank")
    public ResponseEntity<Map<String, Object>> getPlayerRank(@PathVariable Long playerId) {
        Long rank = leaderboardService.getPlayerRank(playerId);
        Double score = leaderboardService.getPlayerScore(playerId);

        Map<String, Object> result = new HashMap<>();
        result.put("playerId", playerId);
        result.put("rank", rank);
        result.put("score", score);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getSystemStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("activeBattlefields", battlefieldManager.getActiveBattlefieldCount());
        status.put("matchingQueueSize", matchingEngine.getWaitingCount());
        status.put("timestamp", new Date());
        return ResponseEntity.ok(status);
    }
}
