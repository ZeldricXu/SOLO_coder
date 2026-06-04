package com.battle.platform.admin;

import com.battle.platform.admin.dto.*;
import com.battle.platform.battlefield.BattlefieldManager;
import com.battle.platform.entity.*;
import com.battle.platform.anticheat.AntiCheatService;
import com.battle.platform.leaderboard.LeaderboardService;
import com.battle.platform.matching.MatchingEngine;
import com.battle.platform.repository.*;
import com.battle.platform.reward.RewardService;
import com.battle.platform.score.ScoreEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final SeasonRepository seasonRepository;
    private final BattlefieldManager battlefieldManager;
    private final MatchingEngine matchingEngine;
    private final LeaderboardService leaderboardService;
    private final RewardService rewardService;
    private final AntiCheatService antiCheatService;
    private final BattleRecordRepository battleRecordRepository;
    private final CheatReportRepository cheatReportRepository;
    private final PlayerRepository playerRepository;

    @PostMapping("/seasons")
    public ResponseEntity<Season> createSeason(@RequestBody SeasonCreateRequest request) {
        Season season = Season.builder()
                .seasonCode(request.getSeasonCode())
                .seasonName(request.getSeasonName())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .status(Season.SeasonStatus.PREPARING)
                .maxPlayersPerServer(request.getMaxPlayersPerServer())
                .bracketSize(request.getBracketSize())
                .rewardConfigJson(request.getRewardConfigJson())
                .build();
        return ResponseEntity.ok(seasonRepository.save(season));
    }

    @PutMapping("/seasons/{id}/status")
    public ResponseEntity<Season> updateSeasonStatus(@PathVariable Long id,
                                                     @RequestParam Season.SeasonStatus status) {
        return seasonRepository.findById(id)
                .map(season -> {
                    season.setStatus(status);
                    return ResponseEntity.ok(seasonRepository.save(season));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/dashboard/realtime")
    public ResponseEntity<Map<String, Object>> getRealtimeDashboard() {
        Map<String, Object> dashboard = new HashMap<>();
        dashboard.put("activeBattlefields", battlefieldManager.getActiveBattlefieldCount());
        dashboard.put("battlefieldStats", battlefieldManager.getBattlefieldStats());
        dashboard.put("matchingQueueSize", matchingEngine.getWaitingCount());
        dashboard.put("matchingBracketStats", matchingEngine.getBracketStats());
        dashboard.put("topPlayersByScore", leaderboardService.getTopPlayersByScore(10));
        dashboard.put("topPlayersByKills", leaderboardService.getTopPlayersByKills(10));
        dashboard.put("topGuildsByScore", leaderboardService.getTopGuildsByScore(10));
        dashboard.put("pendingCheatReports", cheatReportRepository.findByStatus(CheatReport.ReportStatus.PENDING).size());
        dashboard.put("timestamp", LocalDateTime.now());
        return ResponseEntity.ok(dashboard);
    }

    @PostMapping("/battles/{battleId}/cancel")
    public ResponseEntity<Map<String, String>> cancelBattle(@PathVariable String battleId,
                                                            @RequestParam String reason) {
        battlefieldManager.endBattle(battleId);

        Map<String, String> result = new HashMap<>();
        result.put("battleId", battleId);
        result.put("action", "cancelled");
        result.put("reason", reason);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/battles/{battleId}/void-scores")
    public ResponseEntity<Map<String, String>> voidBattleScores(@PathVariable String battleId,
                                                                @RequestParam String reason) {
        var recordOpt = battleRecordRepository.findByBattleId(battleId);
        if (recordOpt.isPresent()) {
            BattleRecord record = recordOpt.get();
            record.setIsAnomalous(true);
            record.setAnomalyReason(reason);
            battleRecordRepository.save(record);
        }

        battlefieldManager.endBattle(battleId);

        Map<String, String> result = new HashMap<>();
        result.put("battleId", battleId);
        result.put("action", "scores_voided");
        result.put("reason", reason);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/rewards/{seasonId}/calculate")
    public ResponseEntity<Map<String, String>> calculateRewards(@PathVariable Long seasonId) {
        Optional<Season> seasonOpt = seasonRepository.findById(seasonId);
        if (seasonOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Season season = seasonOpt.get();
        leaderboardService.archiveSeasonToMySQL(seasonId);
        rewardService.calculateAndCreateRewards(seasonId, season.getRewardConfigJson());

        Map<String, String> result = new HashMap<>();
        result.put("seasonId", seasonId.toString());
        result.put("action", "rewards_calculated");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/rewards/{rewardId}/deliver")
    public ResponseEntity<Map<String, String>> deliverReward(@PathVariable Long rewardId) {
        boolean success = rewardService.deliverReward(rewardId);

        Map<String, String> result = new HashMap<>();
        result.put("rewardId", rewardId.toString());
        result.put("status", success ? "delivered" : "failed");
        return ResponseEntity.ok(result);
    }

    @PostMapping("/rewards/retry-failed")
    public ResponseEntity<Map<String, String>> retryFailedRewards() {
        rewardService.retryFailedRewards();
        return ResponseEntity.ok(Map.of("action", "retry_initiated"));
    }

    @GetMapping("/cheat-reports")
    public ResponseEntity<List<CheatReport>> getCheatReports(
            @RequestParam(defaultValue = "PENDING") CheatReport.ReportStatus status) {
        return ResponseEntity.ok(cheatReportRepository.findByStatus(status));
    }

    @PostMapping("/cheat-reports/{reportId}/review")
    public ResponseEntity<Map<String, String>> reviewCheatReport(
            @PathVariable Long reportId,
            @RequestParam String reviewer,
            @RequestParam boolean confirmed,
            @RequestParam(required = false) String note) {
        antiCheatService.reviewReport(reportId, reviewer, confirmed, note);

        Map<String, String> result = new HashMap<>();
        result.put("reportId", reportId.toString());
        result.put("action", confirmed ? "confirmed" : "false_positive");
        return ResponseEntity.ok(result);
    }

    @GetMapping("/seasons")
    public ResponseEntity<List<Season>> listSeasons() {
        return ResponseEntity.ok(seasonRepository.findAll());
    }

    @GetMapping("/seasons/{id}")
    public ResponseEntity<Season> getSeason(@PathVariable Long id) {
        return seasonRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
