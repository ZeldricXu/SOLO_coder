package com.meshcontrol.mtls.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.mtls.entity.Certificate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface CertificateMapper extends BaseMapper<Certificate> {

    @Select("SELECT * FROM certificate WHERE common_name = #{commonName} AND status = 'active' AND deleted = 0 ORDER BY created_at DESC LIMIT 1")
    Certificate findActiveByCommonName(@Param("commonName") String commonName);

    @Select("SELECT * FROM certificate WHERE cert_type = #{certType} AND status = 'active' AND deleted = 0")
    List<Certificate> findActiveByType(@Param("certType") String certType);

    @Select("SELECT * FROM certificate WHERE status = 'active' AND not_after < #{expiryThreshold} AND deleted = 0")
    List<Certificate> findExpiringSoon(@Param("expiryThreshold") LocalDateTime expiryThreshold);

    @Select("SELECT * FROM certificate WHERE issuer_cert_id = #{issuerCertId} AND deleted = 0")
    List<Certificate> findByIssuer(@Param("issuerCertId") String issuerCertId);
}
