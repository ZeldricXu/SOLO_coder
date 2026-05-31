CREATE TABLE `t_datasource` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `name` VARCHAR(128) NOT NULL COMMENT '数据源名称',
  `type` VARCHAR(32) NOT NULL COMMENT '数据源类型: mysql/postgresql/influxdb等',
  `host` VARCHAR(128) NOT NULL COMMENT '主机地址',
  `port` INT NOT NULL COMMENT '端口',
  `database` VARCHAR(64) NOT NULL COMMENT '数据库名',
  `username` VARCHAR(64) NOT NULL COMMENT '用户名',
  `password` VARCHAR(256) NOT NULL COMMENT '密码',
  `config` TEXT COMMENT '扩展配置(JSON)',
  `status` VARCHAR(16) NOT NULL DEFAULT 'active' COMMENT '状态: active/inactive',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`id`),
  KEY `idx_type` (`type`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据源表';

CREATE TABLE `t_table_schema` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `datasource_id` BIGINT NOT NULL COMMENT '数据源ID',
  `schema_name` VARCHAR(64) COMMENT 'schema名称',
  `table_name` VARCHAR(128) NOT NULL COMMENT '表名',
  `table_comment` VARCHAR(512) COMMENT '表注释',
  `row_count` BIGINT DEFAULT 0 COMMENT '行数',
  `size_bytes` BIGINT DEFAULT 0 COMMENT '大小(字节)',
  `sample_data` TEXT COMMENT '样例数据(JSON)',
  `crawl_status` VARCHAR(16) NOT NULL DEFAULT 'pending' COMMENT '采集状态: pending/running/success/failed',
  `last_crawl_time` DATETIME COMMENT '最后采集时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`id`),
  KEY `idx_datasource` (`datasource_id`),
  KEY `idx_table` (`schema_name`, `table_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='表Schema信息表';

CREATE TABLE `t_column_schema` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `table_id` BIGINT NOT NULL COMMENT '表ID',
  `column_name` VARCHAR(128) NOT NULL COMMENT '列名',
  `column_type` VARCHAR(64) NOT NULL COMMENT '列类型',
  `column_comment` VARCHAR(512) COMMENT '列注释',
  `is_nullable` TINYINT DEFAULT 1 COMMENT '是否可空',
  `is_primary_key` TINYINT DEFAULT 0 COMMENT '是否主键',
  `ordinal_position` INT DEFAULT 0 COMMENT '列顺序',
  `min_value` VARCHAR(256) COMMENT '最小值',
  `max_value` VARCHAR(256) COMMENT '最大值',
  `distinct_count` BIGINT DEFAULT 0 COMMENT '去重数量',
  `null_count` BIGINT DEFAULT 0 COMMENT '空值数量',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`id`),
  KEY `idx_table` (`table_id`),
  KEY `idx_column` (`column_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='列Schema信息表';

CREATE TABLE `t_vector_index` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `name` VARCHAR(128) NOT NULL COMMENT '索引名称',
  `dimension` INT NOT NULL COMMENT '向量维度',
  `metric_type` VARCHAR(16) NOT NULL DEFAULT 'cosine' COMMENT '度量类型: cosine/euclidean',
  `index_type` VARCHAR(32) NOT NULL DEFAULT 'hnsw' COMMENT '索引类型: hnsw/ivf',
  `total_vectors` BIGINT DEFAULT 0 COMMENT '总向量数',
  `status` VARCHAR(16) NOT NULL DEFAULT 'building' COMMENT '状态: building/ready/error',
  `index_params` TEXT COMMENT '索引参数(JSON)',
  `last_build_time` DATETIME COMMENT '最后构建时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='向量索引表';

CREATE TABLE `t_query_plan` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `sql_text` TEXT NOT NULL COMMENT '原始SQL',
  `logical_plan` TEXT COMMENT '逻辑计划(JSON)',
  `physical_plan` TEXT COMMENT '物理计划(JSON)',
  `execution_time_ms` BIGINT COMMENT '执行时间(毫秒)',
  `optimization_rules` VARCHAR(512) COMMENT '应用的优化规则',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='查询计划表';

CREATE TABLE `t_time_series_data` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `metric` VARCHAR(128) NOT NULL COMMENT '指标名称',
  `tags` VARCHAR(512) COMMENT '标签(JSON)',
  `timestamp` DATETIME NOT NULL COMMENT '时间戳',
  `value` DOUBLE NOT NULL COMMENT '值',
  `resolution` VARCHAR(16) NOT NULL DEFAULT 'raw' COMMENT '分辨率: raw/hourly/daily',
  `compression_type` VARCHAR(16) NOT NULL DEFAULT 'none' COMMENT '压缩类型: none/gorilla/tsdiff',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_metric_time` (`metric`, `timestamp`),
  KEY `idx_resolution` (`resolution`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='时序数据表';

CREATE TABLE `t_quality_rule` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `name` VARCHAR(128) NOT NULL COMMENT '规则名称',
  `rule_type` VARCHAR(32) NOT NULL COMMENT '规则类型: null_check/range_check/duplicate_check等',
  `datasource_id` BIGINT COMMENT '数据源ID',
  `table_name` VARCHAR(128) COMMENT '表名',
  `column_name` VARCHAR(128) COMMENT '列名',
  `rule_config` TEXT NOT NULL COMMENT '规则配置(JSON)',
  `severity` VARCHAR(16) NOT NULL DEFAULT 'warning' COMMENT '严重等级: info/warning/error',
  `cron_expression` VARCHAR(64) COMMENT '定时表达式',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  `last_check_time` DATETIME COMMENT '最后检查时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`id`),
  KEY `idx_type` (`rule_type`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量规则表';

CREATE TABLE `t_quality_result` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `rule_id` BIGINT NOT NULL COMMENT '规则ID',
  `check_time` DATETIME NOT NULL COMMENT '检查时间',
  `status` VARCHAR(16) NOT NULL COMMENT '状态: pass/fail',
  `actual_value` VARCHAR(1024) COMMENT '实际值',
  `expected_value` VARCHAR(1024) COMMENT '期望值',
  `error_message` VARCHAR(1024) COMMENT '错误信息',
  `abnormal_data_count` BIGINT DEFAULT 0 COMMENT '异常数据量',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_rule` (`rule_id`),
  KEY `idx_check_time` (`check_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='质量检查结果表';

CREATE TABLE `t_cdc_task` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `name` VARCHAR(128) NOT NULL COMMENT '任务名称',
  `datasource_id` BIGINT NOT NULL COMMENT '数据源ID',
  `table_name` VARCHAR(128) NOT NULL COMMENT '监听表名',
  `output_type` VARCHAR(32) NOT NULL COMMENT '输出类型: kafka/elasticsearch',
  `output_config` TEXT NOT NULL COMMENT '输出配置(JSON)',
  `status` VARCHAR(16) NOT NULL DEFAULT 'stopped' COMMENT '状态: running/stopped/error',
  `last_binlog_position` VARCHAR(256) COMMENT '最后binlog位置',
  `last_process_time` DATETIME COMMENT '最后处理时间',
  `processed_events` BIGINT DEFAULT 0 COMMENT '已处理事件数',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`id`),
  KEY `idx_datasource` (`datasource_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CDC任务表';

CREATE TABLE `t_cdc_event` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `task_id` BIGINT NOT NULL COMMENT '任务ID',
  `event_type` VARCHAR(16) NOT NULL COMMENT '事件类型: insert/update/delete',
  `database` VARCHAR(64) NOT NULL COMMENT '数据库',
  `table_name` VARCHAR(128) NOT NULL COMMENT '表名',
  `before_data` TEXT COMMENT '变更前数据(JSON)',
  `after_data` TEXT COMMENT '变更后数据(JSON)',
  `binlog_position` VARCHAR(256) COMMENT 'binlog位置',
  `event_time` DATETIME NOT NULL COMMENT '事件时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_task` (`task_id`),
  KEY `idx_event_time` (`event_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CDC事件表';

CREATE TABLE `t_lineage_graph` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `source_table` VARCHAR(128) NOT NULL COMMENT '源表',
  `source_column` VARCHAR(128) COMMENT '源列',
  `target_table` VARCHAR(128) NOT NULL COMMENT '目标表',
  `target_column` VARCHAR(128) COMMENT '目标列',
  `transform_type` VARCHAR(32) NOT NULL COMMENT '转换类型: select/join/aggregate等',
  `sql_template` TEXT COMMENT 'SQL模板',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_source` (`source_table`),
  KEY `idx_target` (`target_table`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据血缘关系表';

CREATE TABLE `t_lifecycle_policy` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `name` VARCHAR(128) NOT NULL COMMENT '策略名称',
  `table_name` VARCHAR(128) NOT NULL COMMENT '表名',
  `time_column` VARCHAR(128) NOT NULL COMMENT '时间列',
  `hot_days` INT DEFAULT 7 COMMENT '热数据保留天数',
  `cold_days` INT DEFAULT 30 COMMENT '冷数据保留天数',
  `archive_days` INT DEFAULT 365 COMMENT '归档保留天数',
  `archive_location` VARCHAR(256) COMMENT '归档位置',
  `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  `last_execution_time` DATETIME COMMENT '最后执行时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
  PRIMARY KEY (`id`),
  KEY `idx_table` (`table_name`),
  KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生命周期策略表';

CREATE TABLE `t_lifecycle_log` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `policy_id` BIGINT NOT NULL COMMENT '策略ID',
  `operation_type` VARCHAR(16) NOT NULL COMMENT '操作类型: migrate/archive/cleanup',
  `source_table` VARCHAR(128) COMMENT '源表',
  `target_table` VARCHAR(128) COMMENT '目标表',
  `processed_rows` BIGINT DEFAULT 0 COMMENT '处理行数',
  `status` VARCHAR(16) NOT NULL COMMENT '状态: success/failed',
  `error_message` VARCHAR(1024) COMMENT '错误信息',
  `start_time` DATETIME NOT NULL COMMENT '开始时间',
  `end_time` DATETIME COMMENT '结束时间',
  PRIMARY KEY (`id`),
  KEY `idx_policy` (`policy_id`),
  KEY `idx_operation` (`operation_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='生命周期执行日志表';

CREATE TABLE `t_metrics_snapshot` (
  `id` BIGINT NOT NULL COMMENT '主键ID',
  `snapshot_time` DATETIME NOT NULL COMMENT '快照时间',
  `metrics` TEXT NOT NULL COMMENT '指标数据(JSON)',
  `dimensions` TEXT COMMENT '维度数据(JSON)',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_snapshot_time` (`snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='指标快照表';
