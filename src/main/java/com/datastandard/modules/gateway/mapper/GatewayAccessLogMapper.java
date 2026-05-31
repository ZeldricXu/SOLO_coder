package com.datastandard.modules.gateway.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.datastandard.modules.gateway.dto.AccessLogQuery;
import com.datastandard.modules.gateway.entity.GatewayAccessLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Mapper
public interface GatewayAccessLogMapper extends BaseMapper<GatewayAccessLog> {

    @Select("SELECT * FROM gateway_access_logs WHERE request_id = #{requestId} AND deleted = 0")
    Optional<GatewayAccessLog> findByRequestId(@Param("requestId") String requestId);

    @Select("SELECT * FROM gateway_access_logs WHERE trace_id = #{traceId} AND deleted = 0 ORDER BY request_time ASC")
    List<GatewayAccessLog> findByTraceId(@Param("traceId") String traceId);

    @Select("SELECT * FROM gateway_access_logs WHERE client_ip = #{clientIp} " +
            "AND request_time >= #{startTime} AND request_time < #{endTime} AND deleted = 0 " +
            "ORDER BY request_time DESC")
    List<GatewayAccessLog> findByClientIpAndTimeRange(
            @Param("clientIp") String clientIp,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Select("SELECT * FROM gateway_access_logs WHERE user_id = #{userId} " +
            "AND request_time >= #{startTime} AND request_time < #{endTime} AND deleted = 0 " +
            "ORDER BY request_time DESC")
    List<GatewayAccessLog> findByUserIdAndTimeRange(
            @Param("userId") String userId,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Select("<script>" +
            "SELECT * FROM gateway_access_logs WHERE deleted = 0 " +
            "<if test='query.requestId != null'>AND request_id = #{query.requestId} </if>" +
            "<if test='query.clientIp != null'>AND client_ip = #{query.clientIp} </if>" +
            "<if test='query.userId != null'>AND user_id = #{query.userId} </if>" +
            "<if test='query.path != null'>AND path LIKE CONCAT('%', #{query.path}, '%') </if>" +
            "<if test='query.method != null'>AND method = #{query.method} </if>" +
            "<if test='query.statusCode != null'>AND status_code = #{query.statusCode} </if>" +
            "<if test='query.startTime != null'>AND request_time &gt;= #{query.startTime} </if>" +
            "<if test='query.endTime != null'>AND request_time &lt; #{query.endTime} </if>" +
            "ORDER BY request_time DESC " +
            "LIMIT #{query.size} OFFSET #{offset}" +
            "</script>")
    List<GatewayAccessLog> findByQuery(@Param("query") AccessLogQuery query, @Param("offset") int offset);

    @Select("<script>" +
            "SELECT COUNT(*) FROM gateway_access_logs WHERE deleted = 0 " +
            "<if test='query.requestId != null'>AND request_id = #{query.requestId} </if>" +
            "<if test='query.clientIp != null'>AND client_ip = #{query.clientIp} </if>" +
            "<if test='query.userId != null'>AND user_id = #{query.userId} </if>" +
            "<if test='query.path != null'>AND path LIKE CONCAT('%', #{query.path}, '%') </if>" +
            "<if test='query.method != null'>AND method = #{query.method} </if>" +
            "<if test='query.statusCode != null'>AND status_code = #{query.statusCode} </if>" +
            "<if test='query.startTime != null'>AND request_time &gt;= #{query.startTime} </if>" +
            "<if test='query.endTime != null'>AND request_time &lt; #{query.endTime} </if>" +
            "</script>")
    long countByQuery(@Param("query") AccessLogQuery query);

    @Select("SELECT status_code, COUNT(*) as cnt FROM gateway_access_logs " +
            "WHERE request_time >= #{startTime} AND request_time < #{endTime} AND deleted = 0 " +
            "GROUP BY status_code")
    List<GatewayAccessLog> countByStatusAndTimeRange(
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);
}
