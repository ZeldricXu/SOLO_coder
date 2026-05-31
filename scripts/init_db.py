#!/usr/bin/env python3
"""Initialize database tables."""
import asyncio
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.main import create_app
from core.database import Base, engine

async def init_db():
    """Initialize database tables."""
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    await engine.dispose()
    print("✅ 数据库初始化完成")

if __name__ == "__main__":
    asyncio.run(init_db())
