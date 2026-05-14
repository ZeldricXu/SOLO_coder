package com.datamigrate.dto;

import com.datamigrate.common.VerifyStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class VerifyResponse {
    private VerifyInfo verify;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class VerifyInfo {
        private String verifyId;
        private String verifyType;
        private VerifyStatus verifyStatus;
        private Long totalVerified;
        private Long matchCount;
        private Long diffCount;
    }
}
