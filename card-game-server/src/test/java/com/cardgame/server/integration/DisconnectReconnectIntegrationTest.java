package com.cardgame.server.integration;

import com.cardgame.ai.EnemyAIService;
import com.cardgame.battle.engine.BattleEngine;
import com.cardgame.battle.entity.BattleAction;
import com.cardgame.battle.entity.BattleContext;
import com.cardgame.common.TestDataBuilder;
import com.cardgame.common.entity.Card;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BattleStatus;
import com.cardgame.common.enums.RoomStatus;
import com.cardgame.deck.DeckManager;
import com.cardgame.replay.entity.BattleLog;
import com.cardgame.replay.service.BattleLogService;
import com.cardgame.room.entity.Room;
import com.cardgame.room.manager.RedisRoomStateManager;
import com.cardgame.room.manager.RoomManager;
import com.cardgame.save.entity.GameSave;
import com.cardgame.save.service.SaveService;
import com.cardgame.server.AbstractIntegrationTest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Slf4j
@DisplayName("Disconnect and Reconnect Integration Tests")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DisconnectReconnectIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private RedisRoomStateManager redisRoomStateManager;

    @Autowired
    private BattleEngine battleEngine;

    @Autowired
    private DeckManager deckManager;

    @Autowired
    private EnemyAIService enemyAIService;

    @Autowired
    private SaveService saveService;

    @Autowired
    private BattleLogService battleLogService;

    @BeforeEach
    void setUp() {
        log.info("Setting up disconnect/reconnect integration test...");
    }

    private void createSchema() {
        String schemaSql = """
            CREATE TABLE IF NOT EXISTS player_profiles (
                player_id VARCHAR(64) NOT NULL PRIMARY KEY,
                username VARCHAR(64) NOT NULL UNIQUE,
                nickname VARCHAR(64),
                level INT NOT NULL DEFAULT 1,
                experience INT NOT NULL DEFAULT 0,
                total_play_time_seconds BIGINT NOT NULL DEFAULT 0,
                total_games_played INT NOT NULL DEFAULT 0,
                total_wins INT NOT NULL DEFAULT 0,
                highest_floor_reached INT NOT NULL DEFAULT 0,
                total_gold_earned BIGINT NOT NULL DEFAULT 0,
                unlocked_card_ids TEXT,
                achievements TEXT,
                stats TEXT,
                created_at BIGINT NOT NULL,
                last_login_at BIGINT,
                online TINYINT(1) NOT NULL DEFAULT 0,
                current_save_id VARCHAR(64),
                current_room_id VARCHAR(64)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS game_saves (
                save_id VARCHAR(64) NOT NULL PRIMARY KEY,
                room_id VARCHAR(64) NOT NULL,
                host_player_id VARCHAR(64) NOT NULL,
                player_ids TEXT,
                player_states TEXT,
                player_decks TEXT,
                game_map TEXT,
                current_floor INT NOT NULL DEFAULT 0,
                score INT NOT NULL DEFAULT 0,
                gold INT NOT NULL DEFAULT 0,
                seed BIGINT NOT NULL,
                created_at BIGINT NOT NULL,
                updated_at BIGINT NOT NULL,
                play_time_seconds BIGINT NOT NULL DEFAULT 0,
                progress_data TEXT,
                locked TINYINT(1) NOT NULL DEFAULT 0,
                locked_by VARCHAR(64),
                locked_at BIGINT,
                completed TINYINT(1) NOT NULL DEFAULT 0,
                victory TINYINT(1) NOT NULL DEFAULT 0,
                difficulty VARCHAR(32),
                version VARCHAR(32)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

            CREATE TABLE IF NOT EXISTS battle_logs (
                battle_log_id VARCHAR(64) NOT NULL PRIMARY KEY,
                battle_id VARCHAR(64) NOT NULL,
                room_id VARCHAR(64) NOT NULL,
                save_id VARCHAR(64),
                floor INT NOT NULL,
                seed BIGINT NOT NULL,
                initial_player_states TEXT,
                initial_enemy_states TEXT,
                actions TEXT,
                result VARCHAR(32),
                start_time BIGINT NOT NULL,
                end_time BIGINT,
                duration_ms BIGINT,
                total_turns INT NOT NULL DEFAULT 0,
                total_rounds INT NOT NULL DEFAULT 0,
                stats TEXT,
                version VARCHAR(32)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
            """;

        executeSqlScript(schemaSql);
    }

    @Test
    @DisplayName("Player disconnects during battle - should pause and allow reconnection")
    void playerDisconnectsDuringBattle_ShouldAllowReconnect() throws Exception {
        log.info("Starting: Player disconnects during battle test");

        createSchema();

        log.info("Step 1: Setup room with 3 players");
        Room room = roomManager.createRoom("Battle Room", "player1", 4, false, null);
        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        Player player2 = TestDataBuilder.createMagePlayer("player2");
        Player player3 = TestDataBuilder.createRoguePlayer("player3");

        roomManager.joinRoom(room.getRoomId(), player1, null);
        roomManager.joinRoom(room.getRoomId(), player2, null);
        roomManager.joinRoom(room.getRoomId(), player3, null);
        redisRoomStateManager.saveRoom(room);

        for (Player player : room.getPlayers()) {
            deckManager.initializeStartingDeck(player);
            deckManager.prepareForBattle(player);
        }

        log.info("Step 2: Start battle");
        Enemy enemy1 = TestDataBuilder.createGoblinEnemy("enemy1");
        Enemy enemy2 = TestDataBuilder.createOrcEnemy("enemy2");
        enemyAIService.applyDifficultyScaling(enemy1, 1);
        enemyAIService.applyDifficultyScaling(enemy2, 1);

        BattleContext context = battleEngine.startBattle(room.getRoomId(), 1, room.getPlayers(), List.of(enemy1, enemy2));
        battleLogService.startBattleLogging(context);

        for (Player player : context.getPlayers()) {
            deckManager.drawCards(player, 5);
        }

        log.info("Step 3: Play a few cards to establish battle state");
        Player currentPlayer = context.getCurrentActorPlayer();
        assertThat(currentPlayer).isNotNull();

        Card attackCard = currentPlayer.getCurrentHand().get(0);
        BattleAction action1 = battleEngine.playCard(
                context.getBattleId(),
                currentPlayer.getPlayerId(),
                attackCard.getCardId(),
                List.of(enemy1.getId())
        );
        battleLogService.logAction(context.getBattleId(), action1);

        int player1HpBeforeDisconnect = player1.getCurrentHp();
        int player2HpBeforeDisconnect = player2.getCurrentHp();
        int player3HpBeforeDisconnect = player3.getCurrentHp();
        int enemy1HpBeforeDisconnect = enemy1.getCurrentHp();
        int currentTurnBeforeDisconnect = context.getCurrentTurn();
        int currentRoundBeforeDisconnect = context.getCurrentRound();

        log.info("Battle state before disconnect: Turn {}, Round {}, P1 HP: {}, P2 HP: {}, P3 HP: {}, Enemy HP: {}",
                currentTurnBeforeDisconnect, currentRoundBeforeDisconnect,
                player1HpBeforeDisconnect, player2HpBeforeDisconnect, player3HpBeforeDisconnect,
                enemy1HpBeforeDisconnect);

        log.info("Step 4: Player 2 disconnects");
        roomManager.setPlayerDisconnected("player2");
        redisRoomStateManager.saveRoom(room);

        assertThat(player2.isOnline()).isFalse();
        assertThat(room.getOnlinePlayerCount()).isEqualTo(2);
        assertThat(room.getDisconnectTimes()).containsKey("player2");
        assertThat(redisRoomStateManager.isPlayerOnline("player2")).isFalse();
        assertThat(redisRoomStateManager.isPlayerOnline("player1")).isTrue();

        Room roomAfterDisconnect = redisRoomStateManager.getRoom(room.getRoomId());
        assertThat(roomAfterDisconnect).isNotNull();
        assertThat(roomAfterDisconnect.getPlayer("player2").isOnline()).isFalse();

        log.info("Step 5: Save snapshot of battle state before reconnect");
        BattleContext battleSnapshot = battleEngine.getBattle(context.getBattleId());
        assertThat(battleSnapshot).isNotNull();

        int turnBeforeReconnect = battleSnapshot.getCurrentTurn();
        int roundBeforeReconnect = battleSnapshot.getCurrentRound();

        log.info("Step 6: Player 2 reconnects within timeout");
        boolean reconnected = roomManager.setPlayerReconnected("player2", room.getRoomId());
        redisRoomStateManager.saveRoom(room);

        assertThat(reconnected).isTrue();
        assertThat(player2.isOnline()).isTrue();
        assertThat(room.getOnlinePlayerCount()).isEqualTo(3);
        assertThat(room.getDisconnectTimes()).doesNotContainKey("player2");
        assertThat(redisRoomStateManager.isPlayerOnline("player2")).isTrue();

        log.info("Step 7: Verify room state restored correctly");
        Room restoredRoom = redisRoomStateManager.getRoom(room.getRoomId());
        assertThat(restoredRoom).isNotNull();
        assertThat(restoredRoom.getPlayers()).hasSize(3);
        assertThat(restoredRoom.isAllOnline()).isTrue();

        Player restoredPlayer2 = restoredRoom.getPlayer("player2");
        assertThat(restoredPlayer2).isNotNull();
        assertThat(restoredPlayer2.getCurrentHp()).isEqualTo(player2HpBeforeDisconnect);
        assertThat(restoredPlayer2.getPlayerId()).isEqualTo("player2");

        log.info("Step 8: Verify battle state can continue after reconnection");
        BattleContext battleAfterReconnect = battleEngine.getBattle(context.getBattleId());
        assertThat(battleAfterReconnect).isNotNull();
        assertThat(battleAfterReconnect.getCurrentTurn()).isEqualTo(turnBeforeReconnect);
        assertThat(battleAfterReconnect.getCurrentRound()).isEqualTo(roundBeforeReconnect);
        assertThat(battleAfterReconnect.getStatus()).isEqualTo(BattleStatus.PLAYER_TURN);

        Player battlePlayer2 = battleAfterReconnect.getPlayer("player2");
        assertThat(battlePlayer2).isNotNull();
        assertThat(battlePlayer2.getCurrentHp()).isEqualTo(player2HpBeforeDisconnect);
        assertThat(battlePlayer2.getDrawPile()).isNotNull();
        assertThat(battlePlayer2.getCurrentHand()).isNotNull();

        log.info("Step 9: Continue battle after reconnection");
        battleEngine.endTurn(context.getBattleId());

        BattleContext finalBattleState = battleEngine.getBattle(context.getBattleId());
        assertThat(finalBattleState).isNotNull();
        assertThat(finalBattleState.getStatus()).isIn(
                BattleStatus.ENEMY_TURN, BattleStatus.PLAYER_TURN, BattleStatus.VICTORY
        );

        log.info("✅ Player disconnect/reconnect during battle test completed successfully!");
    }

    @Test
    @DisplayName("Player disconnects and reconnect timeout - should be removed from room")
    void playerDisconnects_Timeout_ShouldBeRemoved() throws Exception {
        log.info("Starting: Player disconnect timeout test");

        createSchema();

        Room room = roomManager.createRoom("Timeout Test Room", "player1", 4, false, null);
        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        Player player2 = TestDataBuilder.createMagePlayer("player2");

        roomManager.joinRoom(room.getRoomId(), player1, null);
        roomManager.joinRoom(room.getRoomId(), player2, null);
        redisRoomStateManager.saveRoom(room);

        assertThat(room.getPlayers()).hasSize(2);

        log.info("Player 2 disconnects");
        roomManager.setPlayerDisconnected("player2");
        redisRoomStateManager.saveRoom(room);

        assertThat(room.getDisconnectTimes()).containsKey("player2");
        long disconnectTime = room.getDisconnectTimes().get("player2");

        log.info("Manually expire the disconnect (simulating timeout)");
        room.getDisconnectTimes().put("player2", disconnectTime - 120000);

        boolean reconnected = roomManager.setPlayerReconnected("player2", room.getRoomId());

        assertThat(reconnected).isFalse();
        log.info("Player 2 failed to reconnect due to timeout as expected");

        log.info("Cleaning up expired disconnects");
        roomManager.cleanupEmptyRooms();

        assertThat(room.getPlayers()).hasSize(1);
        assertThat(room.getPlayer("player2")).isNull();
        assertThat(room.getPlayer("player1")).isNotNull();

        redisRoomStateManager.saveRoom(room);
        Room finalRoom = redisRoomStateManager.getRoom(room.getRoomId());
        assertThat(finalRoom.getPlayers()).hasSize(1);

        log.info("✅ Player disconnect timeout test completed successfully!");
    }

    @Test
    @DisplayName("Multiple players disconnect and reconnect concurrently - should maintain consistency")
    void multiplePlayersDisconnectReconnectConcurrently_ShouldBeConsistent() throws Exception {
        log.info("Starting: Concurrent multiple disconnect/reconnect test");

        createSchema();

        int playerCount = 6;
        Room room = roomManager.createRoom("Concurrent Room", "player0", playerCount, false, null);

        for (int i = 0; i < playerCount; i++) {
            Player player = TestDataBuilder.createWarriorPlayer("player" + i);
            roomManager.joinRoom(room.getRoomId(), player, null);
        }
        redisRoomStateManager.saveRoom(room);

        assertThat(room.getPlayers()).hasSize(playerCount);
        assertThat(room.isAllOnline()).isTrue();

        log.info("Starting concurrent disconnect/reconnect operations...");

        ExecutorService executor = Executors.newFixedThreadPool(12);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(playerCount * 2);
        AtomicInteger errorCount = new AtomicInteger(0);
        AtomicInteger disconnectCount = new AtomicInteger(0);
        AtomicInteger reconnectCount = new AtomicInteger(0);

        for (int i = 0; i < playerCount; i++) {
            final int playerIndex = i;

            executor.submit(() -> {
                try {
                    startLatch.await();
                    roomManager.setPlayerDisconnected("player" + playerIndex);
                    disconnectCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Error disconnecting player{}", playerIndex, e);
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    startLatch.await();
                    Thread.sleep(50 + (playerIndex * 10));
                    roomManager.setPlayerReconnected("player" + playerIndex, room.getRoomId());
                    reconnectCount.incrementAndGet();
                } catch (Exception e) {
                    log.error("Error reconnecting player{}", playerIndex, e);
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(errorCount.get()).isEqualTo(0);
        assertThat(disconnectCount.get()).isEqualTo(playerCount);
        assertThat(reconnectCount.get()).isEqualTo(playerCount);

        redisRoomStateManager.saveRoom(room);

        Room finalRoom = redisRoomStateManager.getRoom(room.getRoomId());
        assertThat(finalRoom).isNotNull();
        assertThat(finalRoom.getPlayers()).hasSize(playerCount);
        assertThat(finalRoom.isAllOnline()).isTrue();
        assertThat(finalRoom.getDisconnectTimes()).isEmpty();

        for (int i = 0; i < playerCount; i++) {
            Player player = finalRoom.getPlayer("player" + i);
            assertThat(player).isNotNull();
            assertThat(player.isOnline()).isTrue();
            assertThat(redisRoomStateManager.isPlayerOnline("player" + i)).isTrue();
        }

        log.info("✅ Concurrent multiple disconnect/reconnect test completed successfully!");
        executor.shutdown();
    }

    @Test
    @DisplayName("Battle turn timeout - should auto-manage disconnected player")
    void battleTurnTimeout_ShouldAutoManageDisconnectedPlayer() throws Exception {
        log.info("Starting: Battle turn timeout auto-manage test");

        createSchema();

        Room room = roomManager.createRoom("Timeout Battle Room", "player1", 3, false, null);
        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        Player player2 = TestDataBuilder.createMagePlayer("player2");
        Player player3 = TestDataBuilder.createRoguePlayer("player3");

        roomManager.joinRoom(room.getRoomId(), player1, null);
        roomManager.joinRoom(room.getRoomId(), player2, null);
        roomManager.joinRoom(room.getRoomId(), player3, null);

        for (Player player : room.getPlayers()) {
            deckManager.initializeStartingDeck(player);
            deckManager.prepareForBattle(player);
        }

        Enemy enemy = TestDataBuilder.createGoblinEnemy("enemy1");
        enemyAIService.applyDifficultyScaling(enemy, 1);

        BattleContext context = battleEngine.startBattle(room.getRoomId(), 1, room.getPlayers(), List.of(enemy));
        battleLogService.startBattleLogging(context);

        for (Player player : context.getPlayers()) {
            deckManager.drawCards(player, 5);
        }

        int initialTurn = context.getCurrentTurn();
        int initialRound = context.getCurrentRound();

        log.info("Step 1: Player 2 disconnects during their turn");
        Player currentPlayer = context.getCurrentActorPlayer();
        log.info("Current actor before disconnect: {}", currentPlayer.getName());

        roomManager.setPlayerDisconnected("player2");
        redisRoomStateManager.saveRoom(room);

        Player disconnectedPlayer = room.getPlayer("player2");
        assertThat(disconnectedPlayer.isOnline()).isFalse();

        log.info("Step 2: Save battle state for timeout recovery");
        BattleContext battleBeforeTimeout = battleEngine.getBattle(context.getBattleId());
        int turnBeforeTimeout = battleBeforeTimeout.getCurrentTurn();
        int player2HpBefore = battleBeforeTimeout.getPlayer("player2").getCurrentHp();
        int player2EnergyBefore = battleBeforeTimeout.getPlayer("player2").getEnergy();

        log.info("Battle state before timeout: Turn {}, Player2 HP: {}, Energy: {}",
                turnBeforeTimeout, player2HpBefore, player2EnergyBefore);

        log.info("Step 3: Simulate auto-end turn for disconnected player");
        battleEngine.endTurn(context.getBattleId());

        BattleContext battleAfterTimeout = battleEngine.getBattle(context.getBattleId());
        assertThat(battleAfterTimeout).isNotNull();

        log.info("Battle state after auto-end: Turn {}, Status: {}",
                battleAfterTimeout.getCurrentTurn(), battleAfterTimeout.getStatus());

        assertThat(battleAfterTimeout.getCurrentTurn()).isGreaterThanOrEqualTo(turnBeforeTimeout);

        log.info("Step 4: Player 2 reconnects");
        boolean reconnected = roomManager.setPlayerReconnected("player2", room.getRoomId());
        assertThat(reconnected).isTrue();

        Player reconnectedPlayer = battleAfterTimeout.getPlayer("player2");
        assertThat(reconnectedPlayer).isNotNull();
        assertThat(reconnectedPlayer.getCurrentHp()).isEqualTo(player2HpBefore);

        log.info("Step 5: Verify battle can continue normally after reconnect");
        Player currentAfterReconnect = battleAfterTimeout.getCurrentActorPlayer();
        assertThat(currentAfterReconnect).isNotNull();
        assertThat(currentAfterReconnect.isOnline()).isTrue();

        if (currentAfterReconnect.getCurrentHand().size() > 0) {
            Card card = currentAfterReconnect.getCurrentHand().get(0);
            BattleAction action = battleEngine.playCard(
                    context.getBattleId(),
                    currentAfterReconnect.getPlayerId(),
                    card.getCardId(),
                    List.of(enemy.getId())
            );
            battleLogService.logAction(context.getBattleId(), action);
            assertThat(action).isNotNull();
        }

        battleEngine.endTurn(context.getBattleId());

        BattleContext finalBattle = battleEngine.getBattle(context.getBattleId());
        assertThat(finalBattle).isNotNull();
        assertThat(finalBattle.getStatus()).isIn(
                BattleStatus.ENEMY_TURN, BattleStatus.PLAYER_TURN, BattleStatus.VICTORY
        );

        log.info("✅ Battle turn timeout auto-manage test completed successfully!");
    }

    @Test
    @DisplayName("Reconnect - should restore from Redis snapshot correctly")
    void reconnect_ShouldRestoreFromRedisSnapshot() throws Exception {
        log.info("Starting: Reconnect from Redis snapshot test");

        createSchema();

        saveService.createPlayerProfile("player1", "player1", "Player1");
        saveService.createPlayerProfile("player2", "player2", "Player2");
        saveService.createPlayerProfile("player3", "player3", "Player3");

        log.info("Step 1: Create and populate room");
        Room room = roomManager.createRoom("Snapshot Test Room", "player1", 4, false, null);
        roomManager.updateRoomStatus(room.getRoomId(), RoomStatus.BATTLE);
        room.setCurrentFloor(5);
        room.setMapSeed(987654321L);

        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        Player player2 = TestDataBuilder.createMagePlayer("player2");
        Player player3 = TestDataBuilder.createRoguePlayer("player3");

        player1.setCurrentHp(45);
        player1.setEnergy(2);
        player1.setBlock(8);

        player2.setCurrentHp(60);
        player2.setEnergy(3);
        player2.setBlock(0);

        player3.setCurrentHp(35);
        player3.setEnergy(1);
        player3.setBlock(15);

        deckManager.initializeStartingDeck(player1);
        deckManager.initializeStartingDeck(player2);
        deckManager.initializeStartingDeck(player3);
        deckManager.drawCards(player1, 5);
        deckManager.drawCards(player2, 3);
        deckManager.drawCards(player3, 5);

        roomManager.joinRoom(room.getRoomId(), player1, null);
        roomManager.joinRoom(room.getRoomId(), player2, null);
        roomManager.joinRoom(room.getRoomId(), player3, null);

        log.info("Step 2: Save state to Redis");
        redisRoomStateManager.saveRoom(room);

        log.info("Step 3: Player 2 disconnects");
        roomManager.setPlayerDisconnected("player2");
        redisRoomStateManager.saveRoom(room);

        assertThat(redisRoomStateManager.isPlayerOnline("player2")).isFalse();
        assertThat(redisRoomStateManager.getOnlinePlayerCount()).isEqualTo(2);

        log.info("Step 4: Verify snapshot in Redis");
        Room snapshotRoom = redisRoomStateManager.getRoom(room.getRoomId());
        assertThat(snapshotRoom).isNotNull();
        assertThat(snapshotRoom.getStatus()).isEqualTo(RoomStatus.BATTLE);
        assertThat(snapshotRoom.getCurrentFloor()).isEqualTo(5);
        assertThat(snapshotRoom.getMapSeed()).isEqualTo(987654321L);
        assertThat(snapshotRoom.getDisconnectTimes()).containsKey("player2");

        Player snapshotPlayer2 = snapshotRoom.getPlayer("player2");
        assertThat(snapshotPlayer2).isNotNull();
        assertThat(snapshotPlayer2.isOnline()).isFalse();
        assertThat(snapshotPlayer2.getCurrentHp()).isEqualTo(60);
        assertThat(snapshotPlayer2.getEnergy()).isEqualTo(3);
        assertThat(snapshotPlayer2.getCurrentHand()).hasSize(3);

        log.info("Step 5: Player 2 reconnects");
        roomManager.setPlayerReconnected("player2", room.getRoomId());
        redisRoomStateManager.saveRoom(room);

        log.info("Step 6: Verify restored state");
        Room restoredRoom = redisRoomStateManager.getRoom(room.getRoomId());
        assertThat(restoredRoom).isNotNull();
        assertThat(restoredRoom.getStatus()).isEqualTo(RoomStatus.BATTLE);
        assertThat(restoredRoom.getCurrentFloor()).isEqualTo(5);
        assertThat(restoredRoom.getMapSeed()).isEqualTo(987654321L);
        assertThat(restoredRoom.isAllOnline()).isTrue();
        assertThat(restoredRoom.getDisconnectTimes()).isEmpty();

        Player restoredPlayer1 = restoredRoom.getPlayer("player1");
        Player restoredPlayer2 = restoredRoom.getPlayer("player2");
        Player restoredPlayer3 = restoredRoom.getPlayer("player3");

        assertThat(restoredPlayer1).isNotNull();
        assertThat(restoredPlayer1.getCurrentHp()).isEqualTo(45);
        assertThat(restoredPlayer1.getEnergy()).isEqualTo(2);
        assertThat(restoredPlayer1.getBlock()).isEqualTo(8);
        assertThat(restoredPlayer1.getCurrentHand()).hasSize(5);

        assertThat(restoredPlayer2).isNotNull();
        assertThat(restoredPlayer2.getCurrentHp()).isEqualTo(60);
        assertThat(restoredPlayer2.getEnergy()).isEqualTo(3);
        assertThat(restoredPlayer2.getBlock()).isEqualTo(0);
        assertThat(restoredPlayer2.getCurrentHand()).hasSize(3);
        assertThat(restoredPlayer2.isOnline()).isTrue();

        assertThat(restoredPlayer3).isNotNull();
        assertThat(restoredPlayer3.getCurrentHp()).isEqualTo(35);
        assertThat(restoredPlayer3.getEnergy()).isEqualTo(1);
        assertThat(restoredPlayer3.getBlock()).isEqualTo(15);
        assertThat(restoredPlayer3.getCurrentHand()).hasSize(5);

        assertThat(redisRoomStateManager.isPlayerOnline("player1")).isTrue();
        assertThat(redisRoomStateManager.isPlayerOnline("player2")).isTrue();
        assertThat(redisRoomStateManager.isPlayerOnline("player3")).isTrue();
        assertThat(redisRoomStateManager.getOnlinePlayerCount()).isEqualTo(3);

        log.info("Step 7: Create game save with restored state");
        GameSave save = saveService.createSave(room.getRoomId(), "player1", restoredRoom.getPlayers());
        save.setCurrentFloor(5);
        save.setMapSeed(987654321L);
        save.setScore(500);
        saveService.updateSave(save);

        GameSave retrievedSave = saveService.getSave(save.getSaveId());
        assertThat(retrievedSave).isNotNull();
        assertThat(retrievedSave.getCurrentFloor()).isEqualTo(5);
        assertThat(retrievedSave.getPlayerIds()).containsExactlyInAnyOrder("player1", "player2", "player3");

        log.info("✅ Reconnect from Redis snapshot test completed successfully!");
    }

    @Test
    @DisplayName("All players disconnect - room should be cleaned up properly")
    void allPlayersDisconnect_RoomShouldBeCleanedUp() throws Exception {
        log.info("Starting: All players disconnect test");

        createSchema();

        Room room = roomManager.createRoom("Empty Room", "player1", 3, false, null);
        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        Player player2 = TestDataBuilder.createMagePlayer("player2");

        roomManager.joinRoom(room.getRoomId(), player1, null);
        roomManager.joinRoom(room.getRoomId(), player2, null);
        redisRoomStateManager.saveRoom(room);

        assertThat(redisRoomStateManager.getRoom(room.getRoomId())).isNotNull();
        assertThat(redisRoomStateManager.getOnlinePlayerCount()).isEqualTo(2);

        log.info("All players disconnect");
        roomManager.setPlayerDisconnected("player1");
        roomManager.setPlayerDisconnected("player2");
        redisRoomStateManager.saveRoom(room);

        assertThat(redisRoomStateManager.getOnlinePlayerCount()).isEqualTo(0);
        assertThat(room.getOnlinePlayerCount()).isEqualTo(0);

        log.info("Expire disconnects and trigger cleanup");
        for (String playerId : List.of("player1", "player2")) {
            room.getDisconnectTimes().put(playerId, System.currentTimeMillis() - 120000);
        }

        roomManager.cleanupEmptyRooms();
        redisRoomStateManager.deleteRoom(room.getRoomId());

        assertThat(room.getPlayers()).isEmpty();
        assertThat(redisRoomStateManager.getRoom(room.getRoomId())).isNull();
        assertThat(redisRoomStateManager.getPlayerRoomId("player1")).isNull();
        assertThat(redisRoomStateManager.getOnlinePlayerCount()).isEqualTo(0);

        log.info("✅ All players disconnect test completed successfully!");
    }
}
