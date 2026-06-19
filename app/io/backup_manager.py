import json
import os
import shutil
import tempfile
import zipfile
from datetime import datetime
from pathlib import Path
from typing import Optional

from app.database import Database
from app.config import Config


BACKUP_MANIFEST_NAME = "backup_manifest.json"
DB_FILENAME = "knowledge.db"
CONFIG_FILENAME = "config.json"
ATTACHMENTS_DIRNAME = "attachments"


def _create_manifest(config: Config) -> dict:
    return {
        "version": "1.0",
        "app": "KnowledgeVault",
        "created_at": datetime.now().isoformat(),
        "database": DB_FILENAME,
        "attachments_dir": ATTACHMENTS_DIRNAME,
        "config": CONFIG_FILENAME,
        "app_data_dir": config.app_data_dir,
    }


def export_backup(db: Database, config: Config, output_zip_path: str) -> bool:
    try:
        out = Path(output_zip_path)
        out.parent.mkdir(parents=True, exist_ok=True)

        tmp_dir = Path(tempfile.mkdtemp(prefix="kv_backup_"))

        try:
            manifest = _create_manifest(config)
            manifest_path = tmp_dir / BACKUP_MANIFEST_NAME
            manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")

            db_src = Path(db.db_path)
            if db_src.exists():
                db_tmp = tmp_dir / DB_FILENAME
                shutil.copy2(db_src, db_tmp)

                wal = db_src.with_suffix(".db-wal")
                shm = db_src.with_suffix(".db-shm")
                for f in [wal, shm]:
                    if f.exists():
                        shutil.copy2(f, tmp_dir / f.name)

            config_tmp = tmp_dir / CONFIG_FILENAME
            config_tmp.write_text(json.dumps(config.__dict__, indent=2, ensure_ascii=False), encoding="utf-8")

            att_src = Path(config.attachments_dir)
            if att_src.exists():
                att_dst = tmp_dir / ATTACHMENTS_DIRNAME
                shutil.copytree(att_src, att_dst, dirs_exist_ok=True)

            if out.exists():
                out.unlink()

            with zipfile.ZipFile(str(out), "w", zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
                for item in tmp_dir.rglob("*"):
                    if item.is_file():
                        arcname = item.relative_to(tmp_dir)
                        zf.write(str(item), arcname=str(arcname))

            return out.exists()

        finally:
            try:
                shutil.rmtree(tmp_dir)
            except Exception:
                pass

    except Exception:
        return False


def _validate_backup(zip_path: str) -> Optional[dict]:
    if not Path(zip_path).exists():
        return None

    try:
        with zipfile.ZipFile(zip_path, "r") as zf:
            if BACKUP_MANIFEST_NAME not in zf.namelist():
                return None
            with zf.open(BACKUP_MANIFEST_NAME) as mf:
                manifest = json.loads(mf.read().decode("utf-8"))
            return manifest
    except Exception:
        return None


def import_backup(
    db: Database,
    config: Config,
    zip_path: str,
    restore_attachments: bool = True,
) -> bool:
    manifest = _validate_backup(zip_path)
    if manifest is None:
        return False

    try:
        tmp_dir = Path(tempfile.mkdtemp(prefix="kv_restore_"))

        try:
            with zipfile.ZipFile(zip_path, "r") as zf:
                zf.extractall(str(tmp_dir))

            db_src = tmp_dir / DB_FILENAME
            if not db_src.exists():
                return False

            db.close()

            db_dst = Path(config.database_path)
            db_dst.parent.mkdir(parents=True, exist_ok=True)
            if db_dst.exists():
                ts = datetime.now().strftime("%Y%m%d_%H%M%S")
                backup_old = db_dst.with_suffix(f".db.bak_{ts}")
                shutil.copy2(db_dst, backup_old)
                db_dst.unlink()
            shutil.copy2(db_src, db_dst)

            for suffix in [".db-wal", ".db-shm"]:
                src_f = tmp_dir / f"{DB_FILENAME}{suffix}"
                dst_f = db_dst.with_suffix(suffix)
                if src_f.exists():
                    if dst_f.exists():
                        dst_f.unlink()
                    shutil.copy2(src_f, dst_f)

            config_src = tmp_dir / CONFIG_FILENAME
            if config_src.exists():
                try:
                    loaded = json.loads(config_src.read_text(encoding="utf-8"))
                    for k, v in loaded.items():
                        if hasattr(config, k):
                            setattr(config, k, v)
                    config.save()
                except Exception:
                    pass

            if restore_attachments:
                att_src = tmp_dir / ATTACHMENTS_DIRNAME
                if att_src.exists():
                    att_dst = Path(config.attachments_dir)
                    if att_dst.exists():
                        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
                        backup_att = att_dst.parent / f"attachments.bak_{ts}"
                        shutil.copytree(att_dst, backup_att, dirs_exist_ok=True)
                    shutil.copytree(att_src, att_dst, dirs_exist_ok=True)

            return True

        finally:
            try:
                shutil.rmtree(tmp_dir)
            except Exception:
                pass

    except Exception:
        return False
