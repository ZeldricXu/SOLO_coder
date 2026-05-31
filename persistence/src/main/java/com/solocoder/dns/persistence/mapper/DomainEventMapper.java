package com.solocoder.dns.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.solocoder.dns.persistence.entity.DomainEventPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DomainEventMapper extends BaseMapper<DomainEventPO> {
}
