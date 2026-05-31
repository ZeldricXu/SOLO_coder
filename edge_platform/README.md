# 边缘计算平台 - Edge Platform

基于事件驱动架构的轻量级边缘计算平台，包含9个核心模块。

## 模块说明

### 1. 调度模块 (scheduler)
- 任务执行状态追踪
- 支持优先级队列
- 乐观锁并发冲突处理（最多重试3次）
- 任务超时控制

### 2. 固件OTA升级模块 (ota)
- 差分升级包生成（bsdiff算法）
- 分批灰度升级策略
- 失败自动回滚机制
- 版本管理

### 3. 设备影子同步模块 (device_shadow)
- 维护云端设备期望状态
- 设备实际状态同步
- 增量(delta)计算
- 定时同步循环

### 4. 边缘规则引擎模块 (rule_engine)
- 轻量规则执行
- 条件表达式求值
- 多动作类型支持
- 冷却时间控制

### 5. 存储管理模块 (storage)
- 对象存储适配（本地/S3）
- 元数据索引
- 标签检索
- 文件管理

### 6. 通知模块 (notification)
- 多渠道消息推送（Email/Webhook/SMS/In-App）
- 模板渲染引擎
- 失败重试机制

### 7. 协议适配转换模块 (protocol)
- 多工业协议驱动（Modbus/MQTT/OPC UA）
- 数据格式标准化
- 跨协议数据转发

### 8. 监控统计模块 (monitoring)
- 业务指标采集
- 多维度聚合分析
- 告警规则配置
- 仪表盘数据

### 9. 边缘推理调度模块 (inference)
- AI模型边缘部署
- 推理任务调度
- 结果回传
- 多模型管理

## 快速开始

```python
import asyncio
from edge_platform.main import EdgePlatform

async def run():
    platform = EdgePlatform()
    platform.initialize()
    await platform.start()
    
    # 获取平台统计
    stats = platform.get_all_stats()
    print(stats)
    
    await platform.stop()

asyncio.run(run())
```

## 目录结构

```
edge_platform/
├── __init__.py
├── main.py
├── common/
│   ├── __init__.py
│   ├── event_bus.py
│   ├── config.py
│   └── exceptions.py
├── scheduler/
├── ota/
├── device_shadow/
├── rule_engine/
├── storage/
├── notification/
├── protocol/
├── monitoring/
└── inference/
```

## 依赖安装

```bash
pip install -r requirements.txt
```
