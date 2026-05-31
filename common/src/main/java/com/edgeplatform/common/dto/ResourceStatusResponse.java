package com.edgeplatform.common.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResourceStatusResponse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String status;
    private Double progress;
}
