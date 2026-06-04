package com.flowplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowplatform.entity.ProcessTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

@Mapper
public interface ProcessTaskMapper extends BaseMapper<ProcessTask> {

    // Requires MySQL 8.0+ and InnoDB engine for SKIP LOCKED support
    @Select("SELECT * FROM process_task WHERE assignee_id = #{userId} AND status = 'PENDING' FOR UPDATE SKIP LOCKED")
    List<ProcessTask> selectPendingByUserIdForUpdate(@Param("userId") Long userId);

    @Select("SELECT node_name, AVG(TIMESTAMPDIFF(HOUR, create_time, complete_time)) as avg_hours FROM process_task WHERE status = 'COMPLETED' AND complete_time IS NOT NULL GROUP BY node_name ORDER BY avg_hours DESC LIMIT 10")
    List<Map<String, Object>> avgHoursByNodeTop10();
}
