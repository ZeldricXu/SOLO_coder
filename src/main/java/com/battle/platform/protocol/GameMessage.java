package com.battle.platform.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameMessage {
    private int msgId;
    private int msgType;
    private long playerId;
    private long timestamp;
    private byte[] payload;

    public static final int TYPE_REQUEST = 1;
    public static final int TYPE_RESPONSE = 2;
    public static final int TYPE_PUSH = 3;

    public static final int MSG_MATCH_JOIN = 1001;
    public static final int MSG_MATCH_LEAVE = 1002;
    public static final int MSG_MATCH_RESULT = 1003;
    public static final int MSG_BATTLE_JOIN = 2001;
    public static final int MSG_BATTLE_LEAVE = 2002;
    public static final int MSG_MOVE = 2003;
    public static final int MSG_SKILL_CAST = 2004;
    public static final int MSG_DAMAGE = 2005;
    public static final int MSG_DEATH = 2006;
    public static final int MSG_RESPAWN = 2007;
    public static final int MSG_CAPTURE = 2008;
    public static final int MSG_BATTLE_STATE = 2009;
    public static final int MSG_SCORE_UPDATE = 3001;
    public static final int MSG_RANK_UPDATE = 3002;
    public static final int MSG_REPLAY_DATA = 4001;
    public static final int MSG_HEARTBEAT = 9001;
    public static final int MSG_ERROR = 9999;
}
