package com.dynamiclog.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dynamiclog.common.entity.Rule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface RuleMapper extends BaseMapper<Rule> {

    @Select("SELECT * FROM rule WHERE event_type = #{eventType} AND enabled = 1 AND deleted = 0 ORDER BY priority DESC")
    List<Rule> findByEventType(@Param("eventType") String eventType);

    @Select("SELECT * FROM rule WHERE namespace = #{namespace} AND enabled = 1 AND deleted = 0")
    List<Rule> findByNamespace(@Param("namespace") String namespace);
}
