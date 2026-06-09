package com.cardgame.map.service;

import com.cardgame.common.enums.MessageType;
import com.cardgame.common.protocol.GameMessage;
import com.cardgame.common.utils.JsonUtils;
import com.cardgame.map.entity.GameMap;
import com.cardgame.netty.dispatcher.MessageHandler;
import com.cardgame.netty.session.PlayerSession;
import com.cardgame.room.manager.RedisRoomStateManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MapNodeSelectHandler implements MessageHandler {

    @Autowired
    private MapService mapService;

    @Autowired
    private RedisRoomStateManager redisRoomStateManager;

    @Autowired
    private MapSyncService mapSyncService;

    @Override
    public MessageType getType() {
        return MessageType.MAP_NODE_SELECT_REQ;
    }

    @Override
    public void handle(PlayerSession session, GameMessage request) throws Exception {
        String playerId = session.getPlayerId();
        String roomId = redisRoomStateManager.getPlayerRoomId(playerId);

        if (roomId == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Not in a room", null));
            return;
        }

        com.cardgame.common.protocol.request.MapNodeSelectRequest req = JsonUtils.fromJson(
                request.getData(), 
                com.cardgame.common.protocol.request.MapNodeSelectRequest.class
        );

        if (req == null || req.getNodeId() == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Invalid request", null));
            return;
        }

        GameMap updatedMap = mapService.selectNode(roomId, req.getNodeId());
        if (updatedMap == null) {
            session.getChannel().writeAndFlush(GameMessage.createResponse(request, 400, "Cannot move to node", null));
            return;
        }

        session.getChannel().writeAndFlush(
                GameMessage.createResponse(request, 0, "success", JsonUtils.toJson(updatedMap))
        );

        mapSyncService.syncMapUpdate(roomId, updatedMap);
    }
}
