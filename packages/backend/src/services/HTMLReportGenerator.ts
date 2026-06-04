import { SimulationResult, SimpleScene, SimpleSimulationConfig, MATERIALS } from '@physics-sim/shared';
type Scene = SimpleScene;
type SimulationConfig = SimpleSimulationConfig;

interface HTMLReportData {
  scene: Scene;
  config: SimulationConfig;
  result: SimulationResult;
  title?: string;
  author?: string;
  date?: string;
  description?: string;
  experimentPurpose?: string;
  conclusion?: string;
  charts?: { dataUrl: string; caption: string }[];
}

export class HTMLReportGenerator {
  async generateHTML(data: HTMLReportData): Promise<string> {
    const title = data.title || '物理仿真实验报告';
    const author = data.author || '物理仿真平台';
    const date = data.date || new Date().toLocaleDateString('zh-CN');

    const body = this.buildBody(data);

    return `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>${this.escapeHTML(title)}</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: "SimSun", "Noto Serif CJK SC", "Source Han Serif SC", serif;
    font-size: 12pt;
    line-height: 1.8;
    color: #222;
    max-width: 210mm;
    margin: 0 auto;
    padding: 25mm 20mm;
    background: #fff;
  }
  h1 {
    text-align: center;
    font-size: 22pt;
    margin-bottom: 8px;
    padding-bottom: 12px;
    border-bottom: 2px solid #333;
  }
  .meta {
    text-align: center;
    color: #555;
    font-size: 11pt;
    margin-bottom: 24px;
  }
  .meta span { margin: 0 12px; }
  h2 {
    font-size: 16pt;
    margin: 24px 0 12px;
    padding: 4px 0;
    border-bottom: 1px solid #999;
  }
  h3 {
    font-size: 13pt;
    margin: 16px 0 8px;
  }
  table {
    width: 100%;
    border-collapse: collapse;
    margin: 12px 0;
    font-size: 10.5pt;
  }
  th, td {
    border: 1px solid #666;
    padding: 6px 10px;
    text-align: center;
  }
  th {
    background: #f0f0f0;
    font-weight: bold;
  }
  tr:nth-child(even) td { background: #fafafa; }
  .chart-container {
    text-align: center;
    margin: 16px 0;
    page-break-inside: avoid;
  }
  .chart-container img {
    max-width: 100%;
    height: auto;
    border: 1px solid #ddd;
  }
  .chart-caption {
    font-size: 10pt;
    color: #555;
    margin-top: 4px;
  }
  ul, ol {
    margin: 8px 0 8px 24px;
  }
  li { margin: 4px 0; }
  .formula {
    text-align: center;
    font-style: italic;
    margin: 12px 0;
    padding: 8px;
    background: #f9f9f9;
    border-left: 3px solid #999;
  }
  .section { page-break-inside: avoid; }
  .footer {
    margin-top: 32px;
    padding-top: 12px;
    border-top: 1px solid #ccc;
    font-size: 9pt;
    color: #888;
    text-align: center;
  }
  @media print {
    body { padding: 15mm; }
    .section { page-break-inside: avoid; }
  }
</style>
</head>
<body>
<h1>${this.escapeHTML(title)}</h1>
<div class="meta">
  <span>作者: ${this.escapeHTML(author)}</span>
  <span>日期: ${this.escapeHTML(date)}</span>
</div>
${body}
<div class="footer">本报告由物理仿真平台自动生成</div>
</body>
</html>`;
  }

  private buildBody(data: HTMLReportData): string {
    const sections: string[] = [];

    if (data.description || data.experimentPurpose) {
      sections.push(this.buildSection('实验描述', `
        ${data.description ? `<h3>实验简介</h3><p>${this.escapeHTML(data.description)}</p>` : ''}
        ${data.experimentPurpose ? `<h3>实验目的</h3><p>${this.escapeHTML(data.experimentPurpose)}</p>` : ''}
      `));
    }

    sections.push(this.buildSection('实验原理', this.buildTheorySection(data)));
    sections.push(this.buildSection('实验装置', this.buildSetupSection(data)));
    sections.push(this.buildSection('仿真参数', this.buildParametersSection(data)));
    sections.push(this.buildSection('实验结果', this.buildResultsSection(data)));
    sections.push(this.buildSection('数据分析', this.buildAnalysisSection(data)));

    if (data.conclusion) {
      sections.push(this.buildSection('结论', `<p>${this.escapeHTML(data.conclusion)}</p>`));
    }

    sections.push(this.buildSection('误差分析', this.buildErrorAnalysisSection(data)));

    if (data.charts && data.charts.length > 0) {
      sections.push(this.buildSection('图表', this.buildChartsSection(data.charts)));
    }

    return sections.join('\n');
  }

