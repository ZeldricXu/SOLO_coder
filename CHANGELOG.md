# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [1.0.0] - 2026-05-27

### ✨ Features

- **工单智能分配模块**：基于技能匹配与负载均衡的工单路由分配
  - 技能匹配算法：支持多种操作符（等于、大于、小于、包含、IN、正则、嵌套字段）
  - 负载均衡算法：基于当前负载、最大并发计算最优分配
  - 分配策略：综合技能匹配度和负载情况加权计算
  - 状态管理：工单状态流转（待分配/处理中/已完成/已关闭/已取消）

- **多租户隔离策略模块**：完善的租户管理与配额控制
  - 租户生命周期管理：创建、激活、挂起、删除
  - 配额管理：硬限制/软限制、阈值告警、自动重置
  - 配置管理：系统配置保护、租户级配置隔离
  - 成员管理：多租户成员权限控制

- **审批规则引擎模块**：灵活可配置的审批流程
  - 规则引擎：支持 AND/OR 条件组合、自定义操作符
  - 流程管理：顺序审批、会签、或签多种审批模式
  - 资源管理：审批完成后自动资源释放
  - 操作留痕：完整的审批记录与状态追踪

- **工程化体系**：完整的开发、测试、部署流程
  - 代码质量门禁：Ruff 静态分析 + Mypy 类型检查 + 80% 覆盖率阈值
  - 统一任务入口：Makefile 一键执行所有常用命令
  - Docker 开发环境：完整的本地开发编排
  - 环境变量管理：开发/预发/生产三环境配置隔离
  - 自动化版本发布：bump2version + CHANGELOG 自动生成

### ✅ Tests

- 工单智能分配模块：36 个测试用例，覆盖参数校验完备性
- 多租户隔离策略模块：35 个测试用例，验证超时降级行为正确性
- 审批规则引擎模块：38 个测试用例，测试资源释放完整性行为
- 总计：164 个测试用例全部通过

### 🛠️ Infrastructure

- FastAPI 异步后端架构
- SQLAlchemy 2.0 异步 ORM
- Pydantic v2 数据校验
- Celery 异步任务队列
- Redis 缓存支持
- Poetry 依赖管理
