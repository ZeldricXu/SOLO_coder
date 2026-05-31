CREATE TABLE IF NOT EXISTS `core_entity` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `entity_id` VARCHAR(64) NOT NULL COMMENT '业务实体ID',
    `type` VARCHAR(32) NOT NULL COMMENT '实体类型',
    `status` VARCHAR(32) NOT NULL COMMENT '状态',
    `attributes` JSON NULL COMMENT '属性',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_entity_id` (`entity_id`),
    KEY `idx_type_status` (`type`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='核心实体表';

CREATE TABLE IF NOT EXISTS `config_definition` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `config_id` VARCHAR(64) NOT NULL COMMENT '配置ID',
    `namespace` VARCHAR(64) NOT NULL COMMENT '命名空间',
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
    `parameters` JSON NULL COMMENT '参数',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `applied_at` DATETIME NULL COMMENT '生效时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_config_id_version` (`config_id`, `version`),
    KEY `idx_namespace` (`namespace`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配置定义表';

CREATE TABLE IF NOT EXISTS `run_instance` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `run_id` VARCHAR(64) NOT NULL COMMENT '运行实例ID',
    `entity_id` VARCHAR(64) NOT NULL COMMENT '关联实体ID',
    `phase` VARCHAR(32) NOT NULL COMMENT '执行阶段',
    `progress` DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT '进度',
    `started_at` DATETIME NULL COMMENT '开始时间',
    `completed_at` DATETIME NULL COMMENT '完成时间',
    `error_detail` TEXT NULL COMMENT '错误详情',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_run_id` (`run_id`),
    KEY `idx_entity_id` (`entity_id`),
    KEY `idx_phase` (`phase`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='运行实例表';

CREATE TABLE IF NOT EXISTS `metrics_snapshot` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `snapshot_id` VARCHAR(64) NOT NULL COMMENT '快照ID',
    `timestamp` DATETIME NOT NULL COMMENT '时间戳',
    `metrics` JSON NULL COMMENT '指标数据',
    `dimensions` JSON NULL COMMENT '维度',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_snapshot_id` (`snapshot_id`),
    KEY `idx_timestamp` (`timestamp`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='统计快照表';

CREATE TABLE IF NOT EXISTS `prompt_version` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `prompt_id` VARCHAR(64) NOT NULL COMMENT 'PromptID',
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本号',
    `content` TEXT NOT NULL COMMENT 'Prompt内容',
    `variables` JSON NULL COMMENT '变量定义',
    `created_by` VARCHAR(64) NULL COMMENT '创建人',
    `description` VARCHAR(512) NULL COMMENT '描述',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_prompt_id_version` (`prompt_id`, `version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Prompt版本表';

CREATE TABLE IF NOT EXISTS `ab_experiment` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `experiment_id` VARCHAR(64) NOT NULL COMMENT '实验ID',
    `name` VARCHAR(128) NOT NULL COMMENT '实验名称',
    `description` VARCHAR(512) NULL COMMENT '描述',
    `control_group_prompt_id` VARCHAR(64) NOT NULL COMMENT '对照组PromptID',
    `control_group_prompt_version` INT NOT NULL COMMENT '对照组Prompt版本',
    `experiment_group_prompt_id` VARCHAR(64) NOT NULL COMMENT '实验组PromptID',
    `experiment_group_prompt_version` INT NOT NULL COMMENT '实验组Prompt版本',
    `traffic_split` DECIMAL(5,4) NOT NULL DEFAULT 0.5 COMMENT '流量分配比例',
    `status` VARCHAR(32) NOT NULL COMMENT '状态:DRAFT,RUNNING,PAUSED,COMPLETED',
    `started_at` DATETIME NULL COMMENT '开始时间',
    `ended_at` DATETIME NULL COMMENT '结束时间',
    `created_by` VARCHAR(64) NULL COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_experiment_id` (`experiment_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AB实验表';

CREATE TABLE IF NOT EXISTS `ab_experiment_result` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `experiment_id` VARCHAR(64) NOT NULL COMMENT '实验ID',
    `group_type` VARCHAR(16) NOT NULL COMMENT '分组:CONTROL,EXPERIMENT',
    `total_requests` BIGINT NOT NULL DEFAULT 0 COMMENT '总请求数',
    `success_count` BIGINT NOT NULL DEFAULT 0 COMMENT '成功数',
    `avg_latency_ms` DECIMAL(10,2) NULL COMMENT '平均延迟',
    `p99_latency_ms` DECIMAL(10,2) NULL COMMENT 'P99延迟',
    `error_rate` DECIMAL(10,6) NULL COMMENT '错误率',
    `satisfaction_score` DECIMAL(5,4) NULL COMMENT '满意度评分',
    `metrics` JSON NULL COMMENT '扩展指标',
    `snapshot_time` DATETIME NOT NULL COMMENT '快照时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_experiment_id` (`experiment_id`),
    KEY `idx_snapshot_time` (`snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AB实验结果表';

CREATE TABLE IF NOT EXISTS `document_pipeline` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `pipeline_id` VARCHAR(64) NOT NULL COMMENT '管道ID',
    `name` VARCHAR(128) NOT NULL COMMENT '管道名称',
    `description` VARCHAR(512) NULL COMMENT '描述',
    `source_type` VARCHAR(32) NOT NULL COMMENT '源文件类型',
    `chunk_size` INT NOT NULL DEFAULT 512 COMMENT '分块大小',
    `chunk_overlap` INT NOT NULL DEFAULT 50 COMMENT '分块重叠',
    `embedding_model` VARCHAR(128) NOT NULL COMMENT '向量模型',
    `vector_dimension` INT NOT NULL COMMENT '向量维度',
    `status` VARCHAR(32) NOT NULL COMMENT '状态',
    `created_by` VARCHAR(64) NULL COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_pipeline_id` (`pipeline_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档解析管道表';

CREATE TABLE IF NOT EXISTS `document_task` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务ID',
    `pipeline_id` VARCHAR(64) NOT NULL COMMENT '管道ID',
    `file_name` VARCHAR(256) NOT NULL COMMENT '文件名',
    `file_path` VARCHAR(512) NOT NULL COMMENT '文件路径',
    `file_size` BIGINT NULL COMMENT '文件大小',
    `status` VARCHAR(32) NOT NULL COMMENT '状态',
    `phase` VARCHAR(32) NULL COMMENT '当前阶段',
    `progress` DECIMAL(5,4) NOT NULL DEFAULT 0 COMMENT '进度',
    `total_chunks` INT NULL COMMENT '总分块数',
    `vector_store` VARCHAR(64) NULL COMMENT '向量存储位置',
    `error_detail` TEXT NULL COMMENT '错误详情',
    `started_at` DATETIME NULL COMMENT '开始时间',
    `completed_at` DATETIME NULL COMMENT '完成时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    KEY `idx_pipeline_id` (`pipeline_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档处理任务表';

CREATE TABLE IF NOT EXISTS `document_chunk` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `chunk_id` VARCHAR(64) NOT NULL COMMENT '块ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务ID',
    `content` TEXT NOT NULL COMMENT '块内容',
    `metadata` JSON NULL COMMENT '元数据',
    `embedding` TEXT NULL COMMENT '向量数据(base64)',
    `page_number` INT NULL COMMENT '页码',
    `start_index` INT NULL COMMENT '起始位置',
    `end_index` INT NULL COMMENT '结束位置',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_chunk_id` (`chunk_id`),
    KEY `idx_task_id` (`task_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档分块表';

CREATE TABLE IF NOT EXISTS `gpu_node` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `node_id` VARCHAR(64) NOT NULL COMMENT '节点ID',
    `hostname` VARCHAR(128) NOT NULL COMMENT '主机名',
    `ip_address` VARCHAR(64) NOT NULL COMMENT 'IP地址',
    `gpu_count` INT NOT NULL DEFAULT 0 COMMENT 'GPU总数',
    `gpu_model` VARCHAR(64) NULL COMMENT 'GPU型号',
    `total_gpu_memory_gb` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '总GPU显存(GB)',
    `available_gpu_memory_gb` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '可用GPU显存(GB)',
    `status` VARCHAR(32) NOT NULL COMMENT '状态:ONLINE,OFFLINE,MAINTENANCE',
    `labels` JSON NULL COMMENT '标签',
    `last_heartbeat` DATETIME NULL COMMENT '最后心跳',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_node_id` (`node_id`),
    UNIQUE KEY `uk_hostname` (`hostname`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GPU节点表';

CREATE TABLE IF NOT EXISTS `gpu_task` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务ID',
    `name` VARCHAR(128) NOT NULL COMMENT '任务名称',
    `task_type` VARCHAR(32) NOT NULL COMMENT '任务类型',
    `priority` INT NOT NULL DEFAULT 5 COMMENT '优先级(1-10,10最高)',
    `required_gpu_memory_gb` DECIMAL(10,2) NOT NULL DEFAULT 0 COMMENT '所需GPU显存(GB)',
    `gpu_count` INT NOT NULL DEFAULT 1 COMMENT '所需GPU数量',
    `node_id` VARCHAR(64) NULL COMMENT '分配的节点ID',
    `gpu_indices` VARCHAR(32) NULL COMMENT '分配的GPU索引(逗号分隔)',
    `status` VARCHAR(32) NOT NULL COMMENT '状态:PENDING,SCHEDULED,RUNNING,COMPLETED,FAILED,CANCELLED',
    `preemptible` TINYINT NOT NULL DEFAULT 1 COMMENT '是否可抢占',
    `preempted_by` VARCHAR(64) NULL COMMENT '被哪个任务抢占',
    `command` TEXT NULL COMMENT '执行命令',
    `parameters` JSON NULL COMMENT '参数',
    `error_detail` TEXT NULL COMMENT '错误详情',
    `submitted_by` VARCHAR(64) NULL COMMENT '提交人',
    `submitted_at` DATETIME NULL COMMENT '提交时间',
    `scheduled_at` DATETIME NULL COMMENT '调度时间',
    `started_at` DATETIME NULL COMMENT '开始时间',
    `completed_at` DATETIME NULL COMMENT '完成时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    KEY `idx_status_priority` (`status`, `priority`),
    KEY `idx_node_id` (`node_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='GPU任务表';

CREATE TABLE IF NOT EXISTS `feature_registry` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `feature_id` VARCHAR(64) NOT NULL COMMENT '特征ID',
    `name` VARCHAR(128) NOT NULL COMMENT '特征名称',
    `description` VARCHAR(512) NULL COMMENT '描述',
    `version` INT NOT NULL DEFAULT 1 COMMENT '版本',
    `data_type` VARCHAR(32) NOT NULL COMMENT '数据类型',
    `feature_type` VARCHAR(32) NOT NULL COMMENT '特征类型:ONLINE,OFFLINE,BOTH',
    `entity` VARCHAR(64) NOT NULL COMMENT '所属实体',
    `source` VARCHAR(128) NULL COMMENT '数据来源',
    `ttl_seconds` BIGINT NULL COMMENT 'TTL(秒)',
    `schema_def` JSON NULL COMMENT 'Schema定义',
    `status` VARCHAR(32) NOT NULL COMMENT '状态',
    `created_by` VARCHAR(64) NULL COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_feature_id_version` (`feature_id`, `version`),
    KEY `idx_entity` (`entity`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='特征注册表';

CREATE TABLE IF NOT EXISTS `feature_value` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `feature_id` VARCHAR(64) NOT NULL COMMENT '特征ID',
    `entity_id` VARCHAR(64) NOT NULL COMMENT '实体ID',
    `value` TEXT NULL COMMENT '特征值',
    `timestamp` DATETIME NOT NULL COMMENT '时间戳',
    `is_online` TINYINT NOT NULL DEFAULT 1 COMMENT '是否在线存储',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_feature_entity_time` (`feature_id`, `entity_id`, `timestamp`),
    KEY `idx_feature_id` (`feature_id`),
    KEY `idx_entity_id` (`entity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='特征值表';

CREATE TABLE IF NOT EXISTS `model_provider` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `provider_id` VARCHAR(64) NOT NULL COMMENT 'ProviderID',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `type` VARCHAR(32) NOT NULL COMMENT '类型:OPENAI,ANTHROPIC,QIANFAN,LOCAL,CUSTOM',
    `api_endpoint` VARCHAR(256) NULL COMMENT 'API端点',
    `api_key` VARCHAR(256) NULL COMMENT 'API Key(加密)',
    `timeout_ms` INT NOT NULL DEFAULT 30000 COMMENT '超时时间',
    `max_retries` INT NOT NULL DEFAULT 3 COMMENT '最大重试次数',
    `weight` INT NOT NULL DEFAULT 1 COMMENT '负载均衡权重',
    `status` VARCHAR(32) NOT NULL COMMENT '状态:ACTIVE,INACTIVE,DEGRADED',
    `circuit_breaker_open` TINYINT NOT NULL DEFAULT 0 COMMENT '熔断器是否打开',
    `error_threshold` DECIMAL(5,4) NOT NULL DEFAULT 0.5 COMMENT '错误阈值',
    `config` JSON NULL COMMENT '扩展配置',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_provider_id` (`provider_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型Provider表';

CREATE TABLE IF NOT EXISTS `route_rule` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `rule_id` VARCHAR(64) NOT NULL COMMENT '规则ID',
    `name` VARCHAR(128) NOT NULL COMMENT '名称',
    `model_name` VARCHAR(128) NOT NULL COMMENT '模型名称',
    `match_condition` JSON NULL COMMENT '匹配条件',
    `provider_ids` JSON NOT NULL COMMENT '目标Provider列表',
    `strategy` VARCHAR(32) NOT NULL DEFAULT 'ROUND_ROBIN' COMMENT '负载策略:ROUND_ROBIN,LEAST_CONN,WEIGHTED_RANDOM',
    `fallback_provider_ids` JSON NULL COMMENT 'Fallback Provider列表',
    `priority` INT NOT NULL DEFAULT 0 COMMENT '优先级',
    `enabled` TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_rule_id` (`rule_id`),
    KEY `idx_model_name` (`model_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='路由规则表';

CREATE TABLE IF NOT EXISTS `model_registry` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `model_id` VARCHAR(64) NOT NULL COMMENT '模型ID',
    `name` VARCHAR(128) NOT NULL COMMENT '模型名称',
    `description` VARCHAR(512) NULL COMMENT '描述',
    `model_type` VARCHAR(32) NOT NULL COMMENT '模型类型:LLM,EMBEDDING,CV,AUDIO',
    `framework` VARCHAR(64) NULL COMMENT '训练框架',
    `base_model` VARCHAR(128) NULL COMMENT '基座模型',
    `created_by` VARCHAR(64) NULL COMMENT '创建人',
    `tags` JSON NULL COMMENT '标签',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model_id` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型注册表';

CREATE TABLE IF NOT EXISTS `model_version` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `version_id` VARCHAR(64) NOT NULL COMMENT '版本ID',
    `model_id` VARCHAR(64) NOT NULL COMMENT '模型ID',
    `version` VARCHAR(32) NOT NULL COMMENT '版本号',
    `stage` VARCHAR(32) NOT NULL DEFAULT 'NONE' COMMENT '阶段:NONE,STAGING,PRODUCTION,ARCHIVED',
    `checkpoint_path` VARCHAR(512) NULL COMMENT 'checkpoint路径',
    `metrics` JSON NULL COMMENT '评估指标',
    `training_params` JSON NULL COMMENT '训练参数',
    `dataset_info` JSON NULL COMMENT '数据集信息',
    `artifact_uri` VARCHAR(512) NULL COMMENT '制品地址',
    `description` VARCHAR(512) NULL COMMENT '描述',
    `created_by` VARCHAR(64) NULL COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_version_id` (`version_id`),
    UNIQUE KEY `uk_model_id_version` (`model_id`, `version`),
    KEY `idx_model_id` (`model_id`),
    KEY `idx_stage` (`stage`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型版本表';

CREATE TABLE IF NOT EXISTS `stage_transition_log` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `log_id` VARCHAR(64) NOT NULL COMMENT '日志ID',
    `version_id` VARCHAR(64) NOT NULL COMMENT '版本ID',
    `from_stage` VARCHAR(32) NULL COMMENT '原阶段',
    `to_stage` VARCHAR(32) NOT NULL COMMENT '目标阶段',
    `reason` VARCHAR(512) NULL COMMENT '原因',
    `operator` VARCHAR(64) NULL COMMENT '操作人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_log_id` (`log_id`),
    KEY `idx_version_id` (`version_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Stage流转日志表';

CREATE TABLE IF NOT EXISTS `evaluation_task` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务ID',
    `name` VARCHAR(128) NOT NULL COMMENT '任务名称',
    `type` VARCHAR(32) NOT NULL COMMENT '类型:OFFLINE,ONLINE',
    `model_id` VARCHAR(64) NOT NULL COMMENT '模型ID',
    `model_version` VARCHAR(32) NOT NULL COMMENT '模型版本',
    `dataset_id` VARCHAR(64) NULL COMMENT '数据集ID',
    `status` VARCHAR(32) NOT NULL COMMENT '状态',
    `metrics_config` JSON NULL COMMENT '指标配置',
    `result_summary` JSON NULL COMMENT '结果汇总',
    `started_at` DATETIME NULL COMMENT '开始时间',
    `completed_at` DATETIME NULL COMMENT '完成时间',
    `created_by` VARCHAR(64) NULL COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_task_id` (`task_id`),
    KEY `idx_model_id` (`model_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估任务表';

CREATE TABLE IF NOT EXISTS `evaluation_metric` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `metric_id` VARCHAR(64) NOT NULL COMMENT '指标ID',
    `task_id` VARCHAR(64) NOT NULL COMMENT '任务ID',
    `metric_name` VARCHAR(64) NOT NULL COMMENT '指标名称',
    `metric_value` DECIMAL(15,6) NOT NULL COMMENT '指标值',
    `comparison_value` DECIMAL(15,6) NULL COMMENT '对比值',
    `trend` VARCHAR(16) NULL COMMENT '趋势:UP,DOWN,STABLE',
    `threshold` DECIMAL(15,6) NULL COMMENT '阈值',
    `is_anomaly` TINYINT NOT NULL DEFAULT 0 COMMENT '是否异常',
    `snapshot_time` DATETIME NOT NULL COMMENT '快照时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_metric_id` (`metric_id`),
    KEY `idx_task_id` (`task_id`),
    KEY `idx_snapshot_time` (`snapshot_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='评估指标表';

CREATE TABLE IF NOT EXISTS `drift_detection_log` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `log_id` VARCHAR(64) NOT NULL COMMENT '日志ID',
    `model_id` VARCHAR(64) NOT NULL COMMENT '模型ID',
    `model_version` VARCHAR(32) NOT NULL COMMENT '模型版本',
    `drift_type` VARCHAR(32) NOT NULL COMMENT '漂移类型:DATA_DRIFT,CONCEPT_DRIFT,PREDICTION_DRIFT',
    `feature_name` VARCHAR(128) NULL COMMENT '特征名称',
    `drift_score` DECIMAL(10,6) NOT NULL COMMENT '漂移分数',
    `threshold` DECIMAL(10,6) NOT NULL COMMENT '阈值',
    `is_detected` TINYINT NOT NULL DEFAULT 0 COMMENT '是否检测到漂移',
    `stats_data` JSON NULL COMMENT '统计数据',
    `detection_time` DATETIME NOT NULL COMMENT '检测时间',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_log_id` (`log_id`),
    KEY `idx_model_version` (`model_id`, `model_version`),
    KEY `idx_detection_time` (`detection_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='漂移检测日志表';

CREATE TABLE IF NOT EXISTS `adversarial_attack` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `attack_id` VARCHAR(64) NOT NULL COMMENT '攻击ID',
    `name` VARCHAR(128) NOT NULL COMMENT '攻击名称',
    `attack_type` VARCHAR(32) NOT NULL COMMENT '攻击类型',
    `strategy` VARCHAR(64) NOT NULL COMMENT '攻击策略',
    `description` VARCHAR(512) NULL COMMENT '描述',
    `target_model_id` VARCHAR(64) NOT NULL COMMENT '目标模型ID',
    `target_model_version` VARCHAR(32) NOT NULL COMMENT '目标模型版本',
    `parameters` JSON NULL COMMENT '攻击参数',
    `status` VARCHAR(32) NOT NULL COMMENT '状态',
    `total_samples` INT NOT NULL DEFAULT 0 COMMENT '生成样本数',
    `success_count` INT NOT NULL DEFAULT 0 COMMENT '成功攻击数',
    `success_rate` DECIMAL(5,4) NULL COMMENT '成功率',
    `started_at` DATETIME NULL COMMENT '开始时间',
    `completed_at` DATETIME NULL COMMENT '完成时间',
    `created_by` VARCHAR(64) NULL COMMENT '创建人',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted` TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_attack_id` (`attack_id`),
    KEY `idx_target_model` (`target_model_id`, `target_model_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对抗攻击表';

CREATE TABLE IF NOT EXISTS `adversarial_sample` (
    `id` BIGINT NOT NULL COMMENT '主键ID',
    `sample_id` VARCHAR(64) NOT NULL COMMENT '样本ID',
    `attack_id` VARCHAR(64) NOT NULL COMMENT '攻击ID',
    `original_prompt` TEXT NOT NULL COMMENT '原始Prompt',
    `adversarial_prompt` TEXT NOT NULL COMMENT '对抗Prompt',
    `target_response` TEXT NULL COMMENT '目标响应',
    `model_response` TEXT NULL COMMENT '模型实际响应',
    `is_success` TINYINT NOT NULL DEFAULT 0 COMMENT '是否攻击成功',
    `attack_score` DECIMAL(10,6) NULL COMMENT '攻击分数',
    `evaluation_result` JSON NULL COMMENT '评估结果',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sample_id` (`sample_id`),
    KEY `idx_attack_id` (`attack_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对抗样本表';
