package com.tracetopology.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tracetopology.infrastructure.persistence.entity.RunInstancePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface RunInstanceMapper extends BaseMapper<RunInstancePO> {

    @Select("SELECT * FROM t_run_instance WHERE entity_id = #{entityId} ORDER BY started_at DESC")
    List<RunInstancePO> findByEntityId(@Param("entityId") String entityId);
}
