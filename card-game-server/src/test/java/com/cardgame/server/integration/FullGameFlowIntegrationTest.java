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
import com.cardgame.common.enums.PlayerClass;
import com.cardgame.common.enums.RoomStatus;
import com.cardgame.deck.DeckManager;
import com.cardgame.map.entity.GameMap;
import com.cardgame.map.generator.MapGenerator;
import com.cardgame.replay.entity.BattleLog;
import com.cardgame.replay.service.BattleLogService;
import com.cardgame.room.entity.Room;
import com.cardgame.room.manager.RedisRoomStateManager;
import com.cardgame.room.manager.RoomManager;
import com.cardgame.save.entity.GameSave;
import com.cardgame.save.entity.PlayerProfile;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Slf4j
@DisplayName("Full Game Flow Integration Tests")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class FullGameFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private RedisRoomStateManager redisRoomStateManager;

    @Autowired
    private MapGenerator mapGenerator;

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
        log.info("Setting up integration test...");
    }

    @Test
    @DisplayName("Full game flow - create room, join players, generate map, battle, save, replay")
    void fullGameFlow_ShouldCompleteSuccessfully() throws Exception {
        log.info("Starting full game flow integration test");

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
        log.info("Database schema created");

        log.info("Step 1: Creating player profiles");
        PlayerProfile profile1 = saveService.createPlayerProfile("player1", "player1", "WarriorOne");
        PlayerProfile profile2 = saveService.createPlayerProfile("player2", "player2", "MageTwo");
        assertThat(profile1).isNotNull();
        assertThat(profile2).isNotNull();
        log.info("Player profiles created: {}, {}", profile1.getPlayerId(), profile2.getPlayerId());

        log.info("Step 2: Creating room");
        Room room = roomManager.createRoom("Adventure Room", "player1", 4, false, null);
        assertThat(room).isNotNull();
        assertThat(room.getRoomId()).isNotEmpty();
        assertThat(room.getStatus()).isEqualTo(RoomStatus.WAITING);
        log.info("Room created: {}", room.getRoomId());

        log.info("Step 3: Adding players to room");
        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        Player player2 = TestDataBuilder.createMagePlayer("player2");

        boolean joined1 = roomManager.joinRoom(room.getRoomId(), player1, null);
        boolean joined2 = roomManager.joinRoom(room.getRoomId(), player2, null);

        assertThat(joined1).isTrue();
        assertThat(joined2).isTrue();
        assertThat(room.getPlayers()).hasSize(2);
        log.info("Players joined room: {}, {}", player1.getPlayerId(), player2.getPlayerId());

        log.info("Step 4: Saving room state to Redis");
        redisRoomStateManager.saveRoom(room);

        Room retrievedRoom = redisRoomStateManager.getRoom(room.getRoomId());
        assertThat(retrievedRoom).isNotNull();
        assertThat(retrievedRoom.getRoomId()).isEqualTo(room.getRoomId());
        assertThat(retrievedRoom.getPlayers()).hasSize(2);
        log.info("Room state saved and retrieved from Redis successfully");

        log.info("Step 5: Generating game map");
        room.setMapSeed(123456789L);
        GameMap gameMap = mapGenerator.generateMap(room.getRoomId(), room.getMapSeed(), 15);

        assertThat(gameMap).isNotNull();
        assertThat(gameMap.getRoomId()).isEqualTo(room.getRoomId());
        assertThat(gameMap.getSeed()).isEqualTo(123456789L);
        assertThat(gameMap.getMaxFloors()).isEqualTo(15);
        assertThat(gameMap.getStartNode()).isNotNull();
        assertThat(gameMap.getBossNode()).isNotNull();
        log.info("Game map generated with {} floors, seed: {}", gameMap.getMaxFloors(), gameMap.getSeed());

        roomManager.updateRoomStatus(room.getRoomId(), RoomStatus.EXPLORING);
        room.setCurrentFloor(1);

        log.info("Step 6: Initializing player decks");
        for (Player player : room.getPlayers()) {
            deckManager.initializeStartingDeck(player);
            deckManager.prepareForBattle(player);
            assertThat(player.getDrawPile()).isNotEmpty();
            assertThat(player.getCurrentHand()).isEmpty();
            log.info("Player {} deck initialized: {} cards in draw pile", 
                    player.getName(), player.getDrawPile().size());
        }

        log.info("Step 7: Starting battle");
        Enemy enemy1 = TestDataBuilder.createGoblinEnemy("enemy1");
        Enemy enemy2 = TestDataBuilder.createSkeletonEnemy("enemy2");
        enemyAIService.applyDifficultyScaling(enemy1, 1);
        enemyAIService.applyDifficultyScaling(enemy2, 1);

        List<Enemy> enemies = List.of(enemy1, enemy2);
        BattleContext context = battleEngine.startBattle(room.getRoomId(), 1, room.getPlayers(), enemies);

        assertThat(context).isNotNull();
        assertThat(context.getBattleId()).isNotEmpty();
        assertThat(context.getStatus()).isEqualTo(BattleStatus.PLAYER_TURN);
        assertThat(context.getPlayers()).hasSize(2);
        assertThat(context.getEnemies()).hasSize(2);
        assertThat(context.getCurrentRound()).isEqualTo(1);
        log.info("Battle started: {}", context.getBattleId());

        log.info("Step 8: Starting battle logging");
        battleLogService.startBattleLogging(context);

        log.info("Step 9: Player 1 draws cards");
        for (Player player : context.getPlayers()) {
            deckManager.drawCards(player, 5);
            assertThat(player.getCurrentHand()).hasSize(5);
            assertThat(player.getDrawPile().size() + player.getCurrentHand().size() + player.getDiscardPile().size())
                    .isEqualTo(player.getMasterDeck().size());
            log.info("Player {} drew 5 cards, hand size: {}", player.getName(), player.getCurrentHand().size());
        }

        log.info("Step 10: Player 1 plays attack card");
        Player currentPlayer = context.getCurrentActorPlayer();
        assertThat(currentPlayer).isNotNull();
        assertThat(currentPlayer.getCurrentEnergy()).isEqualTo(3);

        Card attackCard = currentPlayer.getCurrentHand().stream()
                .filter(c -> "Strike".equals(c.getName()))
                .findFirst()
                .orElse(currentPlayer.getCurrentHand().get(0));

        assertThat(attackCard).isNotNull();
        log.info("Player {} playing card: {} (cost: {})", currentPlayer.getName(), 
                attackCard.getName(), attackCard.getCost());

        int initialEnergy = currentPlayer.getCurrentEnergy();
        int initialEnemyHp = enemy1.getCurrentHp();

        BattleAction playAction = battleEngine.playCard(
                context.getBattleId(),
                currentPlayer.getPlayerId(),
                attackCard.getCardId(),
                List.of(enemy1.getId())
        );

        assertThat(playAction).isNotNull();
        assertThat(playAction.isPlayerAction()).isTrue();
        assertThat(currentPlayer.getCurrentEnergy()).isEqualTo(initialEnergy - attackCard.getCost());
        assertThat(enemy1.getCurrentHp()).isLessThan(initialEnemyHp);
        log.info("Card played successfully! Damage dealt: {}, Enemy HP: {} -> {}",
                playAction.getDamageDealt(), initialEnemyHp, enemy1.getCurrentHp());

        battleLogService.logAction(context.getBattleId(), playAction);

        log.info("Step 11: Ending player turn, executing enemy AI");
        battleEngine.endTurn(context.getBattleId());

        assertThat(context.getStatus()).isIn(BattleStatus.ENEMY_TURN, BattleStatus.PLAYER_TURN, BattleStatus.VICTORY);
        log.info("Turn ended, current battle status: {}", context.getStatus());

        for (BattleAction action : context.getActionHistory()) {
            battleLogService.logAction(context.getBattleId(), action);
        }

        log.info("Step 12: Simulating battle completion (enemies defeated)");
        for (Enemy enemy : context.getEnemies()) {
            enemy.setCurrentHp(0);
        }

        context.setStatus(BattleStatus.VICTORY);
        context.setEndTime(System.currentTimeMillis());

        BattleLog battleLog = battleLogService.endBattleLogging(context.getBattleId(), context);

        assertThat(battleLog).isNotNull();
        assertThat(battleLog.getBattleId()).isEqualTo(context.getBattleId());
        assertThat(battleLog.getResult()).isEqualTo(BattleStatus.VICTORY);
        assertThat(battleLog.getActions()).isNotEmpty();
        log.info("Battle ended with victory! {} actions logged", battleLog.getActions().size());

        log.info("Step 13: Saving battle log to database");
        battleLogService.saveBattleLog(battleLog);

        await().atMost(5, TimeUnit.SECONDS)
                .pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    BattleLog savedLog = battleLogService.getBattleLog(battleLog.getBattleLogId());
                    assertThat(savedLog).isNotNull();
                    assertThat(savedLog.getBattleId()).isEqualTo(context.getBattleId());
                });

        log.info("Battle log saved to database successfully");

        log.info("Step 14: Creating game save");
        GameSave gameSave = saveService.createSave(room.getRoomId(), "player1", room.getPlayers());
        assertThat(gameSave).isNotNull();
        assertThat(gameSave.getSaveId()).isNotEmpty();
        assertThat(gameSave.getRoomId()).isEqualTo(room.getRoomId());
        assertThat(gameSave.getPlayerIds()).hasSize(2);
        log.info("Game save created: {}", gameSave.getSaveId());

        gameSave.setCurrentFloor(1);
        gameSave.setScore(100);
        gameSave.setMapSeed(room.getMapSeed());
        saveService.updateSave(gameSave);

        log.info("Step 15: Verifying save in database");
        GameSave retrievedSave = saveService.getSave(gameSave.getSaveId());
        assertThat(retrievedSave).isNotNull();
        assertThat(retrievedSave.getSaveId()).isEqualTo(gameSave.getSaveId());
        assertThat(retrievedSave.getPlayerIds()).containsExactlyInAnyOrder("player1", "player2");
        assertThat(retrievedSave.getCurrentFloor()).isEqualTo(1);
        assertThat(retrievedSave.getScore()).isEqualTo(100);
        log.info("Game save retrieved and verified successfully");

        log.info("Step 16: Completing save with victory");
        saveService.completeSave(gameSave.getSaveId(), true, 1000, "player1");

        GameSave completedSave = saveService.getSave(gameSave.getSaveId());
        assertThat(completedSave.isCompleted()).isTrue();
        assertThat(completedSave.isVictory()).isTrue();
        assertThat(completedSave.getScore()).isEqualTo(1000);
        log.info("Game save completed with victory, score: {}", completedSave.getScore());

        log.info("Step 17: Verifying player profiles updated");
        PlayerProfile updatedProfile1 = saveService.getPlayerProfile("player1");
        PlayerProfile updatedProfile2 = saveService.getPlayerProfile("player2");

        assertThat(updatedProfile1).isNotNull();
        assertThat(updatedProfile1.getTotalGamesPlayed()).isEqualTo(1);
        assertThat(updatedProfile1.getTotalWins()).isEqualTo(1);
        assertThat(updatedProfile1.getHighestFloorReached()).isEqualTo(1);
        assertThat(updatedProfile1.getExperience()).isGreaterThan(0);

        assertThat(updatedProfile2).isNotNull();
        assertThat(updatedProfile2.getTotalGamesPlayed()).isEqualTo(1);
        assertThat(updatedProfile2.getTotalWins()).isEqualTo(1);
        log.info("Player profiles updated successfully");

        log.info("Step 18: Verifying replay data is queryable");
        List<BattleLog> roomLogs = battleLogService.getBattleLogsForRoom(room.getRoomId(), 10);
        assertThat(roomLogs).isNotEmpty();
        assertThat(roomLogs.get(0).getBattleId()).isEqualTo(context.getBattleId());
        log.info("Replay data queryable: {} logs found for room", roomLogs.size());

        log.info("Step 19: Testing battle replay functionality");
        BattleLogService.BattleReplay replay = battleLogService.createReplay(battleLog.getBattleLogId());
        assertThat(replay).isNotNull();
        assertThat(replay.getTotalTurns()).isGreaterThan(0);
        assertThat(replay.getCurrentPlayerStates()).hasSize(2);
        assertThat(replay.getCurrentEnemyStates()).hasSize(2);
        log.info("Battle replay created successfully, total turns: {}", replay.getTotalTurns());

        log.info("✅ Full game flow integration test completed successfully!");
    }

    @Test
    @DisplayName("Map generation with seed - should produce reproducible results")
    void mapGeneration_WithSeed_ShouldBeReproducible() throws Exception {
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
            """;

        executeSqlScript(schemaSql);

        long seed = 987654321L;
        Room room = roomManager.createRoom("Test Room", "player1", 4, false, null);

        GameMap map1 = mapGenerator.generateMap(room.getRoomId(), seed, 10);
        GameMap map2 = mapGenerator.generateMap(room.getRoomId(), seed, 10);

        assertThat(map1.getSeed()).isEqualTo(map2.getSeed());
        assertThat(map1.getNodes().size()).isEqualTo(map2.getNodes().size());
        assertThat(map1.getStartNode().getNodeType()).isEqualTo(map2.getStartNode().getNodeType());
        assertThat(map1.getBossNode().getNodeType()).isEqualTo(map2.getBossNode().getNodeType());

        for (int i = 0; i < map1.getNodes().size(); i++) {
            var nodes1 = map1.getNodesAtFloor(i);
            var nodes2 = map2.getNodesAtFloor(i);
            assertThat(nodes1.size()).isEqualTo(nodes2.size());
            for (int j = 0; j < nodes1.size(); j++) {
                assertThat(nodes1.get(j).getNodeType()).isEqualTo(nodes2.get(j).getNodeType());
            }
        }

        log.info("Map generation reproducibility test passed with seed: {}", seed);
    }

    @Test
    @DisplayName("Redis room state persistence - should survive multiple operations")
    void redisRoomPersistence_ShouldSurviveOperations() throws Exception {
        Room room = roomManager.createRoom("Redis Test Room", "player1", 4, false, null);
        Player player1 = TestDataBuilder.createWarriorPlayer("player1");
        Player player2 = TestDataBuilder.createMagePlayer("player2");
        Player player3 = TestDataBuilder.createRoguePlayer("player3");

        roomManager.joinRoom(room.getRoomId(), player1, null);
        redisRoomStateManager.saveRoom(room);

        roomManager.joinRoom(room.getRoomId(), player2, null);
        redisRoomStateManager.saveRoom(room);

        Room retrieved1 = redisRoomStateManager.getRoom(room.getRoomId());
        assertThat(retrieved1.getPlayers()).hasSize(2);
        assertThat(redisRoomStateManager.isPlayerOnline("player1")).isTrue();
        assertThat(redisRoomStateManager.isPlayerOnline("player2")).isTrue();

        roomManager.setPlayerDisconnected("player2");
        redisRoomStateManager.saveRoom(room);

        assertThat(redisRoomStateManager.getPlayerRoomId("player1")).isEqualTo(room.getRoomId());
        assertThat(redisRoomStateManager.getOnlinePlayerCount()).isEqualTo(1);

        roomManager.setPlayerReconnected("player2", room.getRoomId());
        redisRoomStateManager.saveRoom(room);

        roomManager.joinRoom(room.getRoomId(), player3, null);
        redisRoomStateManager.saveRoom(room);

        Room finalRoom = redisRoomStateManager.getRoom(room.getRoomId());
        assertThat(finalRoom.getPlayers()).hasSize(3);
        assertThat(finalRoom.isAllOnline()).isTrue();
        assertThat(redisRoomStateManager.getOnlinePlayerCount()).isEqualTo(3);

        List<String> onlinePlayers = redisRoomStateManager.getOnlinePlayers();
        assertThat(onlinePlayers).containsExactlyInAnyOrder("player1", "player2", "player3");

        redisRoomStateManager.deleteRoom(room.getRoomId());

        Room deletedRoom = redisRoomStateManager.getRoom(room.getRoomId());
        assertThat(deletedRoom).isNull();
        assertThat(redisRoomStateManager.getPlayerRoomId("player1")).isNull();
        assertThat(redisRoomStateManager.getOnlinePlayerCount()).isEqualTo(0);

        log.info("Redis room persistence test completed successfully");
    }
}
