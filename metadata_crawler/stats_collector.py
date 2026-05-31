"""
统计信息采集器模块
采集数据的统计信息：行数、空值率、基数、直方图、分位数、相关性
"""

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
from scipy import stats

from .data_source import DataSource
from session307.exceptions import MetadataCrawlError


@dataclass
class Histogram:
    """直方图数据类

    Attributes:
        bins: 分箱边界
        counts: 每个分箱的计数
        bin_width: 分箱宽度
        min_value: 最小值
        max_value: 最大值
    """

    bins: List[float]
    counts: List[int]
    bin_width: float
    min_value: float
    max_value: float

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            直方图的字典表示
        """
        return {
            "bins": self.bins,
            "counts": self.counts,
            "bin_width": self.bin_width,
            "min_value": self.min_value,
            "max_value": self.max_value,
        }


@dataclass
class QuantileStatistics:
    """分位数统计数据类

    Attributes:
        min: 最小值
        q1: 第一四分位数（25%）
        median: 中位数（50%）
        q3: 第三四分位数（75%）
        max: 最大值
        percentiles: 自定义百分位数字典
    """

    min: float
    q1: float
    median: float
    q3: float
    max: float
    percentiles: Dict[int, float] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            分位数统计的字典表示
        """
        return {
            "min": self.min,
            "q1": self.q1,
            "median": self.median,
            "q3": self.q3,
            "max": self.max,
            "percentiles": self.percentiles,
        }


@dataclass
class CorrelationResult:
    """相关性分析结果数据类

    Attributes:
        column1: 第一列名称
        column2: 第二列名称
        pearson_correlation: 皮尔逊相关系数
        spearman_correlation: 斯皮尔曼相关系数
        p_value: p值
        is_significant: 是否统计显著
    """

    column1: str
    column2: str
    pearson_correlation: Optional[float] = None
    spearman_correlation: Optional[float] = None
    p_value: Optional[float] = None
    is_significant: bool = False

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            相关性分析结果的字典表示
        """
        return {
            "column1": self.column1,
            "column2": self.column2,
            "pearson_correlation": self.pearson_correlation,
            "spearman_correlation": self.spearman_correlation,
            "p_value": self.p_value,
            "is_significant": self.is_significant,
        }


@dataclass
class ColumnStatistics:
    """列统计信息数据类

    Attributes:
        column_name: 列名
        data_type: 数据类型
        row_count: 总行数
        non_null_count: 非空行数
        null_count: 空值行数
        null_rate: 空值率
        distinct_count: 唯一值数量（基数）
        distinct_rate: 唯一值率
        min_value: 最小值
        max_value: 最大值
        mean: 平均值
        median: 中位数
        std_dev: 标准差
        variance: 方差
        sum: 总和
        quantiles: 分位数统计
        histogram: 直方图
        top_values: 出现频率最高的值
        mode: 众数
        skewness: 偏度
        kurtosis: 峰度
    """

    column_name: str
    data_type: str
    row_count: int
    non_null_count: int
    null_count: int
    null_rate: float
    distinct_count: int
    distinct_rate: float
    min_value: Optional[Any] = None
    max_value: Optional[Any] = None
    mean: Optional[float] = None
    median: Optional[float] = None
    std_dev: Optional[float] = None
    variance: Optional[float] = None
    sum: Optional[float] = None
    quantiles: Optional[QuantileStatistics] = None
    histogram: Optional[Histogram] = None
    top_values: Optional[List[Tuple[Any, int]]] = None
    mode: Optional[Any] = None
    skewness: Optional[float] = None
    kurtosis: Optional[float] = None

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            列统计信息的字典表示
        """
        return {
            "column_name": self.column_name,
            "data_type": self.data_type,
            "row_count": self.row_count,
            "non_null_count": self.non_null_count,
            "null_count": self.null_count,
            "null_rate": self.null_rate,
            "distinct_count": self.distinct_count,
            "distinct_rate": self.distinct_rate,
            "min_value": self.min_value,
            "max_value": self.max_value,
            "mean": self.mean,
            "median": self.median,
            "std_dev": self.std_dev,
            "variance": self.variance,
            "sum": self.sum,
            "quantiles": self.quantiles.to_dict() if self.quantiles else None,
            "histogram": self.histogram.to_dict() if self.histogram else None,
            "top_values": [(str(v), c) for v, c in self.top_values] if self.top_values else None,
            "mode": str(self.mode) if self.mode is not None else None,
            "skewness": self.skewness,
            "kurtosis": self.kurtosis,
        }


