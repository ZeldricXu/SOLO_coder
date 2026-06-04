package com.flowplatform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.flowplatform.entity.ProcessInstance;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Map;

@Mapper
public interface ProcessInstanceMapper extends BaseMapper<ProcessInstance> {

    @Select("SELECT status, COUNT(*) as cnt FROM process_instance WHERE deleted = 0 GROUP BY status")
    List<Map<String, Object>> countByStatus();

    @Select("SELECT DATE(create_time) as date, COUNT(*) as cnt FROM process_instance WHERE deleted = 0 AND create_time >= DATE_SUB(NOW(), INTERVAL 30 DAY) GROUP BY DATE(create_time) ORDER BY date")
    List<Map<String, Object>> countByDateRecent30Days();

    @Select("SELECT form_id, COUNT(*) as cnt FROM process_instance WHERE deleted = 0 GROUP BY form_id ORDER BY cnt DESC LIMIT 10")
    List<Map<String, Object>> countByFormTop10();

    @Select("SELECT AVG(TIMESTAMPDIFF(HOUR, start_time, end_time)) as avg_hours FROM process_instance WHERE deleted = 0 AND status = 'APPROVED' AND end_time IS NOT NULL")
    Map<String, Object> avgApprovalHours();
}
