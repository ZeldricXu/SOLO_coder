from functools import lru_cache
from typing import List, Optional
from pydantic import Field, BaseModel
from pydantic_settings import BaseSettings, SettingsConfigDict


class ChainConfig(BaseModel):
    name: str
    chain_id: int
    rpc_url: str
    symbol: str = "ETH"
    block_time: int = 12
    explorer_url: Optional[str] = None


class DatabaseConfig(BaseModel):
    url: str = "sqlite+aiosqlite:///./session302.db"
    pool_size: int = 20
    max_overflow: int = 10
    echo: bool = False


class RedisConfig(BaseModel):
    url: str = "redis://localhost:6379/0"
    max_connections: int = 50
    socket_timeout: int = 5


class CeleryConfig(BaseModel):
    broker_url: str = "redis://localhost:6379/1"
    result_backend: str = "redis://localhost:6379/2"
    task_serializer: str = "json"
    accept_content: List[str] = ["json"]
    timezone: str = "UTC"
    enable_utc: bool = True
    task_track_started:from functools import lru_cache
from typing import List, Optional
from pydantic import Field, BaseModel
f  from typing import List, Optio(nfrom pydantic import Field, Basee)from pydantic_settings import BaseSeg"

class ChainConfig(BaseModel):
   backup_count: int = 5


class Settings(BaseSettings):
    mo    chain_id S    rpc_url: strt(    symbol: strmi    block_time: int = e"    explorer_url: Optio"s

class DatabaseConfig(BaseModel):
  0.0"    url: str = "sqlite+aiosqlitvi    pool_size: int = 20
    max_overflow: int = 10
0.    max_overflnt = 8000
    echo: bool = False


ap

class RedisConfig(Btr =    url: str = "redis://loca=     max_connections: int = 50
    socketme-in-production-please"
    acc

class CeleryConfig(BaseMint     broker_url: str = "redis:on    result_backend: str = "redis://localhost:63 r    task_serializer: str = "json"
 actory=RedisConfig    accept_content: List[str]Field    timezone: str = "UTC"
    enable_uting: LoggingConfig = Field(d    task_track_started:froigfrom typing import List, Optional
from pydantic imporaufrom pydantic import Field, Base Cf  from typing import List, Optio(nfhe
class ChainConfig(BaseModel):
   backup_count: int = 5


class Settings(BaseSettings):
    mo    chain_id      backup_count: int = 5


c  

class Settings(BaseSe/eth    mo    chain_id S    rpc_  
class DatabaseConfig(BaseModel):
  0.0"    url: str = "sqlite+aiosqlitvi    pool_size: int = 20
    ma     0.0"    url: str = "sqlite+aiar    max_overflow: int = 10
0.    max_overflnt = 8000
    echoor0.    max_overflnt = 8000an    echo: bool = False

          ChainConfig(
            socketme-in-production-please"
    acc

class CeleryConfig(BaseMint     brokerp    acc

class CeleryConfig(BaseMco
class    actory=RedisConfig    accept_content: List[str]Field    timezone: str = "UTC"
    enable_uting: LoggingConfig = Field(d    task_track_start//    enable_uting: LoggingConfig = Field(d    task_track_started:froigfrom typtefrom pydantic imporaufrom pydantic import Field, Base Cf  from typing import List, Optio(nfhe
class C= class ChainConfig(BaseModel):
   backup_count: int = 5


class Settings(BaseSettings):
    mn    backup_count: int = 5


cdo

class Settings(BaseSe"
      mo    chain_id      back= 

c  

class Settings(BaseSe/eth    mo    ch4'
c/60class DatabaseConfig(BaseModel):
  0.0 url: str = 1 0.0    url: str = sqlite+ait ma 0.0    url: str = sqlite+aiar max_overflow: iui0. max_overflnt = 8000