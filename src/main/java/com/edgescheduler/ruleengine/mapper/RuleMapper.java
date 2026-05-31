package com.edgescheduler.ruleengine.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.ruleengine.entity.Rule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface RuleMapper extends BaseMapper<Rule> {

    @Select("SELECT * FROM rule WHERE rule_id = #{ruleId} AND deleted = 0")
    Rule selectByRuleId(@Param("ruleId") String ruleId);

    @Select("SELECT * FROM rule WHERE trigger_type = #{triggerType} AND enabled = 1 AND deleted = 0")
    List<Rule> selectByTriggerType(@Param("triggerType") String triggerType);

    @Select("SELECT * FROM rule WHERE enabled = 1 AND deleted = 0")
    List<Rule> selectAllEnabled();

    @Update("UPDATE rule SET version = version + 1, enabled = #{enabled}, updated_at = NOW() " +
            "WHERE rule_id = #{ruleId} AND version = #{version} AND deleted = 0")
    int updateEnabledWithVersion(@Param("ruleId") String ruleId,
                                  @Param("enabled") Integer enabled,
                                  @Param("version") Integer version);
}
