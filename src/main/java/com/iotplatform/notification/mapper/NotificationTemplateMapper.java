package com.iotplatform.notification.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.iotplatform.notification.entity.NotificationTemplate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.Optional;

@Mapper
public interface NotificationTemplateMapper extends BaseMapper<NotificationTemplate> {

    @Select("SELECT * FROM notification_template WHERE template_code = #{templateCode} " +
            "AND channel_type = #{channelType} AND enabled = 1 AND deleted = 0")
    Optional<NotificationTemplate> findByCodeAndChannel(@Param("templateCode") String templateCode,
                                                         @Param("channelType") String channelType);
}
