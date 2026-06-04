package com.battle.platform.reward;

import com.battle.platform.entity.RewardRecord;
import com.battle.platform.entity.SeasonRanking;
import com.battle.platform.repository.RewardRecordRepository;
import com.battle.platform.repository.SeasonRankingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("奖励结算与发放单元测试")
class RewardServiceTest {

    @Mock
    private RewardRecordRepository rewardRecordRepository;
    @Mock
    private SeasonRankingRepository seasonRankingRepository;
    @Mock
    private StringRedisTemplate stringRedisTemplate;

    private RewardService rewardService;

    private static final Long SEASON_ID = 1L;

    @BeforeEach
    void setUp() {
        rewardService = new RewardService(rewardRecordRepository, seasonRankingRepository, stringRedisTemplate);
    }

    private SeasonRanking createPersonalRanking(Long playerId, int rank, int score) {
        return SeasonRanking.builder()
                .seasonId(SEASON_ID)
                .rankingType(SeasonRanking.RankingType.TOTAL_SCORE)
                .playerId(playerId)
                .score(score)
                .rank(rank)
                .snapshotAt(LocalDateTime.now())
                .build();
    }

    private SeasonRanking createGuildRanking(Long guildId, int rank, int score) {
        return SeasonRanking.builder()
                .seasonId(SEASON_ID)
                .rankingType(SeasonRanking.RankingType.GUILD_SCORE)
                .guildId(guildId)
                .score(score)
                .rank(rank)
                .snapshotAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("奖励计算与创建")
    class RewardCalculationTest {

        @Test
        @DisplayName("Top3玩家获得传说级奖励")
        void top3PlayerGetsLegendaryReward() {
            List<SeasonRanking> rankings = List.of(
                    createPersonalRanking(1001L, 1, 5000),
                    createPersonalRanking(1002L, 2, 4500),
                    createPersonalRanking(1003L, 3, 4000)
            );

            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.TOTAL_SCORE))
                    .thenReturn(rankings);
            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.GUILD_SCORE))
                    .thenReturn(Collections.emptyList());
            when(rewardRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            rewardService.calculateAndCreateRewards(SEASON_ID, "{}");

            ArgumentCaptor<RewardRecord> captor = ArgumentCaptor.forClass(RewardRecord.class);
            verify(rewardRecordRepository, times(3)).save(captor.capture());

            List<RewardRecord> saved = captor.getAllValues();
            for (RewardRecord r : saved) {
                assertThat(r.getRewardContentJson()).contains("diamond");
                assertThat(r.getRewardContentJson()).contains("legendary_equipment");
                assertThat(r.getStatus()).isEqualTo(RewardRecord.RewardStatus.PENDING);
                assertThat(r.getRetryCount()).isEqualTo(0);
            }
        }

        @Test
        @DisplayName("4-10名玩家获得史诗级奖励")
        void rank4to10GetsEpicReward() {
            List<SeasonRanking> rankings = List.of(
                    createPersonalRanking(1004L, 4, 3500),
                    createPersonalRanking(1010L, 10, 2000)
            );

            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.TOTAL_SCORE))
                    .thenReturn(rankings);
            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.GUILD_SCORE))
                    .thenReturn(Collections.emptyList());
            when(rewardRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            rewardService.calculateAndCreateRewards(SEASON_ID, "{}");

            ArgumentCaptor<RewardRecord> captor = ArgumentCaptor.forClass(RewardRecord.class);
            verify(rewardRecordRepository, times(2)).save(captor.capture());

            for (RewardRecord r : captor.getAllValues()) {
                assertThat(r.getRewardContentJson()).contains("epic_equipment");
            }
        }

        @Test
        @DisplayName("11-50名玩家获得普通奖励")
        void rank11to50GetsNormalReward() {
            List<SeasonRanking> rankings = List.of(
                    createPersonalRanking(1011L, 11, 1500)
            );

            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.TOTAL_SCORE))
                    .thenReturn(rankings);
            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.GUILD_SCORE))
                    .thenReturn(Collections.emptyList());
            when(rewardRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            rewardService.calculateAndCreateRewards(SEASON_ID, "{}");

            ArgumentCaptor<RewardRecord> captor = ArgumentCaptor.forClass(RewardRecord.class);
            verify(rewardRecordRepository).save(captor.capture());

            RewardRecord saved = captor.getValue();
            assertThat(saved.getRewardContentJson()).contains("diamond");
            assertThat(saved.getRewardContentJson()).contains("hero_shard");
            assertThat(saved.getRewardContentJson()).doesNotContain("equipment");
        }

        @Test
        @DisplayName("100名以后玩家获得基础奖励")
        void rankAbove100GetsBasicReward() {
            List<SeasonRanking> rankings = List.of(
                    createPersonalRanking(1101L, 101, 100)
            );

            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.TOTAL_SCORE))
                    .thenReturn(rankings);
            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.GUILD_SCORE))
                    .thenReturn(Collections.emptyList());
            when(rewardRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            rewardService.calculateAndCreateRewards(SEASON_ID, "{}");

            ArgumentCaptor<RewardRecord> captor = ArgumentCaptor.forClass(RewardRecord.class);
            verify(rewardRecordRepository).save(captor.capture());

            RewardRecord saved = captor.getValue();
            assertThat(saved.getRewardContentJson()).isEqualTo("{\"diamond\":100}");
        }

        @Test
        @DisplayName("公会奖励按公会排名计算")
        void guildRewardCalculation() {
            List<SeasonRanking> guildRankings = List.of(
                    createGuildRanking(5001L, 1, 20000),
                    createGuildRanking(5002L, 5, 10000)
            );

            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.TOTAL_SCORE))
                    .thenReturn(Collections.emptyList());
            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.GUILD_SCORE))
                    .thenReturn(guildRankings);
            when(rewardRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            rewardService.calculateAndCreateRewards(SEASON_ID, "{}");

            ArgumentCaptor<RewardRecord> captor = ArgumentCaptor.forClass(RewardRecord.class);
            verify(rewardRecordRepository, times(2)).save(captor.capture());

            List<RewardRecord> saved = captor.getAllValues();
            assertThat(saved.get(0).getRewardType()).isEqualTo(RewardRecord.RewardType.GUILD);
            assertThat(saved.get(0).getGuildId()).isEqualTo(5001L);
            assertThat(saved.get(0).getRewardContentJson()).contains("guild_diamond");
            assertThat(saved.get(0).getRewardContentJson()).contains("guild_exp");
        }

        @Test
        @DisplayName("无排名数据时不创建任何奖励")
        void noRankingsNoRewards() {
            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.TOTAL_SCORE))
                    .thenReturn(Collections.emptyList());
            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.GUILD_SCORE))
                    .thenReturn(Collections.emptyList());

            rewardService.calculateAndCreateRewards(SEASON_ID, "{}");

            verify(rewardRecordRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("奖励发放与校验")
    class RewardDeliveryTest {

        @Test
        @DisplayName("正常发放奖励成功")
        void deliverRewardSuccess() {
            RewardRecord record = RewardRecord.builder()
                    .id(1L)
                    .seasonId(SEASON_ID)
                    .playerId(1001L)
                    .rewardType(RewardRecord.RewardType.PERSONAL)
                    .rank(1)
                    .rewardContentJson("{\"diamond\":5000}")
                    .status(RewardRecord.RewardStatus.PENDING)
                    .retryCount(0)
                    .createdAt(LocalDateTime.now())
                    .build();

            when(rewardRecordRepository.findById(1L)).thenReturn(Optional.of(record));
            when(rewardRecordRepository.findBySeasonIdAndPlayerId(SEASON_ID, 1001L))
                    .thenReturn(List.of(record));
            when(rewardRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = rewardService.deliverReward(1L).isSuccess();

            assertThat(result).isTrue();

            ArgumentCaptor<RewardRecord> captor = ArgumentCaptor.forClass(RewardRecord.class);
            verify(rewardRecordRepository).save(captor.capture());

            RewardRecord saved = captor.getValue();
            assertThat(saved.getStatus()).isEqualTo(RewardRecord.RewardStatus.DELIVERED);
            assertThat(saved.getDeliveredAt()).isNotNull();
        }

        @Test
        @DisplayName("已发放的奖励重复发放直接返回成功")
        void alreadyDeliveredRewardReturnsTrue() {
            RewardRecord record = RewardRecord.builder()
                    .id(1L)
                    .seasonId(SEASON_ID)
                    .playerId(1001L)
                    .rewardType(RewardRecord.RewardType.PERSONAL)
                    .rank(1)
                    .rewardContentJson("{\"diamond\":5000}")
                    .status(RewardRecord.RewardStatus.DELIVERED)
                    .retryCount(0)
                    .createdAt(LocalDateTime.now())
                    .build();

            when(rewardRecordRepository.findById(1L)).thenReturn(Optional.of(record));

            boolean result = rewardService.deliverReward(1L).isSuccess();
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("防重复发放校验——同赛季同类型同排名已发放则拒绝")
        void duplicateRewardPrevention() {
            RewardRecord record = RewardRecord.builder()
                    .id(2L)
                    .seasonId(SEASON_ID)
                    .playerId(1001L)
                    .rewardType(RewardRecord.RewardType.PERSONAL)
                    .rank(1)
                    .rewardContentJson("{\"diamond\":5000}")
                    .status(RewardRecord.RewardStatus.PENDING)
                    .retryCount(0)
                    .createdAt(LocalDateTime.now())
                    .build();

            RewardRecord alreadyDelivered = RewardRecord.builder()
                    .id(1L)
                    .seasonId(SEASON_ID)
                    .playerId(1001L)
                    .rewardType(RewardRecord.RewardType.PERSONAL)
                    .rank(1)
                    .rewardContentJson("{\"diamond\":5000}")
                    .status(RewardRecord.RewardStatus.DELIVERED)
                    .retryCount(0)
                    .createdAt(LocalDateTime.now())
                    .build();

            when(rewardRecordRepository.findById(2L)).thenReturn(Optional.of(record));
            when(rewardRecordRepository.findBySeasonIdAndPlayerId(SEASON_ID, 1001L))
                    .thenReturn(List.of(alreadyDelivered, record));

            boolean result = rewardService.deliverReward(2L).isSuccess();
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("不存在的奖励ID返回false")
        void nonExistentRewardReturnsFalse() {
            when(rewardRecordRepository.findById(999L)).thenReturn(Optional.empty());

            boolean result = rewardService.deliverReward(999L).isSuccess();
            assertThat(result).isFalse();
        }
    }

    @Nested
    @DisplayName("奖励发放失败重试")
    class RewardRetryTest {

        @Test
        @DisplayName("发放失败retryCount+1但未超过3次不标记FAILED")
        void failureIncrementRetryButNotMarkFailedUnder3() {
            RewardRecord record = RewardRecord.builder()
                    .id(1L)
                    .seasonId(SEASON_ID)
                    .playerId(1001L)
                    .rewardType(RewardRecord.RewardType.PERSONAL)
                    .rank(1)
                    .rewardContentJson("{\"diamond\":5000}")
                    .status(RewardRecord.RewardStatus.PENDING)
                    .retryCount(0)
                    .createdAt(LocalDateTime.now())
                    .build();

            RewardService spyService = spy(rewardService);

            when(rewardRecordRepository.findById(1L)).thenReturn(Optional.of(record));
            when(rewardRecordRepository.findBySeasonIdAndPlayerId(SEASON_ID, 1001L))
                    .thenReturn(List.of(record));
            when(rewardRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            doThrow(new RuntimeException("Mailbox unavailable"))
                    .when(spyService).sendToGameMailbox(anyLong(), anyString());

            boolean result = spyService.deliverReward(1L).isSuccess();

            assertThat(result).isFalse();

            ArgumentCaptor<RewardRecord> captor = ArgumentCaptor.forClass(RewardRecord.class);
            verify(rewardRecordRepository).save(captor.capture());

            RewardRecord saved = captor.getValue();
            assertThat(saved.getRetryCount()).isEqualTo(1);
            assertThat(saved.getStatus()).isNotEqualTo(RewardRecord.RewardStatus.FAILED);
        }

        @Test
        @DisplayName("重试3次后标记为FAILED")
        void retry3TimesThenMarkFailed() {
            RewardRecord record = RewardRecord.builder()
                    .id(1L)
                    .seasonId(SEASON_ID)
                    .playerId(1001L)
                    .rewardType(RewardRecord.RewardType.PERSONAL)
                    .rank(1)
                    .rewardContentJson("{\"diamond\":5000}")
                    .status(RewardRecord.RewardStatus.PENDING)
                    .retryCount(2)
                    .createdAt(LocalDateTime.now())
                    .build();

            RewardService spyService = spy(rewardService);

            when(rewardRecordRepository.findById(1L)).thenReturn(Optional.of(record));
            when(rewardRecordRepository.findBySeasonIdAndPlayerId(SEASON_ID, 1001L))
                    .thenReturn(List.of(record));
            when(rewardRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            doThrow(new RuntimeException("Mailbox unavailable"))
                    .when(spyService).sendToGameMailbox(anyLong(), anyString());

            boolean result = spyService.deliverReward(1L).isSuccess();
            assertThat(result).isFalse();

            ArgumentCaptor<RewardRecord> captor = ArgumentCaptor.forClass(RewardRecord.class);
            verify(rewardRecordRepository).save(captor.capture());

            RewardRecord saved = captor.getValue();
            assertThat(saved.getRetryCount()).isEqualTo(3);
            assertThat(saved.getStatus()).isEqualTo(RewardRecord.RewardStatus.FAILED);
        }

        @Test
        @DisplayName("retryFailedRewards重置FAILED记录为PENDING并重新发放")
        void retryFailedRewardsResetsAndRedelivers() {
            RewardRecord failedRecord = RewardRecord.builder()
                    .id(1L)
                    .seasonId(SEASON_ID)
                    .playerId(1001L)
                    .rewardType(RewardRecord.RewardType.PERSONAL)
                    .rank(1)
                    .rewardContentJson("{\"diamond\":5000}")
                    .status(RewardRecord.RewardStatus.FAILED)
                    .retryCount(2)
                    .createdAt(LocalDateTime.now())
                    .build();

            when(rewardRecordRepository.findByStatusAndRetryCountLessThan(
                    RewardRecord.RewardStatus.FAILED, 3))
                    .thenReturn(List.of(failedRecord));
            when(rewardRecordRepository.findById(1L)).thenReturn(Optional.of(failedRecord));
            when(rewardRecordRepository.findBySeasonIdAndPlayerId(SEASON_ID, 1001L))
                    .thenReturn(List.of(failedRecord));
            when(rewardRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            rewardService.retryFailedRewards();

            verify(rewardRecordRepository, atLeast(2)).save(any());
        }

        @Test
        @DisplayName("retryCount已达3次的FAILED记录不再重试")
        void retryCountAt3NotRetried() {
            RewardRecord exhaustedRecord = RewardRecord.builder()
                    .id(1L)
                    .seasonId(SEASON_ID)
                    .playerId(1001L)
                    .rewardType(RewardRecord.RewardType.PERSONAL)
                    .rank(1)
                    .rewardContentJson("{\"diamond\":5000}")
                    .status(RewardRecord.RewardStatus.FAILED)
                    .retryCount(3)
                    .createdAt(LocalDateTime.now())
                    .build();

            when(rewardRecordRepository.findByStatusAndRetryCountLessThan(
                    RewardRecord.RewardStatus.FAILED, 3))
                    .thenReturn(Collections.emptyList());

            rewardService.retryFailedRewards();

            verify(rewardRecordRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("奖励等级覆盖验证")
    class RewardTierCoverageTest {

        @Test
        @DisplayName("全排名段奖励梯度正确")
        void fullRankingTiers() {
            List<SeasonRanking> rankings = List.of(
                    createPersonalRanking(1L, 1, 5000),
                    createPersonalRanking(2L, 3, 4000),
                    createPersonalRanking(3L, 4, 3500),
                    createPersonalRanking(4L, 10, 2000),
                    createPersonalRanking(5L, 11, 1500),
                    createPersonalRanking(6L, 50, 800),
                    createPersonalRanking(7L, 51, 700),
                    createPersonalRanking(8L, 100, 400),
                    createPersonalRanking(9L, 101, 300)
            );

            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.TOTAL_SCORE))
                    .thenReturn(rankings);
            when(seasonRankingRepository.findBySeasonIdAndRankingTypeOrderByRankAsc(
                    SEASON_ID, SeasonRanking.RankingType.GUILD_SCORE))
                    .thenReturn(Collections.emptyList());
            when(rewardRecordRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            rewardService.calculateAndCreateRewards(SEASON_ID, "{}");

            ArgumentCaptor<RewardRecord> captor = ArgumentCaptor.forClass(RewardRecord.class);
            verify(rewardRecordRepository, times(9)).save(captor.capture());

            List<RewardRecord> all = captor.getAllValues();

            assertThat(all.get(0).getRewardContentJson()).contains("legendary_equipment");
            assertThat(all.get(2).getRewardContentJson()).contains("epic_equipment");
            assertThat(all.get(4).getRewardContentJson()).doesNotContain("equipment");
            assertThat(all.get(7).getRewardContentJson()).contains("hero_shard");
            assertThat(all.get(8).getRewardContentJson()).isEqualTo("{\"diamond\":100}");
        }
    }
}
