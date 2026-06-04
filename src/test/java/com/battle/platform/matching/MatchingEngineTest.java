package com.battle.platform.matching;

import com.battle.platform.battlefield.BattlefieldManager;
import com.battle.platform.config.MatchingProperties;
import com.battle.platform.entity.Player;
import com.battle.platform.entity.ServerStat;
import com.battle.platform.netty.GameServerHandler;
import com.battle.platform.repository.PlayerRepository;
import com.battle.platform.repository.ServerStatRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("跨服匹配引擎单元测试")
class MatchingEngineTest {

    @Mock
    private MatchingProperties matchingProperties;
    @Mock
    private BattlefieldManager battlefieldManager;
    @Mock
    private ServerStatRepository serverStatRepository;
    @Mock
    private PlayerRepository playerRepository;
    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ObjectMapper objectMapper;

    private MatchingEngine matchingEngine;

    private static final int BRACKET_SIZE = 200;
    private static final int PLAYERS_PER_MATCH = 200;

    @BeforeEach
    void setUp() {
        lenient().when(matchingProperties.getRatingBracketSize()).thenReturn(BRACKET_SIZE);
        lenient().when(matchingProperties.getWaitTimeWeight()).thenReturn(0.5);
        lenient().when(matchingProperties.getMaxWaitTimeMs()).thenReturn(120000L);
        lenient().when(battlefieldManager.createBattlefield(any())).thenReturn("BF-test-battle");

        matchingEngine = new MatchingEngine(
                matchingProperties, battlefieldManager, serverStatRepository,
                playerRepository, stringRedisTemplate, objectMapper
        );
    }

    private Player createPlayer(Long playerId, int serverId, long combatPower, double rating) {
        return Player.builder()
                .playerId(playerId)
                .serverId(serverId)
                .playerName("Player" + playerId)
                .combatPower(combatPower)
                .rating(rating)
                .totalKills(0)
                .totalDeaths(0)
                .totalAssists(0)
                .totalScore(0)
                .isBanned(false)
                .build();
    }

    private ServerStat createServerStat(int serverId, double powerScore) {
        return ServerStat.builder()
                .serverId(serverId)
                .serverName("Server" + serverId)
                .serverPowerScore(powerScore)
                .avgPlayerCombatPower(50000)
                .totalActivePlayers(1000)
                .openedAt(java.time.LocalDateTime.now())
                .build();
    }

    private void setupPlayerMocks(Map<Long, Player> players, Map<Integer, ServerStat> servers) {
        for (Map.Entry<Long, Player> e : players.entrySet()) {
            when(playerRepository.findByPlayerId(e.getKey())).thenReturn(Optional.of(e.getValue()));
        }
        for (Map.Entry<Integer, ServerStat> e : servers.entrySet()) {
            when(serverStatRepository.findByServerId(e.getKey())).thenReturn(Optional.of(e.getValue()));
        }
    }

    @Nested
    @DisplayName("同rating段玩家优先匹配")
    class SameBracketMatchingTest {

        @Test
        @DisplayName("同档玩家加入后应在同一个bracket队列中")
        void sameBracketPlayersInSameQueue() {
            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();
            servers.put(1, createServerStat(1, 1500.0));

            for (long i = 1; i <= 10; i++) {
                Player p = createPlayer(i, 1, 100000L, 1500.0);
                players.put(i, p);
            }
            setupPlayerMocks(players, servers);

            for (long i = 1; i <= 10; i++) {
                matchingEngine.joinMatch(i);
            }

            assertThat(matchingEngine.getWaitingCount()).isEqualTo(10);

            Map<Integer, Integer> stats = matchingEngine.getBracketStats();
            assertThat(stats).hasSize(1);

            Integer bracketKey = stats.keySet().iterator().next();
            assertThat(stats.get(bracketKey)).isGreaterThanOrEqualTo(10);
        }

