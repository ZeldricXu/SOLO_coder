"""
Schema提取器模块
支持MySQL、PostgreSQL、SQLite等关系型数据库的Schema提取
"""

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Dict, List, Optional

from .data_source import DataSourceType, RelationalDataSource
from session307.exceptions import MetadataCrawlError


class ConstraintType(Enum):
    """约束类型枚举"""

    PRIMARY_KEY = "primary_key"
    FOREIGN_KEY = "foreign_key"
    UNIQUE = "unique"
    NOT_NULL = "not_null"
    CHECK = "check"
    DEFAULT = "default"


class IndexType(Enum):
    """索引类型枚举"""

    BTREE = "btree"
    HASH = "hash"
    FULLTEXT = "fulltext"
    SPATIAL = "spatial"
    GIN = "gin"
    GIST = "gist"
    UNIQUE = "unique"
    NORMAL = "normal"


@dataclass
class ColumnSchema:
    """列Schema数据类

    Attributes:
        name: 列名
        data_type: 数据类型
        column_type: 完整的列类型定义
        nullable: 是否允许为空
        default_value: 默认值
        is_primary_key: 是否为主键
        is_foreign_key: 是否为外键
        is_unique: 是否唯一
        character_maximum_length: 字符最大长度
        numeric_precision: 数值精度
        numeric_scale: 数值小数位数
        datetime_precision: 日期时间精度
        collation: 排序规则
        comment: 列注释
        extra: 额外信息（如auto_increment）
        ordinal_position: 列的位置序号
    """

    name: str
    data_type: str
    column_type: str
    nullable: bool = True
    default_value: Optional[str] = None
    is_primary_key: bool = False
    is_foreign_key: bool = False
    is_unique: bool = False
    character_maximum_length: Optional[int] = None
    numeric_precision: Optional[int] = None
    numeric_scale: Optional[int] = None
    datetime_precision: Optional[int] = None
    collation: Optional[str] = None
    comment: Optional[str] = None
    extra: Optional[str] = None
    ordinal_position: Optional[int] = None

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            列Schema的字典表示
        """
        return {
            "name": self.name,
            "data_type": self.data_type,
            "column_type": self.column_type,
            "nullable": self.nullable,
            "default_value": self.default_value,
            "is_primary_key": self.is_primary_key,
            "is_foreign_key": self.is_foreign_key,
            "is_unique": self.is_unique,
            "character_maximum_length": self.character_maximum_length,
            "numeric_precision": self.numeric_precision,
            "numeric_scale": self.numeric_scale,
            "datetime_precision": self.datetime_precision,
            "collation": self.collation,
            "comment": self.comment,
            "extra": self.extra,
            "ordinal_position": self.ordinal_position,
        }


@dataclass
class IndexSchema:
    """索引Schema数据类

    Attributes:
        name: 索引名称
        columns: 索引包含的列名列表
        index_type: 索引类型
        is_unique: 是否唯一索引
        is_primary: 是否主键索引
        cardinality: 基数（估计的唯一值数量）
        comment: 索引注释
    """

    name: str
    columns: List[str]
    index_type: IndexType = IndexType.NORMAL
    is_unique: bool = False
    is_primary: bool = False
    cardinality: Optional[int] = None
    comment: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            索引Schema的字典表示
        """
        return {
            "name": self.name,
            "columns": self.columns,
            "index_type": self.index_type.value,
            "is_unique": self.is_unique,
            "is_primary": self.is_primary,
            "cardinality": self.cardinality,
            "comment": self.comment,
        }


