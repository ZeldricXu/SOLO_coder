from datetime import datetime
from typing import Optional, Dict, Any, List
from uuid import UUID
from pydantic import BaseModel, Field, ConfigDict


class PromptCreate(BaseModel):
    name: str = Field(..., description="Prompt名称")
    content: str = Field(..., description="Prompt内容")
    template_variables: Dict[str, Any] = Field(default_factory=dict, description="模板变量")
    llm_config: Dict[str, Any] = Field(default_factory=dict, description="模型配置")
    description: Optional[str] = Field(None, description="描述")
    tags: List[str] = Field(default_factory=list, description="标签")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class PromptResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="Prompt ID")
    name: str = Field(..., description="Prompt名称")
    version: int = Field(..., description="版本号")
    content: str = Field(..., description="Prompt内容")
    template_variables: Dict[str, Any] = Field(default_factory=dict, description="模板变量")
    llm_config: Dict[str, Any] = Field(default_factory=dict, description="模型配置")
    is_active: bool = Field(..., description="是否激活")
    description: Optional[str] = Field(None, description="描述")
    tags: List[str] = Field(default_factory=list, description="标签")
    created_by: UUID = Field(..., description="创建人ID")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class PromptVersionResponse(BaseModel):
    id: UUID = Field(..., description="Prompt ID")
    name: str = Field(..., description="Prompt名称")
    version: int = Field(..., description="版本号")
    created_at: datetime = Field(..., description="创建时间")


class ABTestCreate(BaseModel):
    name: str = Field(..., description="AB测试名称")
    control_prompt_id: UUID = Field(..., description="对照组Prompt ID")
    treatment_prompt_id: UUID = Field(..., description="实验组Prompt ID")
    traffic_split: float = Field(0.5, description="流量分配比例")
    primary_metric: str = Field(..., description="主要指标")
    metrics: List[str] = Field(default_factory=list, description="指标列表")
    description: Optional[str] = Field(None, description="描述")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class ABTestResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="AB测试ID")
    name: str = Field(..., description="AB测试名称")
    description: Optional[str] = Field(None, description="描述")
    control_prompt_id: UUID = Field(..., description="对照组Prompt ID")
    treatment_prompt_id: UUID = Field(..., description="实验组Prompt ID")
    traffic_split: float = Field(..., description="流量分配比例")
    status: str = Field(..., description="状态")
    start_time: Optional[datetime] = Field(None, description="开始时间")
    end_time: Optional[datetime] = Field(None, description="结束时间")
    primary_metric: Optional[str] = Field(None, description="主要指标")
    metrics: List[str] = Field(default_factory=list, description="指标列表")
    results: Dict[str, Any] = Field(default_factory=dict, description="结果")
    created_by: UUID = Field(..., description="创建人ID")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class ABTestResult(BaseModel):
    ab_test_id: UUID = Field(..., description="AB测试ID")
    control_impressions: int = Field(..., description="对照组展示次数")
    treatment_impressions: int = Field(..., description="实验组展示次数")
    control_conversions: int = Field(..., description="对照组转化次数")
    treatment_conversions: int = Field(..., description="实验组转化次数")
    confidence: float = Field(..., description="置信度")
    is_statistically_significant: bool = Field(..., description="是否统计显著")
    uplift: float = Field(..., description="提升率")
    details: Dict[str, Any] = Field(default_factory=dict, description="详情")


class PromptExperimentCreate(BaseModel):
    name: str = Field(..., description="实验名称")
    prompt_id: UUID = Field(..., description="Prompt ID")
    test_cases: List[Dict[str, Any]] = Field(default_factory=list, description="测试用例")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class ExperimentEvaluation(BaseModel):
    test_case_id: str = Field(..., description="测试用例ID")
    output: str = Field(..., description="输出")
    scores: Dict[str, float] = Field(..., description="评分")
    feedback: Optional[str] = Field(None, description="反馈")


class PromptExperimentResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="实验ID")
    name: str = Field(..., description="实验名称")
    prompt_id: UUID = Field(..., description="Prompt ID")
    test_cases: List[Dict[str, Any]] = Field(default_factory=list, description="测试用例")
    evaluations: List[Dict[str, Any]] = Field(default_factory=list, description="评估结果")
    status: str = Field(..., description="状态")
    created_by: UUID = Field(..., description="创建人ID")
    results_summary: Dict[str, Any] = Field(default_factory=dict, description="结果摘要")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")
