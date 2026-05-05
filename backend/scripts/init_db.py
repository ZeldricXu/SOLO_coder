import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.core.database import init_db, SessionLocal
from app.core.config import settings
from app.modules.model_manager import model_manager
from app.modules.training_service import training_service
import json


def initialize_database():
    print("正在初始化数据库...")
    init_db()
    print("数据库初始化完成！")


def initialize_default_model():
    print("正在初始化默认模型...")
    model_manager.initialize_default_model()

    active_model = model_manager.get_active_model()
    if active_model:
        print(f"默认模型初始化完成！")
        print(f"  模型ID: {active_model['model_id']}")
        print(f"  版本: {active_model['version']}")
        print(f"  标签: {active_model['labels']}")
    else:
        print("默认模型初始化失败！")


def train_with_sample_data():
    sample_data_path = settings.DATA_DIR / "sample_training_data.json"

    if not sample_data_path.exists():
        print(f"示例训练数据文件不存在: {sample_data_path}")
        return

    print(f"正在加载示例训练数据: {sample_data_path}")

    with open(sample_data_path, "r", encoding="utf-8") as f:
        training_data = json.load(f)

    print(f"加载了 {len(training_data)} 条训练数据")

    print("开始训练模型...")

    result = training_service.start_training(
        training_data=training_data,
        model_type="multilabel_classifier",
        test_size=0.2,
        random_state=42,
        auto_activate=True,
        description="使用示例训练数据训练的模型"
    )

    if result["success"]:
        print("模型训练成功！")
        print(f"  任务ID: {result['job_id']}")
        if result.get("model_info"):
            print(f"  模型ID: {result['model_info']['model_id']}")
            print(f"  版本: {result['model_info']['version']}")
            print(f"  标签: {result['model_info']['labels']}")
        if result.get("metrics"):
            print(f"  准确率: {result['metrics']['accuracy']:.4f}")
            print(f"  精确率: {result['metrics']['precision']:.4f}")
            print(f"  召回率: {result['metrics']['recall']:.4f}")
            print(f"  F1分数: {result['metrics']['f1_score']:.4f}")
    else:
        print(f"模型训练失败: {result['message']}")


def main():
    print("=" * 60)
    print("TextClassifier 初始化脚本")
    print("=" * 60)

    initialize_database()
    print()

    initialize_default_model()
    print()

    train_with_sample_data()
    print()

    print("=" * 60)
    print("初始化完成！")
    print("=" * 60)


if __name__ == "__main__":
    main()