@dataclass
class ForeignKeySchema:
    """外键Schema数据类

    Attributes:
        name: 外键名称
        column: 当前表的列名
        referenced_table: 引用的表名
        referenced_column: 引用的列名
        on_delete: 删除时的动作
        on_update: 更新时的动作
    """

    name: str
    column: str
    referenced_table: str
    referenced_column: str
    on_delete: Optional[str] = None
    on_update: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            外键Schema的字典表示
        """
        return {
            "name": self.name,
            "column": self.column,
            "referenced_table": self.referenced_table,
            "referenced_column": self.referenced_column,
            "on_delete": self.on_delete,
            "on_update": self.on_update,
        }


@dataclass
class TableSchema:
    """表Schema数据类

    Attributes:
        name: 表名
        schema: 所属Schema（如public、dbo等）
        columns: 列Schema列表
        primary_key_columns: 主键列名列表
        indexes: 索引Schema列表
        foreign_keys: 外键Schema列表
        comment: 表注释
        row_count: 行数（估计值）
        data_size: 数据大小（字节）
        index_size: 索引大小（字节）
        create_time: 创建时间
        update_time: 更新时间
        engine: 存储引擎（MySQL）
        collation: 表排序规则
    """

    name: str
    schema: Optional[str] = None
    columns: List[ColumnSchema] = field(default_factory=list)
    primary_key_columns: List[str] = field(default_factory=list)
    indexes: List[IndexSchema] = field(default_factory=list)
    foreign_keys: List[ForeignKeySchema] = field(default_factory=list)
    comment: Optional[str] = None
    row_count: Optional[int] = None
    data_size: Optional[int] = None
    index_size: Optional[int] = None
    create_time: Optional[str] = None
    update_time: Optional[str] = None
    engine: Optional[str] = None
    collation: Optional[str] = None

    def get_column(self, column_name: str) -> Optional[ColumnSchema]:
        """根据列名获取列Schema

        Args:
            column_name: 列名

        Returns:
            列Schema对象，未找到返回None
        """
        for col in self.columns:
            if col.name == column_name:
                return col
        return None

    def get_index(self, index_name: str) -> Optional[IndexSchema]:
        """根据索引名获取索引Schema

        Args:
            index_name: 索引名

        Returns:
            索引Schema对象，未找到返回None
        """
        for idx in self.indexes:
            if idx.name == index_name:
                return idx
        return None

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            表Schema的字典表示
        """
        return {
            "name": self.name,
            "schema": self.schema,
            "columns": [col.to_dict() for col in self.columns],
            "primary_key_columns": self.primary_key_columns,
            "indexes": [idx.to_dict() for idx in self.indexes],
            "foreign_keys": [fk.to_dict() for fk in self.foreign_keys],
            "comment": self.comment,
            "row_count": self.row_count,
            "data_size": self.data_size,
            "index_size": self.index_size,
            "create_time": self.create_time,
            "update_time": self.update_time,
            "engine": self.engine,
            "collation": self.collation,
        }


class SchemaExtractor(ABC):
    """Schema提取器抽象基类"""

    def __init__(self, data_source: RelationalDataSource):
        """初始化Schema提取器

        Args:
            data_source: 关系型数据源
        """
        self.data_source = data_source
        self._connection_manager = data_source.connection_manager

    @abstractmethod
    def extract_table_schema(self, table_name: str, schema_name: Optional[str] = None) -> TableSchema:
        """提取单个表的Schema

        Args:
            table_name: 表名
            schema_name: Schema名称

        Returns:
            表Schema对象
        """
        pass

    @abstractmethod
    def extract_all_tables(self, schema_name: Optional[str] = None) -> List[TableSchema]:
        """提取所有表的Schema

        Args:
            schema_name: Schema名称

        Returns:
            表Schema对象列表
        """
        pass

    @abstractmethod
    def extract_columns(self, table_name: str, schema_name: Optional[str] = None) -> List[ColumnSchema]:
        """提取表的列信息

        Args:
            table_name: 表名
            schema_name: Schema名称

        Returns:
            列Schema对象列表
        """
        pass

    @abstractmethod
    def extract_indexes(self, table_name: str, schema_name: Optional[str] = None) -> List[IndexSchema]:
        """提取表的索引信息

        Args:
            table_name: 表名
            schema_name: Schema名称

        Returns:
            索引Schema对象列表
        """
        pass

    @abstractmethod
    def extract_foreign_keys(
        self, table_name: str, schema_name: Optional[str] = None
    ) -> List[ForeignKeySchema]:
        """提取表的外键信息

        Args:
            table_name: 表名
            schema_name: Schema名称

        Returns:
            外键Schema对象列表
        """
        pass

    @abstractmethod
    def extract_table_metadata(self, table_name: str, schema_name: Optional[str] = None) -> Dict[str, Any]:
        """提取表的元数据（行数、大小等）

        Args:
            table_name: 表名
            schema_name: Schema名称

        Returns:
            表元数据字典
        """
        pass

    def _execute_query(self, query: str, params: Optional[Dict[str, Any]] = None) -> List[tuple]:
        """执行SQL查询

        Args:
            query: SQL查询语句
            params: 查询参数

        Returns:
            查询结果列表

        Raises:
            MetadataCrawlError: 当查询执行失败时抛出
        """
        try:
            return self._connection_manager.execute_query(query, params)
        except Exception as e:
            raise MetadataCrawlError(f"Schema extraction query failed: {e}") from e


