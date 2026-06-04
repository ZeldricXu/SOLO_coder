import asyncio
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from client.main import GameClient
from client.config import resolve_server_address


def main():
    print("=" * 60)
    print("Roguelike Dungeon Game Client")
    print("=" * 60)

    env = None
    if len(sys.argv) >= 2:
        arg = sys.argv[1]
        if arg in ("development", "staging", "production"):
            env = arg
            host, port = resolve_server_address(env)
        else:
            host = arg
            port = int(sys.argv[2]) if len(sys.argv) >= 3 else 8765
    else:
        host, port = resolve_server_address()

    print(f"Environment: {env or 'default'}")
    print(f"Connecting to {host}:{port}...")

    try:
        client = GameClient()
        asyncio.run(client.run(host, port))
    except KeyboardInterrupt:
        print("\nClient shutting down...")
    except Exception as e:
        print(f"Client error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)


if __name__ == "__main__":
    main()
