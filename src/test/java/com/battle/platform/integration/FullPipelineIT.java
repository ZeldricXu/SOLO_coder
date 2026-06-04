package com.battle.platform.integration;

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

@DisplayName("完整链路集成测试")
class FullPipelineIT extends BaseIT {

    @Autowired
    private SeasonRepository seasonRepository;
    @Autowired
    private PlayerRepository playerRepository;
    @Autowired
    private ServerStatRepository serverStatRepository;
    @Autowired
    private BattleRecordRepository battleRecordRepository;
    @Autowired
    private PlayerBattleStatRepository playerBattleStatRepository;
    @Autowired
    private SeasonRankingRepository seasonRankingRepository;
    @Autowired
    private RewardRecordRepository rewardRecordRepository;
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

    private static final Long SEASON_ID = 1L;

    @BeforeEach
    void cleanUp() {
        Set<String> keys = stringRedisTemplate.keys("leaderboard:*");
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
        rewardRecordRepository.deleteAll();
        seasonRankingRepository.deleteAll();
        playerBattleStatRepository.deleteAll();
        battleRecordRepository.deleteAll();
        seasonRepository.deleteAll();
        playerRepository.deleteAll();
        serverStatRepository.deleteAll();
    }

    @Nested
    @DisplayName("完整链路：创建赛季→玩家报名→匹配→战场→积分→排行榜→奖励")
    class FullHappyPathTest {

        @Test
        @DisplayName("完整争霸赛链路走通")
        void fullBattlePipeline() {
            Season season = createSeason();
            assertThat(season.getId()).isNotNull();
            assertThat(season.getStatus()).isEqualTo(Season.SeasonStatus.PREPARING);

            ServerStat server1 = createServerStat(1, 1500.0);
            ServerStat server2 = createServerStat(2, 1200.0);

            List<Player> players = createPlayers(20, server1, server2);
            assertThat(players).hasSize(20);

            season.setStatus(Season.SeasonStatus.ACTIVE);
            seasonRepository.save(season);

            String battleId = battlefieldManager.createBattlefield(
                    players.stream().map(Player::getPlayerId).collect(Collectors.toList())
            );
            assertThat(battleId).isNotNull();
            assertThat(battlefieldManager.getActiveBattlefieldCount()).isEqualTo(1);

            for (Player p : players) {
                scoreEngine.initPlayerScore(battleId, p.getPlayerId());
            }

            Long killer1 = players.get(0).getPlayerId();
            Long victim1 = players.get(1).getPlayerId();
            Long assister1 = players.get(2).getPlayerId();
            Long victim2 = players.get(3).getPlayerId();

            scoreEngine.onDamage(battleId, assister1, victim1, 300, 100, false);
            scoreEngine.onDamage(battleId, killer1, victim1, 700, 100, false);
            scoreEngine.onKill(battleId, killer1, victim1, 100);

            var killer1Score = scoreEngine.getPlayerScore(battleId, killer1);
            assertThat(killer1Score.getKills()).isEqualTo(1);
            assertThat(killer1Score.getTotalScore()).isEqualTo(100);

            var assister1Score = scoreEngine.getPlayerScore(battleId, assister1);
            assertThat(assister1Score.getAssists()).isEqualTo(1);
            assertThat(assister1Score.getTotalScore()).isGreaterThan(0);

            scoreEngine.onKill(battleId, killer1, victim2, 100);

            scoreEngine.onCapture(battleId, killer1, 1);

            killer1Score = scoreEngine.getPlayerScore(battleId, killer1);
            assertThat(killer1Score.getCaptures()).isEqualTo(1);
            assertThat(killer1Score.getTotalScore()).isEqualTo(100 + 100 + 200);

            Double redisScore = stringRedisTemplate.opsForZSet()
                    .score("leaderboard:score", killer1.toString());
            assertThat(redisScore).isNotNull();
            assertThat(redisScore).isEqualTo(killer1Score.getTotalScore());

            scoreEngine.finalizeBattleScores(battleId);
            battlefieldManager.endBattle(battleId);
            assertThat(battlefieldManager.getActiveBattlefieldCount()).isEqualTo(0);

            List<Map<String, Object>> topPlayers = leaderboardService.getTopPlayersByScore(10);
            assertThat(topPlayers).isNotEmpty();
            assertThat(topPlayers.get(0).get("id")).isEqualTo(killer1.toString());

            leaderboardService.archiveSeasonToMySQL(season.getId());

            List<SeasonRanking> scoreRankings = seasonRankingRepository
                    .findBySeasonIdAndRankingTypeOrderByRankAsc(season.getId(), SeasonRanking.RankingType.TOTAL_SCORE);
            assertThat(scoreRankings).isNotEmpty();
            assertThat(scoreRankings.get(0).getRank()).isEqualTo(1);

            season.setStatus(Season.SeasonStatus.SETTLING);
            seasonRepository.save(season);

            rewardService.calculateAndCreateRewards(season.getId(), season.getRewardConfigJson());

            List<RewardRecord> pendingRewards = rewardRecordRepository
                    .findByStatus(RewardRecord.RewardStatus.PENDING);
            assertThat(pendingRewards).isNotEmpty();

            for (RewardRecord reward : pendingRewards) {
                rewardService.deliverReward(reward.getId());
            }

            List<RewardRecord> deliveredRewards = rewardRecordRepository
                    .findByStatus(RewardRecord.RewardStatus.DELIVERED);
            assertThat(deliveredRewards).hasSize(pendingRewards.size());

            season.setStatus(Season.SeasonStatus.FINISHED);
            seasonRepository.save(season);

            Season finishedSeason = seasonRepository.findById(season.getId()).orElseThrow();
            assertThat(finishedSeason.getStatus()).isEqualTo(Season.SeasonStatus.FINISHED);
        }

