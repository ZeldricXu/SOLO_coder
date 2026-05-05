from sqlalchemy import create_engine, Column, String, JSON, DateTime, Integer, Float
from sqlalchemy.ext.declarative import declarative_base
from sqlalchemy.orm import sessionmaker
from datetime import datetime
from app.config.settings import settings

engine = create_engine(settings.DATABASE_URL)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)
Base = declarative_base()

class ComputeTask(Base):
    __tablename__ = "compute_tasks"
    
    id = Column(Integer, primary_key=True, index=True)
    task_id = Column(String, unique=True, index=True)
    task_type = Column(String, index=True)
    input_data = Column(JSON)
    status = Column(String, default="pending")
    progress = Column(Integer, default=0)
    priority = Column(Integer, default=5)
    error_message = Column(String, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    started_at = Column(DateTime, nullable=True)
    completed_at = Column(DateTime, nullable=True)

class ComputeResult(Base):
    __tablename__ = "compute_results"
    
    id = Column(Integer, primary_key=True, index=True)
    result_id = Column(String, unique=True, index=True)
    task_id = Column(String, unique=True, index=True)
    output_data = Column(JSON)
    computed_at = Column(DateTime, default=datetime.utcnow)
    execution_time_seconds = Column(Float, nullable=True)

def init_db():
    Base.metadata.create_all(bind=engine)

def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
