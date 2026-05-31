package com.edgescheduler.ruleengine.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.edgescheduler.ruleengine.entity.RuleExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface RuleExecutionMapper extends BaseMapper<RuleExecution> {

    @Select("SELECT * FROM rule_execution WHERE run_id = #{runId}")
    RuleExecution selectByRunId(@Param("runId") String runId);

    @Select("SELECT * FROM rule_execution WHERE rule_id = #{ruleId} ORDER BY created_at DESC LIMIT #{limit}")
    List<RuleExecution> selectByRuleId(@Param("ruleId") String ruleId,
                                        @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM rule_execution WHERE rule_id = #{ruleId} AND phase = #{phase} " +
            "AND created_at >= #{startTime}")
    int countByRuleIdAndPhase(@Param("ruleId") String ruleId,
                               @Param("phase") String phase,
                               @Param("startTime") LocalDateTime startTime);
}
