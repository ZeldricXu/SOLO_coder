package com.iotplatform.storage.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ObjectDownloadDTO {

    private String objectId;
    private String objectName;
    private String contentType;
    private Long contentLength;
    private byte[] content;
    private String etag;
}