class MySQLSchemaExtractor(SchemaExtractor):
    """MySQL Schema提取器"""

    def extract_table_schema(self, table_name: str, schema_name: Optional[str] = None) -> TableSchema:
        """提取MySQL单个表的Schema

        Args:
            table_name: 表名
            schema_name: 数据库名，None表示当前数据库

        Returns:
            表Schema对象
        """
        schema_name = schema_name or "DATABASE()"
        columns = self.extract_columns(table_name, schema_name)
        indexes = self.extract_indexes(table_name, schema_name)
        foreign_keys = self.extract_foreign_keys(table_name, schema_name)
        metadata = self.extract_table_metadata(table_name, schema_name)

        primary_key_columns = [
            col.name for col in columns if col.is_primary_key
        ]

        return TableSchema(
            name=table_name,
            schema=schema_name if schema_name != "DATABASE()" else None,
            columns=columns,
            primary_key_columns=primary_key_columns,
            indexes=indexes,
            foreign_keys=foreign_keys,
            comment=metadata.get("comment"),
            row_count=metadata.get("row_count"),
            data_size=metadata.get("data_size"),
            index_size=metadata.get("index_size"),
            create_time=metadata.get("create_time"),
            update_time=metadata.get("update_time"),
            engine=metadata.get("engine"),
            collation=metadata.get("collation"),
        )

    def extract_all_tables(self, schema_name: Optional[str] = None) -> List[TableSchema]:
        """提取MySQL所有表的Schema

        Args:
            schema_name: 数据库名，None表示当前数据库

        Returns:
            表Schema对象列表
        """
        tables = self.data_source.list_tables()
        return [self.extract_table_schema(table, schema_name) for table in tables]

    def extract_columns(self, table_name: str, schema_name: Optional[str] = None) -> List[ColumnSchema]:
        """提取MySQL表的列信息

        Args:
            table_name: 表名
            schema_name: 数据库名

        Returns:
            列Schema对象列表
        """
        query = """
            SELECT
                c.column_name,
                c.data_type,
                c.column_type,
                c.is_nullable,
                c.column_default,
                c.character_maximum_length,
                c.numeric_precision,
                c.numeric_scale,
                c.datetime_precision,
                c.collation_name,
                c.column_comment,
                c.extra,
                c.ordinal_position,
                CASE WHEN kcu.column_name IS NOT NULL THEN TRUE ELSE FALSE END as is_primary_key
            FROM information_schema.columns c
            LEFT JOIN information_schema.key_column_usage kcu
                ON c.table_schema = kcu.table_schema
                AND c.table_name = kcu.table_name
                AND c.column_name = kcu.column_name
                AND kcu.constraint_name = 'PRIMARY'
            WHERE c.table_name = :table_name
        """
        params: Dict[str, Any] = {"table_name": table_name}
        if schema_name and schema_name != "DATABASE()":
            query += " AND c.table_schema = :schema_name"
            params["schema_name"] = schema_name
        else:
            query += " AND c.table_schema = DATABASE()"

        query += " ORDER BY c.ordinal_position"

        results = self._execute_query(query, params)

        columns: List[ColumnSchema] = []
        for row in results:
            col = ColumnSchema(
                name=row[0],
                data_type=row[1],
                column_type=row[2],
                nullable=(row[3] == "YES"),
                default_value=row[4],
                character_maximum_length=row[5],
                numeric_precision=row[6],
                numeric_scale=row[7],
                datetime_precision=row[8],
                collation=row[9],
                comment=row[10],
                extra=row[11],
                ordinal_position=row[12],
                is_primary_key=bool(row[13]),
            )
            columns.append(col)

        return columns

    def extract_indexes(self, table_name: str, schema_name: Optional[str] = None) -> List[IndexSchema]:
        """提取MySQL表的索引信息

        Args:
            table_name: 表名
            schema_name: 数据库名

        Returns:
            索引Schema对象列表
        """
        query = """
            SELECT
                index_name,
                column_name,
                index_type,
                non_unique,
                cardinality,
                seq_in_index
            FROM information_schema.statistics
            WHERE table_name = :table_name
        """
        params: Dict[str, Any] = {"table_name": table_name}
        if schema_name and schema_name != "DATABASE()":
            query += " AND table_schema = :schema_name"
            params["schema_name"] = schema_name
        else:
            query += " AND table_schema = DATABASE()"

        query += " ORDER BY index_name, seq_in_index"

        results = self._execute_query(query, params)

        index_map: Dict[str, Dict[str, Any]] = {}
        for row in results:
            idx_name = row[0]
            col_name = row[1]
            idx_type_str = row[2].lower()
            non_unique = bool(row[3])
            cardinality = row[4]

            if idx_name not in index_map:
                try:
                    idx_type = IndexType(idx_type_str)
                except ValueError:
                    idx_type = IndexType.NORMAL

                index_map[idx_name] = {
                    "columns": [],
                    "index_type": idx_type,
                    "is_unique": not non_unique,
                    "is_primary": idx_name == "PRIMARY",
                    "cardinality": cardinality,
                }

            index_map[idx_name]["columns"].append(col_name)

        indexes: List[IndexSchema] = []
        for name, data in index_map.items():
            indexes.append(
                IndexSchema(
                    name=name,
                    columns=data["columns"],
                    index_type=data["index_type"],
                    is_unique=data["is_unique"],
                    is_primary=data["is_primary"],
                    cardinality=data["cardinality"],
                )
            )

        return indexes

    def extract_foreign_keys(
        self, table_name: str, schema_name: Optional[str] = None
    ) -> List[ForeignKeySchema]:
        """提取MySQL表的外键信息

        Args:
            table_name: 表名
            schema_name: 数据库名

        Returns:
            外键Schema对象列表
        """
        query = """
            SELECT
                kcu.constraint_name,
                kcu.column_name,
                kcu.referenced_table_name,
                kcu.referenced_column_name,
                rc.update_rule,
                rc.delete_rule
            FROM information_schema.key_column_usage kcu
            JOIN information_schema.referential_constraints rc
                ON kcu.constraint_name = rc.constraint_name
                AND kcu.table_schema = rc.constraint_schema
            WHERE kcu.table_name = :table_name
            AND kcu.referenced_table_name IS NOT NULL
        """
        params: Dict[str, Any] = {"table_name": table_name}
        if schema_name and schema_name != "DATABASE()":
            query += " AND kcu.table_schema = :schema_name"
            params["schema_name"] = schema_name
        else:
            query += " AND kcu.table_schema = DATABASE()"

        results = self._execute_query(query, params)

        foreign_keys: List[ForeignKeySchema] = []
        for row in results:
            foreign_keys.append(
                ForeignKeySchema(
                    name=row[0],
                    column=row[1],
                    referenced_table=row[2],
                    referenced_column=row[3],
                    on_update=row[4],
                    on_delete=row[5],
                )
            )

        return foreign_keys

    def extract_table_metadata(self, table_name: str, schema_name: Optional[str] = None) -> Dict[str, Any]:
        """提取MySQL表的元数据

        Args:
            table_name: 表名
            schema_name: 数据库名

        Returns:
            表元数据字典
        """
        query = """
            SELECT
                table_comment,
                table_rows,
                data_length,
                index_length,
                create_time,
                update_time,
                engine,
                table_collation
            FROM information_schema.tables
            WHERE table_name = :table_name
        """
        params: Dict[str, Any] = {"table_name": table_name}
        if schema_name and schema_name != "DATABASE()":
            query += " AND table_schema = :schema_name"
            params["schema_name"] = schema_name
        else:
            query += " AND table_schema = DATABASE()"

        results = self._execute_query(query, params)

        if not results:
            return {}

        row = results[0]
        return {
            "comment": row[0],
            "row_count": row[1],
            "data_size": row[2],
            "index_size": row[3],
            "create_time": str(row[4]) if row[4] else None,
            "update_time": str(row[5]) if row[5] else None,
            "engine": row[6],
            "collation": row[7],
        }


