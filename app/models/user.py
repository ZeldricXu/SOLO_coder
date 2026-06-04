from sqlalchemy import Column, Integer, String, DateTime
from sqlalchemy.sql import func

from app.database import Base


class User(Base):
    __tablename__ = "users"

    id = Column(Integer, primary_key=True, autoincrement=True, index=True)
    username = Column(String(50), unique=True, nullable=False, index=True)
    real_name = Column(String(100), nullable=False)
    role = Column(String(20), nullable=False, default="operator")
    email = Column(String(100))
    phone = Column(String(20))
    created_at = Column(DateTime(timezone=True), server_default=func.now())
