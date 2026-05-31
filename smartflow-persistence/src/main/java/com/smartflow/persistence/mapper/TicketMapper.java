package com.smartflow.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.smartflow.persistence.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {
}
