"""
Main entry point for the cloud native middleware platform.
"""

import uvicorn


def main():
    uvicorn.run(
        "app.api.app:app",
        host="0.0.0.0",
        port=8000,
        reload=False,
        log_level="info",
        access_log=True
    )


if __name__ == "__main__":
    main()
