import uvicorn
from src.api.rest.app import create_app
from src.infrastructure.config.settings import get_settings


def main():
    settings = get_settings("config/settings.yaml")
    app = create_app()
    uvicorn.run(
        app,
        host=settings.server.host,
        port=settings.server.port,
        workers=settings.server.workers,
        log_level=settings.server.log_level,
    )


if __name__ == "__main__":
    main()
