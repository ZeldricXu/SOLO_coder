import { Test } from '@nestjs/testing';
import { MetricService } from './metric.service';
import { PrismaService } from '../prisma/prisma.service';
import { DataSourceService } from '../data-source/data-source.service';
import { Aggregation } from '@prisma/client';

function makeRange(year: number, month: number, day: number) {
  const d = new Date(Date.UTC(year, month - 1, day, 12, 0, 0));
  return {
    start: d.toISOString(),
    end: d.toISOString(),
  };
}

function getUTC(result: { start: string; end: string }, key: 'start' | 'end') {
  const d = new Date(result[key]);
  return { year: d.getUTCFullYear(), month: d.getUTCMonth(), day: d.getUTCDate() };
}

describe('MetricService', () => {
  let service: MetricService;
  let prismaMock: any;
  let dataSourceMock: any;

  beforeEach(async () => {
    prismaMock = {
      metric: {
        create: jest.fn(),
        findMany: jest.fn(),
        findUnique: jest.fn(),
        update: jest.fn(),
        delete: jest.fn(),
      },
    };

    dataSourceMock = {
      executeQuery: jest.fn(),
    };

    const module = await Test.createTestingModule({
      providers: [
        MetricService,
        { provide: PrismaService, useValue: prismaMock },
        { provide: DataSourceService, useValue: dataSourceMock },
      ],
    }).compile();

    service = module.get<MetricService>(MetricService);
  });

  describe('shiftDateRange - 环比(mom)边界', () => {
    const shift = (range: { start: string; end: string }, type: 'yoy' | 'mom') =>
      (service as any).shiftDateRange(range, type);

    it('1月31日环比应对齐到12月31日', () => {
      const result = shift(makeRange(2024, 1, 31), 'mom');
      const s = getUTC(result, 'start');
      const e = getUTC(result, 'end');
      expect(s.month).toBe(11);
      expect(s.day).toBe(31);
      expect(e.month).toBe(11);
      expect(e.day).toBe(31);
    });

    it('3月31日环比应对齐到2月28日(非闰年)', () => {
      const result = shift(makeRange(2023, 3, 31), 'mom');
      const s = getUTC(result, 'start');
      const e = getUTC(result, 'end');
      expect(s.month).toBe(1);
      expect(s.day).toBe(28);
      expect(e.month).toBe(1);
      expect(e.day).toBe(28);
    });

    it('3月31日环比应对齐到2月29日(闰年2024)', () => {
      const result = shift(makeRange(2024, 3, 31), 'mom');
      const s = getUTC(result, 'start');
      const e = getUTC(result, 'end');
      expect(s.month).toBe(1);
      expect(s.day).toBe(29);
      expect(e.month).toBe(1);
      expect(e.day).toBe(29);
    });

    it('3月30日环比应对齐到2月28/29日', () => {
      const result = shift(makeRange(2023, 3, 30), 'mom');
      const s = getUTC(result, 'start');
      expect(s.month).toBe(1);
      expect(s.day).toBe(28);
    });

    it('5月31日环比应对齐到4月30日', () => {
      const result = shift(makeRange(2024, 5, 31), 'mom');
      const s = getUTC(result, 'start');
      const e = getUTC(result, 'end');
      expect(s.month).toBe(3);
      expect(s.day).toBe(30);
      expect(e.month).toBe(3);
      expect(e.day).toBe(30);
    });

    it('普通日期1月15日环比应对齐到12月15日', () => {
      const result = shift(makeRange(2024, 1, 15), 'mom');
      const s = getUTC(result, 'start');
      const e = getUTC(result, 'end');
      expect(s.month).toBe(11);
      expect(s.day).toBe(15);
      expect(e.month).toBe(11);
      expect(e.day).toBe(15);
    });

    it('跨月范围(start和end不同月)环比各自偏移', () => {
      const range = {
        start: new Date(Date.UTC(2024, 0, 28, 12, 0, 0)).toISOString(),
        end: new Date(Date.UTC(2024, 1, 5, 12, 0, 0)).toISOString(),
      };
      const result = shift(range, 'mom');
      const s = getUTC(result, 'start');
      const e = getUTC(result, 'end');
      expect(s.month).toBe(11);
      expect(s.day).toBe(28);
      expect(e.month).toBe(0);
      expect(e.day).toBe(5);
    });
  });

  describe('shiftDateRange - 同比(yoy)边界', () => {
    const shift = (range: { start: string; end: string }, type: 'yoy' | 'mom') =>
      (service as any).shiftDateRange(range, type);

    it('同比: 2024-01-15 → 2023-01-15', () => {
      const result = shift(makeRange(2024, 1, 15), 'yoy');
      const s = getUTC(result, 'start');
      expect(s.year).toBe(2023);
      expect(s.month).toBe(0);
      expect(s.day).toBe(15);
    });

    it('闰年处理: 2024-02-29 同比 → JS会回退到2023-03-01(setFullYear无day clamp)', () => {
      const result = shift(makeRange(2024, 2, 29), 'yoy');
      const s = getUTC(result, 'start');
      expect(s.year).toBe(2023);
      expect(s.month).toBe(2);
      expect(s.day).toBe(1);
    });

    it('闰年处理: 2024-02-28 同比 → 2023-02-28', () => {
      const result = shift(makeRange(2024, 2, 28), 'yoy');
      const s = getUTC(result, 'start');
      expect(s.year).toBe(2023);
      expect(s.month).toBe(1);
      expect(s.day).toBe(28);
    });

    it('跨年边界: 2024-01-01 同比 → 2023-01-01', () => {
      const result = shift(makeRange(2024, 1, 1), 'yoy');
      const s = getUTC(result, 'start');
      expect(s.year).toBe(2023);
      expect(s.month).toBe(0);
      expect(s.day).toBe(1);
    });

    it('同比: start和end同时减一年', () => {
      const range = {
        start: new Date(Date.UTC(2024, 5, 1, 12, 0, 0)).toISOString(),
        end: new Date(Date.UTC(2024, 5, 30, 12, 0, 0)).toISOString(),
      };
      const result = shift(range, 'yoy');
      const s = getUTC(result, 'start');
      const e = getUTC(result, 'end');
      expect(s.year).toBe(2023);
      expect(e.year).toBe(2023);
    });
  });

  describe('computeChangeRate', () => {
    const compute = (current: number, previous: number) =>
      (service as any).computeChangeRate(current, previous);

    it('current=150, previous=100 → 0.5', () => {
      expect(compute(150, 100)).toBeCloseTo(0.5);
    });

    it('previous=0 → null', () => {
      expect(compute(50, 0)).toBeNull();
    });

    it('current=80, previous=100 → -0.2', () => {
      expect(compute(80, 100)).toBeCloseTo(-0.2);
    });

    it('current=100, previous=100 → 0', () => {
      expect(compute(100, 100)).toBe(0);
    });
  });

  describe('buildSql', () => {
    const build = (metric: any, dto: any) => (service as any).buildSql(metric, dto);

    it('模板变量替换: {{startDate}}, {{endDate}}', () => {
      const metric = {
        id: 'm1',
        sqlTemplate:
          "SELECT * FROM orders WHERE created_at BETWEEN '{{startDate}}' AND '{{endDate}}'",
        aggregation: Aggregation.NONE,
        dimensions: [],
      };
      const dto = {
        dateRange: { start: '2024-01-01', end: '2024-01-31' },
        dimensions: [],
      };

      const sql = build(metric, dto);
      expect(sql).toContain("'2024-01-01'");
      expect(sql).toContain("'2024-01-31'");
      expect(sql).not.toContain('{{startDate}}');
      expect(sql).not.toContain('{{endDate}}');
    });

    it('维度和 GROUP BY 生成', () => {
      const metric = {
        id: 'm1',
        sqlTemplate:
          "SELECT {{dimensions}}, COUNT(*) AS cnt FROM orders WHERE created_at BETWEEN '{{startDate}}' AND '{{endDate}}' {{groupBy}}",
        aggregation: Aggregation.NONE,
        dimensions: ['product_category', 'channel'],
      };
      const dto = {
        dateRange: { start: '2024-01-01', end: '2024-01-31' },
        dimensions: ['product_category', 'channel'],
      };

      const sql = build(metric, dto);
      expect(sql).toContain('product_category, channel');
      expect(sql).toContain('GROUP BY product_category, channel');
    });

    it('无维度时 {{dimensions}} 和 {{groupBy}} 为空', () => {
      const metric = {
        id: 'm1',
        sqlTemplate:
          "SELECT {{dimensions}} COUNT(*) AS cnt FROM orders WHERE created_at BETWEEN '{{startDate}}' AND '{{endDate}}' {{groupBy}}",
        aggregation: Aggregation.NONE,
        dimensions: [],
      };
      const dto = {
        dateRange: { start: '2024-01-01', end: '2024-01-31' },
      };

      const sql = build(metric, dto);
      expect(sql).not.toContain('GROUP BY');
    });

    it('通过 templateId 查找模板', () => {
      const metric = {
        id: 'm1',
        sqlTemplate: null,
        templateId: 'ecommerce-gmv',
        aggregation: Aggregation.NONE,
        dimensions: [],
      };
      const dto = {
        dateRange: { start: '2024-01-01', end: '2024-01-31' },
        dimensions: ['product_category'],
      };

      const sql = build(metric, dto);
      expect(sql).toContain('SUM(payment_amount) AS gmv');
      expect(sql).toContain('GROUP BY product_category');
    });

    it('无sqlTemplate且无templateId时抛出NotFoundException', () => {
      const metric = {
        id: 'm1',
        sqlTemplate: null,
        templateId: null,
        aggregation: Aggregation.NONE,
        dimensions: [],
      };
      const dto = {
        dateRange: { start: '2024-01-01', end: '2024-01-31' },
      };

      expect(() => build(metric, dto)).toThrow('No SQL template found for metric m1');
    });

    it('应用非 NONE 聚合时包裹子查询', () => {
      const metric = {
        id: 'm1',
        sqlTemplate: "SELECT COUNT(*) AS cnt FROM orders WHERE created_at BETWEEN '{{startDate}}' AND '{{endDate}}'",
        aggregation: Aggregation.SUM,
        dimensions: [],
      };
      const dto = {
        dateRange: { start: '2024-01-01', end: '2024-01-31' },
      };

      const sql = build(metric, dto);
      expect(sql).toContain('SELECT SUM(*) AS aggregated_value FROM (');
      expect(sql).toContain(') AS subquery');
    });

    it('dto.dimensions 覆盖 metric.dimensions', () => {
      const metric = {
        id: 'm1',
        sqlTemplate:
          "SELECT {{dimensions}}, COUNT(*) AS cnt FROM orders WHERE created_at BETWEEN '{{startDate}}' AND '{{endDate}}' {{groupBy}}",
        aggregation: Aggregation.NONE,
        dimensions: ['product_category'],
      };
      const dto = {
        dateRange: { start: '2024-01-01', end: '2024-01-31' },
        dimensions: ['channel'],
      };

      const sql = build(metric, dto);
      expect(sql).toContain('channel');
      expect(sql).toContain('GROUP BY channel');
      expect(sql).not.toContain('product_category');
    });
  });

  describe('getComparison - 集成shiftDateRange和computeChangeRate', () => {
    beforeEach(() => {
      prismaMock.metric.findUnique.mockResolvedValue({
        id: 'metric-1',
        name: 'Test Metric',
        aggregation: Aggregation.SUM,
        sqlTemplate: "SELECT SUM(amount) AS aggregated_value FROM orders WHERE created_at BETWEEN '{{startDate}}' AND '{{endDate}}'",
        dimensions: [],
        dataSourceId: 'ds-1',
      });
    });

    it('同比比较返回正确changeRate', async () => {
      dataSourceMock.executeQuery.mockImplementation((_dsId: string, query: { sql: string }) => {
        if (query.sql.includes('2023-01-15')) {
          return Promise.resolve({ rows: [{ aggregated_value: 100 }] });
        }
        return Promise.resolve({ rows: [{ aggregated_value: 150 }] });
      });

      const result = await service.getComparison('metric-1', {
        type: 'yoy',
        dateRange: {
          start: new Date(Date.UTC(2024, 0, 15, 12, 0, 0)).toISOString(),
          end: new Date(Date.UTC(2024, 0, 15, 12, 0, 0)).toISOString(),
        },
      });

      expect(result.comparisonType).toBe('yoy');
      expect(result.current.value).toBe(150);
      expect(result.previous.value).toBe(100);
      expect(result.changeRate).toBeCloseTo(0.5);
    });

    it('环比比较调用两次executeQuery', async () => {
      dataSourceMock.executeQuery.mockResolvedValue({ rows: [{ aggregated_value: 50 }] });

      await service.getComparison('metric-1', {
        type: 'mom',
        dateRange: {
          start: new Date(Date.UTC(2024, 0, 15, 12, 0, 0)).toISOString(),
          end: new Date(Date.UTC(2024, 0, 15, 12, 0, 0)).toISOString(),
        },
      });

      expect(dataSourceMock.executeQuery).toHaveBeenCalledTimes(2);
    });

    it('previous为0时changeRate返回null', async () => {
      dataSourceMock.executeQuery.mockImplementation((_dsId: string, query: { sql: string }) => {
        if (query.sql.includes('2023-01-15')) {
          return Promise.resolve({ rows: [{ aggregated_value: 0 }] });
        }
        return Promise.resolve({ rows: [{ aggregated_value: 150 }] });
      });

      const result = await service.getComparison('metric-1', {
        type: 'yoy',
        dateRange: {
          start: new Date(Date.UTC(2024, 0, 15, 12, 0, 0)).toISOString(),
          end: new Date(Date.UTC(2024, 0, 15, 12, 0, 0)).toISOString(),
        },
      });

      expect(result.changeRate).toBeNull();
    });
  });
});
