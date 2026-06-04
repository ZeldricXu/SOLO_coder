# GPU故障应急处理手册

## 1. 故障分级标准

| 级别 | 描述 | 响应时间 | 通知范围 |
|------|------|----------|----------|
| P0 | 生产环境核心业务不可用，影响面大 | 立即 | 全员 + 负责人 |
| P1 | 部分GPU故障，自动切换成功 | 15分钟 | 值班人员 |
| P2 | 单GPU故障，不影响服务 | 1小时 | 运维人员 |
| P3 | GPU利用率预警 | 4小时 | 运维人员 |

## 2. 故障排查流程

```
告警触发 → 确认故障等级 → 定位故障原因 → 执行应急预案 → 恢复服务 → 根因分析 → 修复优化
```

### 2.1 快速检查清单

1. **GPU节点状态检查**
   ```bash
   # 检查K8s GPU节点状态
   kubectl get nodes -l nvidia.com/gpu.present=true
   
   # 检查节点详细信息
   kubectl describe node <gpu-node-name>
   ```

2. **GPU设备检查**
   ```bash
   # 登录到GPU节点
   ssh <gpu-node-name>
   
   # 检查GPU状态
   nvidia-smi
   
   # 检查NVIDIA驱动版本
   cat /proc/driver/nvidia/version
   
   # 检查GPU进程
   nvidia-smi pmon
   ```

3. **Pod状态检查**
   ```bash
   # 检查推理Pod状态
   kubectl get pods -n inference-platform -l app=triton-server
   
   # 查看Pod事件
   kubectl describe pod <pod-name> -n inference-platform
   
   # 查看Pod日志
   kubectl logs <pod-name> -n inference-platform --tail 100
   ```

4. **服务状态检查**
   ```bash
   # 检查Triton服务健康
   curl http://<service-ip>:8000/v2/health/ready
   
   # 检查推理平台健康
   curl http://<platform-ip>:8080/health
   
   # 查看实例列表
   curl http://<platform-ip>:8080/api/v1/instances
   ```

## 3. 常见故障类型及处理

### 3.1 GPU节点宕机

**现象**:
- GPU节点NotReady状态
- 该节点上的所有Pod无法调度
- 相关服务QPS下降，延迟上升

**处理步骤**:

1. **确认节点状态**
   ```bash
   kubectl get node <gpu-node-name>
   ```

2. **疏散节点上的Pod**
   ```bash
   kubectl drain <gpu-node-name> --ignore-daemonsets --delete-local-data
   ```

3. **标记节点不可调度**
   ```bash
   kubectl cordon <gpu-node-name>
   ```

4. **检查硬件故障**
   ```bash
   # 远程管理卡检查（如iDRAC, IPMI）
   ipmitool -H <bmc-ip> -U admin power status
   
   # 尝试重启节点
   ipmitool -H <bmc-ip> -U admin power reset
   ```

5. **联系硬件运维**排查服务器硬件问题

6. **节点恢复后**
   ```bash
   # 标记节点可调度
   kubectl uncordon <gpu-node-name>
   ```

**预期恢复时间**: 15-30分钟

### 3.2 单GPU卡故障

**现象**:
- 单个GPU显示ERR状态
- nvidia-smi显示温度异常
- 相关Pod频繁重启

**处理步骤**:

1. **确认GPU故障**
   ```bash
   nvidia-smi
   # 观察是否有GPU显示为ERR
   ```

2. **检查相关Pod**
   ```bash
   kubectl get pods -n inference-platform -o wide | grep <gpu-node-name>
   ```

3. **手动驱逐故障GPU上的Pod**
   ```bash
   # 给节点打标签，让Pod自动迁移
   kubectl label node <gpu-node-name> nvidia.com/gpu.${gpu-id}=faulty
   
   # 或手动删除受影响的Pod
   kubectl delete pod <affected-pod> -n inference-platform
   ```

4. **隔离故障GPU**
   ```bash
   # 在节点上禁用该GPU
   # 修改NVIDIA驱动配置或使用nvidia-cdi
   ```

5. **联系硬件运维**更换GPU卡

**预期恢复时间**: 5-10分钟（Pod自动迁移）

### 3.3 GPU显存溢出（OOM）

**现象**:
- Pod日志显示CUDA out of memory
- 推理请求大量失败
- Pod异常退出，CrashLoopBackOff

**处理步骤**:

1. **查看Pod日志确认OOM**
   ```bash
   kubectl logs <pod-name> -n inference-platform | grep -i out
   ```

2. **检查模型显存使用**
   ```bash
   # 连接到正常Pod查看显存使用
   kubectl exec -it <healthy-pod> -n inference-platform -- nvidia-smi
   ```

