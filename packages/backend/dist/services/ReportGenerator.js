import { MATERIALS } from '@physics-sim/shared';
import { exec } from 'child_process';
import { promises as fs } from 'fs';
import path from 'path';
import os from 'os';
const LATEX_TEMPLATE = `\\documentclass[12pt,a4paper]{article}
\\usepackage[UTF8]{ctex}
\\usepackage{geometry}
\\usepackage{graphicx}
\\usepackage{booktabs}
\\usepackage{amsmath}
\\usepackage{amssymb}
\\usepackage{float}
\\usepackage{subcaption}
\\usepackage{hyperref}
\\usepackage{listings}
\\usepackage{xcolor}

\\geometry{margin=2.5cm}
\\hypersetup{colorlinks=true, linkcolor=blue, citecolor=blue, urlcolor=blue}

\\title{<<TITLE>>}
\\author{<<AUTHOR>>}
\\date{<<DATE>>}

\\begin{document}

\\maketitle

\\tableofcontents
\\newpage

<<CONTENT>>

\\end{document}`;
export class ReportGenerator {
    constructor() {
        this.tempDir = path.join(os.tmpdir(), 'physics-sim-reports');
    }
    async ensureTempDir() {
        try {
            await fs.access(this.tempDir);
        }
        catch {
            await fs.mkdir(this.tempDir, { recursive: true });
        }
    }
    async generateLaTeX(data) {
        await this.ensureTempDir();
        const content = this.buildContent(data);
        const title = data.title || '物理仿真实验报告';
        const author = data.author || '物理仿真平台';
        const date = data.date || new Date().toLocaleDateString('zh-CN');
        let latex = LATEX_TEMPLATE
            .replace('<<TITLE>>', this.escapeLaTeX(title))
            .replace('<<AUTHOR>>', this.escapeLaTeX(author))
            .replace('<<DATE>>', this.escapeLaTeX(date))
            .replace('<<CONTENT>>', content);
        return latex;
    }
    buildContent(data) {
        const sections = [];
        if (data.description || data.experimentPurpose) {
            sections.push(this.buildSection('实验描述', `
        ${data.description ? `\\subsection{实验简介}\n${this.escapeLaTeX(data.description)}\n` : ''}
        ${data.experimentPurpose ? `\\subsection{实验目的}\n${this.escapeLaTeX(data.experimentPurpose)}\n` : ''}
      `));
        }
        sections.push(this.buildSection('实验原理', this.buildTheorySection(data)));
        sections.push(this.buildSection('实验装置', this.buildSetupSection(data)));
        sections.push(this.buildSection('仿真参数', this.buildParametersSection(data)));
        sections.push(this.buildSection('实验结果', this.buildResultsSection(data)));
        sections.push(this.buildSection('数据分析', this.buildAnalysisSection(data)));
        if (data.conclusion) {
            sections.push(this.buildSection('结论', this.escapeLaTeX(data.conclusion)));
        }
        sections.push(this.buildSection('误差分析', this.buildErrorAnalysisSection(data)));
        return sections.join('\n\n');
    }
    buildSection(title, content) {
        return `\\section{${this.escapeLaTeX(title)}}\n\n${content.trim()}\n`;
    }
    buildTheorySection(data) {
        const config = data.config;
        const theories = [];
        if (config.physicsTypes.includes('mechanics')) {
            theories.push(`
        \\subsection{力学原理}
        本仿真基于牛顿力学和刚体动力学。使用Verlet积分进行数值积分，GJK+EPA算法进行碰撞检测，
        位置基动力学(PBD)处理约束。
        
        牛顿第二定律：
        \\begin{equation}
        \\vec{F} = m\\vec{a}
        \\end{equation}
        
        Verlet积分公式：
        \\begin{equation}
        \\vec{x}_{t+\\Delta t} = 2\\vec{x}_t - \\vec{x}_{t-\\Delta t} + \\vec{a}_t\\Delta t^2
        \\end{equation}
      `);
        }
        if (config.physicsTypes.includes('electromagnetics')) {
            theories.push(`
        \\subsection{电磁学原理}
        静电场求解基于泊松方程，使用有限差分法(FDM)离散求解。
        
        泊松方程：
        \\begin{equation}
        \\nabla^2\\varphi = -\\frac{\\rho}{\\varepsilon_0}
        \\end{equation}
        
        库仑定律：
        \\begin{equation}
        \\vec{F} = \\frac{1}{4\\pi\\varepsilon_0}\\frac{q_1q_2}{r^2}\\hat{r}
        \\end{equation}
      `);
        }
        if (config.physicsTypes.includes('thermodynamics')) {
            theories.push(`
        \\subsection{热力学原理}
        热传导求解基于热扩散方程，使用Crank-Nicolson隐式格式。
        
        热扩散方程：
        \\begin{equation}
        \\rho c_p\\frac{\\partial T}{\\partial t} = \\nabla\\cdot(k\\nabla T) + Q
        \\end{equation}
        
        傅里叶热传导定律：
        \\begin{equation}
        \\vec{q} = -k\\nabla T
        \\end{equation}
      `);
        }
        return theories.join('\n\n');
    }
    buildSetupSection(data) {
        const objects = Array.from(data.scene.objects.values());
        const tableData = {
            headers: ['物体ID', '类型', '位置', '材料', '是否静态'],
            rows: objects.map((obj) => [
                obj.id.slice(0, 8),
                this.getObjectTypeName(obj.objectType),
                `(${obj.position.x.toFixed(2)}, ${obj.position.y.toFixed(2)}, ${obj.position.z.toFixed(2)})`,
                MATERIALS[obj.materialId]?.name || obj.materialId,
                obj.isStatic ? '是' : '否',
            ]),
            caption: '实验装置物体列表',
            label: 'tab:objects',
        };
        return `
      本次实验包含 ${objects.length} 个物理对象。具体配置见表~\\ref{tab:objects}。
      
      ${this.buildTable(tableData)}
    `;
    }
    buildParametersSection(data) {
        const { config, scene } = data;
        const tableData = {
            headers: ['参数名称', '数值', '单位'],
            rows: [
                ['仿真时长', config.duration.toString(), 's'],
                ['时间步长', config.timeStep.toString(), 's'],
                ['重力加速度', scene.gravity.y.toString(), 'm/s²'],
                ['碰撞迭代次数', config.collisionIterations?.toString() || '默认', '次'],
                ['约束迭代次数', config.constraintIterations?.toString() || '默认', '次'],
                ['物理类型', config.physicsTypes.join(', '), '-'],
                ['求解器类型', config.solverType || '默认', '-'],
            ],
            caption: '仿真参数设置',
            label: 'tab:parameters',
        };
        return `
      本次仿真使用的参数配置见表~\\ref{tab:parameters}。
      
      ${this.buildTable(tableData)}
    `;
    }
    buildResultsSection(data) {
        const { result } = data;
        if (!result || !result.statistics) {
            return '暂无实验结果数据。';
        }
        const stats = result.statistics;
        const tableData = {
            headers: ['指标', '数值', '单位'],
            rows: [
                ['总仿真步数', stats.totalSteps.toString(), '步'],
                ['渲染帧数', stats.framesRendered.toString(), '帧'],
                ['计算耗时', stats.computationTime.toFixed(3), 's'],
                ['每秒步数', stats.stepsPerSecond.toFixed(1), '步/s'],
                ['实时因子', stats.realTimeFactor.toFixed(2), '×'],
            ],
            caption: '仿真性能统计',
            label: 'tab:performance',
        };
        return `
      仿真已成功完成。性能统计见表~\\ref{tab:performance}。
      
      ${this.buildTable(tableData)}
      
      仿真生成了 ${result.frames?.length || 0} 帧数据，包含 ${Object.keys(result.sensorData || {}).length} 个传感器的测量记录。
    `;
    }
    buildAnalysisSection(data) {
        const sensorData = data.result?.sensorData || {};
        const sensorCount = Object.keys(sensorData).length;
        if (sensorCount === 0) {
            return '本次实验未配置传感器，建议在场景中添加传感器以进行数据分析。';
        }
        const analyses = [`本次实验共配置 ${sensorCount} 个传感器，记录了丰富的实验数据。`];
        for (const [sensorId, records] of Object.entries(sensorData)) {
            const dataArray = records;
            if (dataArray.length < 2)
                continue;
            const values = dataArray.map((d) => typeof d.value === 'number' ? d.value :
                Math.sqrt(d.value.x ** 2 + d.value.y ** 2 + d.value.z ** 2));
            const mean = values.reduce((a, b) => a + b, 0) / values.length;
            const max = Math.max(...values);
            const min = Math.min(...values);
            const variance = values.reduce((acc, v) => acc + Math.pow(v - mean, 2), 0) / values.length;
            const stdDev = Math.sqrt(variance);
            analyses.push(`
        \\subsection{传感器 ${sensorId.slice(0, 8)} 数据分析}
        共采集 ${values.length} 个数据点。统计分析结果：
        \\begin{itemize}
        \\item 均值: $\\bar{x} = ${mean.toFixed(4)}$
        \\item 最大值: $x_{\\text{max}} = ${max.toFixed(4)}$
        \\item 最小值: $x_{\\text{min}} = ${min.toFixed(4)}$
        \\item 标准差: $\\sigma = ${stdDev.toFixed(4)}$
        \\item 方差: $\\sigma^2 = ${variance.toFixed(6)}$
        \\end{itemize}
      `);
        }
        return analyses.join('\n\n');
    }
    buildErrorAnalysisSection(data) {
        const { config } = data;
        return `
      \\subsection{数值误差}
      数值积分使用时间步长 $\\Delta t = ${config.timeStep}$ s。
      截断误差为 $O(\\Delta t^${config.solverType === 'verlet' ? '2' : '1'})$。
      
      \\subsection{舍入误差}
      所有计算使用双精度浮点数（IEEE 754），机器精度约为 $\\varepsilon \\approx 2.2 \\times 10^{-16}$。
      
      \\subsection{累积误差}
      经过 ${data.result?.statistics?.totalSteps || 0} 步积分后，
      位置累积误差约为 $O(\\Delta t \\times \\text{步数})$。
      
      \\subsection{误差控制}
      本仿真采用以下误差控制措施：
      \\begin{itemize}
      \\item 动量和能量守恒监控
      \\item 约束求解迭代收敛检查
      \\item 碰撞检测精度阈值设置
      \\end{itemize}
    `;
    }
    buildTable(table) {
        const colSpec = '|' + table.headers.map(() => 'c|').join('');
        const headerRow = table.headers.map(h => this.escapeLaTeX(h)).join(' & ') + ' \\\\ \\hline';
        const bodyRows = table.rows.map(row => row.map(cell => this.escapeLaTeX(cell)).join(' & ') + ' \\\\ \\hline').join('\n');
        return `
      \\begin{table}[H]
      \\centering
      \\begin{tabular}{${colSpec}}
      \\hline
      ${headerRow}
      ${bodyRows}
      \\end{tabular}
      ${table.caption ? `\\caption{${this.escapeLaTeX(table.caption)}}` : ''}
      ${table.label ? `\\label{${table.label}}` : ''}
      \\end{table}
    `;
    }
    buildFigure(figure) {
        return `
      \\begin{figure}[H]
      \\centering
      \\includegraphics[width=${figure.width || '0.8\\linewidth'}]{${figure.filename}}
      \\caption{${this.escapeLaTeX(figure.caption)}}
      \\label{${figure.label}}
      \\end{figure}
    `;
    }
    getObjectTypeName(type) {
        const names = {
            box: '方块',
            sphere: '球体',
            cylinder: '圆柱体',
            plane: '平面',
            incline: '斜面',
            charge: '电荷',
            magnet: '磁铁',
            spring: '弹簧',
            particle: '粒子',
        };
        return names[type] || type;
    }
    escapeLaTeX(text) {
        return text
            .replace(/\\/g, '\\\\')
            .replace(/\{/g, '\\{')
            .replace(/\}/g, '\\}')
            .replace(/\$/g, '\\$')
            .replace(/&/g, '\\&')
            .replace(/#/g, '\\#')
            .replace(/_/g, '\\_')
            .replace(/%/g, '\\%')
            .replace(/\^/g, '\\textasciicircum{}')
            .replace(/~/g, '\\textasciitilde{}');
    }
    async saveLaTeX(latex, filename) {
        await this.ensureTempDir();
        const filePath = path.join(this.tempDir, filename);
        await fs.writeFile(filePath, latex, 'utf-8');
        return filePath;
    }
    async compileToPDF(latexPath) {
        return new Promise((resolve, reject) => {
            const dir = path.dirname(latexPath);
            const fileName = path.basename(latexPath, '.tex');
            exec(`xelatex -interaction=nonstopmode -output-directory="${dir}" "${latexPath}"`, { timeout: 60000 }, (error, stdout, stderr) => {
                if (error) {
                    console.error('LaTeX compilation error:', stderr);
                    reject(new Error(`LaTeX compilation failed: ${error.message}`));
                    return;
                }
                const pdfPath = path.join(dir, `${fileName}.pdf`);
                resolve(pdfPath);
            });
        });
    }
    async generateFullReport(data) {
        const latex = await this.generateLaTeX(data);
        const latexPath = await this.saveLaTeX(latex, `report_${Date.now()}.tex`);
        try {
            const pdfPath = await this.compileToPDF(latexPath);
            return { latex, pdfPath };
        }
        catch (error) {
            console.warn('PDF compilation failed, returning LaTeX only:', error);
            return { latex };
        }
    }
}
export default ReportGenerator;
//# sourceMappingURL=ReportGenerator.js.map