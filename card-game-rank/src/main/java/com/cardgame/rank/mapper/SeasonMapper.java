package com.cardgame.rank.mapper;

import com.cardgame.rank.entity.Season;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface SeasonMapper {

    @Insert("INSERT INTO seasons (season_id, name, description, start_time, end_time, " +
            "active, rewards, reward_tiers, version) VALUES " +
            "(#{seasonId}, #{name}, #{description}, #{startTime}, #{endTime}, " +
            "#{active}, #{rewards, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "#{rewardTiers, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, #{version})")
    void insertSeason(Season season);

    @Update("UPDATE seasons SET name = #{name}, description = #{description}, " +
            "start_time = #{startTime}, end_time = #{endTime}, active = #{active}, " +
            "rewards = #{rewards, typeHandler=com.cardgame.save.handler.JsonTypeHandler}, " +
            "reward_tiers = #{rewardTiers, typeHandler=com.cardgame.save.handler.JsonTypeHandler} " +
            "WHERE season_id = #{seasonId}")
    void updateSeason(Season season);

    @Select("SELECT * FROM seasons WHERE season_id = #{seasonId}")
    @Results({
            @Result(column = "rewards", property = "rewards",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "reward_tiers", property = "rewardTiers",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class)
    })
    Season findSeasonById(@Param("seasonId") String seasonId);

    @Select("SELECT * FROM seasons WHERE active = 1 ORDER BY start_time DESC LIMIT 1")
    @Results({
            @Result(column = "rewards", property = "rewards",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "reward_tiers", property = "rewardTiers",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class)
    })
    Season findActiveSeason();

    @Select("SELECT * FROM seasons ORDER BY start_time DESC LIMIT 10")
    @Results({
            @Result(column = "rewards", property = "rewards",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class),
            @Result(column = "reward_tiers", property = "rewardTiers",
                    typeHandler = com.cardgame.save.handler.JsonTypeHandler.class)
    })
    List<Season> findAllSeasons();
}
