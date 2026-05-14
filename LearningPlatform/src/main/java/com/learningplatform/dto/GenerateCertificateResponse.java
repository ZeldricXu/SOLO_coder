
package com.learningplatform.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GenerateCertificateResponse {
    private String certificateId;
    private String number;
    private String status;
}
