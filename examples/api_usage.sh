#!/bin/bash

BASE_URL="http://localhost:8080"

echo "=== API Gateway Examples ==="

echo -e "\n1. 创建资源"
curl -X POST "$BASE_URL/api/v1/resources" \
  -H "Content-Type: application/json" \
  -H "X-Trace-ID: trace-001" \
  -d '{
    "type": "task",
    "config": {"timeout": 30},
    "labels": {"env": "production"}
  }' | jq .

echo -e "\n\n2. 执行核心处理"
curl -X POST "$BASE_URL/api/v1/execute" \
  -H "Content-Type: application/json" \
  -H "X-Trace-ID: trace-002" \
  -d '{
    "data": {"key": "value"},
    "namespace": "default",
    "operation": "process"
  }' | jq .

echo -e "\n\n3. 查询资源状态"
curl -X GET "$BASE_URL/api/v1/resources/rsc_001/status" \
  -H "X-Trace-ID: trace-003" | jq .

echo -e "\n\n4. 批量操作"
curl -X POST "$BASE_URL/api/v1/resources/batch" \
  -H "Content-Type: application/json" \
  -H "X-Trace-ID: trace-004" \
  -d '{
    "operations": [
      {"action": "start", "id": "rsc_001"},
      {"action": "stop", "id": "rsc_002"}
    ]
  }' | jq .

echo -e "\n=== Storage Examples ==="

echo -e "\n5. 创建全量备份"
curl -X POST "$BASE_URL/api/v1/storage/backup" \
  -H "Content-Type: application/json" \
  -d '{"type": "full"}' | jq .

echo -e "\n\n6. 创建增量备份"
curl -X POST "$BASE_URL/api/v1/storage/backup" \
  -H "Content-Type: application/json" \
  -d '{"type": "incremental"}' | jq .

echo -e "\n\n7. 列出备份"
curl -X GET "$BASE_URL/api/v1/storage/backups" | jq .

echo -e "\n=== TEE Examples ==="

echo -e "\n8. 创建Enclave"
curl -X POST "$BASE_URL/api/v1/tee/enclave" \
  -H "Content-Type: application/json" \
  -d '{
    "enclave_type": "sgx",
    "size": 1024,
    "ttl": 3600
  }' | jq .

echo -e "\n\n9. 远程证明"
curl -X POST "$BASE_URL/api/v1/tee/enclave/enc_001/attest" \
  -H "Content-Type: application/json" \
  -d '{
    "nonce": "random-nonce-123",
    "challenge": "challenge-data"
  }' | jq .

echo -e "\n\n10. 安全执行"
curl -X POST "$BASE_URL/api/v1/tee/enclave/enc_001/execute" \
  -H "Content-Type: application/json" \
  -d '{
    "function": "hash",
    "arguments": ["sensitive-data"]
  }' | jq .

echo -e "\n=== MPC Examples ==="

echo -e "\n11. 注册参与方"
curl -X POST "$BASE_URL/api/v1/mpc/participant" \
  -H "Content-Type: application/json" \
  -d '{
    "id": "party_001",
    "address": "192.168.1.100:8000",
    "pubkey": "-----BEGIN PUBLIC KEY-----\nMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA..."
  }' | jq .

echo -e "\n\n12. 创建MPC协议"
curl -X POST "$BASE_URL/api/v1/mpc/protocol" \
  -H "Content-Type: application/json" \
  -d '{
    "protocol_type": "spdz",
    "participant_ids": ["party_001", "party_002", "party_003"]
  }' | jq .

echo -e "\n\n13. 执行MPC计算"
curl -X POST "$BASE_URL/api/v1/mpc/protocol/prot_001/execute" \
  -H "Content-Type: application/json" \
  -d '{
    "operation": "sum",
    "inputs": {
      "party_001": 10.5,
      "party_002": 20.3,
      "party_003": 15.7
    }
  }' | jq .

echo -e "\n=== Federated Learning Examples ==="

echo -e "\n14. 创建训练任务"
curl -X POST "$BASE_URL/api/v1/federated/task" \
  -H "Content-Type: application/json" \
  -d '{
    "model_id": "model_v1",
    "config": {"batch_size": 32, "epochs": 10},
    "required_clients": 3
  }' | jq .

echo -e "\n\n15. 分发训练任务"
curl -X POST "$BASE_URL/api/v1/federated/task/task_001/distribute" | jq .

echo -e "\n\n16. 提交梯度"
curl -X POST "$BASE_URL/api/v1/federated/task/task_001/gradient" \
  -H "Content-Type: application/json" \
  -d '{
    "client_id": "client_001",
    "data": {"weights": [0.1, 0.2, 0.3]},
    "weight": 0.33
  }' | jq .

echo -e "\n\n17. 聚合并更新模型"
curl -X POST "$BASE_URL/api/v1/federated/task/task_001/aggregate" | jq .

echo -e "\n=== Data Classification Examples ==="

echo -e "\n18. 扫描并分类数据"
curl -X POST "$BASE_URL/api/v1/classification/scan" \
  -H "Content-Type: application/json" \
  -d '{
    "data": {
      "name": "张三",
      "phone": "13800138000",
      "id_card": "110101199001011234",
      "email": "zhangsan@example.com"
    }
  }' | jq .

echo -e "\n\n19. 列出敏感数据模式"
curl -X GET "$BASE_URL/api/v1/classification/patterns" | jq .

echo -e "\n=== Differential Privacy Examples ==="

echo -e "\n20. 创建隐私账户"
curl -X POST "$BASE_URL/api/v1/privacy/account" \
  -H "Content-Type: application/json" \
  -d '{
    "entity_id": "user_001",
    "epsilon": 5.0,
    "delta": 1e-4
  }' | jq .

echo -e "\n\n21. 添加噪声"
curl -X POST "$BASE_URL/api/v1/privacy/noise" \
  -H "Content-Type: application/json" \
  -d '{
    "value": 42.0,
    "params": {
      "epsilon": 0.1,
      "sensitivity": 1.0,
      "mechanism": "laplace"
    }
  }' | jq .

echo -e "\n=== Health Checks ==="

echo -e "\n22. Liveness Probe"
curl -X GET "$BASE_URL/health/live" | jq .

echo -e "\n\n23. Readiness Probe"
curl -X GET "$BASE_URL/health/ready" | jq .

echo -e "\n\n24. Prometheus Metrics"
curl -X GET "$BASE_URL/health/metrics" | head -30

echo -e "\n=== Done ==="
