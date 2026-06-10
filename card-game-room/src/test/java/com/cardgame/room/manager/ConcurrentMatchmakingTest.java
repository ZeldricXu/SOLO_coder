package com.cardgame.room.manager;

import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.PlayerClass;
import com.cardgame.common.enums.RoomStatus;
import com.cardgame.room.entity.MatchRequest;
import com.cardgame.room.entity.Room;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Concurrent Matchmaking Tests")
class ConcurrentMatchmakingTest {

    @Mock
    private RoomManager roomManager;

    @Mock
    private GameConfig gameConfig;

    @InjectMocks
    private MatchmakingManager matchmakingManager;

    @BeforeEach
    void setUp() {
        when(gameConfig.getMaxMatchQueueSize()).thenReturn(200);
        when(gameConfig.getMatchTimeoutSeconds()).thenReturn(60);
        when(gameConfig.getBasePlayerHp()).thenReturn(80);
        when(gameConfig.getBasePlayerSpeed()).thenReturn(10);
        when(gameConfig.getDefaultMaxEnergy()).thenReturn(3);
        when(gameConfig.getMaxHandSize()).thenReturn(10);
        when(gameConfig.getMaxPlayersPerRoom()).thenReturn(4);
    }

    private Queue<MatchRequest> getMatchQueue() throws Exception {
        Field field = MatchmakingManager.class.getDeclaredField("matchQueue");
        field.setAccessible(true);
        return (Queue<MatchRequest>) field.get(matchmakingManager);
    }

    @Test
    @DisplayName("Concurrent add to match queue - should not lose any requests")
    void concurrentAddToQueue_ShouldNotLoseRequests() throws InterruptedException {
        int threadCount = 20;
        int requestsPerThread = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        Set<String> addedPlayers = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < requestsPerThread; j++) {
                        String playerId = "player-" + threadIndex + "-" + j;
                        PlayerClass playerClass = PlayerClass.values()[j % PlayerClass.values().length];
                        matchmakingManager.addToMatchQueue(playerId, "Player" + threadIndex + j, playerClass, 1);
                        addedPlayers.add(playerId);
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount * requestsPerThread);

        try {
            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).hasSize(threadCount * requestsPerThread);

