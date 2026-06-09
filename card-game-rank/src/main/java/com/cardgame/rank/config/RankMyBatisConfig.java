package com.cardgame.rank.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.cardgame.rank.mapper")
public class RankMyBatisConfig {
}
