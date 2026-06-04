import asyncio
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from server.app import NetworkServer
from server.config import get_config
from server.database import init_db


async def main():
    config = get_config()

    print("=" * 60)
    print("Roguelike Dungeon Game Server")
    print(f"Environment: {config.environment.value}")
    print(f"Map: {config.map_width}x{config.map_height}, Max floors: {config.max_floors}")
    print("=" * 60)

    print("Initializing database...")
    await init_db()
    print("Database initialized.")

    print(f"Starting server on {config.host}:{config.port}...")
    server = NetworkServer(config)

    try:
        await server.start()
    except KeyboardInterrupt:
        print("\nServer shutting down...")
    except Exception as e:
        print(f"Server error: {e}")
        raise


if __name__ == "__main__":
    main()
