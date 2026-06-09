package com.cardgame.save.mapper;

import com.cardgame.save.entity.GameSave;
import com.cardgame.save.entity.PlayerProfile;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface SaveMapper {

    @Insert("INSERT INTO game_saves (save_id, room_id, host_player_id, player_ids, player_states, " +
            "player_decks, game_map, current_floor, score, gold, seed, created_at, updated_at, " +
            "play_time_seconds, progress_data, locked, locked_by, locked_at, completed, victory, " +
            "difficulty, version) VALUES (#{saveId}, #{roomId}, #{hostPlayerId}, #{playerIds, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "#{playerStates, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, #{playerDecks, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "#{gameMap, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, #{currentFloor}, #{score}, #{gold}, #{seed}, " +
            "#{createdAt}, #{updatedAt}, #{playTimeSeconds}, #{progressData, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "#{locked}, #{lockedBy}, #{lockedAt}, #{completed}, #{victory}, #{difficulty}, #{version})")
    void insertGameSave(GameSave save);

    @Update("UPDATE game_saves SET player_states = #{playerStates, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "player_decks = #{playerDecks, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "game_map = #{gameMap, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "current_floor = #{currentFloor}, score = #{score}, gold = #{gold}, " +
            "updated_at = #{updatedAt}, play_time_seconds = #{playTimeSeconds}, " +
            "progress_data = #{progressData, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "locked = #{locked}, locked_by = #{lockedBy}, locked_at = #{lockedAt}, " +
            "completed = #{completed}, victory = #{victory} WHERE save_id = #{saveId}")
    void updateGameSave(GameSave save);

    @Select("SELECT * FROM game_saves WHERE save_id = #{saveId}")
    @Results({
            @Result(column = "player_ids", property = "playerIds", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "player_states", property = "playerStates", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "player_decks", property = "playerDecks", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "game_map", property = "gameMap", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "progress_data", property = "progressData", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class)
    })
    GameSave findGameSaveById(@Param("saveId") String saveId);

    @Select("SELECT * FROM game_saves WHERE room_id = #{roomId} AND completed = 0 ORDER BY created_at DESC LIMIT 1")
    @Results({
            @Result(column = "player_ids", property = "playerIds", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "player_states", property = "playerStates", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "player_decks", property = "playerDecks", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "game_map", property = "gameMap", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "progress_data", property = "progressData", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class)
    })
    GameSave findLatestSaveByRoomId(@Param("roomId") String roomId);

    @Select("SELECT * FROM game_saves WHERE host_player_id = #{playerId} OR player_ids LIKE CONCAT('%', #{playerId}, '%') " +
            "ORDER BY updated_at DESC LIMIT 10")
    @Results({
            @Result(column = "player_ids", property = "playerIds", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "player_states", property = "playerStates", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "player_decks", property = "playerDecks", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "game_map", property = "gameMap", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "progress_data", property = "progressData", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class)
    })
    List<GameSave> findSavesByPlayerId(@Param("playerId") String playerId);

    @Update("UPDATE game_saves SET locked = 1, locked_by = #{playerId}, locked_at = #{lockedAt} WHERE save_id = #{saveId} AND locked = 0")
    int lockSave(@Param("saveId") String saveId, @Param("playerId") String playerId, @Param("lockedAt") long lockedAt);

    @Update("UPDATE game_saves SET locked = 0, locked_by = NULL, locked_at = 0 WHERE save_id = #{saveId}")
    void unlockSave(@Param("saveId") String saveId);

    @Insert("INSERT INTO player_profiles (player_id, username, nickname, level, experience, " +
            "total_play_time_seconds, total_games_played, total_wins, highest_floor_reached, " +
            "total_gold_earned, unlocked_card_ids, achievements, stats, created_at, " +
            "last_login_at, online, current_save_id, current_room_id) VALUES " +
            "(#{playerId}, #{username}, #{nickname}, #{level}, #{experience}, " +
            "#{totalPlayTimeSeconds}, #{totalGamesPlayed}, #{totalWins}, #{highestFloorReached}, " +
            "#{totalGoldEarned}, #{unlockedCardIds, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "#{achievements, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "#{stats, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "#{createdAt}, #{lastLoginAt}, #{online}, #{currentSaveId}, #{currentRoomId})")
    void insertPlayerProfile(PlayerProfile profile);

    @Update("UPDATE player_profiles SET username = #{username}, nickname = #{nickname}, " +
            "level = #{level}, experience = #{experience}, total_play_time_seconds = #{totalPlayTimeSeconds}, " +
            "total_games_played = #{totalGamesPlayed}, total_wins = #{totalWins}, " +
            "highest_floor_reached = #{highestFloorReached}, total_gold_earned = #{totalGoldEarned}, " +
            "unlocked_card_ids = #{unlockedCardIds, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "achievements = #{achievements, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "stats = #{stats, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "last_login_at = #{lastLoginAt}, online = #{online}, " +
            "current_save_id = #{currentSaveId}, current_room_id = #{currentRoomId} " +
            "WHERE player_id = #{playerId}")
    void updatePlayerProfile(PlayerProfile profile);

    @Select("SELECT * FROM player_profiles WHERE player_id = #{playerId}")
    @Results({
            @Result(column = "unlocked_card_ids", property = "unlockedCardIds", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "achievements", property = "achievements", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "stats", property = "stats", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class)
    })
    PlayerProfile findPlayerProfileById(@Param("playerId") String playerId);

    @Select("SELECT * FROM player_profiles WHERE username = #{username}")
    @Results({
            @Result(column = "unlocked_card_ids", property = "unlockedCardIds", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "achievements", property = "achievements", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "stats", property = "stats", typeHandler = com.cardgame.save.handler.JsonTypeHandler.class)
    })
    PlayerProfile findPlayerProfileByUsername(@Param("username") String username);
}
