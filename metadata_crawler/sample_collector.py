"""
样例数据采集器模块
支持随机采样、分层采样、时间范围采样等多种采样方式
"""

from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional, Tuple, Union

import numpy as np
import pandas as pd

from .data_source import DataSource
from session307.exceptions import MetadataCrawlError


class SampleMethod(Enum):
    """采样方法枚举"""

    RANDOM = "random"
    STRATIFIED = "stratified"
    TIME_RANGE = "time_range"
    SYSTEMATIC = "systematic"
    CLUSTER = "cluster"
    RESERVOIR = "reservoir"
    WEIGHTED = "weighted"


@dataclass
class SampleConfig:
    """采样配置数据类

    Attributes:
        method: 采样方法
        sample_size: 采样数量或比例（0-1之间为比例）
        stratify_column: 分层采样的列名
        time_column: 时间范围采样的列名
        start_time: 时间范围开始
        end_time: 时间范围结束
        step: 系统采样的步长
        cluster_column: 聚类采样的列名
        cluster_count: 聚类数量
        weight_column: 加权采样的权重列名
        seed: 随机种子
        with_replacement: 是否有放回采样
        include_columns: 包含的列名列表，None表示所有列
        exclude_columns: 排除的列名列表
    """

    method: SampleMethod = SampleMethod.RANDOM
    sample_size: Union[int, float] = 1000
    stratify_column: Optional[str] = None
    time_column: Optional[str] = None
    start_time: Optional[datetime] = None
    end_time: Optional[datetime] = None
    step: Optional[int] = None
    cluster_column: Optional[str] = None
    cluster_count: Optional[int] = None
    weight_column: Optional[str] = None
    seed: Optional[int] = None
    with_replacement: bool = False
    include_columns: Optional[List[str]] = None
    exclude_columns: Optional[List[str]] = None

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            采样配置的字典表示
        """
        return {
            "method": self.method.value,
            "sample_size": self.sample_size,
            "stratify_column": self.stratify_column,
            "time_column": self.time_column,
            "start_time": self.start_time.isoformat() if self.start_time else None,
            "end_time": self.end_time.isoformat() if self.end_time else None,
            "step": self.step,
            "cluster_column": self.cluster_column,
            "cluster_count": self.cluster_count,
            "weight_column": self.weight_column,
            "seed": self.seed,
            "with_replacement": self.with_replacement,
            "include_columns": self.include_columns,
            "exclude_columns": self.exclude_columns,
        }


@dataclass
class SampleResult:
    """采样结果数据类

    Attributes:
        data: 采样数据
        sample_size: 实际采样数量
        total_size: 总数据量
        method: 使用的采样方法
        config: 采样配置
        sampled_at: 采样时间
        statistics: 采样统计信息
    """

    data: pd.DataFrame
    sample_size: int
    total_size: int
    method: SampleMethod
    config: SampleConfig
    sampled_at: str = field(default_factory=lambda: datetime.now().isoformat())
    statistics: Dict[str, Any] = field(default_factory=dict)

    @property
    def sampling_rate(self) -> float:
        """获取采样率

        Returns:
            采样率（0-1）
        """
        return self.sample_size / self.total_size if self.total_size > 0 else 0.0

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            采样结果的字典表示
        """
        return {
            "sample_size": self.sample_size,
            "total_size": self.total_size,
            "sampling_rate": self.sampling_rate,
            "method": self.method.value,
            "config": self.config.to_dict(),
            "sampled_at": self.sampled_at,
            "statistics": self.statistics,
            "columns": list(self.data.columns),
            "dtypes": {col: str(dtype) for col, dtype in self.data.dtypes.items()},
        }