@dataclass
class TableStatistics:
    """表统计信息数据类

    Attributes:
        table_name: 表名
        row_count: 总行数
        column_count: 列数
        total_size_bytes: 总大小（字节）
        columns: 列统计信息列表
        correlations: 相关性分析结果列表
        sample_size: 用于统计的样本大小
        collected_at: 采集时间
    """

    table_name: str
    row_count: int
    column_count: int
    total_size_bytes: Optional[int] = None
    columns: List[ColumnStatistics] = field(default_factory=list)
    correlations: List[CorrelationResult] = field(default_factory=list)
    sample_size: Optional[int] = None
    collected_at: Optional[str] = None

    def get_column_stats(self, column_name: str) -> Optional[ColumnStatistics]:
        """根据列名获取列统计信息

        Args:
            column_name: 列名

        Returns:
            列统计信息，未找到返回None
        """
        for col in self.columns:
            if col.column_name == column_name:
                return col
        return None

    def to_dict(self) -> Dict[str, Any]:
        """转换为字典

        Returns:
            表统计信息的字典表示
        """
        return {
            "table_name": self.table_name,
            "row_count": self.row_count,
            "column_count": self.column_count,
            "total_size_bytes": self.total_size_bytes,
            "columns": [col.to_dict() for col in self.columns],
            "correlations": [corr.to_dict() for corr in self.correlations],
            "sample_size": self.sample_size,
            "collected_at": self.collected_at,
        }


