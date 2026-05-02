import uvicorn
import os
from pathlib import Path


def main():
    env_path = Path(__file__).parent / ".env"
    
    if not env_path.exists():
        example_path = Path(__file__).parent / ".env.example"
        if example_path.exists():
            print("⚠️  未找到 .env 文件，请先根据 .env.example 创建配置文件")
            print(f"📝 配置文件示例位于: {example_path.absolute()}")
        else:
            print("⚠️  请创建 .env 配置文件")
        print("")
        print("必填配置项:")
        print("  - OPENAI_API_KEY: 您的 OpenAI API Key")
        print("")
    
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=8000,
        reload=True,
        log_level="info"
    )


if __name__ == "__main__":
    main()
