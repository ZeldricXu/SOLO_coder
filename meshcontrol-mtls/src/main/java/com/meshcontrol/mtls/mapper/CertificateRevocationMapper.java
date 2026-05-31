package com.meshcontrol.mtls.mapper;

import com.meshcontrol.common.base.BaseMapper;
import com.meshcontrol.mtls.entity.CertificateRevocation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CertificateRevocationMapper extends BaseMapper<CertificateRevocation> {

    @Select("SELECT * FROM certificate_revocation WHERE cert_id = #{certId}")
    List<CertificateRevocation> findByCertId(@Param("certId") String certId);

    @Select("SELECT * FROM certificate_revocation WHERE revoked_at >= #{since} ORDER BY revoked_at DESC")
    List<CertificateRevocation> findRecent(@Param("since") java.time.LocalDateTime since);
}
