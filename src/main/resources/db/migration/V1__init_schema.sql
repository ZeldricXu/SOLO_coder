CREATE TABLE IF NOT EXISTS core_entity (
    id VARCHAR(64) PRIMARY KEY COMMENT '实体ID',
    type VARCHAR(32) NOT NULL COMMENT '实体类型',
    status VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '状态',
    attributes JSON COMMENT '属性',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_type (type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='核心实体表';

CREATE TABLE IF NOT EXISTS config_definition (
    config_id VARCHAR(64) PRIMARY KEY COMMENT '配置ID',
    namespace VARCHAR(64) NOT NULL COMMENT '命名空间',
    version INT NOT NULL DEFAULT 1 COMMENT '版本',
    parameters JSON COMMENT '参数',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    applied_at DATETIME COMMENT '应用时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_namespace (namespace),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='配置定义表';

CREATE TABLE IF NOT EXISTS run_instance (
    run_id VARCHAR(64) PRIMARY KEY COMMENT '运行实例ID',
    entity_id VARCHAR(64) NOT NULL COMMENT '关联实体ID',
    phase VARCHAR(32) NOT NULL COMMENT '阶段',
    progress DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '进度',
    started_at DATETIME COMMENT '开始时间',
    completed_at DATETIME COMMENT '完成时间',
    error_detail TEXT COMMENT '错误详情',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_entity_id (entity_id),
    INDEX idx_phase (phase)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='运行实例表';

CREATE TABLE IF NOT EXISTS metrics_snapshot (
    snapshot_id VARCHAR(64) PRIMARY KEY COMMENT '快照ID',
    timestamp DATETIME NOT NULL COMMENT '时间戳',
    metrics JSON COMMENT '指标数据',
    dimensions JSON COMMENT '维度',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_timestamp (timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='统计快照表';

CREATE TABLE IF NOT EXISTS quality_rule (
    rule_id VARCHAR(64) PRIMARY KEY COMMENT '规则ID',
    rule_name VARCHAR(128) NOT NULL COMMENT '规则名称',
    rule_type VARCHAR(32) NOT NULL COMMENT '规则类型',
    datasource_id VARCHAR(64) NOT NULL COMMENT '数据源ID',
    table_name VARCHAR(128) NOT NULL COMMENT '表名',
    column_name VARCHAR(128) COMMENT '列名',
    check_expression TEXT NOT NULL COMMENT '校验表达式',
    severity VARCHAR(16) NOT NULL DEFAULT 'warning' COMMENT '严重程度',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    cron_expression VARCHAR(64) COMMENT 'cron表达式',
    last_check_time DATETIME COMMENT '最后校验时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_datasource (datasource_id),
    INDEX idx_rule_type (rule_type),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据质量规则表';

CREATE TABLE IF NOT EXISTS quality_check_result (
    result_id VARCHAR(64) PRIMARY KEY COMMENT '结果ID',
    rule_id VARCHAR(64) NOT NULL COMMENT '规则ID',
    check_time DATETIME NOT NULL COMMENT '校验时间',
    status VARCHAR(32) NOT NULL COMMENT '校验状态',
    total_count BIGINT NOT NULL DEFAULT 0 COMMENT '总记录数',
    error_count BIGINT NOT NULL DEFAULT 0 COMMENT '错误记录数',
    error_sample TEXT COMMENT '错误样例',
    error_detail JSON COMMENT '错误详情',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_rule_id (rule_id),
    INDEX idx_check_time (check_time),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据质量校验结果表';

CREATE TABLE IF NOT EXISTS anomaly_data_record (
    record_id VARCHAR(64) PRIMARY KEY COMMENT '记录ID',
    rule_id VARCHAR(64) NOT NULL COMMENT '规则ID',
    datasource_id VARCHAR(64) NOT NULL COMMENT '数据源ID',
    table_name VARCHAR(128) NOT NULL COMMENT '表名',
    primary_key_value VARCHAR(256) COMMENT '主键值',
    anomaly_type VARCHAR(32) NOT NULL COMMENT '异常类型',
    anomaly_detail JSON COMMENT '异常详情',
    marked TINYINT NOT NULL DEFAULT 1 COMMENT '是否标记',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_rule_id (rule_id),
    INDEX idx_datasource_table (datasource_id, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='异常数据记录表';

CREATE TABLE IF NOT EXISTS vector_index (
    index_id VARCHAR(64) PRIMARY KEY COMMENT '索引ID',
    index_name VARCHAR(128) NOT NULL COMMENT '索引名称',
    datasource_id VARCHAR(64) NOT NULL COMMENT '数据源ID',
    table_name VARCHAR(128) NOT NULL COMMENT '表名',
    column_name VARCHAR(128) NOT NULL COMMENT '列名',
    vector_dimension INT NOT NULL COMMENT '向量维度',
    index_type VARCHAR(32) NOT NULL DEFAULT 'hnsw' COMMENT '索引类型',
    index_params JSON COMMENT '索引参数',
    status VARCHAR(32) NOT NULL DEFAULT 'building' COMMENT '索引状态',
    index_path VARCHAR(256) COMMENT '索引存储路径',
    last_build_time DATETIME COMMENT '最后构建时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_datasource (datasource_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='向量索引表';

CREATE TABLE IF NOT EXISTS vector_embedding (
    embedding_id VARCHAR(64) PRIMARY KEY COMMENT '嵌入ID',
    index_id VARCHAR(64) NOT NULL COMMENT '索引ID',
    data_key VARCHAR(256) NOT NULL COMMENT '数据键',
    vector BLOB NOT NULL COMMENT '向量数据',
    metadata JSON COMMENT '元数据',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_index_id (index_id),
    INDEX idx_data_key (data_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='向量嵌入表';

CREATE TABLE IF NOT EXISTS datasource_info (
    datasource_id VARCHAR(64) PRIMARY KEY COMMENT '数据源ID',
    datasource_name VARCHAR(128) NOT NULL COMMENT '数据源名称',
    datasource_type VARCHAR(32) NOT NULL COMMENT '数据源类型',
    connection_config JSON NOT NULL COMMENT '连接配置',
    status VARCHAR(32) NOT NULL DEFAULT 'active' COMMENT '状态',
    last_crawl_time DATETIME COMMENT '最后爬取时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_type (datasource_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据源信息表';

CREATE TABLE IF NOT EXISTS metadata_schema (
    schema_id VARCHAR(64) PRIMARY KEY COMMENT 'Schema ID',
    datasource_id VARCHAR(64) NOT NULL COMMENT '数据源ID',
    schema_name VARCHAR(128) NOT NULL COMMENT 'Schema名称',
    table_name VARCHAR(128) NOT NULL COMMENT '表名',
    column_name VARCHAR(128) NOT NULL COMMENT '列名',
    data_type VARCHAR(64) NOT NULL COMMENT '数据类型',
    nullable TINYINT NOT NULL DEFAULT 1 COMMENT '是否可空',
    primary_key TINYINT NOT NULL DEFAULT 0 COMMENT '是否主键',
    column_comment VARCHAR(256) COMMENT '列注释',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_datasource_table (datasource_id, schema_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='元数据Schema表';

CREATE TABLE IF NOT EXISTS metadata_statistics (
    stat_id VARCHAR(64) PRIMARY KEY COMMENT '统计ID',
    datasource_id VARCHAR(64) NOT NULL COMMENT '数据源ID',
    schema_name VARCHAR(128) NOT NULL COMMENT 'Schema名称',
    table_name VARCHAR(128) NOT NULL COMMENT '表名',
    column_name VARCHAR(128) COMMENT '列名',
    stat_type VARCHAR(32) NOT NULL COMMENT '统计类型',
    stat_value DOUBLE COMMENT '统计值',
    stat_json JSON COMMENT '统计JSON',
    stat_time DATETIME NOT NULL COMMENT '统计时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_datasource_table (datasource_id, schema_name, table_name),
    INDEX idx_stat_time (stat_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='元数据统计表';

CREATE TABLE IF NOT EXISTS sample_data (
    sample_id VARCHAR(64) PRIMARY KEY COMMENT '样例ID',
    datasource_id VARCHAR(64) NOT NULL COMMENT '数据源ID',
    schema_name VARCHAR(128) NOT NULL COMMENT 'Schema名称',
    table_name VARCHAR(128) NOT NULL COMMENT '表名',
    sample_data JSON NOT NULL COMMENT '样例数据',
    sample_time DATETIME NOT NULL COMMENT '采样时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_datasource_table (datasource_id, schema_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='样例数据表';

CREATE TABLE IF NOT EXISTS cdc_capture_task (
    task_id VARCHAR(64) PRIMARY KEY COMMENT '任务ID',
    task_name VARCHAR(128) NOT NULL COMMENT '任务名称',
    datasource_id VARCHAR(64) NOT NULL COMMENT '数据源ID',
    schema_name VARCHAR(128) COMMENT 'Schema名称',
    table_names JSON COMMENT '监听表名列表',
    status VARCHAR(32) NOT NULL DEFAULT 'stopped' COMMENT '任务状态',
    offset_info JSON COMMENT '偏移量信息',
    output_type VARCHAR(32) NOT NULL DEFAULT 'kafka' COMMENT '输出类型',
    output_config JSON COMMENT '输出配置',
    last_event_time DATETIME COMMENT '最后事件时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_datasource (datasource_id),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CDC捕获任务表';

CREATE TABLE IF NOT EXISTS cdc_event_record (
    event_id VARCHAR(64) PRIMARY KEY COMMENT '事件ID',
    task_id VARCHAR(64) NOT NULL COMMENT '任务ID',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型',
    schema_name VARCHAR(128) NOT NULL COMMENT 'Schema名称',
    table_name VARCHAR(128) NOT NULL COMMENT '表名',
    primary_key_value VARCHAR(256) COMMENT '主键值',
    before_data JSON COMMENT '变更前数据',
    after_data JSON COMMENT '变更后数据',
    event_time DATETIME NOT NULL COMMENT '事件时间',
    serialized_data BLOB COMMENT '序列化数据',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_task_id (task_id),
    INDEX idx_event_time (event_time),
    INDEX idx_table (schema_name, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='CDC事件记录表';

CREATE TABLE IF NOT EXISTS lineage_graph (
    lineage_id VARCHAR(64) PRIMARY KEY COMMENT '血缘ID',
    source_type VARCHAR(32) NOT NULL COMMENT '来源类型',
    source_sql TEXT COMMENT '源SQL',
    graph_data JSON NOT NULL COMMENT '图谱数据',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_source_type (source_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据血缘图谱表';

CREATE TABLE IF NOT EXISTS lineage_node (
    node_id VARCHAR(64) PRIMARY KEY COMMENT '节点ID',
    lineage_id VARCHAR(64) NOT NULL COMMENT '血缘ID',
    node_type VARCHAR(32) NOT NULL COMMENT '节点类型',
    node_name VARCHAR(256) NOT NULL COMMENT '节点名称',
    node_metadata JSON COMMENT '节点元数据',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_lineage_id (lineage_id),
    INDEX idx_node_type (node_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据血缘节点表';

CREATE TABLE IF NOT EXISTS lineage_edge (
    edge_id VARCHAR(64) PRIMARY KEY COMMENT '边ID',
    lineage_id VARCHAR(64) NOT NULL COMMENT '血缘ID',
    source_node_id VARCHAR(64) NOT NULL COMMENT '源节点ID',
    target_node_id VARCHAR(64) NOT NULL COMMENT '目标节点ID',
    edge_type VARCHAR(32) NOT NULL COMMENT '边类型',
    edge_metadata JSON COMMENT '边元数据',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_lineage_id (lineage_id),
    INDEX idx_source_target (source_node_id, target_node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据血缘边表';

CREATE TABLE IF NOT EXISTS timeseries_data (
    data_id VARCHAR(64) PRIMARY KEY COMMENT '数据ID',
    metric_name VARCHAR(128) NOT NULL COMMENT '指标名称',
    timestamp DATETIME NOT NULL COMMENT '时间戳',
    metric_value DOUBLE NOT NULL COMMENT '指标值',
    tags JSON COMMENT '标签',
    resolution VARCHAR(16) NOT NULL DEFAULT 'raw' COMMENT '分辨率',
    compressed TINYINT NOT NULL DEFAULT 0 COMMENT '是否压缩',
    compressed_data BLOB COMMENT '压缩数据',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_metric_time (metric_name, timestamp),
    INDEX idx_resolution (resolution)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='时序数据表';

CREATE TABLE IF NOT EXISTS lifecycle_policy (
    policy_id VARCHAR(64) PRIMARY KEY COMMENT '策略ID',
    policy_name VARCHAR(128) NOT NULL COMMENT '策略名称',
    datasource_id VARCHAR(64) NOT NULL COMMENT '数据源ID',
    table_name VARCHAR(128) NOT NULL COMMENT '表名',
    hot_storage_days INT NOT NULL DEFAULT 30 COMMENT '热存储天数',
    cold_storage_days INT NOT NULL DEFAULT 90 COMMENT '冷存储天数',
    archive_storage_days INT NOT NULL DEFAULT 365 COMMENT '归档存储天数',
    enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    last_migrate_time DATETIME COMMENT '最后迁移时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_datasource (datasource_id),
    INDEX idx_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='生命周期策略表';

CREATE TABLE IF NOT EXISTS data_archive_record (
    archive_id VARCHAR(64) PRIMARY KEY COMMENT '归档ID',
    policy_id VARCHAR(64) NOT NULL COMMENT '策略ID',
    datasource_id VARCHAR(64) NOT NULL COMMENT '数据源ID',
    table_name VARCHAR(128) NOT NULL COMMENT '表名',
    archive_type VARCHAR(32) NOT NULL COMMENT '归档类型',
    archive_path VARCHAR(256) NOT NULL COMMENT '归档路径',
    archive_count BIGINT NOT NULL DEFAULT 0 COMMENT '归档记录数',
    archive_date DATE NOT NULL COMMENT '归档日期',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_policy_id (policy_id),
    INDEX idx_archive_date (archive_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据归档记录表';

CREATE TABLE IF NOT EXISTS stream_query_plan (
    plan_id VARCHAR(64) PRIMARY KEY COMMENT '计划ID',
    query_name VARCHAR(128) COMMENT '查询名称',
    original_sql TEXT NOT NULL COMMENT '原始SQL',
    logical_plan JSON COMMENT '逻辑计划',
    physical_plan JSON COMMENT '物理计划',
    execution_config JSON COMMENT '执行配置',
    status VARCHAR(32) NOT NULL DEFAULT 'created' COMMENT '状态',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '删除标记',
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='流查询计划表';
