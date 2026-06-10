package com.exam.vo;

import lombok.Data;

@Data
public class OnlineStatusVO {
    private Long examId;
    private Integer totalCandidates;
    private Integer onlineCount;
    private Integer offlineCount;
    private Double onlineRate;
}
