#!/usr/bin/env python
import logging
import sys

from behaviortrack.config import settings
from behaviortrack.api import create_app


logging.basicConfig(
    level=getattr(logging, settings.LOG_LEVEL),
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
)

logger = logging.getLogger(__name__)


def main() -> int:
    try:
        app = create_app()
        
        logger.info(f"Starting {settings.APP_NAME} v{settings.APP_VERSION}")
        logger.info(f"Server listening on {settings.HOST}:{settings.PORT}")
        logger.info(f"Debug mode: {settings.DEBUG}")
        
        app.run(
            host=settings.HOST,
            port=settings.PORT,
            debug=settings.DEBUG
        )
        
        return 0
        
    except KeyboardInterrupt:
        logger.info("Server stopped by user")
        return 0
        
    except Exception as e:
        logger.exception(f"Failed to start server: {str(e)}")
        return 1


if __name__ == "__main__":
    sys.exit(main())
