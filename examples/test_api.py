import requests
import json
import os
import hashlib

BASE_URL = "http://localhost:5000/api/v1"


def calculate_file_checksum(file_path: str) -> str:
    sha256_hash = hashlib.sha256()
    with open(file_path, "rb") as f:
        for byte_block in iter(lambda: f.read(4096), b""):
            sha256_hash.update(byte_block)
    return sha256_hash.hexdigest()


def test_health():
    print("=" * 50)
    print("Testing Health Check")
    print("=" * 50)

    response = requests.get(f"{BASE_URL}/health")
    print(f"Status: {response.status_code}")
    print(f"Response: {json.dumps(response.json(), indent=2, ensure_ascii=False)}")
    return response.status_code == 200


def test_create_model():
    print("\n" + "=" * 50)
    print("Testing Create Model")
    print("=" * 50)

    model_data = {
        "model_name": "图像分类模型",
        "model_type": "classification",
        "framework": "mock",
        "model_id": "model_image_classify",
        "tags": ["classification", "image", "mock"],
        "description": "用于测试的图像分类模型"
    }

    response = requests.post(f"{BASE_URL}/models", json=model_data)
    print(f"Status: {response.status_code}")
    result = response.json()
    print(f"Response: {json.dumps(result, indent=2, ensure_ascii=False)}")
    return result


def test_get_model(model_id: str):
    print("\n" + "=" * 50)
    print("Testing Get Model")
    print("=" * 50)

    response = requests.get(f"{BASE_URL}/models/{model_id}")
    print(f"Status: {response.status_code}")
    print(f"Response: {json.dumps(response.json(), indent=2, ensure_ascii=False)}")


def test_list_models():
    print("\n" + "=" * 50)
    print("Testing List Models")
    print("=" * 50)

    response = requests.get(f"{BASE_URL}/models")
    print(f"Status: {response.status_code}")
    result = response.json()
    print(f"Model count: {len(result.get('data', []))}")
    print(f"Response: {json.dumps(result, indent=2, ensure_ascii=False)}")


def test_create_version():
    print("\n" + "=" * 50)
    print("Testing Create Version")
    print("=" * 50)

    sample_file = os.path.join(os.path.dirname(__file__), "sample_model_v1.txt")
    model_size = os.path.getsize(sample_file)
    checksum = calculate_file_checksum(sample_file)

    version_data = {
        "model_id": "model_image_classify",
        "version": "v1",
        "model_file": "sample_model_v1.txt",
        "model_size": model_size,
        "training_params": {"epochs": 100, "lr": 0.001, "batch_size": 32},
        "accuracy": 0.95,
        "checksum": checksum,
        "notes": "初始版本，准确率95%"
    }

    response = requests.post(f"{BASE_URL}/models/versions", json=version_data)
    print(f"Status: {response.status_code}")
    result = response.json()
    print(f"Response: {json.dumps(result, indent=2, ensure_ascii=False)}")
    return result


def test_list_versions(model_id: str):
    print("\n" + "=" * 50)
    print("Testing List Versions")
    print("=" * 50)

    response = requests.get(f"{BASE_URL}/models/versions?model_id={model_id}")
    print(f"Status: {response.status_code}")
    print(f"Response: {json.dumps(response.json(), indent=2, ensure_ascii=False)}")


def test_deploy_model():
    print("\n" + "=" * 50)
    print("Testing Deploy Model")
    print("=" * 50)

    deploy_data = {
        "model_id": "model_image_classify",
        "version": "v1",
        "replicas": 1
    }

    response = requests.post(f"{BASE_URL}/models/deploy", json=deploy_data)
    print(f"Status: {response.status_code}")
    result = response.json()
    print(f"Response: {json.dumps(result, indent=2, ensure_ascii=False)}")
    return result


def test_inference():
    print("\n" + "=" * 50)
    print("Testing Inference")
    print("=" * 50)

    inference_data = {
        "model_id": "model_image_classify",
        "version": "v1",
        "input_data": "0.1,0.2,0.3,0.4,0.5,0.6,0.7,0.8,0.9,1.0"
    }

    response = requests.post(f"{BASE_URL}/models/inference", json=inference_data)
    print(f"Status: {response.status_code}")
    result = response.json()
    print(f"Response: {json.dumps(result, indent=2, ensure_ascii=False)}")
    return result


def test_batch_inference():
    print("\n" + "=" * 50)
    print("Testing Batch Inference")
    print("=" * 50)

    batch_data = {
        "model_id": "model_image_classify",
        "version": "v1",
        "inputs": [
            "0.1,0.2,0.3",
            "0.4,0.5,0.6",
            "0.7,0.8,0.9"
        ]
    }

    response = requests.post(f"{BASE_URL}/models/inference/batch", json=batch_data)
    print(f"Status: {response.status_code}")
    result = response.json()
    print(f"Response: {json.dumps(result, indent=2, ensure_ascii=False)}")


def test_get_stats(model_id: str):
    print("\n" + "=" * 50)
    print("Testing Get Stats")
    print("=" * 50)

    response = requests.get(f"{BASE_URL}/models/stats?model_id={model_id}")
    print(f"Status: {response.status_code}")
    print(f"Response: {json.dumps(response.json(), indent=2, ensure_ascii=False)}")


def test_list_deployments():
    print("\n" + "=" * 50)
    print("Testing List Deployments")
    print("=" * 50)

    response = requests.get(f"{BASE_URL}/models/deployments")
    print(f"Status: {response.status_code}")
    result = response.json()
    print(f"Deployment count: {len(result.get('data', []))}")
    print(f"Response: {json.dumps(result, indent=2, ensure_ascii=False)}")


def test_list_loaded_models():
    print("\n" + "=" * 50)
    print("Testing List Loaded Models")
    print("=" * 50)

    response = requests.get(f"{BASE_URL}/models/loaded")
    print(f"Status: {response.status_code}")
    print(f"Response: {json.dumps(response.json(), indent=2, ensure_ascii=False)}")


def main():
    print("=" * 60)
    print("ModelServe API Test Suite")
    print("=" * 60)

    if not test_health():
        print("\nServer is not running. Please start the server first:")
        print("  python app.py")
        return

    model_result = test_create_model()

    if model_result.get("code") == 200:
        model_id = model_result["data"]["model_id"]
        test_get_model(model_id)

    test_list_models()

    version_result = test_create_version()

    if version_result.get("code") == 200:
        test_list_versions("model_image_classify")

    deploy_result = test_deploy_model()

    if deploy_result.get("code") == 200:
        test_list_deployments()
        test_list_loaded_models()

    test_inference()
    test_batch_inference()

    test_get_stats("model_image_classify")

    print("\n" + "=" * 60)
    print("Test Suite Complete")
    print("=" * 60)


if __name__ == "__main__":
    main()
