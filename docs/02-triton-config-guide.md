# Triton模型配置文件指南

本指南详细介绍Triton Inference Server的config.pbtxt配置文件中的常用配置项。

## 配置文件结构

```protobuf
name: "model-name"
backend: "pytorch_libtorch"
max_batch_size: 32

input [
  {
    name: "input_0"
    data_type: TYPE_FP32
    dims: [ 3, 224, 224 ]
  }
]

output [
  {
    name: "output_0"
    data_type: TYPE_FP32
    dims: [ 1000 ]
  }
]

# 动态批处理配置
dynamic_batching {
  preferred_batch_size: [ 8, 16, 32 ]
  max_queue_delay_microseconds: 1000
}

# 实例组配置
instance_group [
  {
    count: 2
    kind: KIND_GPU
    gpus: [ 0, 1 ]
  }
]

# 模型版本策略
version_policy: { latest { num_versions: 2 } }
```

## 核心配置项说明

### 1. 基础配置

#### name
- **类型**: string
- **说明**: 模型名称，必须与目录名一致
- **示例**: `name: "resnet50"`

#### platform / backend
- **类型**: string
- **说明**: 模型平台或后端类型
- **可选值**:
  - `tensorflow_savedmodel` - TensorFlow SavedModel
  - `pytorch_libtorch` - PyTorch TorchScript
  - `onnxruntime_onnx` - ONNX
  - `tensorrt_plan` - TensorRT
  - `python` - Python后端
- **示例**: `backend: "pytorch_libtorch"`

#### max_batch_size
- **类型**: int32
- **说明**: 模型支持的最大batch size
- **注意**: 设置为0表示不支持batching
- **示例**: `max_batch_size: 32`

### 2. 输入输出配置

#### input / output
- **类型**: repeated Message
- **说明**: 定义模型的输入和输出张量

```protobuf
input [
  {
    name: "input_0"              # 张量名称，必须与模型一致
    data_type: TYPE_FP32         # 数据类型
    dims: [ 3, 224, 224 ]        # 张量维度（不含batch维度）
    reshape: { shape: [ -1 ] }   # 可选：输入重塑
    is_shape_tensor: false       # 是否为形状张量
  }
]
```

##### 支持的数据类型
| 类型 | 说明 |
|------|------|
| TYPE_BOOL | 布尔型 |
| TYPE_UINT8 | 8位无符号整数 |
| TYPE_UINT16 | 16位无符号整数 |
| TYPE_UINT32 | 32位无符号整数 |
| TYPE_UINT64 | 64位无符号整数 |
| TYPE_INT8 | 8位有符号整数 |
| TYPE_INT16 | 16位有符号整数 |
| TYPE_INT32 | 32位有符号整数 |
| TYPE_INT64 | 64位有符号整数 |
| TYPE_FP16 | 16位浮点数 |
| TYPE_FP32 | 32位浮点数 |
| TYPE_FP64 | 64位浮点数 |
| TYPE_STRING | 字符串 |

### 3. 动态批处理 (Dynamic Batching)

**作用**: 将多个推理请求组合成一个批次进行处理，提高吞吐量。

```protobuf
dynamic_batching {
  # 优先使用的batch size列表
  preferred_batch_size: [ 8, 16, 32 ]
  
  # 最大排队等待时间（微秒）
  max_queue_delay_microseconds: 1000
  
  # 是否保留排序
  preserve_ordering: false
  
  # 优先级配置
  priority_levels: 2
  default_priority_level: 1
  default_queue_policy: {
    timeout_action: DELAY
  }
}
```

#### 配置项说明

| 配置项 | 说明 | 推荐值 |
|--------|------|--------|
| preferred_batch_size | 优先batch size列表。当队列中的请求数达到这些值之一时立即发送。 | 根据GPU显存调整，如 [8, 16, 32] |
| max_queue_delay_microseconds | 最大等待时间。即使没有达到preferred_batch_size，超过此时间也会发送。 | 1000-5000 (1-5ms) |
| preserve_ordering | 是否保持请求顺序。如果需要严格顺序推理，设置为true。 | false |
| priority_levels | 优先级级别数量。 | 2-5 |

