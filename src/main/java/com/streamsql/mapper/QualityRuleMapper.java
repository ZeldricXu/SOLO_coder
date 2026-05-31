package com.streamsql.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.streamsql.entity.QualityRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface QualityRuleMapper extends BaseMapper<QualityRule> {

    @Select("SELECT * FROM quality_rule WHERE enabled = 1 AND deleted = 0")
    List<QualityRule> selectEnabledRules();
}
