package com.cardgame.rank.mapper;

import com.cardgame.rank.entity.DailyChallenge;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface DailyChallengeMapper {

    @Insert("INSERT INTO daily_challenges (challenge_id, date, seed, description, " +
            "difficulty, target_floor, score_multiplier, modifiers, start_time, end_time) VALUES " +
            "(#{challengeId}, #{date}, #{seed}, #{description}, #{difficulty}, " +
            "#{targetFloor}, #{scoreMultiplier}, " +
            "#{modifiers, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "#{startTime}, #{endTime})")
    void insertChallenge(DailyChallenge challenge);

    @Select("SELECT * FROM daily_challenges WHERE challenge_id = #{challengeId}")
    @Results({
            @Result(column = "modifiers", property = "modifiers",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class)
    })
    DailyChallenge findChallengeById(@Param("challengeId") String challengeId);

    @Select("SELECT * FROM daily_challenges WHERE date = #{date}")
    @Results({
            @Result(column = "modifiers", property = "modifiers",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class)
    })
    DailyChallenge findChallengeByDate(@Param("date") String date);

    @Select("SELECT * FROM daily_challenges ORDER BY date DESC LIMIT #{days}")
    @Results({
            @Result(column = "modifiers", property = "modifiers",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class)
    })
    List<DailyChallenge> findRecentChallenges(@Param("days") int days);

    @Delete("DELETE FROM daily_challenges WHERE date < #{cutoffDate}")
    int deleteChallengesBefore(@Param("cutoffDate") String cutoffDate);
}