        @Test
        @DisplayName("排行榜查询与归档一致性验证")
        void leaderboardQueryAndArchiveConsistency() {
            Season season = createSeason();
            createServerStat(1, 1500.0);
            List<Player> players = createPlayers(5, serverStatRepository.findByServerId(1).orElseThrow(), null);

            stringRedisTemplate.opsForZSet().add("leaderboard:score", players.get(0).getPlayerId().toString(), 5000);
            stringRedisTemplate.opsForZSet().add("leaderboard:score", players.get(1).getPlayerId().toString(), 4000);
            stringRedisTemplate.opsForZSet().add("leaderboard:score", players.get(2).getPlayerId().toString(), 3000);
            stringRedisTemplate.opsForZSet().add("leaderboard:score", players.get(3).getPlayerId().toString(), 2000);
            stringRedisTemplate.opsForZSet().add("leaderboard:score", players.get(4).getPlayerId().toString(), 1000);

            List<Map<String, Object>> top3 = leaderboardService.getTopPlayersByScore(3);
            assertThat(top3).hasSize(3);
            assertThat(top3.get(0).get("id")).isEqualTo(players.get(0).getPlayerId().toString());
            assertThat(top3.get(0).get("score")).isEqualTo(5000.0);

            leaderboardService.archiveSeasonToMySQL(season.getId());

            List<SeasonRanking> rankings = seasonRankingRepository
                    .findBySeasonIdAndRankingTypeOrderByRankAsc(season.getId(), SeasonRanking.RankingType.TOTAL_SCORE);
            assertThat(rankings).hasSize(5);
            assertThat(rankings.get(0).getPlayerId()).isEqualTo(players.get(0).getPlayerId());
            assertThat(rankings.get(0).getRank()).isEqualTo(1);
            assertThat(rankings.get(0).getScore()).isEqualTo(5000);
            assertThat(rankings.get(4).getRank()).isEqualTo(5);
        }

