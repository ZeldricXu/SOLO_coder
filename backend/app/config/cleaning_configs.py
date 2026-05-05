"""
数据清洗配置示例
包含常用的清洗规则模板
"""

DEFAULT_CLEANING_CONFIG = {
    "field_configs": [
        {
            "field_id": "*",
            "field_name": "默认规则",
            "null_treatment": "keep",
            "outlier_method": "none",
            "text_normalization": []
        }
    ],
    "global_settings": {
        "drop_duplicates": False,
        "reset_index": True
    }
}

BASIC_CLEANING_CONFIG = {
    "field_configs": [
        {
            "field_id": "*",
            "field_name": "通用清洗",
            "null_treatment": "keep",
            "outlier_method": "none",
            "text_normalization": ["trim"]
        }
    ],
    "global_settings": {
        "drop_duplicates": False,
        "reset_index": True
    }
}

STRICT_CLEANING_CONFIG = {
    "field_configs": [
        {
            "field_id": "*",
            "field_name": "严格清洗",
            "null_treatment": "drop",
            "outlier_method": "iqr",
            "outlier_action": "mark",
            "text_normalization": ["trim", "lower"]
        }
    ],
    "global_settings": {
        "drop_duplicates": True,
        "reset_index": True
    }
}

NUMERIC_CLEANING_CONFIG = {
    "field_configs": [
        {
            "field_id": "*_numeric",
            "field_name": "数值字段",
            "null_treatment": "fill_mean",
            "outlier_method": "z_score",
            "outlier_threshold": 3.0,
            "outlier_action": "cap",
            "numeric_decimals": 4
        }
    ],
    "global_settings": {
        "drop_duplicates": False,
        "reset_index": True
    }
}

TEXT_CLEANING_CONFIG = {
    "field_configs": [
        {
            "field_id": "*_text",
            "field_name": "文本字段",
            "null_treatment": "fill",
            "fill_value": "未知",
            "text_normalization": ["trim", "lower", "remove_special"]
        }
    ],
    "global_settings": {
        "drop_duplicates": False,
        "reset_index": True
    }
}

CUSTOMER_SURVEY_CONFIG = {
    "description": "客户满意度调查专用清洗配置",
    "field_configs": [
        {
            "field_id": "q_gender",
            "field_name": "性别",
            "null_treatment": "mark",
            "text_normalization": ["trim", "lower"]
        },
        {
            "field_id": "q_age",
            "field_name": "年龄",
            "null_treatment": "fill_median",
            "outlier_method": "iqr",
            "outlier_action": "cap",
            "numeric_decimals": 0
        },
        {
            "field_id": "q_satisfaction",
            "field_name": "满意度评分",
            "null_treatment": "fill_mean",
            "outlier_method": "z_score",
            "outlier_threshold": 2.5,
            "outlier_action": "mark",
            "numeric_decimals": 1
        },
        {
            "field_id": "q_income",
            "field_name": "收入水平",
            "null_treatment": "fill_mode",
            "text_normalization": ["trim"]
        },
        {
            "field_id": "q_comment",
            "field_name": "用户评论",
            "null_treatment": "fill",
            "fill_value": "",
            "text_normalization": ["trim"]
        }
    ],
    "global_settings": {
        "drop_duplicates": True,
        "reset_index": True
    }
}

MARKET_RESEARCH_CONFIG = {
    "description": "市场调研专用清洗配置",
    "field_configs": [
        {
            "field_id": "q_brand_awareness",
            "field_name": "品牌认知",
            "null_treatment": "drop",
            "text_normalization": ["trim"]
        },
        {
            "field_id": "q_purchase_intent",
            "field_name": "购买意愿",
            "null_treatment": "fill_mode",
            "outlier_method": "none"
        },
        {
            "field_id": "q_price_sensitivity",
            "field_name": "价格敏感度",
            "null_treatment": "fill_mean",
            "outlier_method": "percentile",
            "lower_percentile": 1.0,
            "upper_percentile": 99.0,
            "outlier_action": "cap"
        }
    ],
    "global_settings": {
        "drop_duplicates": True,
        "reset_index": True
    }
}

def get_config_by_name(config_name: str) -> dict:
    """
    根据名称获取预设配置
    
    Args:
        config_name: 配置名称（default, basic, strict, numeric, text, customer, market）
        
    Returns:
        配置字典
    """
    configs = {
        "default": DEFAULT_CLEANING_CONFIG,
        "basic": BASIC_CLEANING_CONFIG,
        "strict": STRICT_CLEANING_CONFIG,
        "numeric": NUMERIC_CLEANING_CONFIG,
        "text": TEXT_CLEANING_CONFIG,
        "customer": CUSTOMER_SURVEY_CONFIG,
        "market": MARKET_RESEARCH_CONFIG
    }
    
    return configs.get(config_name.lower(), DEFAULT_CLEANING_CONFIG)

def create_field_config(
    field_id: str,
    field_name: str,
    null_treatment: str = "keep",
    fill_value: Any = None,
    outlier_method: str = "none",
    outlier_threshold: float = 3.0,
    outlier_action: str = "mark",
    text_normalization: List[str] = None,
    date_format: str = None,
    numeric_decimals: int = None
) -> dict:
    """
    创建单个字段的清洗配置
    
    Args:
        field_id: 字段ID
        field_name: 字段名称
        null_treatment: 空值处理方式（keep, drop, fill, fill_mean, fill_median, fill_mode, mark）
        fill_value: 填充值（当null_treatment为fill时）
        outlier_method: 异常值检测方法（none, z_score, iqr, percentile）
        outlier_threshold: 异常值阈值（Z-score阈值或百分位数范围）
        outlier_action: 异常值处理方式（mark, drop, cap）
        text_normalization: 文本标准化列表（trim, lower, upper, remove_whitespace, remove_special）
        date_format: 日期格式（如 "%Y-%m-%d"）
        numeric_decimals: 数值小数位数
        
    Returns:
        字段配置字典
    """
    config = {
        "field_id": field_id,
        "field_name": field_name,
        "null_treatment": null_treatment,
        "outlier_method": outlier_method
    }
    
    if fill_value is not None:
        config["fill_value"] = fill_value
    if outlier_method != "none":
        config["outlier_threshold"] = outlier_threshold
        config["outlier_action"] = outlier_action
    if text_normalization:
        config["text_normalization"] = text_normalization
    if date_format:
        config["date_format"] = date_format
    if numeric_decimals is not None:
        config["numeric_decimals"] = numeric_decimals
    
    return config

def create_cleaning_config(
    field_configs: List[dict],
    drop_duplicates: bool = False,
    reset_index: bool = True
) -> dict:
    """
    创建完整的清洗配置
    
    Args:
        field_configs: 字段配置列表
        drop_duplicates: 是否删除重复行
        reset_index: 是否重置索引
        
    Returns:
        完整的清洗配置字典
    """
    return {
        "field_configs": field_configs,
        "global_settings": {
            "drop_duplicates": drop_duplicates,
            "reset_index": reset_index
        }
    }