3. **临时解决方案**:
   - 减小模型batch size
   - 减少instance count
   - 启用FP16混合精度

4. **调整部署配置**:
   ```bash
   # 修改Helm values，增加GPU资源限制
   vim helm/inference-platform/values-production.yaml
   
   # 升级部署
   helm upgrade inference-platform ./helm/inference-platform \
     -f helm/inference-platform/values-production.yaml \
     -n inference-platform
   ```

5. **长期解决方案**:
   - 模型量化（FP16, INT8）
   - 模型剪枝
   - 模型并行

**预期恢复时间**: 5-15分钟

### 3.4 GPU过热降频

**现象**:
- GPU温度 > 85°C
- 推理延迟显著增加
- nvidia-smi显示降频状态

**处理步骤**:

1. **确认GPU温度**
   ```bash
   nvidia-smi --query-gpu=temperature.gpu --format=csv
   ```

2. **检查机房环境**
   - 确认空调运行正常
   - 确认机房温度在正常范围（18-27°C）

3. **减轻GPU负载**
   ```bash
   # 临时扩容，分散负载
   kubectl scale deployment <triton-deployment> -n inference-platform --replicas=+2
   ```

4. **检查风扇状态**
   ```bash
   ipmitool sensor list | grep -i fan
   ```

5. **联系机房运维**处理散热问题

**预期恢复时间**: 10-20分钟

### 3.5 NVIDIA驱动故障

**现象**:
- nvidia-smi命令失败
- Pod无法启动，显示找不到GPU设备
- dmesg显示NVIDIA驱动错误

**处理步骤**:

1. **确认驱动状态**
   ```bash
   nvidia-smi
   dmesg | grep -i nvidia
   lsmod | grep nvidia
   ```

2. **重新加载驱动**
   ```bash
   # 卸载驱动
   rmmod nvidia_uvm nvidia_drm nvidia_modeset nvidia
   
   # 重新加载
   modprobe nvidia
   ```

3. **重启容器运行时**
   ```bash
   systemctl restart containerd
   # 或
   systemctl restart docker
   ```

4. **如果问题持续，考虑升级/降级驱动版本**

5. **驱动版本兼容性检查**:
   - NVIDIA驱动版本: 535.xxx+
   - CUDA版本: 12.0+
   - Triton版本: 24.01+

**预期恢复时间**: 10-30分钟

### 3.6 GPU Plugin故障

**现象**:
- Pod处于Pending状态
- 事件显示nvidia.com/gpu资源不足
- kubectl describe node显示GPU可分配数量为0

**处理步骤**:

1. **检查GPU Plugin Pod**
   ```bash
   kubectl get pods -n kube-system -l name=nvidia-device-plugin-ds
   ```

2. **查看GPU Plugin日志**
   ```bash
   kubectl logs -n kube-system -l name=nvidia-device-plugin-ds
   ```

3. **重启GPU Plugin**
   ```bash
   kubectl rollout restart daemonset nvidia-device-plugin-daemonset -n kube-system
   ```

4. **验证GPU资源**
   ```bash
   kubectl describe node <gpu-node-name> | grep -A 10 "Allocated resources"
   ```

**预期恢复时间**: 2-5分钟

## 4. 应急预案

### 4.1 P0级故障（全平台不可用）

**执行步骤**:

1. **立即通知**相关人员（电话 + 企业微信 + 钉钉）

2. **快速降级**:
   - 停止所有非核心业务推理
   - 将核心业务切换到备用集群
   - 启用CPU兜底方案（如果有）

3. **快速回滚**:
   - 如果是新上线导致，立即回滚
   - `helm rollback inference-platform -n inference-platform`

4. **并行排查**:
   - 检查K8s集群状态
   - 检查网络连通性
   - 检查存储服务
   - 检查数据库状态

5. **恢复后**进行压力测试验证

### 4.2 多GPU节点故障

**执行步骤**:

1. **停止新模型部署**
   ```bash
   # 通过API暂停自动扩缩容
   curl -X POST http://platform/api/v1/orchestrator/pause
   ```

2. **降低非核心模型副本数**
   ```bash
   # 手动调整副本数
   kubectl scale deployment <non-critical-model> -n inference-platform --replicas=0
   ```

3. **启动备用GPU节点**（如果有）

4. **启动CPU实例作为临时替代**
   - 修改instance_group，增加KIND_CPU实例
   - 降低CPU实例的batch size

## 5. 告警规则配置

### 5.1 Prometheus告警规则

