import uvicorn

from videoprocess.app import create_app
from videoprocess.config import settings


app = create_app()


def main():
    print("=" * 70)
    print(f"  {settings.app_name}")
    print(f"  版本: v{settings.app_version}")
    print("=" * 70)
    print(f"  服务地址: http://{settings.api_host}:{settings.api_port}")
    print(f"  API文档:  http://{settings.api_host}:{settings.api_port}/docs")
    print(f"  存储目录: {settings.storage_dir}")
    print("=" * 70)

    uvicorn.run(
        "main:app",
        host=settings.api_host,
        port=settings.api_port,
        reload=settings.debug,
        log_level="info",
    )


if __name__ == "__main__":
    main()
