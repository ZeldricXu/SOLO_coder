import os

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

DATABASE_PATH = os.path.join(BASE_DIR, "deskai.db")

SUPPORTED_EXTENSIONS = [".txt", ".pdf"]

DEFAULT_WATCH_DIR = os.path.join(BASE_DIR, "watch_folder")

LLM_API_ENDPOINT = "http://localhost:8000/api/v1/generate_summary"

MAX_RETRIES = 3

RETRY_DELAY = 2

MAX_SUMMARY_LENGTH = 200

WINDOW_WIDTH = 300
WINDOW_HEIGHT = 200

LOG_FILE = os.path.join(BASE_DIR, "deskai.log")
