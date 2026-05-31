package com.didauth.module.storage.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class StoreContentResponse implements Serializable {

    private String contentId;
    private String storageType;
    private String cid;
    private String contentHash;
    private Long contentSize;
    private String pinStatus;
}