```yaml
groups:
  - name: gpu_alerts
    rules:
      - alert: GPUNodeDown
        expr: kube_node_status_condition{condition="Ready", status="false", node=~"gpu-.*"} == 1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "GPU节点 {{ $labels.node }} 宕机"
          description: "GPU节点 {{ $labels.node }} 已经5分钟未就绪"

      - alert: GPUHighTemperature
        expr: nvidia_gpu_temperature_celsius > 85
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "GPU温度过高"
          description: "GPU {{ $labels.gpu }} 温度为 {{ $value }}°C"

      - alert: GPUMemoryHigh
        expr: nvidia_gpu_memory_used_bytes / nvidia_gpu_memory_total_bytes > 0.9
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "GPU显存使用率过高"
          description: "GPU {{ $labels.gpu }} 显存使用率为 {{ $value | humanizePercentage }}"

      - alert: GPUUtilizationLow
        expr: avg_over_time(nvidia_gpu_utilization[10m]) < 10
        for: 30m
        labels:
          severity: info
        annotations:
          summary: "GPU利用率过低"
          description: "GPU {{ $labels.gpu }} 过去30分钟平均利用率为 {{ $value }}%"

      - alert: TritonPodCrashLooping
        expr: kube_pod_container_status_restarts_total{namespace="inference-platform", pod=~".*triton.*"} > 3
        for: 15m
        labels:
          severity: critical
        annotations:
          summary: "Triton Pod频繁重启"
          description: "Pod {{ $labels.pod }} 在15分钟内重启 {{ $value }} 次"

      - alert: InferenceErrorRateHigh
        expr: rate(inference_requests_failed_total[5m]) / rate(inference_requests_total[5m]) > 0.05
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "推理错误率过高"
          description: "模型 {{ $labels.model }} 错误率为 {{ $value | humanizePercentage }}"

      - alert: InferenceLatencyHigh
        expr: histogram_quantile(0.95, rate(inference_request_duration_seconds_bucket[5m])) > 1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "推理延迟过高"
          description: "模型 {{ $labels.model }} P95延迟为 {{ $value }}s"
```

### 5.2 告警通知渠道

| 告警级别 | 企业微信 | 钉钉 | 邮件 | 电话 |
|----------|----------|------|------|------|
| P0 | ✅ 全员群 | ✅ 全员群 | ✅ | ✅ 值班人员 |
| P1 | ✅ 运维群 | ✅ 运维群 | ✅ | ❌ |
| P2 | ✅ 运维群 | ❌ | ✅ | ❌ |
| P3 | ❌ | ❌ | ✅ | ❌ |

## 6. 日常运维检查清单

### 每日检查
- [ ] GPU节点状态正常
- [ ] GPU温度 < 80°C
- [ ] GPU显存使用率 < 80%
- [ ] 推理错误率 < 1%
- [ ] 没有Pod CrashLoopBackOff

### 每周检查
- [ ] NVIDIA驱动版本兼容性
- [ ] GPU固件版本
- [ ] 机房空调运行状态
- [ ] 告警规则有效性

### 每月检查
- [ ] GPU压力测试
- [ ] 故障演练（单节点宕机）
- [ ] 驱动版本评估升级
- [ ] 容量规划回顾

## 7. 联系清单

| 角色 | 联系人 | 电话 | 企业微信 |
|------|--------|------|----------|
| 值班运维 | XXX | 138-xxxx-xxxx | @xxx |
| K8s负责人 | XXX | 138-xxxx-xxxx | @xxx |
| GPU硬件运维 | XXX | 138-xxxx-xxxx | @xxx |
| 算法负责人 | XXX | 138-xxxx-xxxx | @xxx |
| 平台负责人 | XXX | 138-xxxx-xxxx | @xxx |

## 8. 附录：常用命令速查

### GPU状态检查
```bash
# 基本状态
nvidia-smi

# 实时监控
nvidia-smi dmon -s puvm -d 1

# 进程监控
nvidia-smi pmon -s u -d 1

# 温度监控
nvidia-smi --query-gpu=temperature.gpu,utilization.gpu,memory.used --format=csv -l 1
```

### Kubernetes相关
```bash
# 查看GPU节点
kubectl get nodes -L nvidia.com/gpu.count -L nvidia.com/gpu.product

# 查看GPU资源分配
kubectl describe nodes -l nvidia.com/gpu.present=true | grep -E "^Name:|nvidia.com/gpu"

# 查看Triton Pod分布
kubectl get pods -n inference-platform -l app=triton-server -o wide

# 查看Pod事件
kubectl get events -n inference-platform --sort-by=.lastTimestamp
```

### 日志查看
```bash
# Triton日志
kubectl logs -n inference-platform -l app=triton-server --tail=100 -f

# 编排器日志
kubectl logs -n inference-platform -l app.kubernetes.io/name=inference-platform --tail=100 -f

# GPU插件日志
kubectl logs -n kube-system -l name=nvidia-device-plugin-ds --tail=100
```