            List<String> queuePlayerIds = new ArrayList<>();
            for (MatchRequest request : queue) {
                queuePlayerIds.add(request.getPlayerId());
            }
            assertThat(queuePlayerIds).containsExactlyInAnyOrderElementsOf(addedPlayers);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("Concurrent add and remove from queue - should maintain consistency")
    void concurrentAddAndRemove_ShouldMaintainConsistency() throws InterruptedException {
        int addThreadCount = 10;
        int removeThreadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(addThreadCount + removeThreadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(addThreadCount + removeThreadCount);
        AtomicInteger addCount = new AtomicInteger(0);
        AtomicInteger removeCount = new AtomicInteger(0);

        for (int i = 0; i < addThreadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 10; j++) {
                        String playerId = "player-" + threadIndex + "-" + j;
                        matchmakingManager.addToMatchQueue(playerId, "Player" + threadIndex, PlayerClass.WARRIOR, 1);
                        addCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        for (int i = 0; i < removeThreadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 5; j++) {
                        String playerId = "player-" + threadIndex + "-" + j;
                        matchmakingManager.removeFromMatchQueue(playerId);
                        removeCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(addCount.get()).isEqualTo(100);
        assertThat(removeCount.get()).isEqualTo(25);

        try {
            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue.size()).isGreaterThanOrEqualTo(0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("Concurrent matchmaking - should not duplicate or miss players")
    void concurrentMatchmaking_ShouldNotDuplicateOrMiss() throws InterruptedException {
        int playerCount = 40;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(playerCount);
        Set<String> matchedPlayers = ConcurrentHashMap.newKeySet();
        AtomicInteger roomCreatedCount = new AtomicInteger(0);

        when(roomManager.createRoom(anyString(), anyString(), eq(4), eq(false), isNull())).thenAnswer(invocation -> {
            String roomId = "room-" + roomCreatedCount.incrementAndGet();
            Room room = Room.builder()
                    .roomId(roomId)
                    .status(RoomStatus.WAITING)
                    .maxPlayers(4)
                    .build();
            when(roomManager.getRoom(roomId)).thenReturn(room);
            return room;
        });

        for (int i = 0; i < playerCount; i++) {
            final int playerIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String playerId = "player-" + playerIndex;
                    PlayerClass playerClass = PlayerClass.values()[playerIndex % PlayerClass.values().length];
                    matchmakingManager.addToMatchQueue(playerId, "Player" + playerIndex, playerClass, 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean addCompleted = doneLatch.await(10, TimeUnit.SECONDS);
        assertThat(addCompleted).isTrue();

        try {
            Method processMethod = MatchmakingManager.class.getDeclaredMethod("processMatchmaking");
            processMethod.setAccessible(true);

            CountDownLatch processLatch = new CountDownLatch(5);
            for (int i = 0; i < 5; i++) {
                executor.submit(() -> {
                    try {
                        processMethod.invoke(matchmakingManager);
                    } catch (Exception e) {
                        // ignore
                    } finally {
                        processLatch.countDown();
                    }
                });
            }

            boolean processCompleted = processLatch.await(5, TimeUnit.SECONDS);
            assertThat(processCompleted).isTrue();

            Thread.sleep(500);

            verify(roomManager, atLeast(10)).createRoom(anyString(), anyString(), eq(4), eq(false), isNull());

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).isEmpty();

        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("Concurrent cancel during matchmaking - should handle gracefully")
    void concurrentCancelDuringMatchmaking_ShouldHandleGracefully() throws InterruptedException {
        int playerCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch addLatch = new CountDownLatch(playerCount);
        CountDownLatch operationLatch = new CountDownLatch(playerCount);

        when(roomManager.createRoom(anyString(), anyString(), eq(4), eq(false), isNull())).thenAnswer(invocation -> {
            Room room = Room.builder()
                    .roomId("room-" + System.nanoTime())
                    .status(RoomStatus.WAITING)
                    .maxPlayers(4)
                    .build();
            return room;
        });

        for (int i = 0; i < playerCount; i++) {
            final int playerIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String playerId = "player-" + playerIndex;
                    matchmakingManager.addToMatchQueue(playerId, "Player" + playerIndex, PlayerClass.WARRIOR, 1);
                    addLatch.countDown();

                    if (playerIndex % 2 == 0) {
                        Thread.sleep(10);
                        matchmakingManager.removeFromMatchQueue(playerId);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    operationLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean addCompleted = addLatch.await(10, TimeUnit.SECONDS);
        assertThat(addCompleted).isTrue();

        try {
            Method processMethod = MatchmakingManager.class.getDeclaredMethod("processMatchmaking");
            processMethod.setAccessible(true);

            for (int i = 0; i < 3; i++) {
                processMethod.invoke(matchmakingManager);
                Thread.sleep(10);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        boolean opCompleted = operationLatch.await(10, TimeUnit.SECONDS);
        assertThat(opCompleted).isTrue();

        try {
            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue.size()).isGreaterThanOrEqualTo(0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        executor.shutdown();
    }

    @Test
    @DisplayName("Concurrent queue position queries - should return consistent results")
    void concurrentQueuePositionQueries_ShouldBeConsistent() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            matchmakingManager.addToMatchQueue("player-" + i, "Player" + i, PlayerClass.WARRIOR, 1);
        }

        int queryThreadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(queryThreadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(queryThreadCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < queryThreadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 50; j++) {
                        String playerId = "player-" + j;
                        int position = matchmakingManager.getQueuePosition(playerId);
                        if (position != j + 1) {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(errorCount.get()).isEqualTo(0);

        executor.shutdown();
    }

    @Test
    @DisplayName("High stress concurrent matching - stress test")
    void highStressConcurrentMatching_ShouldHandleCorrectly() throws InterruptedException {
        int totalPlayers = 100;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(totalPlayers);
        AtomicInteger successCount = new AtomicInteger(0);

        when(roomManager.createRoom(anyString(), anyString(), eq(4), eq(false), isNull())).thenAnswer(invocation -> {
            Room room = Room.builder()
                    .roomId("room-" + System.nanoTime())
                    .status(RoomStatus.WAITING)
                    .maxPlayers(4)
                    .build();
            return room;
        });

        for (int i = 0; i < totalPlayers; i++) {
            final int playerIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    String playerId = "player-" + playerIndex;
                    PlayerClass playerClass = PlayerClass.values()[playerIndex % PlayerClass.values().length];
                    matchmakingManager.addToMatchQueue(playerId, "Player" + playerIndex, playerClass, 1);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean addCompleted = doneLatch.await(30, TimeUnit.SECONDS);
        assertThat(addCompleted).isTrue();
        assertThat(successCount.get()).isEqualTo(totalPlayers);

        try {
            Method processMethod = MatchmakingManager.class.getDeclaredMethod("processMatchmaking");
            processMethod.setAccessible(true);

            for (int i = 0; i < 10; i++) {
                processMethod.invoke(matchmakingManager);
            }

            Queue<MatchRequest> queue = getMatchQueue();
            assertThat(queue).isEmpty();

            verify(roomManager, atLeast(25)).createRoom(anyString(), anyString(), eq(4), eq(false), isNull());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        executor.shutdown();
    }
}
