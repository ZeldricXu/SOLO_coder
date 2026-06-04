# 新模型接入指南

本指南描述了如何将一个新的机器学习模型部署到推理平台。

## 整体流程

```
模型训练完成 → 模型文件准备 → 上传到模型仓库 → 注册模型 → 创建模型版本 → 部署实例 → 验证调用
```

## 1. 模型文件准备

### 1.1 Triton模型目录结构

```
model-store/
└── your-model-name/
    ├── 1/                          # 版本号，建议使用递增整数
    │   └── model.savedmodel/       # TensorFlow SavedModel
    │       ├── saved_model.pb
    │       └── variables/
    │   # 或者
    │   └── model.pt                # PyTorch模型文件
    │   # 或者
    │   └── model.plan              # TensorRT引擎
    │   # 或者
    │   └── model.onnx              # ONNX模型
    └── config.pbtxt                # Triton模型配置文件
```

### 1.2 支持的模型格式

| 格式 | 后端 | 文件扩展名 |
|------|------|-----------|
| TensorFlow | tensorflow_savedmodel | .savedmodel/ |
| PyTorch | pytorch | .pt, .pth |
| ONNX | onnxruntime | .onnx |
| TensorRT | tensorrt | .plan |
| Python | python | model.py |

## 2. 上传模型文件

### 2.1 本地开发环境（docker-compose）

直接将模型文件复制到本地 `model-store` 目录：

```bash
# 创建模型目录结构
mkdir -p model-store/your-model-name/1

# 复制模型文件
cp -r /path/to/your/model model-store/your-model-name/1/

# 创建配置文件
vim model-store/your-model-name/config.pbtxt
```

### 2.2 生产环境（S3/NFS）

#### S3上传

```bash
# 使用AWS CLI上传
aws s3 cp --recursive your-model-name/ s3://inference-models-production/your-model-name/

# 或者使用MinIO客户端
mc cp --recursive your-model-name/ minio/inference-models/your-model-name/
```

#### NFS共享存储

```bash
# 挂载NFS共享
mount -t nfs nfs-server:/inference-models /mnt/inference-models

# 复制模型文件
cp -r your-model-name/ /mnt/inference-models/
```

## 3. 注册模型

调用推理平台API注册模型：

```bash
curl -X POST http://inference-platform.your-company.com/api/v1/models \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${API_TOKEN}" \
  -d '{
    "name": "resnet50-image-classification",
    "description": "ResNet50图像分类模型",
    "framework": "pytorch",
    "task_type": "classification",
    "input_type": "image",
    "output_type": "classification",
    "labels": ["vision", "classification"],
    "namespace": "default"
  }'
```

## 4. 创建模型版本

```bash
curl -X POST http://inference-platform.your-company.com/api/v1/models/resnet50-image-classification/versions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${API_TOKEN}" \
  -d '{
    "version": "1.0.0",
    "description": "初始版本，基于ImageNet训练",
    "accuracy": 0.925,
    "gpu_memory_mb": 4096,
    "metrics": {
      "accuracy": 0.925,
      "precision": 0.918,
      "recall": 0.922
    },
    "training_dataset": "ImageNet 2012",
    "base_model_version": null
  }'
```

## 5. 部署模型实例

### 5.1 通过API部署

```bash
curl -X POST http://inference-platform.your-company.com/api/v1/models/resnet50-image-classification/versions/1.0.0/deploy \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${API_TOKEN}" \
  -d '{
    "min_replicas": 2,
    "max_replicas": 8,
    "gpu_count": 1,
    "autoscaling": {
      "enabled": true,
      "cpu_threshold": 70,
      "memory_threshold": 80
    }
  }'
```

### 5.2 通过Helm部署（K8s环境）

```bash
# 添加模型实例配置到values-production.yaml
vim helm/inference-platform/values-production.yaml

# 升级部署
helm upgrade inference-platform ./helm/inference-platform \
  -f helm/inference-platform/values-production.yaml \
  -n inference-platform
```

## 6. 验证部署

### 6.1 检查实例状态

```bash
curl http://inference-platform.your-company.com/api/v1/instances \
  -H "Authorization: Bearer ${API_TOKEN}"
```

### 6.2 测试推理请求

```bash
# 使用测试图片进行推理
curl -X POST http://inference-platform.your-company.com/v2/models/resnet50-image-classification/infer \
  -H "Content-Type: application/json" \
  -T test_image.json
```

请求示例（JSON格式）：
```json
{
  "id": "request-001",
  "inputs": [
    {
      "name": "input_0",
      "shape": [1, 3, 224, 224],
      "datatype": "FP32",
      "data": [...]
    }
  ]
}
```

### 6.3 使用SDK调用

```python
from inference_sdk import InferenceClient

client = InferenceClient(
    endpoint="http://inference-platform.your-company.com",
    api_key="${API_TOKEN}"
)

# 同步调用
result = client.infer(
    model_name="resnet50-image-classification",
    version="1.0.0",
    inputs={"input_0": image_data}
)

# 异步调用
async_result = client.infer_async(
    model_name="resnet50-image-classification",
    version="1.0.0",
    inputs={"input_0": image_data}
)
```

## 7. 配置A/B测试（可选）

如果需要进行灰度发布或A/B测试：

```bash
curl -X POST http://inference-platform.your-company.com/api/v1/ab-test \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${API_TOKEN}" \
  -d '{
    "name": "resnet50-v1-vs-v2",
    "model_name": "resnet50-image-classification",
    "versions": [
      {
        "version": "1.0.0",
        "weight": 70
      },
      {
        "version": "2.0.0",
        "weight": 30
      }
    ],
    "features": {
      "user_segment": ["vip"]
    },
    "start_time": "2024-01-01T00:00:00Z",
    "end_time": "2024-01-15T00:00:00Z"
  }'
```

## 8. 监控与告警

部署后，在Grafana大盘中查看：
- 吞吐量（QPS）
- 平均延迟（p50, p95, p99）
- GPU利用率
- 错误率

配置告警规则：
- 错误率 > 1% 持续5分钟 → P2告警
- 平均延迟 > 500ms → P2告警
- GPU利用率 > 90% 持续10分钟 → 自动扩缩容触发

## 9. 回滚操作

如果新版本有问题，快速回滚：

```bash
# 切换到旧版本
curl -X POST http://inference-platform.your-company.com/api/v1/models/resnet50-image-classification/rollback \
  -H "Content-Type: application/json" \
  -d '{
    "target_version": "1.0.0"
  }'
```

## 常见问题

### Q: 模型部署后状态一直是STARTING？

A: 检查以下几点：
1. Triton容器日志：`kubectl logs -l app=triton-server,model=xxx`
2. 模型配置文件语法：`tritonserver --model-repository=/models --model-control-mode=poll`
3. GPU资源是否充足：`kubectl describe node <gpu-node>`

### Q: 推理请求超时？

A: 
1. 检查模型batch size配置
2. 增加实例数量
3. 检查网络延迟
4. 考虑使用动态批处理

### Q: 如何优化推理性能？

A:
1. 使用TensorRT优化模型
2. 配置动态批处理（dynamic batching）
3. 调整instance group数量
4. 使用FP16/INT8量化
