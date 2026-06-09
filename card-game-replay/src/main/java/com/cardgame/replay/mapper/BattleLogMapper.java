package com.cardgame.replay.mapper;

import com.cardgame.replay.entity.BattleLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface BattleLogMapper {

    @Insert("INSERT INTO battle_logs (battle_log_id, battle_id, room_id, save_id, floor, seed, " +
            "initial_player_states, initial_enemy_states, actions, result, start_time, end_time, " +
            "duration_ms, total_turns, total_rounds, stats, version) VALUES " +
            "(#{battleLogId}, #{battleId}, #{roomId}, #{saveId}, #{floor}, #{seed}, " +
            "#{initialPlayerStates, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "#{initialEnemyStates, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "#{actions, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "#{result, typeHandler=org.apache.ibatis.type.EnumTypeHandler}, " +
            "#{startTime}, #{endTime}, #{durationMs}, #{totalTurns}, #{totalRounds}, " +
            "#{stats, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, #{version})")
    void insertBattleLog(BattleLog log);

    @Select("SELECT * FROM battle_logs WHERE battle_log_id = #{battleLogId}")
    @Results({
            @Result(column = "initial_player_states", property = "initialPlayerStates",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "initial_enemy_states", property = "initialEnemyStates",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "actions", property = "actions",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "stats", property = "stats",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "result", property = "result",
                    typeHandler = org.apache.ibatis.type.EnumTypeHandler.class)
    })
    BattleLog findBattleLogById(@Param("battleLogId") String battleLogId);

    @Select("SELECT * FROM battle_logs WHERE battle_id = #{battleId}")
    @Results({
            @Result(column = "initial_player_states", property = "initialPlayerStates",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "initial_enemy_states", property = "initialEnemyStates",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "actions", property = "actions",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "stats", property = "stats",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "result", property = "result",
                    typeHandler = org.apache.ibatis.type.EnumTypeHandler.class)
    })
    BattleLog findBattleLogByBattleId(@Param("battleId") String battleId);

    @Select("SELECT * FROM battle_logs WHERE save_id = #{saveId} ORDER BY start_time DESC LIMIT #{limit}")
    @Results({
            @Result(column = "initial_player_states", property = "initialPlayerStates",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "initial_enemy_states", property = "initialEnemyStates",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "actions", property = "actions",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "stats", property = "stats",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "result", property = "result",
                    typeHandler = org.apache.ibatis.type.EnumTypeHandler.class)
    })
    List<BattleLog> findBattleLogsBySaveId(@Param("saveId") String saveId, @Param("limit") int limit);

    @Select("SELECT * FROM battle_logs WHERE room_id = #{roomId} ORDER BY start_time DESC LIMIT #{limit}")
    @Results({
            @Result(column = "initial_player_states", property = "initialPlayerStates",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "initial_enemy_states", property = "initialEnemyStates",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "actions", property = "actions",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "stats", property = "stats",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "result", property = "result",
                    typeHandler = org.apache.ibatis.type.EnumTypeHandler.class)
    })
    List<BattleLog> findBattleLogsByRoomId(@Param("roomId") String roomId, @Param("limit") int limit);

    @Select("SELECT stats FROM battle_logs WHERE result = 'VICTORY' ORDER BY start_time DESC LIMIT 1000")
    @Results({
            @Result(column = "stats", property = "stats",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class)
    })
    List<java.util.Map<String, Object>> findVictoryStatsForAnalysis(@Param("limit") int limit);
}