#### 动态批处理最佳实践

1. **吞吐量优先**:
   ```protobuf
   dynamic_batching {
     preferred_batch_size: [ 32, 64 ]
     max_queue_delay_microseconds: 5000  # 5ms
   }
   ```

2. **延迟优先**:
   ```protobuf
   dynamic_batching {
     preferred_batch_size: [ 4, 8 ]
     max_queue_delay_microseconds: 500   # 0.5ms
   }
   ```

3. **序列模型（如Transformer）**:
   ```protobuf
   dynamic_batching {
     preferred_batch_size: [ 1, 2, 4, 8 ]
     max_queue_delay_microseconds: 2000
   }
   ```

### 4. 实例组 (Instance Group)

**作用**: 控制模型在CPU/GPU上的并行实例数量。

```protobuf
instance_group [
  {
    # 实例数量
    count: 2
    
    # 实例类型：KIND_GPU / KIND_CPU / KIND_MODEL
    kind: KIND_GPU
    
    # 使用的GPU列表（仅KIND_GPU有效）
    gpus: [ 0, 1 ]
    
    # 每个GPU的实例数
    count_per_gpu: 1
    
    # 实例名称（可选）
    name: "gpu_instance"
    
    # 资源配置
    resource_limit {
      kind: "nvidia.com/gpu"
      count: 1
    }
  }
]
```

#### 实例类型说明

| 类型 | 说明 | 适用场景 |
|------|------|----------|
| KIND_GPU | GPU加速实例 | 大多数深度学习模型 |
| KIND_CPU | CPU实例 | 小模型、预处理后处理 |
| KIND_MODEL | 模型定义 | 封装模型逻辑 |

#### 实例组配置示例

1. **单GPU多实例** (适用于小模型):
   ```protobuf
   instance_group [
     {
       count: 4
       kind: KIND_GPU
       gpus: [ 0 ]
     }
   ]
   ```

2. **多GPU各一个实例**:
   ```protobuf
   instance_group [
     {
       count: 2
       kind: KIND_GPU
       gpus: [ 0, 1 ]
     }
   ]
   ```

3. **CPU+GPU混合**:
   ```protobuf
   instance_group [
     {
       count: 2
       kind: KIND_GPU
       gpus: [ 0, 1 ]
     },
     {
       count: 4
       kind: KIND_CPU
     }
   ]
   ```

### 5. 模型版本策略

#### 策略类型

1. **最新N个版本** (推荐):
   ```protobuf
   version_policy: {
     latest {
       num_versions: 2    # 保留最新的2个版本
     }
   }
   ```

2. **所有版本**:
   ```protobuf
   version_policy: { all { } }
   ```

3. **指定版本**:
   ```protobuf
   version_policy: {
     specific {
       versions: [ 1, 3, 5 ]  # 只加载版本1、3、5
     }
   }
   ```

### 6. 模型优化配置

#### 优化等级
```protobuf
optimization {
  # 优化优先级
  priority: PRIORITY_MAX
  
  # TensorRT优化配置
  input_pinned_memory: true
  output_pinned_memory: true
  
  # GPU内核超时（毫秒）
  kernel_timeout: 10000
}
```

#### TensorRT特定配置
```protobuf
optimization {
  execution_accelerators {
    gpu_execution_accelerator: [
      {
        name: "tensorrt"
        parameters {
          key: "precision_mode"
          value: "fp16"
        }
        parameters {
          key: "max_workspace_size_bytes"
          value: "2147483648"  # 2GB
        }
        parameters {
          key: "max_cached_engines"
          value: "2"
        }
      }
    ]
  }
}
```

#### 精度模式说明
| 模式 | 说明 | 性能 | 精度 |
|------|------|------|------|
| fp32 | 单精度浮点数 | 基准 | 最高 |
| fp16 | 半精度浮点数 | ~2x | 轻微下降 |
| int8 | 8位整数 | ~4x | 需要校准 |

### 7. 模型预热 (Warmup)

**作用**: 服务启动时预热模型，避免首次请求延迟。

