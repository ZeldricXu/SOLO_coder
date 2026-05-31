import os
import shutil
import hashlib
import gzip
import json
from datetime import datetime
from typing import Any, Dict, List, Optional
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from app.models import BackupRecord
from app.config import settings
from app.database import sync_engine
from app.logger import logger


class StorageError(Exception):
    pass


class StorageManager:
    def __init__(self, db: AsyncSession = None):
        self.db = db
        self._ensure_directories()
    
    def _ensure_directories(self):
        directories = [
            settings.STORAGE_BASE_PATH,
            settings.BACKUP_PATH,
            settings.FIRMWARE_PATH,
            settings.MODELS_PATH
        ]
        for directory in directories:
            os.makedirs(directory, exist_ok=True)
    
    async def create_backup(
        self,
        backup_type: str = "full",
        tables: List[str] = None
    ) -> BackupRecord:
        timestamp = datetime.utcnow().strftime("%Y%m%d_%H%M%S")
        backup_id = f"backup_{timestamp}"
        backup_filename = f"{backup_id}.json.gz"
        backup_path = os.path.join(settings.BACKUP_PATH, backup_filename)
        
        logger.info("Starting backup", backup_id=backup_id, backup_type=backup_type)
        
        try:
            backup_data = await self._export_data(tables)
            
            with gzip.open(backup_path, 'wt', encoding='utf-8') as f:
                json.dump(backup_data, f, default=str, indent=2)
            
            file_size = os.path.getsize(backup_path)
            checksum = self._calculate_file_checksum(backup_path)
            
            record = BackupRecord(
                backup_id=backup_id,
                backup_type=backup_type,
                file_path=backup_path,
                file_size=file_size,
                checksum=checksum,
                status="completed"
            )
            
            if self.db:
                self.db.add(record)
                await self.db.flush()
            
            logger.info("Backup completed", backup_id=backup_id, file_size=file_size)
            return record
        
        except Exception as e:
            logger.error("Backup failed", error=str(e))
            raise StorageError(f"Backup failed: {e}")
    
    async def restore_backup(
        self,
        backup_id: str,
        tables: List[str] = None
    ) -> Dict[str, Any]:
        if self.db:
            stmt = select(BackupRecord).where(BackupRecord.backup_id == backup_id)
            result = await self.db.execute(stmt)
            record = result.scalar_one_or_none()
            
            if not record:
                raise StorageError(f"Backup record not found: {backup_id}")
            
            backup_path = record.file_path
        else:
            backup_filename = f"{backup_id}.json.gz"
            backup_path = os.path.join(settings.BACKUP_PATH, backup_filename)
        
        if not os.path.exists(backup_path):
            raise StorageError(f"Backup file not found: {backup_path}")
        
        logger.info("Starting restore", backup_id=backup_id)
        
        try:
            with gzip.open(backup_path, 'rt', encoding='utf-8') as f:
                backup_data = json.load(f)
            
            restore_stats = await self._import_data(backup_data, tables)
            
            logger.info("Restore completed", backup_id=backup_id, stats=restore_stats)
            return {
                "backup_id": backup_id,
                "status": "completed",
                "stats": restore_stats
            }
        
        except Exception as e:
            logger.error("Restore failed", error=str(e))
            raise StorageError(f"Restore failed: {e}")
    
    async def list_backups(self, limit: int = 100) -> List[Dict[str, Any]]:
        backups = []
        
        if self.db:
            stmt = select(BackupRecord).order_by(BackupRecord.created_at.desc()).limit(limit)
            result = await self.db.execute(stmt)
            records = result.scalars().all()
            
            for record in records:
                backups.append({
                    "backup_id": record.backup_id,
                    "backup_type": record.backup_type,
                    "file_path": record.file_path,
                    "file_size": record.file_size,
                    "checksum": record.checksum,
                    "status": record.status,
                    "created_at": record.created_at.isoformat() if record.created_at else None
                })
        else:
            for filename in sorted(os.listdir(settings.BACKUP_PATH), reverse=True):
                if filename.endswith('.json.gz') and filename.startswith('backup_'):
                    filepath = os.path.join(settings.BACKUP_PATH, filename)
                    backups.append({
                        "backup_id": filename.replace('.json.gz', ''),
                        "file_path": filepath,
                        "file_size": os.path.getsize(filepath),
                        "modified_at": datetime.fromtimestamp(os.path.getmtime(filepath)).isoformat()
                    })
                    if len(backups) >= limit:
                        break
        
        return backups
    
    async def delete_backup(self, backup_id: str) -> bool:
        if self.db:
            stmt = select(BackupRecord).where(BackupRecord.backup_id == backup_id)
            result = await self.db.execute(stmt)
            record = result.scalar_one_or_none()
            
            if not record:
                return False
            
            filepath = record.file_path
            await self.db.delete(record)
            await self.db.flush()
        else:
            filename = f"{backup_id}.json.gz"
            filepath = os.path.join(settings.BACKUP_PATH, filename)
        
        if os.path.exists(filepath):
            os.remove(filepath)
            logger.info("Deleted backup", backup_id=backup_id)
            return True
        
        return False
    
    async def verify_backup(self, backup_id: str) -> Dict[str, Any]:
        if self.db:
            stmt = select(BackupRecord).where(BackupRecord.backup_id == backup_id)
            result = await self.db.execute(stmt)
            record = result.scalar_one_or_none()
            
            if not record:
                return {"valid": False, "error": "Backup record not found"}
            
            filepath = record.file_path
            expected_checksum = record.checksum
        else:
            filename = f"{backup_id}.json.gz"
            filepath = os.path.join(settings.BACKUP_PATH, filename)
            expected_checksum = None
        
        if not os.path.exists(filepath):
            return {"valid": False, "error": "Backup file not found"}
        
        actual_checksum = self._calculate_file_checksum(filepath)
        
        valid = True
        error = None
        
        if expected_checksum and actual_checksum != expected_checksum:
            valid = False
            error = "Checksum mismatch"
        
        try:
            with gzip.open(filepath, 'rt', encoding='utf-8') as f:
                json.load(f)
        except Exception as e:
            valid = False
            error = f"Invalid JSON: {e}"
        
        return {
            "valid": valid,
            "file_size": os.path.getsize(filepath),
            "checksum": actual_checksum,
            "error": error
        }
    
    async def _export_data(self, tables: List[str] = None) -> Dict[str, Any]:
        from app.database import Base
        from sqlalchemy.orm import sessionmaker
        
        Session = sessionmaker(bind=sync_engine)
        session = Session()
        
        try:
            export_data = {
                "meta": {
                    "version": "1.0",
                    "exported_at": datetime.utcnow().isoformat(),
                    "tables": []
                },
                "data": {}
            }
            
            for table_name, table in Base.metadata.tables.items():
                if tables and table_name not in tables:
                    continue
                
                export_data["meta"]["tables"].append(table_name)
                
                try:
                    rows = session.execute(table.select()).fetchall()
                    export_data["data"][table_name] = [
                        {key: value for key, value in dict(row).items()}
                        for row in rows
                    ]
                except Exception as e:
                    logger.warning(f"Failed to export table {table_name}: {e}")
                    export_data["data"][table_name] = []
            
            return export_data
        finally:
            session.close()
    
    async def _import_data(self, backup_data: Dict[str, Any], tables: List[str] = None) -> Dict[str, Any]:
        from app.database import Base
        from sqlalchemy.orm import sessionmaker
        
        Session = sessionmaker(bind=sync_engine)
        session = Session()
        
        stats = {}
        
        try:
            for table_name, rows in backup_data.get("data", {}).items():
                if tables and table_name not in tables:
                    continue
                
                table = Base.metadata.tables.get(table_name)
                if table is None:
                    continue
                
                try:
                    for row in rows:
                        session.execute(table.insert().values(**row))
                    session.commit()
                    stats[table_name] = len(rows)
                except Exception as e:
                    session.rollback()
                    logger.warning(f"Failed to import table {table_name}: {e}")
                    stats[table_name] = {"error": str(e)}
            
            return stats
        finally:
            session.close()
    
    def _calculate_file_checksum(self, filepath: str) -> str:
        sha256 = hashlib.sha256()
        with open(filepath, 'rb') as f:
            for chunk in iter(lambda: f.read(8192), b''):
                sha256.update(chunk)
        return sha256.hexdigest()
    
    async def upload_file(
        self,
        category: str,
        filename: str,
        content: bytes
    ) -> Dict[str, Any]:
        category_paths = {
            "firmware": settings.FIRMWARE_PATH,
            "model": settings.MODELS_PATH
        }
        
        if category not in category_paths:
            raise StorageError(f"Invalid category: {category}")
        
        filepath = os.path.join(category_paths[category], filename)
        
        os.makedirs(os.path.dirname(filepath), exist_ok=True)
        
        with open(filepath, 'wb') as f:
            f.write(content)
        
        checksum = self._calculate_file_checksum(filepath)
        file_size = len(content)
        
        logger.info("File uploaded", category=category, filename=filename, size=file_size)
        
        return {
            "filepath": filepath,
            "filename": filename,
            "file_size": file_size,
            "checksum": checksum,
            "uploaded_at": datetime.utcnow().isoformat()
        }
    
    async def download_file(self, filepath: str) -> bytes:
        if not os.path.exists(filepath):
            raise StorageError(f"File not found: {filepath}")
        
        with open(filepath, 'rb') as f:
            return f.read()
    
    def get_storage_stats(self) -> Dict[str, Any]:
        def get_directory_size(path: str) -> int:
            total = 0
            if os.path.exists(path):
                for entry in os.scandir(path):
                    if entry.is_file():
                        total += entry.stat().st_size
                    elif entry.is_dir():
                        total += get_directory_size(entry.path)
            return total
        
        return {
            "base_path": settings.STORAGE_BASE_PATH,
            "backup": {
                "path": settings.BACKUP_PATH,
                "size_bytes": get_directory_size(settings.BACKUP_PATH)
            },
            "firmware": {
                "path": settings.FIRMWARE_PATH,
                "size_bytes": get_directory_size(settings.FIRMWARE_PATH)
            },
            "models": {
                "path": settings.MODELS_PATH,
                "size_bytes": get_directory_size(settings.MODELS_PATH)
            }
        }
