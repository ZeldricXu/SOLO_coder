import uvicorn
from src.core import settings


def main():
    uvicorn.run(
        "src.main:app",
        host=settings.host,
        port=settings.port,
        reload=settings.debug,
        workers=1 if settings.debug else 4,
    )


if __name__ == "__main__":
    main()