class StatsCollector:
    """统计信息采集器

    采集数据的统计信息，包括基本统计量、直方图、分位数、相关性等
    """

    def __init__(
        self,
        data_source: DataSource,
        sample_size: Optional[int] = None,
        histogram_bins: int = 20,
        significance_level: float = 0.05,
    ):
        """初始化统计信息采集器

        Args:
            data_source: 数据源
            sample_size: 采样大小，None表示使用全部数据
            histogram_bins: 直方图分箱数
            significance_level: 相关性分析显著性水平
        """
        self.data_source = data_source
        self.sample_size = sample_size
        self.histogram_bins = histogram_bins
        self.significance_level = significance_level

    def _get_sample_data(
        self,
        table_name: str,
        columns: Optional[List[str]] = None,
        sample_size: Optional[int] = None,
    ) -> pd.DataFrame:
        """获取样本数据

        Args:
            table_name: 表名
            columns: 列名列表，None表示所有列
            sample_size: 采样大小，None使用默认值

        Returns:
            样本数据DataFrame

        Raises:
            MetadataCrawlError: 当获取数据失败时抛出
        """
        try:
            size = sample_size or self.sample_size
            df = self.data_source.read_data(table_name, columns=columns, limit=size)
            return df
        except Exception as e:
            raise MetadataCrawlError(f"Failed to get sample data: {e}") from e

    def _is_numeric_column(self, series: pd.Series) -> bool:
        """判断列是否为数值类型

        Args:
            series: pandas Series

        Returns:
            是数值类型返回True，否则返回False
        """
        return pd.api.types.is_numeric_dtype(series)

    def _is_datetime_column(self, series: pd.Series) -> bool:
        """判断列是否为日期时间类型

        Args:
            series: pandas Series

        Returns:
            是日期时间类型返回True，否则返回False
        """
        return pd.api.types.is_datetime64_any_dtype(series)

    def collect_column_statistics(
        self,
        table_name: str,
        column_name: str,
        sample_size: Optional[int] = None,
        include_histogram: bool = True,
        include_quantiles: bool = True,
        include_top_values: bool = True,
        top_values_count: int = 10,
    ) -> ColumnStatistics:
        """采集单列统计信息

        Args:
            table_name: 表名
            column_name: 列名
            sample_size: 采样大小
            include_histogram: 是否包含直方图
            include_quantiles: 是否包含分位数统计
            include_top_values: 是否包含高频值
            top_values_count: 高频值数量

        Returns:
            列统计信息

        Raises:
            MetadataCrawlError: 当采集失败时抛出
        """
        try:
            df = self._get_sample_data(table_name, columns=[column_name], sample_size=sample_size)
            series = df[column_name]
            row_count = len(series)
            non_null_count = series.notna().sum()
            null_count = row_count - non_null_count
            null_rate = null_count / row_count if row_count > 0 else 0.0

            distinct_count = series.nunique(dropna=True)
            distinct_rate = distinct_count / non_null_count if non_null_count > 0 else 0.0

            col_stats = ColumnStatistics(
                column_name=column_name,
                data_type=str(series.dtype),
                row_count=row_count,
                non_null_count=non_null_count,
                null_count=null_count,
                null_rate=null_rate,
                distinct_count=distinct_count,
                distinct_rate=distinct_rate,
            )

            non_null_series = series.dropna()

            if len(non_null_series) == 0:
                return col_stats

            is_numeric = self._is_numeric_column(series)
            is_datetime = self._is_datetime_column(series)

            if is_numeric:
                col_stats.min_value = float(non_null_series.min())
                col_stats.max_value = float(non_null_series.max())
                col_stats.mean = float(non_null_series.mean())
                col_stats.median = float(non_null_series.median())
                col_stats.std_dev = float(non_null_series.std())
                col_stats.variance = float(non_null_series.var())
                col_stats.sum = float(non_null_series.sum())

                try:
                    col_stats.skewness = float(non_null_series.skew())
                except Exception:
                    pass

                try:
                    col_stats.kurtosis = float(non_null_series.kurtosis())
                except Exception:
                    pass

                if include_quantiles:
                    col_stats.quantiles = self._calculate_quantiles(non_null_series)

                if include_histogram:
                    col_stats.histogram = self._calculate_histogram(non_null_series)

            elif is_datetime:
                col_stats.min_value = str(non_null_series.min())
                col_stats.max_value = str(non_null_series.max())

                try:
                    dt_series = non_null_series.astype("int64") // 10**9
                    if include_histogram:
                        col_stats.histogram = self._calculate_histogram(dt_series)
                except Exception:
                    pass

            else:
                try:
                    col_stats.min_value = str(non_null_series.min())
                    col_stats.max_value = str(non_null_series.max())
                except Exception:
                    pass

            if include_top_values:
                col_stats.top_values = self._get_top_values(
                    non_null_series, top_values_count
                )
                if col_stats.top_values:
                    col_stats.mode = col_stats.top_values[0][0]

            return col_stats

        except Exception as e:
            raise MetadataCrawlError(
                f"Failed to collect statistics for column {column_name}: {e}"
            ) from e

    def collect_table_statistics(
        self,
        table_name: str,
        columns: Optional[List[str]] = None,
        sample_size: Optional[int] = None,
        include_correlations: bool = True,
        correlation_columns: Optional[List[str]] = None,
        include_histogram: bool = True,
        include_quantiles: bool = True,
        include_top_values: bool = True,
    ) -> TableStatistics:
        """采集表的统计信息

        Args:
            table_name: 表名
            columns: 列名列表，None表示所有列
            sample_size: 采样大小
            include_correlations: 是否包含相关性分析
            correlation_columns: 用于相关性分析的列名列表
            include_histogram: 是否包含直方图
            include_quantiles: 是否包含分位数统计
            include_top_values: 是否包含高频值

        Returns:
            表统计信息

        Raises:
            MetadataCrawlError: 当采集失败时抛出
        """
        try:
            from datetime import datetime

            df = self._get_sample_data(table_name, columns=columns, sample_size=sample_size)
            row_count = len(df)
            column_count = len(df.columns)

            actual_sample_size = sample_size or self.sample_size or row_count

            col_stats_list: List[ColumnStatistics] = []
            for col in df.columns:
                col_stats = self.collect_column_statistics(
                    table_name=table_name,
                    column_name=col,
                    sample_size=actual_sample_size,
                    include_histogram=include_histogram,
                    include_quantiles=include_quantiles,
                    include_top_values=include_top_values,
                )
                col_stats_list.append(col_stats)

            table_stats = TableStatistics(
                table_name=table_name,
                row_count=row_count,
                column_count=column_count,
                columns=col_stats_list,
                sample_size=actual_sample_size,
                collected_at=datetime.now().isoformat(),
            )

            if include_correlations:
                corr_cols = correlation_columns or [
                    col for col in df.columns if self._is_numeric_column(df[col])
                ]
                if len(corr_cols) >= 2:
                    table_stats.correlations = self._calculate_correlations(df[corr_cols])

            return table_stats

        except Exception as e:
            raise MetadataCrawlError(
                f"Failed to collect statistics for table {table_name}: {e}"
            ) from e

    def _calculate_quantiles(self, series: pd.Series) -> QuantileStatistics:
        """计算分位数统计

        Args:
            series: 非空数值Series

        Returns:
            分位数统计对象
        """
        try:
            q1 = float(series.quantile(0.25))
            median = float(series.quantile(0.5))
            q3 = float(series.quantile(0.75))
            min_val = float(series.min())
            max_val = float(series.max())

            percentiles: Dict[int, float] = {}
            for p in [5, 10, 90, 95, 99]:
                percentiles[p] = float(series.quantile(p / 100))

            return QuantileStatistics(
                min=min_val,
                q1=q1,
                median=median,
                q3=q3,
                max=max_val,
                percentiles=percentiles,
            )
        except Exception as e:
            raise MetadataCrawlError(f"Failed to calculate quantiles: {e}") from e

    def _calculate_histogram(self, series: pd.Series) -> Optional[Histogram]:
        """计算直方图

        Args:
            series: 数值Series

        Returns:
            直方图对象，计算失败返回None
        """
        try:
            values = series.dropna()
            if len(values) == 0:
                return None

            counts, bin_edges = np.histogram(values, bins=self.histogram_bins)
            bin_width = float(bin_edges[1] - bin_edges[0]) if len(bin_edges) > 1 else 0.0

            return Histogram(
                bins=[float(x) for x in bin_edges],
                counts=[int(x) for x in counts],
                bin_width=bin_width,
                min_value=float(values.min()),
                max_value=float(values.max()),
            )
        except Exception:
            return None

    def _get_top_values(
        self, series: pd.Series, top_count: int
    ) -> Optional[List[Tuple[Any, int]]]:
        """获取出现频率最高的值

        Args:
            series: 数据Series
            top_count: 返回的数量

        Returns:
            (值, 出现次数)元组列表
        """
        try:
            value_counts = series.value_counts().head(top_count)
            return [(value, int(count)) for value, count in value_counts.items()]
        except Exception:
            return None

    def _calculate_correlations(
        self, df: pd.DataFrame, method: str = "all"
    ) -> List[CorrelationResult]:
        """计算列之间的相关性

        Args:
            df: 数值列的DataFrame
            method: 相关性计算方法（pearson, spearman, all）

        Returns:
            相关性分析结果列表
        """
        correlations: List[CorrelationResult] = []
        columns = df.columns.tolist()

        for i in range(len(columns)):
            for j in range(i + 1, len(columns)):
                col1, col2 = columns[i], columns[j]
                series1 = df[col1].dropna()
                series2 = df[col2].dropna()

                common_index = series1.index.intersection(series2.index)
                if len(common_index) < 3:
                    continue

                x = series1.loc[common_index]
                y = series2.loc[common_index]

                corr_result = CorrelationResult(column1=col1, column2=col2)

                if method in ("pearson", "all"):
                    try:
                        pearson_corr, p_value = stats.pearsonr(x, y)
                        corr_result.pearson_correlation = float(pearson_corr)
                        corr_result.p_value = float(p_value)
                        corr_result.is_significant = p_value < self.significance_level
                    except Exception:
                        pass

                if method in ("spearman", "all"):
                    try:
                        spearman_corr, _ = stats.spearmanr(x, y)
                        corr_result.spearman_correlation = float(spearman_corr)
                    except Exception:
                        pass

                correlations.append(corr_result)

        return correlations

    def detect_outliers(
        self,
        table_name: str,
        column_name: str,
        method: str = "iqr",
        threshold: float = 1.5,
        sample_size: Optional[int] = None,
    ) -> Tuple[List[Any], Dict[str, Any]]:
        """检测异常值

        Args:
            table_name: 表名
            column_name: 列名
            method: 检测方法（iqr, zscore, modified_zscore）
            threshold: 异常阈值
            sample_size: 采样大小

        Returns:
            (异常值列表, 统计信息字典)

        Raises:
            MetadataCrawlError: 当检测失败时抛出
        """
        try:
            df = self._get_sample_data(table_name, columns=[column_name], sample_size=sample_size)
            series = df[column_name].dropna()

            if not self._is_numeric_column(series):
                raise ValueError(f"Column {column_name} is not numeric")

            stats_info: Dict[str, Any] = {}
            outliers: List[Any] = []

            if method == "iqr":
                q1 = series.quantile(0.25)
                q3 = series.quantile(0.75)
                iqr = q3 - q1
                lower_bound = q1 - threshold * iqr
                upper_bound = q3 + threshold * iqr

                stats_info = {
                    "method": "iqr",
                    "q1": float(q1),
                    "q3": float(q3),
                    "iqr": float(iqr),
                    "lower_bound": float(lower_bound),
                    "upper_bound": float(upper_bound),
                }

                outliers = series[
                    (series < lower_bound) | (series > upper_bound)
                ].tolist()

            elif method == "zscore":
                mean = series.mean()
                std = series.std()

                if std == 0:
                    return [], {"method": "zscore", "error": "Zero standard deviation"}

                z_scores = (series - mean) / std

                stats_info = {
                    "method": "zscore",
                    "mean": float(mean),
                    "std": float(std),
                    "threshold": threshold,
                }

                outliers = series[z_scores.abs() > threshold].tolist()

            elif method == "modified_zscore":
                median = series.median()
                mad = (series - median).abs().median()

                if mad == 0:
                    return [], {"method": "modified_zscore", "error": "Zero MAD"}

                modified_z = 0.6745 * (series - median) / mad

                stats_info = {
                    "method": "modified_zscore",
                    "median": float(median),
                    "mad": float(mad),
                    "threshold": threshold,
                }

                outliers = series[modified_z.abs() > threshold].tolist()

            else:
                raise ValueError(f"Unknown outlier detection method: {method}")

            stats_info["outlier_count"] = len(outliers)
            stats_info["outlier_rate"] = len(outliers) / len(series) if len(series) > 0 else 0

            return outliers, stats_info

        except Exception as e:
            raise MetadataCrawlError(f"Failed to detect outliers: {e}") from e

    def summarize_data_quality(
        self,
        table_name: str,
        columns: Optional[List[str]] = None,
        sample_size: Optional[int] = None,
    ) -> Dict[str, Any]:
        """生成数据质量摘要

        Args:
            table_name: 表名
            columns: 列名列表
            sample_size: 采样大小

        Returns:
            数据质量摘要字典
        """
        try:
            table_stats = self.collect_table_statistics(
                table_name=table_name,
                columns=columns,
                sample_size=sample_size,
                include_correlations=False,
                include_histogram=False,
                include_quantiles=False,
                include_top_values=False,
            )

            quality_summary: Dict[str, Any] = {
                "table_name": table_name,
                "row_count": table_stats.row_count,
                "column_count": table_stats.column_count,
                "columns": {},
                "overall": {},
            }

            total_null_rate = 0.0
            total_distinct_rate = 0.0
            high_null_columns: List[str] = []
            low_cardinality_columns: List[str] = []

            for col_stats in table_stats.columns:
                quality_summary["columns"][col_stats.column_name] = {
                    "null_rate": col_stats.null_rate,
                    "distinct_rate": col_stats.distinct_rate,
                    "data_type": col_stats.data_type,
                }

                total_null_rate += col_stats.null_rate
                total_distinct_rate += col_stats.distinct_rate

                if col_stats.null_rate > 0.3:
                    high_null_columns.append(col_stats.column_name)

                if col_stats.distinct_rate < 0.01 and col_stats.null_rate < 1.0:
                    low_cardinality_columns.append(col_stats.column_name)

            quality_summary["overall"] = {
                "avg_null_rate": total_null_rate / table_stats.column_count
                if table_stats.column_count > 0
                else 0,
                "avg_distinct_rate": total_distinct_rate / table_stats.column_count
                if table_stats.column_count > 0
                else 0,
                "high_null_columns": high_null_columns,
                "low_cardinality_columns": low_cardinality_columns,
            }

            return quality_summary

        except Exception as e:
            raise MetadataCrawlError(f"Failed to summarize data quality: {e}") from e
