package com.contractai.ticket.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.ticket.entity.Ticket;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface TicketMapper extends BaseMapper<Ticket> {
}