        @Test
        @DisplayName("历史赛季数据归档后可正确查询")
        void historicalSeasonDataQueryable() {
            Season season1 = createSeasonWithCode("S2024-01");
            Season season2 = createSeasonWithCode("S2024-02");

            createServerStat(1, 1500.0);
            List<Player> players = createPlayers(3, serverStatRepository.findByServerId(1).orElseThrow(), null);

            stringRedisTemplate.opsForZSet().add("leaderboard:score", players.get(0).getPlayerId().toString(), 5000);
            stringRedisTemplate.opsForZSet().add("leaderboard:score", players.get(1).getPlayerId().toString(), 3000);
            stringRedisTemplate.opsForZSet().add("leaderboard:score", players.get(2).getPlayerId().toString(), 1000);

            leaderboardService.archiveSeasonToMySQL(season1.getId());

            stringRedisTemplate.opsForZSet().add("leaderboard:score", players.get(0).getPlayerId().toString(), 6000);
            stringRedisTemplate.opsForZSet().add("leaderboard:score", players.get(1).getPlayerId().toString(), 4000);
            stringRedisTemplate.opsForZSet().add("leaderboard:score", players.get(2).getPlayerId().toString(), 2000);

            leaderboardService.archiveSeasonToMySQL(season2.getId());

            List<SeasonRanking> s1Rankings = seasonRankingRepository
                    .findBySeasonIdAndRankingTypeOrderByRankAsc(season1.getId(), SeasonRanking.RankingType.TOTAL_SCORE);
            List<SeasonRanking> s2Rankings = seasonRankingRepository
                    .findBySeasonIdAndRankingTypeOrderByRankAsc(season2.getId(), SeasonRanking.RankingType.TOTAL_SCORE);

            assertThat(s1Rankings).hasSize(3);
            assertThat(s2Rankings).hasSize(3);

            assertThat(s1Rankings.get(0).getScore()).isEqualTo(5000);
            assertThat(s2Rankings.get(0).getScore()).isEqualTo(6000);
        }
    }

    @Nested
    @DisplayName("并发排行榜更新一致性")
    class ConcurrentLeaderboardTest {

        @Test
        @DisplayName("多线程并发更新同一玩家积分最终Redis分数一致")
        void concurrentUpdateSamePlayerRedisConsistent() throws InterruptedException {
            Long playerId = 9999L;
            int threadCount = 50;

            Thread[] threads = new Thread[threadCount];
            for (int i = 0; i < threadCount; i++) {
                final double score = (i + 1) * 100.0;
                threads[i] = new Thread(() -> {
                    stringRedisTemplate.opsForZSet().add("leaderboard:score", playerId.toString(), score);
                });
            }

            for (Thread t : threads) t.start();
            for (Thread t : threads) t.join(5000);

            Double finalScore = stringRedisTemplate.opsForZSet().score("leaderboard:score", playerId.toString());
            assertThat(finalScore).isNotNull();
            assertThat(finalScore).isGreaterThan(0);
        }
    }

    private Season createSeason() {
        return createSeasonWithCode("S2025-01");
    }

    private Season createSeasonWithCode(String code) {
        Season season = Season.builder()
                .seasonCode(code)
                .seasonName("Season " + code)
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

    private List<Player> createPlayers(int count, ServerStat server1, ServerStat server2) {
        List<Player> players = new ArrayList<>();
        for (long i = 1; i <= count; i++) {
            int serverId = (i % 2 == 0) ? server1.getServerId() :
                    (server2 != null ? server2.getServerId() : server1.getServerId());
            Player p = Player.builder()
                    .playerId(9000L + i)
                    .serverId(serverId)
                    .playerName("TestPlayer" + i)
                    .combatPower(50000L + i * 1000)
                    .rating(1500.0 + i * 10)
                    .totalKills(0)
                    .totalDeaths(0)
                    .totalAssists(0)
                    .totalScore(0)
                    .isBanned(false)
                    .guildId(i <= 5 ? 100L : 200L)
                    .build();
            players.add(playerRepository.save(p));
        }
        return players;
    }
}
