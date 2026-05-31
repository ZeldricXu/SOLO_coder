package com.tracetopology.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tracetopology.infrastructure.persistence.entity.ServiceNodePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ServiceNodeMapper extends BaseMapper<ServiceNodePO> {

    @Select("SELECT * FROM t_service_node WHERE namespace = #{namespace}")
    IPage<ServiceNodePO> findByNamespace(Page<ServiceNodePO> page, @Param("namespace") String namespace);
}
