package com.iotplatform.protocol.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iotplatform.protocol.entity.ProtocolAdapter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import java.util.List;
import java.util.Optional;

@Mapper
public interface ProtocolAdapterMapper extends BaseMapper<ProtocolAdapter> {

    @Select("SELECT * FROM protocol_adapter WHERE adapter_id = #{adapterId} AND deleted = 0")
    Optional<ProtocolAdapter> findByAdapterId(@Param("adapterId") String adapterId);

    @Select("SELECT * FROM protocol_adapter WHERE protocol_type = #{protocolType} AND enabled = 1 AND deleted = 0")
    List<ProtocolAdapter> findByProtocolType(@Param("protocolType") String protocolType);

    @Select("SELECT * FROM protocol_adapter WHERE enabled = 1 AND deleted = 0")
    List<ProtocolAdapter> findAllEnabled();

    IPage<ProtocolAdapter> selectAdapterPage(Page<ProtocolAdapter> page,
                                              @Param("protocolType") String protocolType,
                                              @Param("enabled") Boolean enabled);
}