  private buildSection(title: string, content: string): string {
    return `<div class="section"><h2>${this.escapeHTML(title)}</h2>${content}</div>`;
  }

  private buildTheorySection(data: HTMLReportData): string {
    const config = data.config;
    const theories: string[] = [];

    if (config.physicsTypes.includes('mechanics')) {
      theories.push(`
        <h3>力学原理</h3>
        <p>本仿真基于牛顿力学和刚体动力学。使用Verlet积分进行数值积分，GJK+EPA算法进行碰撞检测，位置基动力学(PBD)处理约束。</p>
        <div class="formula">F = ma</div>
        <div class="formula">x(t+Δt) = 2x(t) - x(t-Δt) + a(t)Δt²</div>
      `);
    }

    if (config.physicsTypes.includes('electromagnetics')) {
      theories.push(`
        <h3>电磁学原理</h3>
        <p>静电场求解基于泊松方程，使用有限差分法(FDM)离散求解。</p>
        <div class="formula">∇²φ = -ρ/ε₀</div>
        <div class="formula">F = (1/4πε₀) × (q₁q₂/r²) r̂</div>
      `);
    }

    if (config.physicsTypes.includes('thermodynamics')) {
      theories.push(`
        <h3>热力学原理</h3>
        <p>热传导求解基于热扩散方程，使用Crank-Nicolson隐式格式。</p>
        <div class="formula">ρcₚ ∂T/∂t = ∇·(k∇T) + Q</div>
        <div class="formula">q = -k∇T</div>
      `);
    }

    if (config.physicsTypes.includes('fluiddynamics')) {
      theories.push(`
        <h3>流体力学原理</h3>
        <p>不可压缩流体仿真基于格子Boltzmann方法(LBM)，采用D2Q9模型和BGK碰撞算子。</p>
        <div class="formula">fᵢ(x+cᵢΔt, t+Δt) = fᵢ(x,t) - (1/τ)(fᵢ - fᵢᵉᑫ)</div>
        <div class="formula">ρ = Σfᵢ, &nbsp; ρu = Σcᵢfᵢ</div>
      `);
    }

    return theories.join('\n');
  }

  private buildSetupSection(data: HTMLReportData): string {
    const objects = Array.from(data.scene.objects.values());

    const rows = objects.map(obj => `
      <tr>
        <td>${this.escapeHTML(obj.id.slice(0, 8))}</td>
        <td>${this.escapeHTML(this.getObjectTypeName(obj.objectType))}</td>
        <td>(${obj.position.x.toFixed(2)}, ${obj.position.y.toFixed(2)}, ${obj.position.z.toFixed(2)})</td>
        <td>${this.escapeHTML(MATERIALS[obj.materialId]?.name || obj.materialId)}</td>
        <td>${obj.isStatic ? '是' : '否'}</td>
      </tr>
    `).join('');

    return `
      <p>本次实验包含 ${objects.length} 个物理对象。</p>
      <table>
        <thead><tr><th>物体ID</th><th>类型</th><th>位置</th><th>材料</th><th>是否静态</th></tr></thead>
        <tbody>${rows}</tbody>
      </table>
    `;
  }

  private buildParametersSection(data: HTMLReportData): string {
    const { config, scene } = data;

    return `
      <table>
        <thead><tr><th>参数名称</th><th>数值</th><th>单位</th></tr></thead>
        <tbody>
          <tr><td>仿真时长</td><td>${config.duration}</td><td>s</td></tr>
          <tr><td>时间步长</td><td>${config.timeStep}</td><td>s</td></tr>
          <tr><td>重力加速度</td><td>${scene.gravity.y}</td><td>m/s²</td></tr>
          <tr><td>碰撞迭代次数</td><td>${config.collisionIterations || '默认'}</td><td>次</td></tr>
          <tr><td>约束迭代次数</td><td>${config.constraintIterations || '默认'}</td><td>次</td></tr>
          <tr><td>物理类型</td><td>${config.physicsTypes.join(', ')}</td><td>-</td></tr>
        </tbody>
      </table>
    `;
  }

