package com.contractai.ticket.mapper;

import com.contractai.common.mapper.BaseMapper;
import com.contractai.ticket.entity.TicketAssignmentLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TicketAssignmentLogMapper extends BaseMapper<TicketAssignmentLog> {

    @Select("SELECT * FROM ticket_assignment_log WHERE ticket_id = #{ticketId} AND tenant_id = #{tenantId} AND deleted = 0 ORDER BY assigned_at DESC")
    List<TicketAssignmentLog> findByTicketId(@Param("ticketId") Long ticketId, @Param("tenantId") Long tenantId);
}
