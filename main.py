import sys
from pathlib import Path

from PyQt6.QtWidgets import QApplication
from PyQt6.QtCore import Qt

from app.config import Config
from app.database import Database
from app.ui.main_window import MainWindow


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("KnowledgeVault")
    app.setOrganizationName("KnowledgeVault")
    app.setStyle("Fusion")

    config = Config()
    config.ensure_directories()

    db = Database(config.database_path)
    db.init_schema()

    window = MainWindow(config, db)
    window.show()

    sys.exit(app.exec())


if __name__ == "__main__":
    main()