class PostgreSQLSchemaExtractor(SchemaExtractor):
    """PostgreSQL Schema提取器"""

    def extract_table_schema(self, table_name: str, schema_name: Optional[str] = None) -> TableSchema:
        """提取PostgreSQL单个表的Schema

        Args:
            table_name: 表名
            schema_name: Schema名，默认public

        Returns:
            表Schema对象
        """
        schema_name = schema_name or "public"
        columns = self.extract_columns(table_name, schema_name)
        indexes = self.extract_indexes(table_name, schema_name)
        foreign_keys = self.extract_foreign_keys(table_name, schema_name)
        metadata = self.extract_table_metadata(table_name, schema_name)

        primary_key_columns = [
            col.name for col in columns if col.is_primary_key
        ]

        return TableSchema(
            name=table_name,
            schema=schema_name,
            columns=columns,
            primary_key_columns=primary_key_columns,
            indexes=indexes,
            foreign_keys=foreign_keys,
            comment=metadata.get("comment"),
            row_count=metadata.get("row_count"),
            data_size=metadata.get("data_size"),
            index_size=metadata.get("index_size"),
            create_time=metadata.get("create_time"),
        )

    def extract_all_tables(self, schema_name: Optional[str] = None) -> List[TableSchema]:
        """提取PostgreSQL所有表的Schema

        Args:
            schema_name: Schema名

        Returns:
            表Schema对象列表
        """
        schema_name = schema_name or "public"
        tables = self.data_source.list_tables()
        return [self.extract_table_schema(table, schema_name) for table in tables]

    def extract_columns(self, table_name: str, schema_name: Optional[str] = None) -> List[ColumnSchema]:
        """提取PostgreSQL表的列信息

        Args:
            table_name: 表名
            schema_name: Schema名

        Returns:
            列Schema对象列表
        """
        schema_name = schema_name or "public"
        query = """
            SELECT
                c.column_name,
                c.data_type,
                c.udt_name,
                c.is_nullable,
                c.column_default,
                c.character_maximum_length,
                c.numeric_precision,
                c.numeric_scale,
                c.datetime_precision,
                c.collation_name,
                pg_catalog.col_description(
                    (quote_ident(c.table_schema) || '.' || quote_ident(c.table_name))::regclass,
                    c.ordinal_position
                ) as column_comment,
                c.ordinal_position,
                CASE WHEN kcu.column_name IS NOT NULL THEN TRUE ELSE FALSE END as is_primary_key
            FROM information_schema.columns c
            LEFT JOIN information_schema.table_constraints tc
                ON c.table_schema = tc.table_schema
                AND c.table_name = tc.table_name
                AND tc.constraint_type = 'PRIMARY KEY'
            LEFT JOIN information_schema.key_column_usage kcu
                ON tc.constraint_schema = kcu.constraint_schema
                AND tc.constraint_name = kcu.constraint_name
                AND c.column_name = kcu.column_name
            WHERE c.table_name = :table_name
            AND c.table_schema = :schema_name
            ORDER BY c.ordinal_position
        """
        params = {"table_name": table_name, "schema_name": schema_name}
        results = self._execute_query(query, params)

        columns: List[ColumnSchema] = []
        for row in results:
            col = ColumnSchema(
                name=row[0],
                data_type=row[1],
                column_type=row[2],
                nullable=(row[3] == "YES"),
                default_value=row[4],
                character_maximum_length=row[5],
                numeric_precision=row[6],
                numeric_scale=row[7],
                datetime_precision=row[8],
                collation=row[9],
                comment=row[10],
                ordinal_position=row[11],
                is_primary_key=bool(row[12]),
            )
            columns.append(col)

        return columns

    def extract_indexes(self, table_name: str, schema_name: Optional[str] = None) -> List[IndexSchema]:
        """提取PostgreSQL表的索引信息

        Args:
            table_name: 表名
            schema_name: Schema名

        Returns:
            索引Schema对象列表
        """
        schema_name = schema_name or "public"
        query = """
            SELECT
                i.relname as index_name,
                a.attname as column_name,
                am.amname as index_type,
                ix.indisunique,
                ix.indisprimary,
                c.reltuples::bigint as cardinality,
                array_position(ix.indkey, a.attnum) as seq_in_index
            FROM pg_index ix
            JOIN pg_class t ON ix.indrelid = t.oid
            JOIN pg_class i ON ix.indexrelid = i.oid
            JOIN pg_am am ON i.relam = am.oid
            JOIN pg_namespace n ON t.relnamespace = n.oid
            JOIN unnest(ix.indkey) WITH ORDINALITY AS k(attnum, ord) ON true
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = k.attnum
            WHERE t.relname = :table_name
            AND n.nspname = :schema_name
            ORDER BY i.relname, k.ord
        """
        params = {"table_name": table_name, "schema_name": schema_name}
        results = self._execute_query(query, params)

        index_map: Dict[str, Dict[str, Any]] = {}
        for row in results:
            idx_name = row[0]
            col_name = row[1]
            idx_type_str = row[2]
            is_unique = bool(row[3])
            is_primary = bool(row[4])
            cardinality = row[5]

            if idx_name not in index_map:
                try:
                    idx_type = IndexType(idx_type_str)
                except ValueError:
                    idx_type = IndexType.NORMAL

                index_map[idx_name] = {
                    "columns": [],
                    "index_type": idx_type,
                    "is_unique": is_unique,
                    "is_primary": is_primary,
                    "cardinality": cardinality,
                }

            index_map[idx_name]["columns"].append(col_name)

        indexes: List[IndexSchema] = []
        for name, data in index_map.items():
            indexes.append(
                IndexSchema(
                    name=name,
                    columns=data["columns"],
                    index_type=data["index_type"],
                    is_unique=data["is_unique"],
                    is_primary=data["is_primary"],
                    cardinality=data["cardinality"],
                )
            )

        return indexes

    def extract_foreign_keys(
        self, table_name: str, schema_name: Optional[str] = None
    ) -> List[ForeignKeySchema]:
        """提取PostgreSQL表的外键信息

        Args:
            table_name: 表名
            schema_name: Schema名

        Returns:
            外键Schema对象列表
        """
        schema_name = schema_name or "public"
        query = """
            SELECT
                tc.constraint_name,
                kcu.column_name,
                ccu.table_name AS foreign_table_name,
                ccu.column_name AS foreign_column_name,
                rc.update_rule,
                rc.delete_rule
            FROM information_schema.table_constraints AS tc
            JOIN information_schema.key_column_usage AS kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
            JOIN information_schema.constraint_column_usage AS ccu
                ON ccu.constraint_name = tc.constraint_name
                AND ccu.table_schema = tc.table_schema
            JOIN information_schema.referential_constraints rc
                ON tc.constraint_name = rc.constraint_name
                AND tc.table_schema = rc.constraint_schema
            WHERE tc.constraint_type = 'FOREIGN KEY'
            AND tc.table_name = :table_name
            AND tc.table_schema = :schema_name
        """
        params = {"table_name": table_name, "schema_name": schema_name}
        results = self._execute_query(query, params)

        foreign_keys: List[ForeignKeySchema] = []
        for row in results:
            foreign_keys.append(
                ForeignKeySchema(
                    name=row[0],
                    column=row[1],
                    referenced_table=row[2],
                    referenced_column=row[3],
                    on_update=row[4],
                    on_delete=row[5],
                )
            )

        return foreign_keys

    def extract_table_metadata(self, table_name: str, schema_name: Optional[str] = None) -> Dict[str, Any]:
        """提取PostgreSQL表的元数据

        Args:
            table_name: 表名
            schema_name: Schema名

        Returns:
            表元数据字典
        """
        schema_name = schema_name or "public"
        query = """
            SELECT
                obj_description(pc.oid) as table_comment,
                pc.reltuples::bigint as row_count,
                pg_total_relation_size(pc.oid) as total_size,
                pg_relation_size(pc.oid) as data_size,
                pg_indexes_size(pc.oid) as index_size,
                (pg_stat_get_create_time(pc.oid))::text as create_time
            FROM pg_class pc
            JOIN pg_namespace pn ON pc.relnamespace = pn.oid
            WHERE pc.relname = :table_name
            AND pn.nspname = :schema_name
        """
        params = {"table_name": table_name, "schema_name": schema_name}
        results = self._execute_query(query, params)

        if not results:
            return {}

        row = results[0]
        return {
            "comment": row[0],
            "row_count": row[1],
            "data_size": row[3],
            "index_size": row[4],
            "total_size": row[2],
            "create_time": row[5],
        }


