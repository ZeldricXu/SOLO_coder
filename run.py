import uvicorn
from fileengine.api import create_app
from fileengine.config import settings

app = create_app()

if __name__ == "__main__":
    print(f"=" * 60)
    print(f"  FileEngine 文件处理服务 v{settings.app_version}")
    print(f"  服务地址: http://{settings.api_host}:{settings.api_port}")
    print(f"  API文档: http://{settings.api_host}:{settings.api_port}/docs")
    print(f"=" * 60)

    uvicorn.run(
        "run:app",
        host=settings.api_host,
        port=settings.api_port,
        reload=settings.debug,
        log_level="info",
    )
