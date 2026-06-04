package com.battle.platform.anticheat;

import com.battle.platform.config.AntiCheatProperties;
import com.battle.platform.entity.CheatReport;
import com.battle.platform.repository.CheatReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class AntiCheatService {

    private final AntiCheatProperties properties;
    private final CheatReportRepository cheatReportRepository;
    private final StringRedisTemplate stringRedisTemplate;

    private final ConcurrentHashMap<Long, PlayerBehaviorProfile> behaviorProfiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<Long>> ipPlayerMap = new ConcurrentHashMap<>();

    private static final String SUSPICIOUS_SET = "anticheat:suspicious";
    private static final String BANNED_SET = "anticheat:banned";

    public void recordKill(Long playerId, boolean isHeadshot, String battleId) {
        PlayerBehaviorProfile profile = behaviorProfiles.computeIfAbsent(playerId,
                k -> new PlayerBehaviorProfile(playerId));

        profile.recordKill(isHeadshot);
        profile.recordBattle(battleId);

        checkHeadshotRate(playerId, profile);
    }

    public void recordMovement(Long playerId, double fromX, double fromZ,
                               double toX, double toZ, long deltaTimeMs) {
        PlayerBehaviorProfile profile = behaviorProfiles.computeIfAbsent(playerId,
                k -> new PlayerBehaviorProfile(playerId));

        double distance = Math.sqrt(Math.pow(toX - fromX, 2) + Math.pow(toZ - fromZ, 2));
        double speed = distance / (deltaTimeMs / 1000.0);

        profile.recordMovement(distance, speed, deltaTimeMs);

        if (speed > properties.getSpeedHackThreshold()) {
            reportCheat(playerId, battleIdFromContext(), CheatReport.CheatType.SPEED_HACK,
                    speed, "Speed: " + String.format("%.2f", speed) + " units/s (threshold: " + properties.getSpeedHackThreshold() + ")");
        }
    }

    public void recordIPConnection(String ip, Long playerId) {
        ipPlayerMap.computeIfAbsent(ip, k -> ConcurrentHashMap.newKeySet()).add(playerId);

        Set<Long> playersFromIp = ipPlayerMap.get(ip);
        if (playersFromIp != null && playersFromIp.size() > properties.getSameIpPlayerLimit()) {
            for (Long pid : playersFromIp) {
                reportCheat(pid, null, CheatReport.CheatType.MULTI_ACCOUNT,
                        0.8, "Same IP " + ip + " has " + playersFromIp.size() + " accounts");
            }
        }
    }

    public void checkHeadshotRate(Long playerId, PlayerBehaviorProfile profile) {
        double headshotRate = profile.getHeadshotRate();
        if (profile.getTotalKills() >= 10 && headshotRate > properties.getHeadshotRateThreshold()) {
            reportCheat(playerId, profile.getCurrentBattleId(), CheatReport.CheatType.AIMBOT,
                    headshotRate, "Headshot rate: " + String.format("%.2f%%", headshotRate * 100)
                            + " over " + profile.getTotalKills() + " kills");
        }
    }

    public void analyzeTrajectory(Long playerId, List<double[]> positions, List<Long> timestamps) {
        if (positions.size() < 10) return;

        int directionChanges = 0;
        for (int i = 2; i < positions.size(); i++) {
            double dx1 = positions.get(i - 1)[0] - positions.get(i - 2)[0];
            double dz1 = positions.get(i - 1)[1] - positions.get(i - 2)[1];
            double dx2 = positions.get(i)[0] - positions.get(i - 1)[0];
            double dz2 = positions.get(i)[1] - positions.get(i - 1)[1];

            double dot = dx1 * dx2 + dz1 * dz2;
            double mag1 = Math.sqrt(dx1 * dx1 + dz1 * dz1);
            double mag2 = Math.sqrt(dx2 * dx2 + dz2 * dz2);

            if (mag1 > 0.01 && mag2 > 0.01) {
                double cosAngle = dot / (mag1 * mag2);
                if (cosAngle < -0.5) {
                    directionChanges++;
                }
            }
        }

        double changeRate = (double) directionChanges / positions.size();
        if (changeRate > 0.5) {
            reportCheat(playerId, battleIdFromContext(), CheatReport.CheatType.ABNORMAL_TRAJECTORY,
                    changeRate, "Direction change rate: " + String.format("%.2f", changeRate));
        }
    }

    private void reportCheat(Long playerId, String battleId, CheatReport.CheatType type,
                             double confidence, String detail) {
        CheatReport report = CheatReport.builder()
                .playerId(playerId)
                .battleId(battleId != null ? battleId : "unknown")
                .cheatType(type)
                .confidence(confidence)
                .detail(detail)
                .status(CheatReport.ReportStatus.PENDING)
                .build();

        cheatReportRepository.save(report);

        stringRedisTemplate.opsForSet().add(SUSPICIOUS_SET, playerId.toString());

        log.warn("Cheat detected: player={} type={} confidence={:.2f} detail={}",
                playerId, type, confidence, detail);
    }

    public List<CheatReport> getPendingReports() {
        return cheatReportRepository.findByStatus(CheatReport.ReportStatus.PENDING);
    }

    @Transactional
    public void reviewReport(Long reportId, String reviewer, boolean confirmed, String note) {
        Optional<CheatReport> opt = cheatReportRepository.findById(reportId);
        if (opt.isEmpty()) return;

        CheatReport report = opt.get();
        report.setReviewer(reviewer);
        report.setReviewNote(note);
        report.setReviewedAt(LocalDateTime.now());

        if (confirmed) {
            report.setStatus(CheatReport.ReportStatus.CONFIRMED);
            stringRedisTemplate.opsForSet().add(BANNED_SET, report.getPlayerId().toString());
            stringRedisTemplate.opsForSet().remove(SUSPICIOUS_SET, report.getPlayerId().toString());
        } else {
            report.setStatus(CheatReport.ReportStatus.FALSE_POSITIVE);
            stringRedisTemplate.opsForSet().remove(SUSPICIOUS_SET, report.getPlayerId().toString());
        }

        cheatReportRepository.save(report);
    }

    public boolean isPlayerBanned(Long playerId) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(BANNED_SET, playerId.toString()));
    }

    public boolean isPlayerSuspicious(Long playerId) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(SUSPICIOUS_SET, playerId.toString()));
    }

    public PlayerBehaviorProfile getBehaviorProfile(Long playerId) {
        return behaviorProfiles.get(playerId);
    }

    private String battleIdFromContext() {
        return "unknown";
    }
}
