from __future__ import annotations

import asyncio
import logging
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from server.config import get_config, Environment
from server.database import init_db
from server.app import NetworkServer
from server.config_schemas import validate_configs_on_startup


def main():
    config = get_config()

    logging.basicConfig(
        level=getattr(logging, config.log_level.upper(), logging.INFO),
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
    )

    logger = logging.getLogger("dungeon")

    env_label = config.environment.value
    logger.info("=" * 60)
    logger.info("Roguelike Dungeon Game Server")
    logger.info("Environment: %s", env_label)
    logger.info("Map: %dx%d, Max floors: %d", config.map_width, config.map_height, config.max_floors)
    logger.info("Monster strength: %.1fx", config.monster_strength_mult)
    logger.info("=" * 60)

    async def run():
        logger.info("Validating configuration files...")
        validate_configs_on_startup(config.data_dir)
        logger.info("Initializing database...")
        await init_db()
        logger.info("Database initialized.")

        server = NetworkServer(config)
        await server.start()

    try:
        asyncio.run(run())
    except KeyboardInterrupt:
        logger.info("Server stopped by user")


if __name__ == "__main__":
    main()
