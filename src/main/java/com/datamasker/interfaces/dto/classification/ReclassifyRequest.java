package com.datamasker.interfaces.dto.classification;

import lombok.Data;

@Data
public class ReclassifyRequest {

    private String category;

    private String level;
}