class SQLiteSchemaExtractor(SchemaExtractor):
    """SQLite Schema提取器"""

    def extract_table_schema(self, table_name: str, schema_name: Optional[str] = None) -> TableSchema:
        """提取SQLite单个表的Schema

        Args:
            table_name: 表名
            schema_name: 忽略，SQLite不支持Schema

        Returns:
            表Schema对象
        """
        columns = self.extract_columns(table_name)
        indexes = self.extract_indexes(table_name)
        foreign_keys = self.extract_foreign_keys(table_name)
        metadata = self.extract_table_metadata(table_name)

        primary_key_columns = [
            col.name for col in columns if col.is_primary_key
        ]

        return TableSchema(
            name=table_name,
            columns=columns,
            primary_key_columns=primary_key_columns,
            indexes=indexes,
            foreign_keys=foreign_keys,
            row_count=metadata.get("row_count"),
            create_time=metadata.get("create_time"),
        )

    def extract_all_tables(self, schema_name: Optional[str] = None) -> List[TableSchema]:
        """提取SQLite所有表的Schema

        Args:
            schema_name: 忽略

        Returns:
            表Schema对象列表
        """
        tables = self.data_source.list_tables()
        return [self.extract_table_schema(table) for table in tables]

    def extract_columns(self, table_name: str, schema_name: Optional[str] = None) -> List[ColumnSchema]:
        """提取SQLite表的列信息

        Args:
            table_name: 表名
            schema_name: 忽略

        Returns:
            列Schema对象列表
        """
        query = f"PRAGMA table_info({table_name})"
        results = self._execute_query(query)

        columns: List[ColumnSchema] = []
        for row in results:
            col = ColumnSchema(
                name=row[1],
                data_type=row[2].lower() if row[2] else "text",
                column_type=row[2] or "TEXT",
                nullable=(row[3] == 0),
                default_value=row[4],
                is_primary_key=(row[5] > 0),
                ordinal_position=row[0],
            )
            columns.append(col)

        return columns

    def extract_indexes(self, table_name: str, schema_name: Optional[str] = None) -> List[IndexSchema]:
        """提取SQLite表的索引信息

        Args:
            table_name: 表名
            schema_name: 忽略

        Returns:
            索引Schema对象列表
        """
        query = f"PRAGMA index_list({table_name})"
        results = self._execute_query(query)

        indexes: List[IndexSchema] = []
        for row in results:
            idx_name = row[1]
            is_unique = bool(row[2])
            is_primary = (row[3] == "pk")
            seq = row[0]

            idx_info_query = f"PRAGMA index_info({idx_name})"
            idx_info_results = self._execute_query(idx_info_query)
            columns = [info_row[2] for info_row in idx_info_results]

            indexes.append(
                IndexSchema(
                    name=idx_name,
                    columns=columns,
                    index_type=IndexType.UNIQUE if is_unique else IndexType.NORMAL,
                    is_unique=is_unique,
                    is_primary=is_primary,
                )
            )

        return indexes

    def extract_foreign_keys(
        self, table_name: str, schema_name: Optional[str] = None
    ) -> List[ForeignKeySchema]:
        """提取SQLite表的外键信息

        Args:
            table_name: 表名
            schema_name: 忽略

        Returns:
            外键Schema对象列表
        """
        query = f"PRAGMA foreign_key_list({table_name})"
        results = self._execute_query(query)

        foreign_keys: List[ForeignKeySchema] = []
        for row in results:
            foreign_keys.append(
                ForeignKeySchema(
                    name=f"fk_{table_name}_{row[2]}_{row[3]}",
                    column=row[3],
                    referenced_table=row[2],
                    referenced_column=row[4],
                    on_delete=row[5],
                    on_update=row[6],
                )
            )

        return foreign_keys

    def extract_table_metadata(self, table_name: str, schema_name: Optional[str] = None) -> Dict[str, Any]:
        """提取SQLite表的元数据

        Args:
            table_name: 表名
            schema_name: 忽略

        Returns:
            表元数据字典
        """
        try:
            row_count = self.data_source.get_row_count(table_name)
            return {"row_count": row_count}
        except Exception:
            return {}


def create_schema_extractor(data_source: RelationalDataSource) -> SchemaExtractor:
    """根据数据源类型创建对应的Schema提取器

    Args:
        data_source: 关系型数据源

    Returns:
        对应的Schema提取器实例

    Raises:
        ValueError: 当数据源类型不支持时抛出
    """
    source_type = data_source.source_type

    if source_type == DataSourceType.MYSQL:
        return MySQLSchemaExtractor(data_source)
    elif source_type == DataSourceType.POSTGRESQL:
        return PostgreSQLSchemaExtractor(data_source)
    elif source_type == DataSourceType.SQLITE:
        return SQLiteSchemaExtractor(data_source)
    elif source_type == DataSourceType.RELATIONAL:
        return MySQLSchemaExtractor(data_source)
    else:
        raise ValueError(f"Unsupported data source type for schema extraction: {source_type}")
