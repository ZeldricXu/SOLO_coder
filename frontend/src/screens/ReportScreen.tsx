import React, { useEffect, useState, useCallback } from 'react';
import {
  View,
  Text,
  ScrollView,
  StyleSheet,
  ActivityIndicator,
  TouchableOpacity,
  Alert,
  Dimensions,
  Picker,
  RefreshControl,
} from 'react-native';
import { LineChart, BarChart, PieChart } from 'react-native-chart-kit';
import Icon from 'react-native-vector-icons/MaterialIcons';
import { appApi, statisticsApi, reportApi, feedbackApi } from '../services/api';
import type { App, SummaryStats, ChartData, FeedbackStats, AsyncStatsResponse } from '../types';

const screenWidth = Dimensions.get('window').width;

const chartConfig = {
  backgroundGradientFrom: '#FFF',
  backgroundGradientTo: '#FFF',
  decimalPlaces: 0,
  color: (opacity = 1) => `rgba(25, 118, 210, ${opacity})`,
  labelColor: (opacity = 1) => `rgba(66, 66, 66, ${opacity})`,
  style: {
    borderRadius: 16,
  },
  propsForDots: {
    r: '4',
    strokeWidth: '2',
    stroke: '#1976D2',
  },
};

const chartConfig2 = {
  ...chartConfig,
  color: (opacity = 1) => `rgba(76, 175, 80, ${opacity})`,
  propsForDots: {
    r: '4',
    strokeWidth: '2',
    stroke: '#4CAF50',
  },
};

type LoadingState = 'idle' | 'loading' | 'calculating' | 'error';