```protobuf
model_warmup [
  {
    name: "warmup_bs8"
    
    # 输入数据类型：RANDOM / ZERO / FILE
    type: RANDOM
    
    # 张量形状
    input_tensor {
      key: "input_0"
      value {
        zero: { dims: [ 8, 3, 224, 224 ] }
      }
    }
    
    # 预热次数
    iterations: 10
    
    # 批量大小
    batch_size: 8
  }
]
```

### 8. 完整配置示例

#### 示例1: ResNet50图像分类（GPU + 动态批处理）

```protobuf
name: "resnet50"
backend: "pytorch_libtorch"
max_batch_size: 64

input [
  {
    name: "input_0"
    data_type: TYPE_FP32
    dims: [ 3, 224, 224 ]
  }
]

output [
  {
    name: "output_0"
    data_type: TYPE_FP32
    dims: [ 1000 ]
  }
]

dynamic_batching {
  preferred_batch_size: [ 8, 16, 32, 64 ]
  max_queue_delay_microseconds: 2000
}

instance_group [
  {
    count: 2
    kind: KIND_GPU
    gpus: [ 0 ]
  }
]

version_policy: { latest { num_versions: 2 } }

optimization {
  execution_accelerators {
    gpu_execution_accelerator: [
      {
        name: "tensorrt"
        parameters {
          key: "precision_mode"
          value: "fp16"
        }
      }
    ]
  }
}

model_warmup [
  {
    name: "warmup"
    type: RANDOM
    input_tensor {
      key: "input_0"
      value {
        zero: { dims: [ 32, 3, 224, 224 ] }
      }
    }
    iterations: 5
    batch_size: 32
  }
]
```

#### 示例2: BERT文本分类（多GPU）

```protobuf
name: "bert-base"
backend: "onnxruntime_onnx"
max_batch_size: 32

input [
  {
    name: "input_ids"
    data_type: TYPE_INT64
    dims: [ 128 ]
  },
  {
    name: "attention_mask"
    data_type: TYPE_INT64
    dims: [ 128 ]
  }
]

output [
  {
    name: "logits"
    data_type: TYPE_FP32
    dims: [ 2 ]
  }
]

dynamic_batching {
  preferred_batch_size: [ 4, 8, 16, 32 ]
  max_queue_delay_microseconds: 5000
  preserve_ordering: true
}

instance_group [
  {
    count: 1
    kind: KIND_GPU
    gpus: [ 0, 1 ]
    count_per_gpu: 1
  }
]

version_policy: { latest { num_versions: 1 } }
```

#### 示例3: CPU轻量模型

```protobuf
name: "text-preprocess"
backend: "python"
max_batch_size: 128

input [
  {
    name: "text"
    data_type: TYPE_STRING
    dims: [ 1 ]
  }
]

output [
  {
    name: "tokens"
    data_type: TYPE_INT64
    dims: [ 128 ]
  }
]

dynamic_batching {
  preferred_batch_size: [ 32, 64, 128 ]
  max_queue_delay_microseconds: 10000
}

instance_group [
  {
    count: 8
    kind: KIND_CPU
  }
]

version_policy: { all { } }
```

### 9. 配置调优指南

#### 步骤1: 确定最大Batch Size
```bash
# 使用triton-client进行压力测试
perf_analyzer -m model-name -b 1:32 --concurrency-range 1:16
```

#### 步骤2: 配置动态批处理
- **高QPS场景**: 增大 `preferred_batch_size`，增大 `max_queue_delay_microseconds`
- **低延迟场景**: 减小 `preferred_batch_size`，减小 `max_queue_delay_microseconds`

#### 步骤3: 调整实例数量
- GPU利用率 > 85%: 考虑增加GPU或减少实例数
- GPU利用率 < 50%: 可以增加每个GPU的实例数

#### 步骤4: 启用混合精度
- 验证精度：先在验证集上测试FP16精度
- 启用TensorRT：使用TensorRT引擎进一步加速

## 参考文档

- [Triton官方文档](https://docs.nvidia.com/deeplearning/triton-inference-server/user-guide/docs/)
- [模型配置proto定义](https://github.com/triton-inference-server/common/blob/main/proto/model_config.proto)