  private buildResultsSection(data: HTMLReportData): string {
    const { result } = data;
    if (!result || !result.statistics) {
      return '<p>暂无实验结果数据。</p>';
    }

    const stats = result.statistics;

    return `
      <p>仿真已成功完成。</p>
      <table>
        <thead><tr><th>指标</th><th>数值</th><th>单位</th></tr></thead>
        <tbody>
          <tr><td>总仿真步数</td><td>${stats.totalSteps}</td><td>步</td></tr>
          <tr><td>渲染帧数</td><td>${stats.framesRendered}</td><td>帧</td></tr>
          <tr><td>计算耗时</td><td>${stats.computationTime.toFixed(3)}</td><td>s</td></tr>
          <tr><td>每秒步数</td><td>${stats.stepsPerSecond.toFixed(1)}</td><td>步/s</td></tr>
          <tr><td>实时因子</td><td>${stats.realTimeFactor.toFixed(2)}</td><td>×</td></tr>
        </tbody>
      </table>
      <p>仿真生成了 ${result.frames?.length || 0} 帧数据，包含 ${Object.keys(result.sensorData || {}).length} 个传感器的测量记录。</p>
    `;
  }

  private buildAnalysisSection(data: HTMLReportData): string {
    const sensorData = data.result?.sensorData || {};
    const sensorCount = Object.keys(sensorData).length;

    if (sensorCount === 0) {
      return '<p>本次实验未配置传感器，建议在场景中添加传感器以进行数据分析。</p>';
    }

    const analyses: string[] = [`<p>本次实验共配置 ${sensorCount} 个传感器。</p>`];

    for (const [sensorId, records] of Object.entries(sensorData)) {
      const dataArray = records as any[];
      if (dataArray.length < 2) continue;

      const values = dataArray.map(d =>
        typeof d.value === 'number' ? d.value :
        Math.sqrt(d.value.x ** 2 + d.value.y ** 2 + d.value.z ** 2)
      );

      const mean = values.reduce((a: number, b: number) => a + b, 0) / values.length;
      const max = Math.max(...values);
      const min = Math.min(...values);
      const variance = values.reduce((acc: number, v: number) => acc + Math.pow(v - mean, 2), 0) / values.length;
      const stdDev = Math.sqrt(variance);

      analyses.push(`
        <h3>传感器 ${sensorId.slice(0, 8)} 数据分析</h3>
        <p>共采集 ${values.length} 个数据点。</p>
        <ul>
          <li>均值: ${mean.toFixed(4)}</li>
          <li>最大值: ${max.toFixed(4)}</li>
          <li>最小值: ${min.toFixed(4)}</li>
          <li>标准差: ${stdDev.toFixed(4)}</li>
          <li>方差: ${variance.toFixed(6)}</li>
        </ul>
      `);
    }

    return analyses.join('\n');
  }

  private buildErrorAnalysisSection(data: HTMLReportData): string {
    const { config } = data;
    return `
      <h3>数值误差</h3>
      <p>数值积分使用时间步长 Δt = ${config.timeStep} s。截断误差为 O(Δt${config.solverType === 'verlet' ? '²' : ''})。</p>
      <h3>舍入误差</h3>
      <p>所有计算使用双精度浮点数（IEEE 754），机器精度约为 ε ≈ 2.2 × 10⁻¹⁶。</p>
      <h3>误差控制</h3>
      <ul>
        <li>动量和能量守恒监控</li>
        <li>约束求解迭代收敛检查</li>
        <li>碰撞检测精度阈值设置</li>
      </ul>
    `;
  }

  private buildChartsSection(charts: { dataUrl: string; caption: string }[]): string {
    return charts.map(chart => `
      <div class="chart-container">
        <img src="${chart.dataUrl}" alt="${this.escapeHTML(chart.caption)}" />
        <div class="chart-caption">${this.escapeHTML(chart.caption)}</div>
      </div>
    `).join('\n');
  }

  private getObjectTypeName(type: string): string {
    const names: Record<string, string> = {
      box: '方块', sphere: '球体', cylinder: '圆柱体', plane: '平面',
      incline: '斜面', charge: '电荷', magnet: '磁铁', spring: '弹簧', particle: '粒子',
    };
    return names[type] || type;
  }

  private escapeHTML(text: string): string {
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#039;');
  }
}

export default HTMLReportGenerator;