        @Test
        @DisplayName("不同rating段的玩家分到不同bracket队列")
        void differentBracketPlayersInDifferentQueues() {
            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();

            servers.put(1, createServerStat(1, 500.0));
            servers.put(2, createServerStat(2, 2000.0));
            servers.put(3, createServerStat(3, 3500.0));

            Player lowRating = createPlayer(1L, 1, 10000L, 500.0);
            Player midRating = createPlayer(2L, 2, 100000L, 2000.0);
            Player highRating = createPlayer(3L, 3, 1000000L, 3500.0);
            players.put(1L, lowRating);
            players.put(2L, midRating);
            players.put(3L, highRating);
            setupPlayerMocks(players, servers);

            matchingEngine.joinMatch(1L);
            matchingEngine.joinMatch(2L);
            matchingEngine.joinMatch(3L);

            Map<Integer, Integer> stats = matchingEngine.getBracketStats();
            assertThat(stats).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("tick时同档200人全部匹配成功")
        void tickMatchesFullBracketOf200() {
            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();
            servers.put(1, createServerStat(1, 1500.0));

            for (long i = 1; i <= 200; i++) {
                Player p = createPlayer(i, 1, 100000L, 1500.0);
                players.put(i, p);
            }
            setupPlayerMocks(players, servers);

            for (long i = 1; i <= 200; i++) {
                matchingEngine.joinMatch(i);
            }

            matchingEngine.tick();

            verify(battlefieldManager, times(1)).createBattlefield(any());

            ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
            verify(battlefieldManager).createBattlefield(captor.capture());
            assertThat(captor.getValue()).hasSize(200);

            assertThat(matchingEngine.getWaitingCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("等待超时放宽匹配阈值")
    class WaitTimeoutExpandTest {

        @Test
        @DisplayName("等待超过最大时间一半后扩展bracket")
        void expandBracketAfterHalfMaxWait() {
            when(matchingProperties.getMaxWaitTimeMs()).thenReturn(100L);

            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();
            servers.put(1, createServerStat(1, 1500.0));

            Player p = createPlayer(1L, 1, 100000L, 1500.0);
            players.put(1L, p);
            setupPlayerMocks(players, servers);

            matchingEngine.joinMatch(1L);

            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            matchingEngine.tick();

            Map<Integer, Integer> stats = matchingEngine.getBracketStats();
            int totalTickets = stats.values().stream().mapToInt(Integer::intValue).sum();
            assertThat(totalTickets).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("等待时间权重增加匹配优先级")
        void waitTimeIncreasesPriority() {
            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();
            servers.put(1, createServerStat(1, 1500.0));

            Player p1 = createPlayer(1L, 1, 100000L, 1500.0);
            players.put(1L, p1);
            setupPlayerMocks(players, servers);

            matchingEngine.joinMatch(1L);

            assertThat(matchingEngine.getWaitingCount()).isEqualTo(1);

            MatchingPlayer mp = MatchingPlayer.builder()
                    .playerId(1L)
                    .serverId(1)
                    .combatPower(100000L)
                    .rating(1500.0)
                    .serverPowerScore(1500.0)
                    .joinTimeMs(System.currentTimeMillis() - 60000)
                    .build();

            double waitPriority = mp.getWaitTimePriority(0.5);
            assertThat(waitPriority).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("匹配池清空操作")
    class PoolClearTest {

        @Test
        @DisplayName("赛季结束后清空所有等待玩家")
        void clearAllWaitingPlayersAfterSeason() {
            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();
            servers.put(1, createServerStat(1, 1500.0));

            for (long i = 1; i <= 50; i++) {
                Player p = createPlayer(i, 1, 100000L, 1500.0);
                players.put(i, p);
            }
            setupPlayerMocks(players, servers);

            for (long i = 1; i <= 50; i++) {
                matchingEngine.joinMatch(i);
            }

            assertThat(matchingEngine.getWaitingCount()).isEqualTo(50);

            for (long i = 1; i <= 50; i++) {
                matchingEngine.leaveMatch(i);
            }

            assertThat(matchingEngine.getWaitingCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("玩家离开后不再参与匹配")
        void playerNoLongerInPoolAfterLeave() {
            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();
            servers.put(1, createServerStat(1, 1500.0));

            Player p = createPlayer(1L, 1, 100000L, 1500.0);
            players.put(1L, p);
            setupPlayerMocks(players, servers);

            matchingEngine.joinMatch(1L);
            assertThat(matchingEngine.getWaitingCount()).isEqualTo(1);

            matchingEngine.leaveMatch(1L);
            assertThat(matchingEngine.getWaitingCount()).isEqualTo(0);

            matchingEngine.tick();
            verify(battlefieldManager, never()).createBattlefield(any());
        }

        @Test
        @DisplayName("重复离开同一玩家不报错")
        void leaveSamePlayerTwiceNoError() {
            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();
            servers.put(1, createServerStat(1, 1500.0));
            Player p = createPlayer(1L, 1, 100000L, 1500.0);
            players.put(1L, p);
            setupPlayerMocks(players, servers);

            matchingEngine.joinMatch(1L);
            matchingEngine.leaveMatch(1L);
            matchingEngine.leaveMatch(1L);

            assertThat(matchingEngine.getWaitingCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("边界条件：匹配池只有一个玩家")
    class SinglePlayerBoundaryTest {

        @Test
        @DisplayName("不足开赛人数时tick不创建战场")
        void noBattleCreatedWithInsufficientPlayers() {
            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();
            servers.put(1, createServerStat(1, 1500.0));

            Player p = createPlayer(1L, 1, 100000L, 1500.0);
            players.put(1L, p);
            setupPlayerMocks(players, servers);

            matchingEngine.joinMatch(1L);
            matchingEngine.tick();

            verify(battlefieldManager, never()).createBattlefield(any());
            assertThat(matchingEngine.getWaitingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("199人时tick不创建战场")
        void noBattleCreatedWith199Players() {
            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();
            servers.put(1, createServerStat(1, 1500.0));

            for (long i = 1; i <= 199; i++) {
                Player p = createPlayer(i, 1, 100000L, 1500.0);
                players.put(i, p);
            }
            setupPlayerMocks(players, servers);

            for (long i = 1; i <= 199; i++) {
                matchingEngine.joinMatch(i);
            }

            matchingEngine.tick();

            verify(battlefieldManager, never()).createBattlefield(any());
            assertThat(matchingEngine.getWaitingCount()).isEqualTo(199);
        }

        @Test
        @DisplayName("201人时先匹配200人，剩余1人留在池中")
        void extraPlayerStaysInPoolAfterPartialMatch() {
            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();
            servers.put(1, createServerStat(1, 1500.0));

            for (long i = 1; i <= 201; i++) {
                Player p = createPlayer(i, 1, 100000L, 1500.0);
                players.put(i, p);
            }
            setupPlayerMocks(players, servers);

            for (long i = 1; i <= 201; i++) {
                matchingEngine.joinMatch(i);
            }

            matchingEngine.tick();

            verify(battlefieldManager, times(1)).createBattlefield(any());
            assertThat(matchingEngine.getWaitingCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("空匹配池tick无异常")
        void tickOnEmptyPoolNoError() {
            matchingEngine.tick();
            verify(battlefieldManager, never()).createBattlefield(any());
            assertThat(matchingEngine.getWaitingCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("匹配池容量上限拒绝策略")
    class PoolCapacityRejectionTest {

        @Test
        @DisplayName("被封禁的玩家无法加入匹配池")
        void bannedPlayerCannotJoin() {
            Player bannedPlayer = Player.builder()
                    .playerId(1L)
                    .serverId(1)
                    .playerName("BannedPlayer")
                    .combatPower(100000L)
                    .rating(1500.0)
                    .totalKills(0)
                    .totalDeaths(0)
                    .totalAssists(0)
                    .totalScore(0)
                    .isBanned(true)
                    .build();

            when(playerRepository.findByPlayerId(1L)).thenReturn(Optional.of(bannedPlayer));

            matchingEngine.joinMatch(1L);

            assertThat(matchingEngine.getWaitingCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("不存在的玩家无法加入匹配池")
        void nonExistentPlayerCannotJoin() {
            when(playerRepository.findByPlayerId(999L)).thenReturn(Optional.empty());

            matchingEngine.joinMatch(999L);

            assertThat(matchingEngine.getWaitingCount()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("MatchingPlayer评分计算")
    class MatchingPlayerRatingTest {

        @Test
        @DisplayName("综合Rating计算公式正确")
        void compositeRatingCalculation() {
            MatchingPlayer mp = MatchingPlayer.builder()
                    .playerId(1L)
                    .serverId(1)
                    .combatPower(100000L)
                    .rating(2000.0)
                    .serverPowerScore(1500.0)
                    .joinTimeMs(System.currentTimeMillis())
                    .build();

            double composite = mp.getCompositeRating();

            double expectedCombat = Math.log10(100000) * 1000 * 0.4;
            double expectedServer = 1500.0 * 0.3;
            double expectedPersonal = 2000.0 * 0.3;

            assertThat(composite).isCloseTo(expectedCombat + expectedServer + expectedPersonal, within(0.01));
        }

        @Test
        @DisplayName("战力为0时归一化不会崩溃")
        void zeroCombatPowerNoCrash() {
            MatchingPlayer mp = MatchingPlayer.builder()
                    .playerId(1L)
                    .serverId(1)
                    .combatPower(0L)
                    .rating(1000.0)
                    .serverPowerScore(1000.0)
                    .joinTimeMs(System.currentTimeMillis())
                    .build();

            double composite = mp.getCompositeRating();
            assertThat(composite).isFinite();
            assertThat(composite).isGreaterThan(0);
        }

        @Test
        @DisplayName("rating为null时默认1000")
        void nullRatingDefaultsTo1000() {
            MatchingPlayer mp = MatchingPlayer.builder()
                    .playerId(1L)
                    .serverId(1)
                    .combatPower(100000L)
                    .rating(null)
                    .serverPowerScore(1500.0)
                    .joinTimeMs(System.currentTimeMillis())
                    .build();

            double composite = mp.getCompositeRating();
            double expectedPersonal = 1000.0 * 0.3;
            double expectedCombat = Math.log10(100000) * 1000 * 0.4;
            double expectedServer = 1500.0 * 0.3;

            assertThat(composite).isCloseTo(expectedCombat + expectedServer + expectedPersonal, within(0.01));
        }

        @Test
        @DisplayName("bracket分档计算正确")
        void bracketCalculation() {
            MatchingPlayer mp = MatchingPlayer.builder()
                    .playerId(1L)
                    .serverId(1)
                    .combatPower(100000L)
                    .rating(2000.0)
                    .serverPowerScore(1500.0)
                    .joinTimeMs(System.currentTimeMillis())
                    .build();

            int bracket = mp.getRatingBracket(200);
            double composite = mp.getCompositeRating();
            int expectedBracket = (int) (composite / 200);

            assertThat(bracket).isEqualTo(expectedBracket);
        }
    }

    @Nested
    @DisplayName("并发加入匹配池")
    class ConcurrentJoinTest {

        @Test
        @DisplayName("多线程并发加入匹配池不丢人")
        void concurrentJoinNoLostPlayers() throws InterruptedException {
            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();
            servers.put(1, createServerStat(1, 1500.0));

            int totalPlayers = 100;
            for (long i = 1; i <= totalPlayers; i++) {
                Player p = createPlayer(i, 1, 100000L, 1500.0);
                players.put(i, p);
            }
            setupPlayerMocks(players, servers);

            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(totalPlayers);

            for (long i = 1; i <= totalPlayers; i++) {
                final long pid = i;
                executor.submit(() -> {
                    try {
                        matchingEngine.joinMatch(pid);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(matchingEngine.getWaitingCount()).isEqualTo(totalPlayers);

            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("400人匹配出两个战场")
    class TwoBattlesFrom400Test {

        @Test
        @DisplayName("400同档玩家tick后创建2个战场")
        void twoBattlesFrom400Players() {
            Map<Long, Player> players = new HashMap<>();
            Map<Integer, ServerStat> servers = new HashMap<>();
            servers.put(1, createServerStat(1, 1500.0));

            for (long i = 1; i <= 400; i++) {
                Player p = createPlayer(i, 1, 100000L, 1500.0);
                players.put(i, p);
            }
            setupPlayerMocks(players, servers);

            for (long i = 1; i <= 400; i++) {
                matchingEngine.joinMatch(i);
            }

            matchingEngine.tick();
            matchingEngine.tick();

            verify(battlefieldManager, times(2)).createBattlefield(any());
            assertThat(matchingEngine.getWaitingCount()).isEqualTo(0);
        }
    }
}
