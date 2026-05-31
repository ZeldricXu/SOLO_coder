package com.solocoder.platform.storage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private String key;
    private byte[] data;
    private Map<String, String> metadata;
    private String contentType;
    private long size;
}