export default function ReportScreen() {
  const [apps, setApps] = useState<App[]>([]);
  const [selectedAppId, setSelectedAppId] = useState<string>('');
  const [summaryLoadingState, setSummaryLoadingState] = useState<LoadingState>('idle');
  const [chartLoadingState, setChartLoadingState] = useState<LoadingState>('idle');
  const [summary, setSummary] = useState<SummaryStats | null>(null);
  const [chartData, setChartData] = useState<ChartData | null>(null);
  const [feedbackStats, setFeedbackStats] = useState<FeedbackStats | null>(null);
  const [reportType, setReportType] = useState('week');
  const [generatingDemo, setGeneratingDemo] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [calculatingMessage, setCalculatingMessage] = useState('');

  useEffect(() => {
    loadApps();
  }, []);

  const loadApps = async () => {
    try {
      const response = await appApi.getApps();
      setApps(response.data.data);
      if (response.data.data.length > 0) {
        setSelectedAppId(response.data.data[0].appId);
      }
    } catch (error) {
      console.error('Failed to load apps:', error);
    }
  };

  useEffect(() => {
    if (selectedAppId) {
      loadReportData();
    }
  }, [selectedAppId, reportType]);

  const loadSummaryData = useCallback(async (appId: string): Promise<SummaryStats | null> => {
    setSummaryLoadingState('loading');
    try {
      const response = await statisticsApi.getSummary(appId);
      const data = response.data.data;

      if (data.cacheHit && data.data) {
        setSummaryLoadingState('idle');
        return data.data as SummaryStats;
      }

      if (data.status === 'calculating' || data.status === 'submitted') {
        setSummaryLoadingState('calculating');
        setCalculatingMessage('统计摘要正在计算中...');

        const waitResponse = await statisticsApi.waitForSummary(appId, 15);
        if (waitResponse.data.data.ready && waitResponse.data.data.data) {
          setSummaryLoadingState('idle');
          return waitResponse.data.data.data;
        }
      }

      setSummaryLoadingState('idle');
      return data.data as SummaryStats;
    } catch (error) {
      console.error('Failed to load summary:', error);
      setSummaryLoadingState('error');
      return null;
    }
  }, []);

  const loadChartData = useCallback(async (appId: string, startDate: string, endDate: string): Promise<ChartData | null> => {
    setChartLoadingState('loading');
    try {
      const response = await statisticsApi.getChartData({
        appId,
        startDate,
        endDate,
      });
      const data = response.data.data;

      if (data.cacheHit && data.data) {
        setChartLoadingState('idle');
        return data.data as ChartData;
      }

      if (data.status === 'calculating' || data.status === 'submitted') {
        setChartLoadingState('calculating');
        setCalculatingMessage('图表数据正在计算中...');

        const waitResponse = await statisticsApi.waitForChart(appId, startDate, endDate, 15);
        if (waitResponse.data.data.ready && waitResponse.data.data.data) {
          setChartLoadingState('idle');
          return waitResponse.data.data.data;
        }
      }

      setChartLoadingState('idle');
      return data.data as ChartData;
    } catch (error) {
      console.error('Failed to load chart:', error);
      setChartLoadingState('error');
      return null;
    }
  }, []);

  const loadReportData = async () => {
    try {
      const endDate = new Date();
      const startDate = new Date();

      switch (reportType) {
        case 'week':
          startDate.setDate(endDate.getDate() - 6);
          break;
        case 'month':
          startDate.setDate(endDate.getDate() - 29);
          break;
        default:
          startDate.setDate(endDate.getDate() - 6);
      }

      const formatDate = (date: Date) => date.toISOString().split('T')[0];

      const [summaryResult, chartResult, feedbackRes] = await Promise.all([
        loadSummaryData(selectedAppId),
        loadChartData(selectedAppId, formatDate(startDate), formatDate(endDate)),
        feedbackApi.getStats(selectedAppId),
      ]);

      if (summaryResult) {
        setSummary(summaryResult);
      }
      if (chartResult) {
        setChartData(chartResult);
      }
      setFeedbackStats(feedbackRes.data.data);
    } catch (error) {
      console.error('Failed to load report:', error);
    }
  };

  const handleRefresh = async () => {
    setRefreshing(true);
    try {
      await statisticsApi.forceRefresh(selectedAppId);
      await loadReportData();
    } catch (error) {
      console.error('Failed to refresh:', error);
    } finally {
      setRefreshing(false);
    }
  };

  const handleGenerateDemo = async () => {
    if (!selectedAppId) {
      Alert.alert('提示', '请先选择应用');
      return;
    }

    try {
      setGeneratingDemo(true);
      await statisticsApi.generateDemo(selectedAppId, 30);
      Alert.alert('成功', '演示数据已生成，统计缓存已更新');
      await loadReportData();
    } catch (error: any) {
      Alert.alert('生成失败', error.message);
    } finally {
      setGeneratingDemo(false);
    }
  };

  const renderStatusIndicator = (state: LoadingState, message?: string) => {
    if (state === 'loading') {
      return (
        <View style={styles.statusIndicator}>
          <ActivityIndicator size="small" color="#1976D2" />
          <Text style={styles.statusText}>加载中...</Text>
        </View>
      );
    }
    if (state === 'calculating') {
      return (
        <View style={styles.calculatingIndicator}>
          <ActivityIndicator size="small" color="#4CAF50" />
          <Text style={styles.calculatingText}>{message || '正在计算统计数据...'}</Text>
        </View>
      );
    }
    if (state === 'error') {
      return (
        <View style={styles.errorIndicator}>
          <Icon name="error-outline" size={20} color="#F44336" />
          <Text style={styles.errorText}>加载失败，请重试</Text>
        </View>
      );
    }
    return null;
  };

  const renderSummaryCards = () => {
    if (summaryLoadingState !== 'idle' || !summary) {
      return (
        <View style={styles.summaryContainer}>
          {[1, 2, 3].map((i) => (
            <View key={i} style={[styles.summaryCard, styles.skeletonCard]}>
              <ActivityIndicator size="small" color="#CCC" />
            </View>
          ))}
        </View>
      );
    }

    const cards = [
      {
        icon: 'cloud-download',
        label: '总下载量',
        value: summary.totalDownloads?.toLocaleString() || '0',
        color: '#1976D2',
        bgColor: '#E3F2FD',
      },
      {
        icon: 'people',
        label: '总活跃用户',
        value: summary.totalActiveUsers?.toLocaleString() || '0',
        color: '#4CAF50',
        bgColor: '#E8F5E9',
      },
      {
        icon: 'star',
        label: '平均评分',
        value: summary.avgRating?.toFixed(1) || '0.0',
        color: '#FFC107',
        bgColor: '#FFF8E1',
      },
    ];

    return (
      <View style={styles.summaryContainer}>
        {cards.map((card, index) => (
          <View key={index} style={[styles.summaryCard, { backgroundColor: card.bgColor }]}>
            <Icon name={card.icon} size={28} color={card.color} />
            <Text style={[styles.summaryValue, { color: card.color }]}>{card.value}</Text>
            <Text style={styles.summaryLabel}>{card.label}</Text>
          </View>
        ))}
      </View>
    );
  };

  const renderDownloadChart = () => {
    if (chartLoadingState !== 'idle') {
      return (
        <View style={styles.chartCard}>
          <View style={styles.chartHeader}>
            <Icon name="cloud-download" size={20} color="#1976D2" />
            <Text style={styles.chartTitle}>下载量趋势</Text>
          </View>
          {renderStatusIndicator(chartLoadingState, calculatingMessage)}
        </View>
      );
    }

    if (!chartData || !chartData.labels || chartData.labels.length === 0) {
      return (
        <View style={styles.chartCard}>
          <View style={styles.chartHeader}>
            <Icon name="cloud-download" size={20} color="#1976D2" />
            <Text style={styles.chartTitle}>下载量趋势</Text>
          </View>
          <View style={styles.emptyChart}>
            <Icon name="insert-chart" size={48} color="#CCC" />
            <Text style={styles.emptyText}>暂无下载数据</Text>
          </View>
        </View>
      );
    }

    return (
      <View style={styles.chartCard}>
        <View style={styles.chartHeader}>
          <Icon name="cloud-download" size={20} color="#1976D2" />
          <Text style={styles.chartTitle}>下载量趋势</Text>
          {chartData.calculatedAt && (
            <Text style={styles.chartMeta}>更新于: {new Date(chartData.calculatedAt).toLocaleString()}</Text>
          )}
        </View>
        <LineChart
          data={{
            labels: chartData.labels.map(l => l.split('-').slice(1).join('/')),
            datasets: [
              {
                data: chartData.downloads,
              },
            ],
          }}
          width={screenWidth - 48}
          height={200}
          chartConfig={chartConfig}
          bezier
          style={styles.chartStyle}
        />
      </View>
    );
  };

  const renderActiveUsersChart = () => {
    if (chartLoadingState !== 'idle') {
      return (
        <View style={styles.chartCard}>
          <View style={styles.chartHeader}>
            <Icon name="people" size={20} color="#4CAF50" />
            <Text style={styles.chartTitle}>活跃用户趋势</Text>
          </View>
          {renderStatusIndicator(chartLoadingState)}
        </View>
      );
    }

    if (!chartData || !chartData.labels || chartData.labels.length === 0) {
      return (
        <View style={styles.chartCard}>
          <View style={styles.chartHeader}>
            <Icon name="people" size={20} color="#4CAF50" />
            <Text style={styles.chartTitle}>活跃用户趋势</Text>
          </View>
          <View style={styles.emptyChart}>
            <Icon name="people" size={48} color="#CCC" />
            <Text style={styles.emptyText}>暂无活跃用户数据</Text>
          </View>
        </View>
      );
    }

    return (
      <View style={styles.chartCard}>
        <View style={styles.chartHeader}>
          <Icon name="people" size={20} color="#4CAF50" />
          <Text style={styles.chartTitle}>活跃用户趋势</Text>
        </View>
        <LineChart
          data={{
            labels: chartData.labels.map(l => l.split('-').slice(1).join('/')),
            datasets: [
              {
                data: chartData.activeUsers,
              },
            ],
          }}
          width={screenWidth - 48}
          height={200}
          chartConfig={chartConfig2}
          bezier
          style={styles.chartStyle}
        />
      </View>
    );
  };

  const renderRatingChart = () => {
    if (chartLoadingState !== 'idle') {
      return (
        <View style={styles.chartCard}>
          <View style={styles.chartHeader}>
            <Icon name="star" size={20} color="#FFC107" />
            <Text style={styles.chartTitle}>评分趋势</Text>
          </View>
          {renderStatusIndicator(chartLoadingState)}
        </View>
      );
    }

    if (!chartData || !chartData.labels || chartData.labels.length === 0) {
      return (
        <View style={styles.chartCard}>
          <View style={styles.chartHeader}>
            <Icon name="star" size={20} color="#FFC107" />
            <Text style={styles.chartTitle}>评分趋势</Text>
          </View>
          <View style={styles.emptyChart}>
            <Icon name="star" size={48} color="#CCC" />
            <Text style={styles.emptyText}>暂无评分数据</Text>
          </View>
        </View>
      );
    }

    return (
      <View style={styles.chartCard}>
        <View style={styles.chartHeader}>
          <Icon name="star" size={20} color="#FFC107" />
          <Text style={styles.chartTitle}>评分趋势</Text>
        </View>
        <BarChart
          data={{
            labels: chartData.labels.map(l => l.split('-').slice(1).join('/')),
            datasets: [
              {
                data: chartData.ratings,
              },
            ],
          }}
          width={screenWidth - 48}
          height={200}
          yAxisLabel=""
          yAxisSuffix=""
          chartConfig={{
            ...chartConfig,
            color: (opacity = 1) => `rgba(255, 193, 7, ${opacity})`,
          }}
          fromZero
          showValuesOnTopOfBars
          style={styles.chartStyle}
        />
      </View>
    );
  };

  const renderFeedbackPieChart = () => {
    if (!feedbackStats) return null;

    const pieData = [
      {
        name: '待处理',
        population: feedbackStats.pending,
        color: '#FF9800',
        legendFontColor: '#757575',
        legendFontSize: 12,
      },
      {
        name: '处理中',
        population: feedbackStats.processing,
        color: '#2196F3',
        legendFontColor: '#757575',
        legendFontSize: 12,
      },
      {
        name: '已处理',
        population: feedbackStats.processed,
        color: '#4CAF50',
        legendFontColor: '#757575',
        legendFontSize: 12,
      },
      {
        name: '已关闭',
        population: feedbackStats.closed,
        color: '#9E9E9E',
        legendFontColor: '#757575',
        legendFontSize: 12,
      },
    ].filter(item => item.population > 0);

    if (pieData.length === 0) {
      return (
        <View style={styles.chartCard}>
          <View style={styles.chartHeader}>
            <Icon name="feedback" size={20} color="#E91E63" />
            <Text style={styles.chartTitle}>反馈状态分布</Text>
          </View>
          <View style={styles.emptyChart}>
            <Icon name="feedback" size={48} color="#CCC" />
            <Text style={styles.emptyText}>暂无反馈数据</Text>
          </View>
        </View>
      );
    }

    return (
      <View style={styles.chartCard}>
        <View style={styles.chartHeader}>
          <Icon name="feedback" size={20} color="#E91E63" />
          <Text style={styles.chartTitle}>反馈状态分布</Text>
          <Text style={styles.chartMeta}>总计: {feedbackStats.total} 条</Text>
        </View>
        <PieChart
          data={pieData}
          width={screenWidth - 48}
          height={200}
          chartConfig={chartConfig}
          accessor="population"
          backgroundColor="transparent"
          paddingLeft="15"
          absolute
        />
      </View>
    );
  };

  if (apps.length === 0) {
    return (
      <View style={styles.emptyContainer}>
        <Icon name="insert-chart-outlined" size={64} color="#CCC" />
        <Text style={styles.emptyText}>暂无应用</Text>
        <Text style={styles.emptySubtext}>请先在应用管理中创建应用</Text>
      </View>
    );
  }

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={styles.contentContainer}
      refreshControl={
        <RefreshControl refreshing={refreshing} onRefresh={handleRefresh} />
      }
    >
      <View style={styles.filterCard}>
        <View style={styles.filterRow}>
          <Text style={styles.filterLabel}>选择应用</Text>
          <View style={styles.pickerContainer}>
            <Picker
              selectedValue={selectedAppId}
              onValueChange={(value) => setSelectedAppId(value)}
              style={styles.picker}
            >
              {apps.map((app) => (
                <Picker.Item key={app.appId} label={app.name} value={app.appId} />
              ))}
            </Picker>
          </View>
        </View>

        <View style={styles.filterRow}>
          <Text style={styles.filterLabel}>时间范围</Text>
          <View style={styles.chipContainer}>
            <TouchableOpacity
              style={[styles.chip, reportType === 'week' && styles.chipActive]}
              onPress={() => setReportType('week')}
            >
              <Text style={[styles.chipText, reportType === 'week' && styles.chipTextActive]}>本周</Text>
            </TouchableOpacity>
            <TouchableOpacity
              style={[styles.chip, reportType === 'month' && styles.chipActive]}
              onPress={() => setReportType('month')}
            >
              <Text style={[styles.chipText, reportType === 'month' && styles.chipTextActive]}>本月</Text>
            </TouchableOpacity>
          </View>
        </View>

        <View style={styles.buttonRow}>
          <TouchableOpacity
            style={[styles.actionButton, styles.refreshButton]}
            onPress={handleRefresh}
            disabled={refreshing}
          >
            <Icon name="refresh" size={18} color="#FFF" />
            <Text style={styles.actionButtonText}>刷新数据</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.actionButton, styles.demoButton, generatingDemo && styles.disabledButton]}
            onPress={handleGenerateDemo}
            disabled={generatingDemo}
          >
            {generatingDemo ? (
              <ActivityIndicator size="small" color="#1976D2" />
            ) : (
              <>
                <Icon name="autorenew" size={18} color="#1976D2" />
                <Text style={styles.demoButtonText}>生成演示</Text>
              </>
            )}
          </TouchableOpacity>
        </View>
      </View>

      {renderSummaryCards()}
      {renderDownloadChart()}
      {renderActiveUsersChart()}
      {renderRatingChart()}
      {renderFeedbackPieChart()}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#F5F5F5',
  },
  contentContainer: {
    padding: 16,
    paddingBottom: 32,
  },
  filterCard: {
    backgroundColor: '#FFF',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    elevation: 2,
  },
  filterRow: {
    marginBottom: 12,
  },
  filterLabel: {
    fontSize: 14,
    fontWeight: '500',
    color: '#424242',
    marginBottom: 8,
  },
  pickerContainer: {
    backgroundColor: '#F5F5F5',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#E0E0E0',
  },
  picker: {
    height: 50,
  },
  chipContainer: {
    flexDirection: 'row',
  },
  chip: {
    paddingHorizontal: 20,
    paddingVertical: 10,
    borderRadius: 20,
    marginRight: 12,
    backgroundColor: '#F0F0F0',
  },
  chipActive: {
    backgroundColor: '#1976D2',
  },
  chipText: {
    fontSize: 14,
    color: '#757575',
  },
  chipTextActive: {
    color: '#FFF',
    fontWeight: '500',
  },
  buttonRow: {
    flexDirection: 'row',
    marginTop: 8,
  },
  actionButton: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 12,
    borderRadius: 8,
    marginHorizontal: 4,
  },
  refreshButton: {
    backgroundColor: '#1976D2',
  },
  demoButton: {
    borderWidth: 1,
    borderColor: '#1976D2',
    backgroundColor: 'transparent',
  },
  actionButtonText: {
    fontSize: 14,
    color: '#FFF',
    fontWeight: '500',
    marginLeft: 6,
  },
  demoButtonText: {
    fontSize: 14,
    color: '#1976D2',
    fontWeight: '500',
    marginLeft: 6,
  },
  disabledButton: {
    opacity: 0.5,
  },
  statusIndicator: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 20,
  },
  statusText: {
    fontSize: 14,
    color: '#757575',
    marginLeft: 8,
  },
  calculatingIndicator: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 30,
    backgroundColor: '#E8F5E9',
    borderRadius: 8,
  },
  calculatingText: {
    fontSize: 14,
    color: '#2E7D32',
    marginLeft: 8,
    fontWeight: '500',
  },
  errorIndicator: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 20,
  },
  errorText: {
    fontSize: 14,
    color: '#F44336',
    marginLeft: 8,
  },
  emptyContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  emptyText: {
    fontSize: 18,
    fontWeight: '500',
    color: '#757575',
    marginTop: 12,
  },
  emptySubtext: {
    fontSize: 14,
    color: '#9E9E9E',
    marginTop: 8,
  },
  summaryContainer: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 16,
  },
  summaryCard: {
    width: '31%',
    borderRadius: 12,
    padding: 12,
    alignItems: 'center',
  },
  skeletonCard: {
    backgroundColor: '#F0F0F0',
    justifyContent: 'center',
    height: 100,
  },
  summaryValue: {
    fontSize: 18,
    fontWeight: '700',
    marginTop: 6,
  },
  summaryLabel: {
    fontSize: 10,
    color: '#757575',
    marginTop: 2,
  },
  chartCard: {
    backgroundColor: '#FFF',
    borderRadius: 12,
    padding: 16,
    marginBottom: 16,
    elevation: 2,
  },
  chartHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
    flexWrap: 'wrap',
  },
  chartTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: '#424242',
    marginLeft: 8,
  },
  chartMeta: {
    fontSize: 10,
    color: '#9E9E9E',
    marginLeft: 8,
    marginRight: 8,
  },
  chartStyle: {
    borderRadius: 8,
    marginRight: -16,
  },
  emptyChart: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 40,
  },
});
