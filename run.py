#!/usr/bin/env python3
"""
Cloud Native Engine - 启动脚本
"""

import os
import sys
import uvicorn

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from src.modules.config_module import get_app_config
from src.modules.logging_module import LogManager, get_logger


def main():
    config = get_app_config()

    LogManager(
        log_dir=config.logging.dir,
        log_level=config.logging.level,
        max_bytes=config.logging.max_bytes,
        backup_count=config.logging.backup_count,
        enable_json=config.logging.enable_json,
        enable_console=config.logging.enable_console,
        service_name=config.app_name,
    )

    logger = get_logger(__name__)
    logger.info(
        "Starting Cloud Native Engine",
        app_name=config.app_name,
        environment=config.app_env,
        debug=config.app_debug,
        host=config.app_host,
        port=config.app_port,
    )

    uvicorn.run(
        "src.api.main:app",
        host=config.app_host,
        port=config.app_port,
        reload=config.app_debug,
        log_level=config.logging.level.lower(),
        access_log=True,
    )


if __name__ == "__main__":
    main()
