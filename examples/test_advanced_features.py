import requests
import json
import os
import hashlib
import threading
import time
import concurrent.futures

BASE_URL = "http://localhost:5000/api/v1"


def calculate_file_checksum(file_path: str) -> str:
    sha256_hash = hashlib.sha256()
    with open(file_path, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    return sha256_hash.hexdigest()


def test_health():
    print("=" * 60)
    print("Testing Health Check")
    print("=" * 60)

    response = requests.get(f"{BASE_URL}/health")
    print(f"Status: {response.status_code}")
    print(f"Response: {json.dumps(response.json(), indent=2, ensure_ascii=False)}")
    return response.status_code == 200


def setup_test_environment():
    print("\n" + "=" * 60)
    print("Setting Up Test Environment")
    print("=" * 60)

    model_data = {
        "model_name": "高级测试模型",
        "model_type": "classification",
        "framework": "mock",
        "model_id": "model_advanced_test",
        "tags": ["advanced", "test", "batching"],
        "description": "用于高级功能测试的模型"
    }

    response = requests.post(f"{BASE_URL}/models", json=model_data)
    print(f"Create Model Status: {response.status_code}")
    result = response.json()
    print(f"Create Model Response: {json.dumps(result, indent=2, ensure_ascii=False)}")

    sample_file = os.path.join(os.path.dirname(__file__), "sample_model_v1.txt")
    model_size = os.path.getsize(sample_file)
    checksum = calculate_file_checksum(sample_file)

    version_data = {
        "model_id": "model_advanced_test",
        "version": "v1",
        "model_file": "sample_model_v1.txt",
        "model_size": model_size,
        "training_params": {"epochs": 100, "lr": 0.001},
        "accuracy": 0.95,
        "checksum": checksum,
        "notes": "高级测试版本v1"
    }

    response = requests.post(f"{BASE_URL}/models/versions", json=version_data)
    print(f"\nCreate Version Status: {response.status_code}")
    result = response.json()
    print(f"Create Version Response: {json.dumps(result, indent=2, ensure_ascii=False)}")

    return True


def test_deployment_with_health_check():
    print("\n" + "=" * 60)
    print("Testing Deployment with Health Check")
    print("=" * 60)

    deploy_data = {
        "model_id": "model_advanced_test",
        "version": "v1",
        "replicas": 1
    }

    print("\n1. Deploying model with health check enabled...")
    response = requests.post(f"{BASE_URL}/models/deploy", json=deploy_data)
    print(f"Deploy Status: {response.status_code}")
    result = response.json()
    print(f"Deploy Response: {json.dumps(result, indent=2, ensure_ascii=False)}")

    if result.get("code") == 200:
        deploy_id = result["data"].get("deploy_id")

        print("\n2. Getting deployment details with health check result...")
        response = requests.get(f"{BASE_URL}/models/deployments/{deploy_id}")
        print(f"Get Deployment Status: {response.status_code}")
        details = response.json()
        print(f"Health Info: {json.dumps(details.get('data', {}).get('health', {}), indent=2, ensure_ascii=False)}")

        print("\n3. Performing runtime health check...")
        response = requests.post(f"{BASE_URL}/models/deployments/{deploy_id}/healthcheck")
        print(f"Runtime Health Check Status: {response.status_code}")
        health_result = response.json()
        print(f"Health Check Result: {json.dumps(health_result, indent=2, ensure_ascii=False)}")

        print("\n4. Getting full deployment details...")
        response = requests.get(f"{BASE_URL}/models/deploy/details/{deploy_id}")
        print(f"Deployment Details Status: {response.status_code}")
        details_result = response.json()
        print(f"Details: {json.dumps(details_result, indent=2, ensure_ascii=False)}")

        return deploy_id

    return None


def test_inference_batching_mechanism():
    print("\n" + "=" * 60)
    print("Testing Inference Batching Mechanism")
    print("=" * 60)

    print("\n1. Checking if batching is enabled...")
    response = requests.get(
        f"{BASE_URL}/models/inference/batching/enabled",
        params={"model_id": "model_advanced_test", "version": "v1"}
    )
    print(f"Batching Enabled Status: {response.status_code}")
    result = response.json()
    print(f"Batching Enabled Response: {json.dumps(result, indent=2, ensure_ascii=False)}")

    print("\n2. Getting initial batching stats...")
    response = requests.get(
        f"{BASE_URL}/models/inference/batching/stats",
        params={"model_id": "model_advanced_test", "version": "v1"}
    )
    print(f"Initial Stats Status: {response.status_code}")
    initial_stats = response.json()
    print(f"Initial Stats: {json.dumps(initial_stats, indent=2, ensure_ascii=False)}")

    print("\n3. Performing multiple concurrent inferences to test batching...")

    def perform_inference(request_num):
        inference_data = {
            "model_id": "model_advanced_test",
            "version": "v1",
            "input_data": f"0.{request_num},0.2,0.3,0.4,0.5"
        }
        try:
            response = requests.post(f"{BASE_URL}/models/inference", json=inference_data)
            return response.json()
        except Exception as e:
            return {"error": str(e)}

    num_requests = 10
    print(f"  Sending {num_requests} concurrent inference requests...")

    start_time = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
        futures = [executor.submit(perform_inference, i) for i in range(num_requests)]
        results = [future.result() for future in concurrent.futures.as_completed(futures)]
    end_time = time.time()

    success_count = sum(1 for r in results if r.get('code') == 200)
    print(f"  Total time: {end_time - start_time:.3f} seconds")
    print(f"  Success rate: {success_count}/{num_requests}")

    print("\n4. Getting updated batching stats...")
    response = requests.get(
        f"{BASE_URL}/models/inference/batching/stats",
        params={"model_id": "model_advanced_test", "version": "v1"}
    )
    print(f"Updated Stats Status: {response.status_code}")
    updated_stats = response.json()
    print(f"Updated Stats: {json.dumps(updated_stats, indent=2, ensure_ascii=False)}")

    print("\n5. Listing loaded models with batching info...")
    response = requests.get(f"{BASE_URL}/models/loaded")
    print(f"Loaded Models Status: {response.status_code}")
    loaded_models = response.json()
    print(f"Loaded Models: {json.dumps(loaded_models, indent=2, ensure_ascii=False)}")


def test_async_monitoring():
    print("\n" + "=" * 60)
    print("Testing Async Monitoring")
    print("=" * 60)

    print("\n1. Checking monitoring queue status...")
    response = requests.get(f"{BASE_URL}/monitoring/queue/status")
    print(f"Queue Status: {response.status_code}")
    queue_status = response.json()
    print(f"Queue Status Response: {json.dumps(queue_status, indent=2, ensure_ascii=False)}")

    print("\n2. Performing several inferences to generate monitoring data...")
    for i in range(5):
        inference_data = {
            "model_id": "model_advanced_test",
            "version": "v1",
            "input_data": f"0.1,0.2,0.3,0.4,0.{i}"
        }
        requests.post(f"{BASE_URL}/models/inference", json=inference_data)

    print("\n3. Checking monitoring queue status after inferences...")
    response = requests.get(f"{BASE_URL}/monitoring/queue/status")
    queue_status_after = response.json()
    print(f"Queue Status After Inferences: {json.dumps(queue_status_after, indent=2, ensure_ascii=False)}")

    print("\n4. Flushing monitoring buffer...")
    response = requests.post(f"{BASE_URL}/monitoring/flush")
    print(f"Flush Status: {response.status_code}")
    print(f"Flush Response: {json.dumps(response.json(), indent=2, ensure_ascii=False)}")

    print("\n5. Getting model stats...")
    response = requests.get(f"{BASE_URL}/models/stats?model_id=model_advanced_test")
    print(f"Stats Status: {response.status_code}")
    stats_result = response.json()
    print(f"Stats Response: {json.dumps(stats_result, indent=2, ensure_ascii=False)}")


def test_concurrent_batching_stress():
    print("\n" + "=" * 60)
    print("Testing Concurrent Batching Stress Test")
    print("=" * 60)

    def perform_inference(request_num):
        inference_data = {
            "model_id": "model_advanced_test",
            "version": "v1",
            "input_data": [0.1 + request_num * 0.01, 0.2, 0.3, 0.4, 0.5]
        }
        try:
            start = time.time()
            response = requests.post(f"{BASE_URL}/models/inference", json=inference_data)
            end = time.time()
            return {
                "request_num": request_num,
                "success": response.status_code == 200,
                "response_time_ms": (end - start) * 1000,
                "result": response.json()
            }
        except Exception as e:
            return {
                "request_num": request_num,
                "success": False,
                "error": str(e)
            }

    num_concurrent = 50
    print(f"\nSending {num_concurrent} concurrent inference requests...")

    start_time = time.time()
    with concurrent.futures.ThreadPoolExecutor(max_workers=30) as executor:
        futures = [executor.submit(perform_inference, i) for i in range(num_concurrent)]
        results = [future.result() for future in concurrent.futures.as_completed(futures)]
    end_time = time.time()

    success_count = sum(1 for r in results if r.get('success'))
    avg_response_time = sum(r.get('response_time_ms', 0) for r in results if r.get('success')) / success_count if success_count > 0 else 0
    throughput = num_concurrent / (end_time - start_time)

    print(f"\nStress Test Results:")
    print(f"  Total requests: {num_concurrent}")
    print(f"  Success count: {success_count}")
    print(f"  Total time: {end_time - start_time:.3f} seconds")
    print(f"  Avg response time: {avg_response_time:.2f} ms")
    print(f"  Throughput: {throughput:.2f} requests/second")

    print("\nGetting batching stats after stress test...")
    response = requests.get(
        f"{BASE_URL}/models/inference/batching/stats",
        params={"model_id": "model_advanced_test", "version": "v1"}
    )
    stats = response.json()
    print(f"Batching Stats: {json.dumps(stats, indent=2, ensure_ascii=False)}")


def main():
    print("=" * 70)
    print("ModelServe Advanced Features Test Suite")
    print("=" * 70)
    print("Testing:")
    print("  1. Deployment with Health Check")
    print("  2. Inference Batching Mechanism")
    print("  3. Async Monitoring")
    print("  4. Concurrent Batching Stress Test")
    print("=" * 70)

    if not test_health():
        print("\nServer is not running. Please start the server first:")
        print("  python app.py")
        return

    setup_test_environment()

    deploy_id = test_deployment_with_health_check()

    if deploy_id:
        test_inference_batching_mechanism()
        test_async_monitoring()
        test_concurrent_batching_stress()

    print("\n" + "=" * 70)
    print("Advanced Features Test Suite Complete")
    print("=" * 70)
    print("\nKey Features Tested:")
    print("  - [X] Deployment with Health Check (自动健康检测)")
    print("  - [X] Inference Batching Mechanism (推理批处理机制)")
    print("  - [X] Async Monitoring (异步监控记录)")
    print("  - [X] Concurrent Batching Stress Test (并发批处理压力测试)")


if __name__ == "__main__":
    main()
