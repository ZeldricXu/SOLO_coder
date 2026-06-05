import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.core.database import init_db
from app.core.logging_config import get_logger

logger = get_logger(__name__)

if __name__ == "__main__":
    try:
        init_db()
        logger.info("Database tables created successfully")
        print("✅ Database tables created successfully")
    except Exception as e:
        logger.error(f"Failed to initialize database: {e}", exc_info=True)
        print(f"❌ Failed to initialize database: {e}")
        sys.exit(1)
