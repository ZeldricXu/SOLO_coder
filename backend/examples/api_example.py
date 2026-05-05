import requests
import json

BASE_URL = "http://localhost:8000"
API_V1 = f"{BASE_URL}/api/v1"


def print_separator():
    print("=" * 80)


def test_health_check():
    print_separator()
    print("1. 健康检查")
    print_separator()

    response = requests.get(f"{BASE_URL}/health")
    print(f"状态码: {response.status_code}")
    print(f"响应: {json.dumps(response.json(), ensure_ascii=False, indent=2)}")


def test_get_info():
    print_separator()
    print("2. 获取服务信息")
    print_separator()

    response = requests.get(f"{API_V1}/info")
    print(f"状态码: {response.status_code}")
    print(f"响应: {json.dumps(response.json(), ensure_ascii=False, indent=2)}")


def test_classify_predict():
    print_separator()
    print("3. 单文本分类预测")
    print_separator()

    request_data = {
        "text": "这款产品质量很好，客服服务态度也不错，价格也很实惠，物流速度也很快",
        "request_id": "req_001",
        "options": {
            "confidence_threshold": 0.5,
            "save_result": True
        }
    }

    print(f"请求数据: {json.dumps(request_data, ensure_ascii=False, indent=2)}")
    print()

    response = requests.post(
        f"{API_V1}/classify/predict",
        json=request_data
    )

    print(f"状态码: {response.status_code}")
    print(f"响应: {json.dumps(response.json(), ensure_ascii=False, indent=2)}")


def test_batch_classify():
    print_separator()
    print("4. 批量文本分类预测")
    print_separator()

    request_data = {
        "texts": [
            "这款产品质量很好，客服服务态度也不错",
            "价格比其他店铺便宜很多，性价比很高",
            "物流太慢了，等了好几天才收到，客服也不给力",
            "产品用了几天就坏了，联系售后说是人为损坏不给保修",
            "售后服务很贴心，有什么问题都能及时解决，让人很放心"
        ],
        "request_id": "req_batch_001",
        "options": {
            "confidence_threshold": 0.5,
            "save_result": True
        }
    }

    print(f"请求包含 {len(request_data['texts'])} 条文本")
    print()

    response = requests.post(
        f"{API_V1}/classify/batch",
        json=request_data
    )

    print(f"状态码: {response.status_code}")
    result = response.json()
    print(f"处理数量: {result.get('processed_count', 0)}")
    print(f"成功数量: {result.get('success_count', 0)}")
    print(f"失败数量: {result.get('failed_count', 0)}")
    print()

    if result.get('results'):
        for i, r in enumerate(result['results']):
            print(f"文本 {i+1}: {r['text'][:50]}...")
            print(f"  分类: {r['categories']}")
            print(f"  情感: {r['sentiment']}")
            print(f"  关键词: {r['keywords']}")
            print(f"  状态: {r['status']}")
            print()


def test_list_results():
    print_separator()
    print("5. 获取分类结果列表")
    print_separator()

    response = requests.get(f"{API_V1}/classify/results?limit=10")
    print(f"状态码: {response.status_code}")
    result = response.json()
    print(f"总数量: {result.get('total_count', 0)}")
    print(f"返回数量: {len(result.get('results', []))}")


def test_statistics():
    print_separator()
    print("6. 获取统计信息")
    print_separator()

    response = requests.get(f"{API_V1}/classify/statistics")
    print(f"状态码: {response.status_code}")
    print(f"响应: {json.dumps(response.json(), ensure_ascii=False, indent=2)}")


def test_list_models():
    print_separator()
    print("7. 获取模型列表")
    print_separator()

    response = requests.get(f"{API_V1}/model/list")
    print(f"状态码: {response.status_code}")
    result = response.json()
    print(f"模型数量: {result.get('total_count', 0)}")

    models = result.get('models', [])
    for model in models:
        print()
        print(f"  模型ID: {model['model_id']}")
        print(f"  版本: {model['version']}")
        print(f"  类型: {model['model_type']}")
        print(f"  标签: {model['labels']}")
        print(f"  激活状态: {model['is_active']}")
        print(f"  准确率: {model['accuracy']:.4f}")


def test_train_model():
    print_separator()
    print("8. 模型训练示例（使用模拟数据）")
    print_separator()

    training_data = [
        {"text": "这款产品质量很好，做工精细，使用效果非常棒", "labels": ["产品质量"]},
        {"text": "价格比其他店铺便宜很多，性价比很高", "labels": ["价格"]},
        {"text": "客服态度很好，有问必答，解决问题非常及时", "labels": ["客服服务"]},
        {"text": "物流速度很快，当天就发货了，第二天就收到了", "labels": ["物流配送"]},
        {"text": "售后服务很好，有问题很快就解决了", "labels": ["售后"]},
        {"text": "产品质量一般，做工比较粗糙，感觉不值这个价格", "labels": ["产品质量", "价格"]},
        {"text": "价格有点贵，但是质量还可以，客服服务态度也不错", "labels": ["价格", "产品质量", "客服服务"]},
        {"text": "物流太慢了，等了好几天才收到，客服也不给力", "labels": ["物流配送", "客服服务"]},
        {"text": "收到货发现有问题，联系客服后很快就解决了，售后服务态度很好", "labels": ["售后", "客服服务"]}
    ]

    request_data = {
        "training_data": training_data,
        "model_type": "multilabel_classifier",
        "test_size": 0.2,
        "random_state": 42,
        "auto_activate": False,
        "description": "API示例训练的模型"
    }

    print(f"训练数据数量: {len(training_data)}")
    print()

    response = requests.post(
        f"{API_V1}/model/train",
        json=request_data
    )

    print(f"状态码: {response.status_code}")
    result = response.json()

    if result.get("success"):
        print(f"训练成功!")
        print(f"任务ID: {result.get('job_id')}")
        if result.get("model_info"):
            model = result["model_info"]
            print(f"模型ID: {model['model_id']}")
            print(f"版本: {model['version']}")
            print(f"标签: {model['labels']}")
        if result.get("metrics"):
            metrics = result["metrics"]
            print(f"准确率: {metrics['accuracy']:.4f}")
            print(f"精确率: {metrics['precision']:.4f}")
            print(f"召回率: {metrics['recall']:.4f}")
            print(f"F1分数: {metrics['f1_score']:.4f}")
    else:
        print(f"训练失败: {result.get('message')}")


def main():
    print("=" * 80)
    print("TextClassifier API 示例测试")
    print("=" * 80)
    print()

    try:
        test_health_check()
        test_get_info()
        test_classify_predict()
        test_batch_classify()
        test_list_results()
        test_statistics()
        test_list_models()

        print()
        print_separator()
        print("测试完成!")
        print_separator()

    except requests.exceptions.ConnectionError:
        print()
        print("=" * 80)
        print("错误: 无法连接到服务器")
        print("请确保服务已启动: python run.py")
        print("=" * 80)


if __name__ == "__main__":
    main()