class SampleCollector:
    """样例数据采集器

    支持多种采样方式：随机采样、分层采样、时间范围采样等
    """

    def __init__(self, data_source: DataSource, default_seed: Optional[int] = None):
        """初始化样例数据采集器

        Args:
            data_source: 数据源
            default_seed: 默认随机种子
        """
        self.data_source = data_source
        self.default_seed = default_seed

    def _get_seed(self, config: SampleConfig) -> Optional[int]:
        """获取随机种子

        Args:
            config: 采样配置

        Returns:
            随机种子
        """
        return config.seed or self.default_seed

    def _select_columns(
        self,
        df: pd.DataFrame,
        config: SampleConfig,
    ) -> pd.DataFrame:
        """选择列

        Args:
            df: 原始DataFrame
            config: 采样配置

        Returns:
            选择列后的DataFrame
        """
        if config.include_columns:
            available_cols = [c for c in config.include_columns if c in df.columns]
            if available_cols:
                df = df[available_cols]

        if config.exclude_columns:
            cols_to_keep = [c for c in df.columns if c not in config.exclude_columns]
            df = df[cols_to_keep]

        return df

    def _calculate_sample_count(self, total: int, config: SampleConfig) -> int:
        """计算实际采样数量

        Args:
            total: 总数据量
            config: 采样配置

        Returns:
            实际采样数量
        """
        if isinstance(config.sample_size, float) and 0 < config.sample_size <= 1:
            return int(total * config.sample_size)
        elif isinstance(config.sample_size, int):
            return min(config.sample_size, total)
        else:
            return min(1000, total)

    def collect_sample(
        self,
        table_name: str,
        config: SampleConfig,
    ) -> SampleResult:
        """采集样例数据

        Args:
            table_name: 表名
            config: 采样配置

        Returns:
            采样结果

        Raises:
            MetadataCrawlError: 当采样失败时抛出
        """
        try:
            if config.method == SampleMethod.RANDOM:
                return self.random_sample(table_name, config)
            elif config.method == SampleMethod.STRATIFIED:
                return self.stratified_sample(table_name, config)
            elif config.method == SampleMethod.TIME_RANGE:
                return self.time_range_sample(table_name, config)
            elif config.method == SampleMethod.SYSTEMATIC:
                return self.systematic_sample(table_name, config)
            elif config.method == SampleMethod.CLUSTER:
                return self.cluster_sample(table_name, config)
            elif config.method == SampleMethod.RESERVOIR:
                return self.reservoir_sample(table_name, config)
            elif config.method == SampleMethod.WEIGHTED:
                return self.weighted_sample(table_name, config)
            else:
                raise ValueError(f"Unsupported sampling method: {config.method}")
        except Exception as e:
            raise MetadataCrawlError(f"Failed to collect sample: {e}") from e

    def random_sample(
        self,
        table_name: str,
        config: SampleConfig,
    ) -> SampleResult:
        """随机采样

        Args:
            table_name: 表名
            config: 采样配置

        Returns:
            采样结果

        Raises:
            MetadataCrawlError: 当采样失败时抛出
        """
        try:
            total_count = self.data_source.get_row_count(table_name)
            sample_count = self._calculate_sample_count(total_count, config)

            if sample_count <= 0:
                return SampleResult(
                    data=pd.DataFrame(),
                    sample_size=0,
                    total_size=total_count,
                    method=SampleMethod.RANDOM,
                    config=config,
                )

            if isinstance(self.data_source.get_row_count, type(lambda: None)):
                pass

            if config.with_replacement:
                sample_count_query = sample_count
            else:
                sample_count_query = min(sample_count, total_count)

            df = self.data_source.read_data(
                table_name,
                columns=config.include_columns,
                limit=sample_count_query,
            )

            seed = self._get_seed(config)
            if seed is not None:
                np.random.seed(seed)

            sampled_df = df.sample(
                n=min(sample_count, len(df)),
                replace=config.with_replacement,
                random_state=seed,
            )

            sampled_df = self._select_columns(sampled_df, config)

            statistics = {
                "sampling_method": "random",
                "with_replacement": config.with_replacement,
                "expected_sample_count": sample_count,
                "actual_sample_count": len(sampled_df),
            }

            return SampleResult(
                data=sampled_df,
                sample_size=len(sampled_df),
                total_size=total_count,
                method=SampleMethod.RANDOM,
                config=config,
                statistics=statistics,
            )

        except Exception as e:
            raise MetadataCrawlError(f"Random sampling failed: {e}") from e

    def stratified_sample(
        self,
        table_name: str,
        config: SampleConfig,
    ) -> SampleResult:
        """分层采样

        Args:
            table_name: 表名
            config: 采样配置

        Returns:
            采样结果

        Raises:
            MetadataCrawlError: 当采样失败时抛出
        """
        if not config.stratify_column:
            raise ValueError("Stratified sampling requires stratify_column")

        try:
            df = self.data_source.read_data(
                table_name,
                columns=config.include_columns,
            )

            total_count = len(df)
            stratify_col = config.stratify_column

            if stratify_col not in df.columns:
                raise ValueError(f"Stratify column {stratify_col} not found in data")

            sample_count = self._calculate_sample_count(total_count, config)

            if sample_count <= 0:
                return SampleResult(
                    data=pd.DataFrame(),
                    sample_size=0,
                    total_size=total_count,
                    method=SampleMethod.STRATIFIED,
                    config=config,
                )

            seed = self._get_seed(config)
            groups = df.groupby(stratify_col)
            group_counts = groups.size()

            group_sample_sizes = {}
            for group_name, group_size in group_counts.items():
                proportion = group_size / total_count if total_count > 0 else 0
                group_sample_size = max(1, int(sample_count * proportion))
                group_sample_sizes[group_name] = min(group_sample_size, group_size)

            sampled_dfs = []
            for group_name, group_df in groups:
                n_samples = group_sample_sizes.get(group_name, 1)
                sampled_group = group_df.sample(
                    n=min(n_samples, len(group_df)),
                    replace=config.with_replacement,
                    random_state=seed,
                )
                sampled_dfs.append(sampled_group)

            sampled_df = pd.concat(sampled_dfs, ignore_index=True)
            sampled_df = self._select_columns(sampled_df, config)

            statistics = {
                "sampling_method": "stratified",
                "stratify_column": stratify_col,
                "num_strata": len(group_counts),
                "strata_distribution": group_counts.to_dict(),
                "strata_sample_sizes": group_sample_sizes,
                "with_replacement": config.with_replacement,
            }

            return SampleResult(
                data=sampled_df,
                sample_size=len(sampled_df),
                total_size=total_count,
                method=SampleMethod.STRATIFIED,
                config=config,
                statistics=statistics,
            )

        except Exception as e:
            raise MetadataCrawlError(f"Stratified sampling failed: {e}") from e

    def time_range_sample(
        self,
        table_name: str,
        config: SampleConfig,
    ) -> SampleResult:
        """时间范围采样

        Args:
            table_name: 表名
            config: 采样配置

        Returns:
            采样结果

        Raises:
            MetadataCrawlError: 当采样失败时抛出
        """
        if not config.time_column:
            raise ValueError("Time range sampling requires time_column")

        try:
            time_col = config.time_column
            filters: Dict[str, Any] = {}

            columns_to_read = config.include_columns
            if columns_to_read and time_col not in columns_to_read:
                columns_to_read = columns_to_read + [time_col]

            df = self.data_source.read_data(
                table_name,
                columns=columns_to_read,
            )

            if time_col not in df.columns:
                raise ValueError(f"Time column {time_col} not found in data")

            df[time_col] = pd.to_datetime(df[time_col], errors="coerce")
            df = df.dropna(subset=[time_col])

            if config.start_time:
                df = df[df[time_col] >= pd.Timestamp(config.start_time)]

            if config.end_time:
                df = df[df[time_col] <= pd.Timestamp(config.end_time)]

            total_count = len(df)
            sample_count = self._calculate_sample_count(total_count, config)

            if sample_count <= 0 or len(df) == 0:
                return SampleResult(
                    data=pd.DataFrame(),
                    sample_size=0,
                    total_size=total_count,
                    method=SampleMethod.TIME_RANGE,
                    config=config,
                )

            seed = self._get_seed(config)
            sampled_df = df.sample(
                n=min(sample_count, len(df)),
                replace=config.with_replacement,
                random_state=seed,
            )

            sampled_df = self._select_columns(sampled_df, config)

            statistics = {
                "sampling_method": "time_range",
                "time_column": time_col,
                "start_time": config.start_time.isoformat() if config.start_time else None,
                "end_time": config.end_time.isoformat() if config.end_time else None,
                "actual_time_range": {
                    "min": str(sampled_df[time_col].min()) if len(sampled_df) > 0 else None,
                    "max": str(sampled_df[time_col].max()) if len(sampled_df) > 0 else None,
                },
                "filtered_count": len(df),
            }

            return SampleResult(
                data=sampled_df,
                sample_size=len(sampled_df),
                total_size=total_count,
                method=SampleMethod.TIME_RANGE,
                config=config,
                statistics=statistics,
            )

        except Exception as e:
            raise MetadataCrawlError(f"Time range sampling failed: {e}") from e

    def systematic_sample(
        self,
        table_name: str,
        config: SampleConfig,
    ) -> SampleResult:
        """系统采样（等间隔采样）

        Args:
            table_name: 表名
            config: 采样配置

        Returns:
            采样结果

        Raises:
            MetadataCrawlError: 当采样失败时抛出
        """
        try:
            df = self.data_source.read_data(
                table_name,
                columns=config.include_columns,
            )

            total_count = len(df)
            sample_count = self._calculate_sample_count(total_count, config)

            if sample_count <= 0:
                return SampleResult(
                    data=pd.DataFrame(),
                    sample_size=0,
                    total_size=total_count,
                    method=SampleMethod.SYSTEMATIC,
                    config=config,
                )

            step = config.step or max(1, total_count // sample_count)

            seed = self._get_seed(config)
            if seed is not None:
                np.random.seed(seed)
            start = np.random.randint(0, step) if step > 0 else 0

            indices = list(range(start, total_count, step))
            sampled_df = df.iloc[indices]

            if len(sampled_df) > sample_count:
                sampled_df = sampled_df.head(sample_count)

            sampled_df = self._select_columns(sampled_df, config)

            statistics = {
                "sampling_method": "systematic",
                "step": step,
                "start_index": start,
                "num_samples": len(indices),
            }

            return SampleResult(
                data=sampled_df,
                sample_size=len(sampled_df),
                total_size=total_count,
                method=SampleMethod.SYSTEMATIC,
                config=config,
                statistics=statistics,
            )

        except Exception as e:
            raise MetadataCrawlError(f"Systematic sampling failed: {e}") from e

    def cluster_sample(
        self,
        table_name: str,
        config: SampleConfig,
    ) -> SampleResult:
        """聚类采样

        Args:
            table_name: 表名
            config: 采样配置

        Returns:
            采样结果

        Raises:
            MetadataCrawlError: 当采样失败时抛出
        """
        if not config.cluster_column:
            raise ValueError("Cluster sampling requires cluster_column")

        try:
            cluster_col = config.cluster_column
            cluster_count = config.cluster_count or 5

            columns_to_read = config.include_columns
            if columns_to_read and cluster_col not in columns_to_read:
                columns_to_read = columns_to_read + [cluster_col]

            df = self.data_source.read_data(
                table_name,
                columns=columns_to_read,
            )

            total_count = len(df)

            if cluster_col not in df.columns:
                raise ValueError(f"Cluster column {cluster_col} not found in data")

            clusters = df[cluster_col].unique()
            num_clusters = len(clusters)

            if num_clusters == 0:
                return SampleResult(
                    data=pd.DataFrame(),
                    sample_size=0,
                    total_size=total_count,
                    method=SampleMethod.CLUSTER,
                    config=config,
                )

            seed = self._get_seed(config)
            if seed is not None:
                np.random.seed(seed)

            selected_clusters = np.random.choice(
                clusters,
                size=min(cluster_count, num_clusters),
                replace=False,
            )

            sampled_df = df[df[cluster_col].isin(selected_clusters)]
            sampled_df = self._select_columns(sampled_df, config)

            statistics = {
                "sampling_method": "cluster",
                "cluster_column": cluster_col,
                "total_clusters": num_clusters,
                "selected_clusters": list(selected_clusters),
                "cluster_sizes": {
                    str(c): int((df[cluster_col] == c).sum()) for c in selected_clusters
                },
            }

            return SampleResult(
                data=sampled_df,
                sample_size=len(sampled_df),
                total_size=total_count,
                method=SampleMethod.CLUSTER,
                config=config,
                statistics=statistics,
            )

        except Exception as e:
            raise MetadataCrawlError(f"Cluster sampling failed: {e}") from e

    def reservoir_sample(
        self,
        table_name: str,
        config: SampleConfig,
    ) -> SampleResult:
        """蓄水池采样（适用于大数据流）

        Args:
            table_name: 表名
            config: 采样配置

        Returns:
            采样结果

        Raises:
            MetadataCrawlError: 当采样失败时抛出
        """
        try:
            if not isinstance(config.sample_size, int):
                raise ValueError("Reservoir sampling requires integer sample_size")

            reservoir_size = config.sample_size

            df = self.data_source.read_data(
                table_name,
                columns=config.include_columns,
            )

            total_count = len(df)

            if total_count <= reservoir_size:
                sampled_df = df.copy()
            else:
                seed = self._get_seed(config)
                if seed is not None:
                    np.random.seed(seed)

                reservoir = list(range(reservoir_size))

                for i in range(reservoir_size, total_count):
                    j = np.random.randint(0, i + 1)
                    if j < reservoir_size:
                        reservoir[j] = i

                sampled_df = df.iloc[reservoir]

            sampled_df = self._select_columns(sampled_df, config)

            statistics = {
                "sampling_method": "reservoir",
                "reservoir_size": reservoir_size,
                "stream_size": total_count,
            }

            return SampleResult(
                data=sampled_df,
                sample_size=len(sampled_df),
                total_size=total_count,
                method=SampleMethod.RESERVOIR,
                config=config,
                statistics=statistics,
            )

        except Exception as e:
            raise MetadataCrawlError(f"Reservoir sampling failed: {e}") from e

    def weighted_sample(
        self,
        table_name: str,
        config: SampleConfig,
    ) -> SampleResult:
        """加权采样

        Args:
            table_name: 表名
            config: 采样配置

        Returns:
            采样结果

        Raises:
            MetadataCrawlError: 当采样失败时抛出
        """
        if not config.weight_column:
            raise ValueError("Weighted sampling requires weight_column")

        try:
            weight_col = config.weight_column

            columns_to_read = config.include_columns
            if columns_to_read and weight_col not in columns_to_read:
                columns_to_read = columns_to_read + [weight_col]

            df = self.data_source.read_data(
                table_name,
                columns=columns_to_read,
            )

            total_count = len(df)
            sample_count = self._calculate_sample_count(total_count, config)

            if sample_count <= 0:
                return SampleResult(
                    data=pd.DataFrame(),
                    sample_size=0,
                    total_size=total_count,
                    method=SampleMethod.WEIGHTED,
                    config=config,
                )

            if weight_col not in df.columns:
                raise ValueError(f"Weight column {weight_col} not found in data")

            weights = pd.to_numeric(df[weight_col], errors="coerce")
            weights = weights.fillna(0)

            if (weights < 0).any():
                raise ValueError("Weights must be non-negative")

            weight_sum = weights.sum()
            if weight_sum == 0:
                raise ValueError("Sum of weights is zero")

            weights = weights / weight_sum

            seed = self._get_seed(config)
            sampled_indices = np.random.choice(
                df.index,
                size=min(sample_count, len(df)),
                replace=config.with_replacement,
                p=weights,
            )

            sampled_df = df.loc[sampled_indices]
            sampled_df = self._select_columns(sampled_df, config)

            statistics = {
                "sampling_method": "weighted",
                "weight_column": weight_col,
                "weight_sum": float(weight_sum),
                "weight_mean": float(weights.mean()),
                "weight_std": float(weights.std()),
                "with_replacement": config.with_replacement,
            }

            return SampleResult(
                data=sampled_df,
                sample_size=len(sampled_df),
                total_size=total_count,
                method=SampleMethod.WEIGHTED,
                config=config,
                statistics=statistics,
            )

        except Exception as e:
            raise MetadataCrawlError(f"Weighted sampling failed: {e}") from e

    def collect_batch_samples(
        self,
        table_names: List[str],
        config: SampleConfig,
    ) -> Dict[str, SampleResult]:
        """批量采集多个表的样例数据

        Args:
            table_names: 表名列表
            config: 采样配置

        Returns:
            表名到采样结果的映射字典

        Raises:
            MetadataCrawlError: 当采样失败时抛出
        """
        results: Dict[str, SampleResult] = {}
        for table_name in table_names:
            try:
                results[table_name] = self.collect_sample(table_name, config)
            except Exception as e:
                raise MetadataCrawlError(
                    f"Failed to collect sample for table {table_name}: {e}"
                ) from e
        return results

    def save_sample(
        self,
        sample_result: SampleResult,
        output_path: str,
        format: str = "csv",
        **kwargs: Any,
    ) -> None:
        """保存采样结果到文件

        Args:
            sample_result: 采样结果
            output_path: 输出文件路径
            format: 文件格式（csv, parquet, json, excel）
            **kwargs: 额外的保存参数

        Raises:
            MetadataCrawlError: 当保存失败时抛出
        """
        try:
            if format == "csv":
                sample_result.data.to_csv(output_path, index=False, **kwargs)
            elif format == "parquet":
                sample_result.data.to_parquet(output_path, index=False, **kwargs)
            elif format == "json":
                sample_result.data.to_json(output_path, orient="records", **kwargs)
            elif format == "excel":
                sample_result.data.to_excel(output_path, index=False, **kwargs)
            else:
                raise ValueError(f"Unsupported format: {format}")
        except Exception as e:
            raise MetadataCrawlError(f"Failed to save sample: {e}") from e

    def analyze_sample_bias(
        self,
        sample_result: SampleResult,
        columns: Optional[List[str]] = None,
    ) -> Dict[str, Any]:
        """分析采样偏差

        Args:
            sample_result: 采样结果
            columns: 要分析的列名列表，None表示所有数值列

        Returns:
            偏差分析结果字典
        """
        df = sample_result.data

        if columns is None:
            columns = [
                col for col in df.columns if pd.api.types.is_numeric_dtype(df[col])
            ]

        analysis: Dict[str, Any] = {
            "sample_size": sample_result.sample_size,
            "total_size": sample_result.total_size,
            "sampling_rate": sample_result.sampling_rate,
            "columns": {},
        }

        for col in columns:
            if col not in df.columns:
                continue

            series = df[col].dropna()
            if len(series) == 0 or not pd.api.types.is_numeric_dtype(series):
                continue

            analysis["columns"][col] = {
                "mean": float(series.mean()),
                "median": float(series.median()),
                "std": float(series.std()),
                "min": float(series.min()),
                "max": float(series.max()),
                "skewness": float(series.skew()) if len(series) > 2 else None,
                "kurtosis": float(series.kurtosis()) if len(series) > 3 else None,
            }

        return analysis
