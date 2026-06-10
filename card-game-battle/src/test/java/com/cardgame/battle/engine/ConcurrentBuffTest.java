package com.cardgame.battle.engine;

import com.cardgame.battle.entity.BattleContext;
import com.cardgame.common.TestDataBuilder;
import com.cardgame.common.entity.Buff;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BattleStatus;
import com.cardgame.common.enums.BuffType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Concurrent Buff Application Tests")
class ConcurrentBuffTest {

    private BattleContext context;
    private Player player1;
    private Player player2;
    private Enemy enemy;
    private BuffSystem buffSystem;

    @BeforeEach
    void setUp() {
        buffSystem = new BuffSystem();
        player1 = TestDataBuilder.createWarriorPlayer("player1");
        player2 = TestDataBuilder.createMagePlayer("player2");
        enemy = TestDataBuilder.createGoblinEnemy("enemy1");

        player1.setMaxHp(80);
        player1.setCurrentHp(80);
        player2.setMaxHp(70);
        player2.setCurrentHp(70);
        enemy.setMaxHp(50);
        enemy.setCurrentHp(50);

        context = BattleContext.builder()
                .battleId("test-battle")
                .roomId("test-room")
                .floor(1)
                .status(BattleStatus.IN_PROGRESS)
                .currentTurn(1)
                .currentRound(1)
                .players(List.of(player1, player2))
                .enemies(List.of(enemy))
                .build();
        context.getCharacterMap().put(player1.getPlayerId(), player1);
        context.getCharacterMap().put(player2.getPlayerId(), player2);
        context.getCharacterMap().put(enemy.getId(), enemy);
    }

    @Test
    @DisplayName("Concurrent buff application - should stack correctly without overwriting")
    void concurrentBuffApplication_ShouldStackCorrectly() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Buff buff = TestDataBuilder.createBuff(BuffType.WEAK, 1, 3);
                    buff.setSourceId("player_" + index);
                    buff.setDebuff(true);
                    buffSystem.applyBuff(enemy, buff);
                    successCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(enemy.getBuffStacks(BuffType.WEAK.name())).isEqualTo(threadCount);

        executor.shutdown();
    }

    @Test
    @DisplayName("Two players apply buff to same enemy - both buffs should stack")
    void twoPlayersApplyBuff_SameEnemy_BothShouldStack() throws InterruptedException {
        int iterations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        Runnable applyBuff = () -> {
            try {
                startLatch.await();
                for (int i = 0; i < iterations; i++) {
                    Buff buff = TestDataBuilder.createBuff(BuffType.WEAK, 1, 3);
                    buff.setDebuff(true);
                    buffSystem.applyBuff(enemy, buff);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        };

        executor.submit(applyBuff);
        executor.submit(applyBuff);

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(enemy.getBuffStacks(BuffType.WEAK.name())).isEqualTo(2 * iterations);

        executor.shutdown();
    }

    @Test
    @DisplayName("Concurrent buff and debuff - concurrent application")
    void concurrentBuffAndDebuff_ShouldHandleCorrectly() throws InterruptedException {
        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    if (index % 2 == 0) {
                        Buff buff = TestDataBuilder.createBuff(BuffType.STRENGTH, 1, -1);
                        buffSystem.applyBuff(player1, buff);
                    } else {
                        Buff debuff = TestDataBuilder.createBuff(BuffType.WEAK, 1, 3);
                        debuff.setDebuff(true);
                        buffSystem.applyBuff(player1, debuff);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(player1.getBuffStacks(BuffType.STRENGTH.name())).isEqualTo(threadCount / 2);
        assertThat(player1.getBuffStacks(BuffType.WEAK.name())).isEqualTo(threadCount / 2);

        executor.shutdown();
    }

    @Test
    @DisplayName("Concurrent buff application with different durations - should use longer duration")
    void concurrentBuff_DifferentDurations_ShouldUseLonger() throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        executor.submit(() -> {
            try {
                startLatch.await();
                Buff buff = TestDataBuilder.createBuff(BuffType.WEAK, 2, 2);
                buff.setDebuff(true);
                buffSystem.applyBuff(enemy, buff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                Thread.sleep(10);
                Buff buff = TestDataBuilder.createBuff(BuffType.WEAK, 3, 5);
                buff.setDebuff(true);
                buffSystem.applyBuff(enemy, buff);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(enemy.getBuffStacks(BuffType.WEAK.name())).isEqualTo(5);
        assertThat(enemy.getBuffs().get(BuffType.WEAK.name()).getDuration()).isEqualTo(5);

        executor.shutdown();
    }

    @Test
    @DisplayName("Concurrent buff application - high contention stress test")
    void concurrentBuff_HighContention_ShouldHandleCorrectly() throws InterruptedException {
        int threadCount = 50;
        int operationsPerThread = 20;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < operationsPerThread; j++) {
                        Buff buff = TestDataBuilder.createBuff(BuffType.POISON, 1, 3);
                        buff.setDebuff(true);
                        buffSystem.applyBuff(enemy, buff);
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
        assertThat(enemy.getBuffStacks(BuffType.POISON.name())).isEqualTo(threadCount * operationsPerThread);

        executor.shutdown();
    }

    @Test
    @DisplayName("Concurrent buff application and removal - should maintain consistency")
    void concurrentBuffAndRemoval_ShouldMaintainConsistency() throws InterruptedException {
        int applyThreads = 5;
        int removeThreads = 3;
        ExecutorService executor = Executors.newFixedThreadPool(applyThreads + removeThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(applyThreads + removeThreads);

        for (int i = 0; i < applyThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 100; j++) {
                        Buff buff = TestDataBuilder.createBuff(BuffType.WEAK, 1, 3);
                        buff.setDebuff(true);
                        buffSystem.applyBuff(enemy, buff);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        for (int i = 0; i < removeThreads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < 50; j++) {
                        buffSystem.removeBuff(enemy, BuffType.WEAK);
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
        int finalStacks = enemy.getBuffStacks(BuffType.WEAK.name());
        assertThat(finalStacks).isGreaterThanOrEqualTo(0);

        executor.shutdown();
    }

    @Test
    @DisplayName("Concurrent turn end buff processing - should process correctly")
    void concurrentTurnEndProcessing_ShouldProcessCorrectly() throws InterruptedException {
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    buffSystem.processTurnEndBuffs(enemy);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(5, TimeUnit.SECONDS);

        assertThat(completed).isTrue();

        executor.shutdown();
    }
}
