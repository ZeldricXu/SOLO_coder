"""
FileEngine 文件处理服务 - 主程序入口
"""

from .api import create_app


app = create_app()


def main():
    """
    命令行启动入口
    """
    import uvicorn
    from .config import settings

    uvicorn.run(
        "fileengine.main:app",
        host=settings.api_host,
        port=settings.api_port,
        reload=settings.debug,
        log_level="info" if settings.debug else "warning",
    )


if __name__ == "__main__":
    main()
