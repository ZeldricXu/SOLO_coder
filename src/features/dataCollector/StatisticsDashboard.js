import React, { useMemo } from 'react';
import { Card, Row, Col, Statistic, Descriptions, Empty } from 'antd';
import {
  FileTextOutlined,
  ClockCircleOutlined,
  CheckCircleOutlined,
  RiseOutlined,
} from '@ant-design/icons';
import { useSelector } from 'react-redux';
import ReactECharts from 'echarts-for-react';
import {
  selectTotalSubmissions,
  selectTodaySubmissions,
  selectLastSubmitTime,
  selectSubmissions,
  selectFieldStatistics,
} from './dataCollectorSlice';

const StatisticsDashboard = ({ formConfig }) => {
  const totalSubmissions = useSelector(selectTotalSubmissions);
  const todaySubmissions = useSelector(selectTodaySubmissions);
  const lastSubmitTime = useSelector(selectLastSubmitTime);
  const submissions = useSelector(selectSubmissions);
  const fieldStatistics = useSelector(selectFieldStatistics);

  const submissionTrendOption = useMemo(() => {
    const dateCounts = {};
    submissions.forEach(submission => {
      if (submission.submitted_at) {
        const date = new Date(submission.submitted_at).toLocaleDateString('zh-CN');
        dateCounts[date] = (dateCounts[date] || 0) + 1;
      }
    });

    const dates = Object.keys(dateCounts).sort();
    const values = dates.map(date => dateCounts[date]);

    return {
      title: {
        text: '提交趋势',
        left: 'center',
        textStyle: { fontSize: 14 },
      },
      tooltip: {
        trigger: 'axis',
      },
      xAxis: {
        type: 'category',
        data: dates,
        axisLabel: { rotate: 45 },
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
      },
      series: [
        {
          name: '提交数',
          type: 'line',
          data: values,
          smooth: true,
          areaStyle: { opacity: 0.3 },
        },
      ],
    };
  }, [submissions]);

  const fieldChartOptions = useMemo(() => {
    const charts = [];

    if (!formConfig) return charts;

    const allComponents = [];
    if (formConfig.form_type === 'multi_step' && formConfig.steps) {
      formConfig.steps.forEach(step => {
        (step.components || []).forEach(comp => {
          allComponents.push(comp);
        });
      });
    } else if (formConfig.components) {
      allComponents.push(...formConfig.components);
    }

    allComponents.forEach(comp => {
      const stats = fieldStatistics[comp.component_id];
      if (!stats || !stats.valueCounts || Object.keys(stats.valueCounts).length === 0) return;

      if (
        comp.component_type === 'radio' ||
        comp.component_type === 'checkbox' ||
        comp.component_type === 'select' ||
        comp.component_type === 'rating'
      ) {
        const valueCounts = stats.valueCounts;

        const option = {
          title: {
            text: comp.label,
            left: 'center',
            textStyle: { fontSize: 14 },
          },
          tooltip: {
            trigger: 'item',
            formatter: '{b}: {c} ({d}%)',
          },
          legend: {
            type: 'scroll',
            orient: 'vertical',
            right: 10,
            top: 'center',
          },
          series: [
            {
              name: comp.label,
              type: 'pie',
              radius: ['40%', '70%'],
              center: ['40%', '50%'],
              avoidLabelOverlap: false,
              itemStyle: {
                borderRadius: 10,
                borderColor: '#fff',
                borderWidth: 2,
              },
              label: {
                show: false,
              },
              emphasis: {
                label: {
                  show: true,
                  fontSize: 16,
                  fontWeight: 'bold',
                },
              },
              labelLine: {
                show: false,
              },
              data: Object.entries(valueCounts).map(([name, value]) => ({
                name,
                value,
              })),
            },
          ],
        };

        charts.push({
          id: comp.component_id,
          option,
          type: comp.component_type,
        });
      }
    });

    return charts;
  }, [formConfig, fieldStatistics]);

  if (totalSubmissions === 0) {
    return (
      <Card>
        <Empty
          description="暂无统计数据"
          image={Empty.PRESENTED_IMAGE_SIMPLE}
        />
      </Card>
    );
  }

  return (
    <div>
      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic
              title="总提交数"
              value={totalSubmissions}
              prefix={<FileTextOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="今日提交"
              value={todaySubmissions}
              prefix={<RiseOutlined />}
              valueStyle={{ color: '#3f8600' }}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="最近提交时间"
              value={
                lastSubmitTime
                  ? new Date(lastSubmitTime).toLocaleString('zh-CN')
                  : '-'
              }
              prefix={<ClockCircleOutlined />}
            />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic
              title="表单状态"
              value="正常"
              prefix={<CheckCircleOutlined />}
              valueStyle={{ color: '#3f8600' }}
            />
          </Card>
        </Col>
      </Row>

      <Row gutter={16} style={{ marginBottom: 16 }}>
        <Col span={24}>
          <Card>
            <ReactECharts
              option={submissionTrendOption}
              style={{ height: 300 }}
              opts={{ renderer: 'canvas' }}
            />
          </Card>
        </Col>
      </Row>

      {fieldChartOptions.length > 0 && (
        <Row gutter={16}>
          {fieldChartOptions.map((chart, index) => (
            <Col span={12} key={chart.id} style={{ marginBottom: 16 }}>
              <Card>
                <ReactECharts
                  option={chart.option}
                  style={{ height: 300 }}
                  opts={{ renderer: 'canvas' }}
                />
              </Card>
            </Col>
          ))}
        </Row>
      )}
    </div>
  );
};

export default StatisticsDashboard;
