# APIShield - API安全网关与攻击检测系统

APIShield是一个综合性的API安全平台，集成了8个核心安全模块，为数据处理和AI场景提供全方位的安全保障。

## 功能模块

### 1. 审计日志防篡改模块 (audit_chain)
- 基于哈希链的审计日志存储
- 操作日志完整性验证
- 篡改检测与定位
- 支持按操作类型和序列号查询

### 2. 动态数据脱敏模块 (data_masking)
- 基于用户角色的动态脱敏策略
- 支持多种脱敏规则：掩码、替换、哈希、截断
- 内置常用敏感数据模式识别（身份证、手机号、邮箱、银行卡等）
- 支持自定义脱敏规则和角色配置

### 3. 密钥分片管理模块 (shamir)
- Shamir秘密共享算法实现
- 密钥分片生成与分发
- 阈值密钥恢复
- 分片持有者管理

### 4. 可信执行环境模块 (tee_manager)
- Enclave生命周期管理（创建、启动、暂停、恢复、销毁）
- 远程证明与身份认证
- 安全数据加密与解密
- Enclave健康状态监控

### 5. 联邦学习协调模块 (federated_learning)
- 训练任务创建与分发
- 客户端注册与管理
- FedAvg梯度聚合算法
- 全局模型版本管理
- 训练进度监控

### 6. 数据分类分级模块 (data_classification)
- 敏感数据自动扫描识别
- 五级分类等级（公开、内部、秘密、机密、绝密）
- 内置10种敏感数据识别模式
- 分类策略管理与自动应用

### 7. 差分隐私注入模块 (differential_privacy)
- Laplace和Gaussian噪声机制
- 隐私预算管理与消耗跟踪
- 支持计数、求和、平均等统计查询
- 高级组合定理计算

### 8. 安全多方计算模块 (mpc_coordinator)
- 支持多种MPC协议（秘密共享、混淆电路、同态加密）
- 参与方注册与会话管理
- 安全计算操作（求和、平均、乘积、最大、最小）
- 计算进度监控与结果验证

## 快速开始

### 安装依赖

```bash
cd session164
pip install -r requirements.txt
```

### 启动服务

```bash
python -m uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

### 访问API文档

- Swagger UI: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc

## API接口概览

所有API接口均以 `/api/v1` 为前缀。

### 审计日志
- `POST /api/v1/audit/logs` - 添加审计日志
- `GET /api/v1/audit/logs` - 查询审计日志
- `GET /api/v1/audit/verify` - 验证链完整性
- `GET /api/v1/audit/detect-tampering` - 检测篡改

### 数据脱敏
- `POST /api/v1/masking/mask` - 数据脱敏
- `POST /api/v1/masking/auto-mask` - 自动识别并脱敏
- `GET /api/v1/masking/roles` - 获取角色列表
- `GET /api/v1/masking/roles/{role_name}` - 获取角色脱敏规则

### 密钥分片
- `POST /api/v1/shamir/split` - 密钥分片
- `POST /api/v1/shamir/reconstruct` - 密钥恢复
- `GET /api/v1/shamir/keys` - 列出密钥
- `DELETE /api/v1/shamir/keys/{key_id}` - 删除密钥

### TEE管理
- `POST /api/v1/tee/enclaves` - 创建Enclave
- `POST /api/v1/tee/enclaves/{id}/start` - 启动Enclave
- `POST /api/v1/tee/enclaves/{id}/attest` - 生成证明
- `GET /api/v1/tee/enclaves/{id}/health` - 健康检查

### 联邦学习
- `POST /api/v1/fl/clients` - 注册客户端
- `POST /api/v1/fl/tasks` - 创建训练任务
- `POST /api/v1/fl/updates` - 提交模型更新
- `POST /api/v1/fl/tasks/{id}/aggregate` - 聚合梯度

### 数据分类
- `POST /api/v1/classification/scan` - 扫描数据
- `POST /api/v1/classification/apply-policy/{policy_id}` - 应用策略
- `GET /api/v1/classification/levels` - 获取分类等级
- `GET /api/v1/classification/patterns` - 获取识别模式

### 差分隐私
- `POST /api/v1/dp/budgets` - 创建隐私预算
- `POST /api/v1/dp/noise` - 添加噪声
- `POST /api/v1/dp/count` - 隐私计数
- `POST /api/v1/dp/sum` - 隐私求和
- `POST /api/v1/dp/average` - 隐私平均

### 安全多方计算
- `POST /api/v1/mpc/parties` - 注册参与方
- `POST /api/v1/mpc/sessions` - 创建计算会话
- `POST /api/v1/mpc/inputs` - 提交加密输入
- `POST /api/v1/mpc/sessions/{id}/compute` - 执行计算
- `GET /api/v1/mpc/sessions/{id}/result` - 获取计算结果

## 技术栈

- **语言**: Python 3.8+
- **Web框架**: FastAPI
- **数据验证**: Pydantic v2
- **异步支持**: ASGI (Uvicorn)
- **零第三方加密依赖**: 全部使用Python标准库实现

## 项目结构

```
session164/
├── core/
│   ├── __init__.py
│   ├── models.py          # 公共数据模型
│   ├── config.py          # 配置管理
│   ├── utils.py           # 工具函数
│   ├── audit_chain.py     # 审计日志哈希链
│   ├── data_masking.py    # 动态数据脱敏
│   ├── shamir.py          # 密钥分片管理
│   ├── tee_manager.py     # TEE Enclave管理
│   ├── federated_learning.py  # 联邦学习协调
│   ├── data_classification.py  # 数据分类分级
│   ├── differential_privacy.py # 差分隐私注入
│   └── mpc_coordinator.py     # 安全多方计算协调
├── api/
│   ├── __init__.py
│   └── routes.py          # API路由定义
├── tests/
│   └── __init__.py
├── main.py                # 应用入口
├── requirements.txt       # 依赖清单
└── README.md              # 项目文档
```

## 使用示例

### 1. 添加审计日志

```python
import requests

response = requests.post(
    "http://localhost:8000/api/v1/audit/logs",
    json={
        "action": "user_login",
        "actor": "user_001",
        "resource": "auth_service",
        "details": {"ip": "192.168.1.100", "success": True}
    }
)
print(response.json())
```

### 2. 数据脱敏

```python
import requests

data = {
    "name": "张三",
    "email": "zhangsan@example.com",
    "phone": "13800138000",
    "id_card": "110101199001011234"
}

response = requests.post(
    "http://localhost:8000/api/v1/masking/mask",
    json={"data": data, "role": "user"}
)
print(response.json()["data"])
```

### 3. 密钥分片与恢复

```python
import requests

# 生成分片
response = requests.post(
    "http://localhost:8000/api/v1/shamir/split",
    json={"key_length": 32, "threshold": 3, "total": 5}
)
result = response.json()["data"]
shares = result["shares"]
key_id = result["metadata"]["key_id"]

# 恢复密钥
response = requests.post(
    "http://localhost:8000/api/v1/shamir/reconstruct",
    json={"shares": shares[:3]}
)
reconstructed_key = response.json()["data"]["key"]
```

## 注意事项

1. 本项目为演示版本，所有加密算法均使用Python标准库实现，生产环境建议使用专业的密码学库（如cryptography）。
2. 当前实现使用内存存储，重启后数据会丢失。如需持久化，请集成数据库。
3. TEE模块为模拟实现，实际部署需要配合硬件TEE（如Intel SGX、AMD SEV）使用。
4. 联邦学习和MPC模块为协调层实现，需要配合实际的客户端SDK使用。

## License

MIT License
