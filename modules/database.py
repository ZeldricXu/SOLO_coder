import sqlite3
import threading
import os
import uuid
from datetime import datetime
import logging

import sys
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from config import DATABASE_PATH

logger = logging.getLogger(__name__)


class DatabaseManager:
    _instance = None
    _lock = threading.Lock()
    
    def __new__(cls):
        if cls._instance is None:
            with cls._lock:
                if cls._instance is None:
                    cls._instance = super().__new__(cls)
                    cls._instance._initialized = False
        return cls._instance
    
    def __init__(self):
        if self._initialized:
            return
        
        self._db_path = DATABASE_PATH
        self._local = threading.local()
        self._write_lock = threading.Lock()
        self._initialized = True
        
        self._init_database()
        logger.info("DatabaseManager initialized")
    
    def _get_connection(self):
        if not hasattr(self._local, 'connection'):
            self._local.connection = sqlite3.connect(
                self._db_path, 
                check_same_thread=False,
                isolation_level=None
            )
            self._local.connection.row_factory = sqlite3.Row
        return self._local.connection
    
    def _init_database(self):
        conn = self._get_connection()
        cursor = conn.cursor()
        
        cursor.execute('''
            CREATE TABLE IF NOT EXISTS documents (
                file_id TEXT PRIMARY KEY,
                file_path TEXT NOT NULL UNIQUE,
                file_type TEXT NOT NULL,
                summary_text TEXT,
                status TEXT NOT NULL DEFAULT 'PENDING',
                error_message TEXT,
                created_at TIMESTAMP NOT NULL,
                updated_at TIMESTAMP NOT NULL
            )
        ''')
        
        cursor.execute('''
            CREATE INDEX IF NOT EXISTS idx_file_path ON documents(file_path)
        ''')
        
        cursor.execute('''
            CREATE INDEX IF NOT EXISTS idx_status ON documents(status)
        ''')
        
        cursor.execute('''
            CREATE INDEX IF NOT EXISTS idx_created_at ON documents(created_at)
        ''')
        
        conn.commit()
        logger.info("Database initialized successfully")
    
    def _generate_file_id(self):
        return str(uuid.uuid4())
    
    def _get_current_timestamp(self):
        return datetime.now().isoformat()
    
    def create_document(self, file_path, file_type):
        with self._write_lock:
            conn = self._get_connection()
            cursor = conn.cursor()
            
            try:
                file_id = self._generate_file_id()
                now = self._get_current_timestamp()
                
                cursor.execute('''
                    INSERT INTO documents 
                    (file_id, file_path, file_type, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'PENDING', ?, ?)
                ''', (file_id, file_path, file_type, now, now))
                
                conn.commit()
                logger.info(f"Document created: {file_path}")
                return file_id
            except sqlite3.IntegrityError:
                cursor.execute('''
                    SELECT file_id FROM documents WHERE file_path = ?
                ''', (file_path,))
                result = cursor.fetchone()
                if result:
                    logger.warning(f"Document already exists: {file_path}")
                    return result['file_id']
                return None
            except Exception as e:
                logger.error(f"Error creating document: {str(e)}")
                return None
    
    def update_document_status(self, file_path, status, summary_text=None, error_message=None):
        with self._write_lock:
            conn = self._get_connection()
            cursor = conn.cursor()
            
            try:
                now = self._get_current_timestamp()
                
                if summary_text is not None:
                    cursor.execute('''
                        UPDATE documents 
                        SET status = ?, summary_text = ?, error_message = ?, updated_at = ?
                        WHERE file_path = ?
                    ''', (status, summary_text, error_message, now, file_path))
                else:
                    cursor.execute('''
                        UPDATE documents 
                        SET status = ?, error_message = ?, updated_at = ?
                        WHERE file_path = ?
                    ''', (status, error_message, now, file_path))
                
                conn.commit()
                logger.info(f"Document status updated: {file_path} -> {status}")
                return cursor.rowcount > 0
            except Exception as e:
                logger.error(f"Error updating document status: {str(e)}")
                return False
    
    def get_document_by_path(self, file_path):
        conn = self._get_connection()
        cursor = conn.cursor()
        
        try:
            cursor.execute('''
                SELECT * FROM documents WHERE file_path = ?
            ''', (file_path,))
            result = cursor.fetchone()
            return dict(result) if result else None
        except Exception as e:
            logger.error(f"Error getting document by path: {str(e)}")
            return None
    
    def get_document_by_id(self, file_id):
        conn = self._get_connection()
        cursor = conn.cursor()
        
        try:
            cursor.execute('''
                SELECT * FROM documents WHERE file_id = ?
            ''', (file_id,))
            result = cursor.fetchone()
            return dict(result) if result else None
        except Exception as e:
            logger.error(f"Error getting document by id: {str(e)}")
            return None
    
    def get_recent_documents(self, limit=10):
        conn = self._get_connection()
        cursor = conn.cursor()
        
        try:
            cursor.execute('''
                SELECT * FROM documents 
                ORDER BY created_at DESC 
                LIMIT ?
            ''', (limit,))
            results = cursor.fetchall()
            return [dict(row) for row in results]
        except Exception as e:
            logger.error(f"Error getting recent documents: {str(e)}")
            return []
    
    def search_documents(self, keyword):
        conn = self._get_connection()
        cursor = conn.cursor()
        
        try:
            search_pattern = f"%{keyword}%"
            cursor.execute('''
                SELECT * FROM documents 
                WHERE file_path LIKE ? OR summary_text LIKE ?
                ORDER BY created_at DESC
            ''', (search_pattern, search_pattern))
            results = cursor.fetchall()
            return [dict(row) for row in results]
        except Exception as e:
            logger.error(f"Error searching documents: {str(e)}")
            return []
    
    def get_all_documents(self):
        conn = self._get_connection()
        cursor = conn.cursor()
        
        try:
            cursor.execute('''
                SELECT * FROM documents ORDER BY created_at DESC
            ''')
            results = cursor.fetchall()
            return [dict(row) for row in results]
        except Exception as e:
            logger.error(f"Error getting all documents: {str(e)}")
            return []
    
    def delete_document(self, file_path):
        with self._write_lock:
            conn = self._get_connection()
            cursor = conn.cursor()
            
            try:
                cursor.execute('''
                    DELETE FROM documents WHERE file_path = ?
                ''', (file_path,))
                conn.commit()
                logger.info(f"Document deleted: {file_path}")
                return cursor.rowcount > 0
            except Exception as e:
                logger.error(f"Error deleting document: {str(e)}")
                return False
    
    def close(self):
        if hasattr(self._local, 'connection'):
            self._local.connection.close()
            delattr(self._local, 'connection')
        logger.info("Database connection closed")
