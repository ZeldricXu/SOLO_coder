package com.cardgame.battle.handler;

import com.cardgame.common.entity.BattleContext;
import com.cardgame.battle.engine.BattleEngine;
import com.cardgame.battle.service.BattleSyncService;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.EnemyTemplate;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.MessageType;
import com.cardgame.common.enums.RoomStatus;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.utils.JsonUtils;
import com.cardgame.netty.dispatcher.MessageHandler;
import com.cardgame.netty.session.PlayerSession;
import com.cardgame.room.entity.Room;
import com.cardgame.room.manager.RedisRoomStateManager;
import com.cardgame.room.manager.RoomManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class StartGameHandler implements MessageHandler {

    @Autowired
    private BattleEngine battleEngine;

    @Autowired
    private BattleSyncService battleSyncService;

    @Autowired
    private RoomManager roomManager;

    @Autowired
    private RedisRoomStateManager redisRoomStateManager;

    @Autowired
    private com.cardgame.ai.EnemyTemplateLibrary enemyTemplateLibrary;

    @Override
    public MessageType getType() {
        return MessageType.START_GAME_REQ;
    }

    @Override
    public void handle(PlayerSession session, GameMessage request) throws Exception {
        String playerId = session.getPlayerId();
        String roomId = redisRoomStateManager.getPlayerRoomId(playerId);

        if (roomId == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Not in a room", null));
            return;
        }

        Room room = roomManager.getRoom(roomId);
        if (room == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 404, "Room not found", null));
            return;
        }

        if (!room.getOwnerId().equals(playerId)) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 403, "Not room owner", null));
            return;
        }

        if (!room.isAllReady()) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Not all players ready", null));
            return;
        }

        if (room.getPlayers().size() < 1) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Need at least 1 player", null));
            return;
        }

        List<Player> players = new ArrayList<>(room.getPlayers());
        List<Enemy> enemies = createEnemies(1, players.size());

        roomManager.updateRoomStatus(roomId, RoomStatus.PLAYING);
        redisRoomStateManager.saveRoom(room);

        BattleContext context = battleEngine.startBattle(roomId, 1, players, enemies);

        log.info("Game started in room {} with {} players", roomId, players.size());

        session.getChannel().writeAndFlush(
                GameMessage.createResponse(request, 0, "success", JsonUtils.toJson(context))
        );

        battleSyncService.syncBattleStatus(context.getBattleId());
    }

    private List<Enemy> createEnemies(int floor, int playerCount) {
        List<Enemy> enemies = new ArrayList<>();
        int enemyCount = Math.min(3, playerCount + 1);

        List<EnemyTemplate> templates = enemyTemplateLibrary.getEnemiesForFloor(floor);
        java.util.Collections.shuffle(templates);

        for (int i = 0; i < Math.min(enemyCount, templates.size()); i++) {
            enemies.add(templates.get(i).createEnemy(floor));
        }

        return enemies;
    }
}
