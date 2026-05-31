package com.chaoslab.modules.mtls.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RevocationRequest {

    @NotBlank(message = "证书ID不能为空")
    private String certId;

    private String reason;

    private String revokedBy;
}
