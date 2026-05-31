package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("image_layer")
public class ImageLayer extends BaseEntity {

    private String layerId;
    private String digest;
    private Long sizeBytes;
    private String mediaType;
    private String blobPath;
    private Boolean downloaded;
    private LocalDateTime downloadedAt;
    private Integer p2pSeeders;
}
