import os

BASE_DIR = os.path.abspath(os.path.dirname(os.path.dirname(__file__)))

SIGNAL_DATA_DIR = os.path.join(BASE_DIR, "app", "data", "signals")
RESULTS_DATA_DIR = os.path.join(BASE_DIR, "app", "data", "results")
STATIC_IMAGE_DIR = os.path.join(BASE_DIR, "app", "static", "images")

UPLOAD_FOLDER = os.path.join(BASE_DIR, "app", "data", "uploads")
MAX_CONTENT_LENGTH = 100 * 1024 * 1024
ALLOWED_EXTENSIONS = {"csv", "txt", "bin", "dat"}

FLASK_DEBUG = True
SECRET_KEY = "signal-process-secret-key-2026"

def get_signal_file_path(signal_id: str) -> str:
    return os.path.join(SIGNAL_DATA_DIR, signal_id)

def ensure_directories():
    directories = [
        SIGNAL_DATA_DIR,
        RESULTS_DATA_DIR,
        STATIC_IMAGE_DIR,
        UPLOAD_FOLDER,
    ]
    for directory in directories:
        os.makedirs(directory, exist_ok=True)
