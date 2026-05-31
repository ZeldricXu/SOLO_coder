package com.chaoslab.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.chaoslab.entity.EventSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EventSnapshotMapper extends BaseMapper<EventSnapshot> {

    @Select("SELECT * FROM event_snapshot WHERE aggregate_id = #{aggregateId} ORDER BY sequence_number DESC LIMIT 1")
    EventSnapshot findLatestByAggregateId(@Param("aggregateId") String aggregateId);
}
