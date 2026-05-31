package com.observability.common.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class ResourceStatusResponse implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String status;

    private Double progress;

    private String errorDetail;
}
