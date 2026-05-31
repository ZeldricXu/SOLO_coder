package com.dynamiclog.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dynamiclog.common.entity.OfflineData;
import com.dynamiclog.common.enums.SyncStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;

@Mapper
public interface OfflineDataMapper extends BaseMapper<OfflineData> {

    @Select("SELECT * FROM offline_data WHERE sync_status = #{status} AND deleted = 0 ORDER BY created_at ASC")
    List<OfflineData> findBySyncStatus(@Param("status") SyncStatus status);

    @Select("SELECT * FROM offline_data WHERE data_key = #{dataKey} AND deleted = 0 ORDER BY created_at DESC LIMIT 1")
    OfflineData findLatestByDataKey(@Param("dataKey") String dataKey);
}
