package com.battle.platform.integration;

import com.battle.platform.battlefield.BattlefieldInstance;
import com.battle.platform.battlefield.BattlefieldManager;
import com.battle.platform.entity.*;
import com.battle.platform.leaderboard.LeaderboardService;
import com.battle.platform.repository.*;
import com.battle.platform.reward.RewardService;
import com.battle.platform.score.ScoreEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("异常链路集成测试")
class AnomalyPipelineIT extends BaseIT {

    @Autowired
    private SeasonRepository seasonRepository;
    @Autowired
    private PlayerRepository playerRepository;
    @Autowired
    private ServerStatRepository serverStatRepository;
    @Autowired
    private RewardRecordRepository rewardRecordRepository;
    @Autowired
    private SeasonRankingRepository seasonRankingRepository;
    @Autowired
    private BattleRecordRepository battleRecordRepository;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private LeaderboardService leaderboardService;
    @Autowired
    private RewardService rewardService;
    @Autowired
    private ScoreEngine scoreEngine;
    @Autowired
    private BattlefieldManager battlefieldManager;

    @BeforeEach
    void cleanUp() {
        Set<String> keys = stringRedisTemplate.keys("leaderboard:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        rewardRecordRepository.deleteAll();
        seasonRankingRepository.deleteAll();
        battleRecordRepository.deleteAll();
        seasonRepository.deleteAll();
        playerRepository.deleteAll();
        serverStatRepository.deleteAll();
    }

    @Nested
    @DisplayName("玩家掉线重连场景")
    class PlayerDisconnectReconnectTest {

        @Test
        @DisplayName("玩家掉线后战场仍可继续，其他玩家积分不受影响")
        void playerDisconnectBattleContinues() {
            Season season = createSeason();
            ServerStat server = createServerStat(1, 1500.0);
            List<Player> players = createPlayers(5, server);

            String battleId = battlefieldManager.createBattlefield(
                    players.stream().map(Player::getPlayerId).collect(Collectors.toList())
            );

            for (Player p : players) {
                scoreEngine.initPlayerScore(battleId, p.getPlayerId());
            }

            Long p1 = players.get(0).getPlayerId();
            Long p2 = players.get(1).getPlayerId();
            Long p3 = players.get(2).getPlayerId();

            scoreEngine.onKill(battleId, p1, p2, 100);
            scoreEngine.onCapture(battleId, p1, 1);

            battlefieldManager.playerDisconnect(p2);

            assertThat(battlefieldManager.getActiveBattlefieldCount()).isEqualTo(1);

            scoreEngine.onKill(battleId, p3, p1, 100);

            var p3Score = scoreEngine.getPlayerScore(battleId, p3);
            assertThat(p3Score.getKills()).isEqualTo(1);
            assertThat(p3Score.getTotalScore()).isEqualTo(100);

            var p1Score = scoreEngine.getPlayerScore(battleId, p1);
            assertThat(p1Score.getTotalScore()).isEqualTo(100 + 200);

            battlefieldManager.playerLeave(p1);
            battlefieldManager.playerLeave(p3);

            scoreEngine.finalizeBattleScores(battleId);
        }

        @Test
        @DisplayName("最后一个玩家离开后战场自动结束")
        void lastPlayerLeaveBattleEnds() {
            ServerStat server = createServerStat(1, 1500.0);
            Player p1 = createSinglePlayer(9001L, server);

            String battleId = battlefieldManager.createBattlefield(List.of(p1.getPlayerId()));
            scoreEngine.initPlayerScore(battleId, p1.getPlayerId());

            assertThat(battlefieldManager.getActiveBattlefieldCount()).isEqualTo(1);

            battlefieldManager.playerLeave(p1.getPlayerId());

            assertThat(battlefieldManager.getActiveBattlefieldCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("匹配过程中服务器重启恢复场景")
    class ServerRestartRecoveryTest {

        @Test
        @DisplayName("Redis排行榜数据在重启模拟后仍可查询")
        void redisLeaderboardSurvivesRestart() {
            stringRedisTemplate.opsForZSet().add("leaderboard:score", "1001", 5000);
            stringRedisTemplate.opsForZSet().add("leaderboard:score", "1002", 3000);
            stringRedisTemplate.opsForZSet().add("leaderboard:score", "1003", 1000);

            List<Map<String, Object>> top = leaderboardService.getTopPlayersByScore(3);
            assertThat(top).hasSize(3);
            assertThat(top.get(0).get("id")).isEqualTo("1001");

            stringRedisTemplate.opsForZSet().add("leaderboard:score", "1001", 6000);
            stringRedisTemplate.opsForZSet().add("leaderboard:score", "1002", 5500);

            top = leaderboardService.getTopPlayersByScore(3);
            assertThat(top).hasSize(3);

            Double score1001 = stringRedisTemplate.opsForZSet().score("leaderboard:score", "1001");
            assertThat(score1001).isEqualTo(6000.0);
        }

        @Test
        @DisplayName("MySQL赛季数据持久化不丢失")
        void mysqlSeasonDataPersists() {
            Season season = createSeason();
            Long seasonId = season.getId();

            Season found = seasonRepository.findById(seasonId).orElseThrow();
            assertThat(found.getSeasonCode()).isEqualTo(season.getSeasonCode());

            found.setStatus(Season.SeasonStatus.ACTIVE);
            seasonRepository.save(found);

            Season reloaded = seasonRepository.findById(seasonId).orElseThrow();
            assertThat(reloaded.getStatus()).isEqualTo(Season.SeasonStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("奖励发放失败重试最终成功")
    class RewardRetrySuccessTest {

        @Test
        @DisplayName("奖励记录创建后可多次尝试发放")
        void rewardRecordCanBeRetriedMultipleTimes() {
            Season season = createSeason();
            ServerStat server = createServerStat(1, 1500.0);
            Player player = createSinglePlayer(9001L, server);

            stringRedisTemplate.opsForZSet().add("leaderboard:score", player.getPlayerId().toString(), 5000);

            leaderboardService.archiveSeasonToMySQL(season.getId());

            rewardService.calculateAndCreateRewards(season.getId(), season.getRewardConfigJson());

            List<RewardRecord> pending = rewardRecordRepository.findByStatus(RewardRecord.RewardStatus.PENDING);
            assertThat(pending).isNotEmpty();

            RewardRecord record = pending.get(0);
            Long rewardId = record.getId();

            boolean delivered = rewardService.deliverReward(rewardId).isSuccess();
            assertThat(delivered).isTrue();

            RewardRecord afterDelivery = rewardRecordRepository.findById(rewardId).orElseThrow();
            assertThat(afterDelivery.getStatus()).isEqualTo(RewardRecord.RewardStatus.DELIVERED);
            assertThat(afterDelivery.getDeliveredAt()).isNotNull();
        }

        @Test
        @DisplayName("防重复发放——第二次发放已发放的奖励返回true但不重复处理")
        void duplicateDeliveryReturnsTrueButNoDoubleSend() {
            Season season = createSeason();
            ServerStat server = createServerStat(1, 1500.0);
            Player player = createSinglePlayer(9001L, server);

            stringRedisTemplate.opsForZSet().add("leaderboard:score", player.getPlayerId().toString(), 5000);
            leaderboardService.archiveSeasonToMySQL(season.getId());
            rewardService.calculateAndCreateRewards(season.getId(), season.getRewardConfigJson());

            RewardRecord record = rewardRecordRepository.findByStatus(RewardRecord.RewardStatus.PENDING).get(0);
            Long rewardId = record.getId();

            boolean first = rewardService.deliverReward(rewardId).isSuccess();
            assertThat(first).isTrue();

            boolean second = rewardService.deliverReward(rewardId).isSuccess();
            assertThat(second).isTrue();

            long deliveredCount = rewardRecordRepository.findBySeasonIdAndPlayerId(season.getId(), player.getPlayerId())
                    .stream()
                    .filter(r -> r.getStatus() == RewardRecord.RewardStatus.DELIVERED)
                    .count();
            assertThat(deliveredCount).isEqualTo(1);
        }

        @Test
        @DisplayName("retryFailedRewards处理失败的奖励记录")
        void retryFailedRewardsProcessesFailedRecords() {
            RewardRecord failedRecord = RewardRecord.builder()
                    .seasonId(999L)
                    .playerId(9001L)
                    .rewardType(RewardRecord.RewardType.PERSONAL)
                    .rank(1)
                    .rewardContentJson("{\"diamond\":5000}")
                    .status(RewardRecord.RewardStatus.FAILED)
                    .retryCount(2)
                    .createdAt(LocalDateTime.now())
                    .build();
            rewardRecordRepository.save(failedRecord);

            rewardService.retryFailedRewards();

            RewardRecord updated = rewardRecordRepository.findById(failedRecord.getId()).orElseThrow();
            assertThat(updated.getStatus()).isNotEqualTo(RewardRecord.RewardStatus.FAILED);
        }
    }

    @Nested
    @DisplayName("战场异常积分作废")
    class AnomalousBattleScoreVoidTest {

        @Test
        @DisplayName("异常对局标记后battleRecord记录为anomalous")
        void anomalousBattleFlaggedInRecord() {
            Season season = createSeason();
            ServerStat server = createServerStat(1, 1500.0);
            List<Player> players = createPlayers(5, server);

            String battleId = battlefieldManager.createBattlefield(
                    players.stream().map(Player::getPlayerId).collect(Collectors.toList())
            );

            BattleRecord record = BattleRecord.builder()
                    .seasonId(season.getId())
                    .battleId(battleId)
                    .battleFieldId(1)
                    .status(BattleRecord.BattleStatus.IN_PROGRESS)
                    .playerCount(5)
                    .startedAt(LocalDateTime.now())
                    .isAnomalous(false)
                    .build();
            battleRecordRepository.save(record);

            record.setIsAnomalous(true);
            record.setAnomalyReason("Detected cheating behavior");
            battleRecordRepository.save(record);

            BattleRecord reloaded = battleRecordRepository.findByBattleId(battleId).orElseThrow();
            assertThat(reloaded.getIsAnomalous()).isTrue();
            assertThat(reloaded.getAnomalyReason()).isEqualTo("Detected cheating behavior");

            battlefieldManager.endBattle(battleId);
        }
    }

    private Season createSeason() {
        Season season = Season.builder()
                .seasonCode("S2025-TEST-" + UUID.randomUUID().toString().substring(0, 4))
                .seasonName("Test Season")
                .startTime(LocalDateTime.now())
                .endTime(LocalDateTime.now().plusDays(14))
                .status(Season.SeasonStatus.PREPARING)
                .maxPlayersPerServer(100)
                .bracketSize(200)
                .rewardConfigJson("{}")
                .build();
        return seasonRepository.save(season);
    }

    private ServerStat createServerStat(int serverId, double powerScore) {
        ServerStat stat = ServerStat.builder()
                .serverId(serverId)
                .serverName("Server " + serverId)
                .serverPowerScore(powerScore)
                .avgPlayerCombatPower(50000)
                .totalActivePlayers(1000)
                .openedAt(LocalDateTime.now())
                .build();
        return serverStatRepository.save(stat);
    }

    private List<Player> createPlayers(int count, ServerStat server) {
        List<Player> players = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            Player p = Player.builder()
                    .playerId(9000L + i)
                    .serverId(server.getServerId())
                    .playerName("TestPlayer" + i)
                    .combatPower(50000L + i * 1000)
                    .rating(1500.0 + i * 10)
                    .totalKills(0)
                    .totalDeaths(0)
                    .totalAssists(0)
                    .totalScore(0)
                    .isBanned(false)
                    .guildId(100L)
                    .build();
            players.add(playerRepository.save(p));
        }
        return players;
    }

    private Player createSinglePlayer(Long playerId, ServerStat server) {
        Player p = Player.builder()
                .playerId(playerId)
                .serverId(server.getServerId())
                .playerName("SoloPlayer" + playerId)
                .combatPower(80000L)
                .rating(1800.0)
                .totalKills(0)
                .totalDeaths(0)
                .totalAssists(0)
                .totalScore(0)
                .isBanned(false)
                .guildId(100L)
                .build();
        return playerRepository.save(p);
    }
}
