from datetime import datetime
from typing import Optional, Dict, Any
from uuid import UUID
from pydantic import BaseModel, Field, ConfigDict


class SchemaVersionCreate(BaseModel):
    schema_name: str = Field(..., description="Schema名称")
    definition: Dict[str, Any] = Field(..., description="Schema定义")
    description: Optional[str] = Field(None, description="描述")
    migration_script: Optional[str] = Field(None, description="迁移脚本")
    rollback_script: Optional[str] = Field(None, description="回滚脚本")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class SchemaVersionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="版本ID")
    schema_name: str = Field(..., description="Schema名称")
    version: int = Field(..., description="版本号")
    definition: Dict[str, Any] = Field(..., description="Schema定义")
    description: Optional[str] = Field(None, description="描述")
    is_current: bool = Field(..., description="是否为当前版本")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class DataMigrationCreate(BaseModel):
    name: str = Field(..., description="迁移名称")
    source_schema_version_id: UUID = Field(..., description="源Schema版本ID")
    target_schema_version_id: UUID = Field(..., description="目标Schema版本ID")
    script: str = Field(..., description="迁移脚本")
    rollback_script: Optional[str] = Field(None, description="回滚脚本")
    is_auto_recoverable: bool = Field(True, description="是否可自动恢复")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class DataMigrationResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: UUID = Field(..., description="迁移ID")
    name: str = Field(..., description="迁移名称")
    source_schema_version_id: UUID = Field(..., description="源Schema版本ID")
    target_schema_version_id: UUID = Field(..., description="目标Schema版本ID")
    status: str = Field(..., description="状态")
    started_at: Optional[datetime] = Field(None, description="开始时间")
    completed_at: Optional[datetime] = Field(None, description="完成时间")
    rows_processed: int = Field(..., description="已处理行数")
    rows_failed: int = Field(..., description="失败行数")
    error_message: Optional[str] = Field(None, description="错误信息")
    is_auto_recoverable: bool = Field(..., description="是否可自动恢复")
    retry_count: int = Field(..., description="重试次数")
    created_at: datetime = Field(..., description="创建时间")
    metadata: Dict[str, Any] = Field(default_factory=dict, description="元数据")


class MigrationExecuteRequest(BaseModel):
    migration_id: UUID = Field(..., description="迁移ID")
    mode: str = Field("execute", description="执行模式: execute/rollback")
    dry_run: bool = Field(False, description="是否试运行")
