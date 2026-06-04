import { Scene } from './scene';
import { SensorData, DataAnalysisResult, FFTSpectrum, CurveFitResult } from './sensors';
export interface ReportSection {
    id: string;
    title: string;
    type: 'text' | 'table' | 'figure' | 'equation';
    content: string;
}
export interface ReportFigure {
    id: string;
    title: string;
    caption: string;
    sensorData: SensorData[];
    analysis?: DataAnalysisResult;
    fft?: FFTSpectrum;
    curveFit?: CurveFitResult;
}
export interface ReportTable {
    id: string;
    title: string;
    headers: string[];
    rows: string[][];
}
export interface ExperimentReport {
    id: string;
    title: string;
    author: string;
    date: string;
    abstract: string;
    scene: Scene;
    sections: ReportSection[];
    figures: ReportFigure[];
    tables: ReportTable[];
    conclusion: string;
    references: string[];
    latexSource?: string;
}
export declare const REPORT_TEMPLATE = "\n\\documentclass{article}\n\\usepackage{amsmath}\n\\usepackage{graphicx}\n\\usepackage{booktabs}\n\\usepackage{siunitx}\n\\title{<<title>>}\n\\author{<<author>>}\n\\date{<<date>>}\n\\begin{document}\n\\maketitle\n\\begin{abstract}\n<<abstract>>\n\\end{abstract}\n<<sections>>\n<<figures>>\n<<tables>>\n\\section{Conclusion}\n<<conclusion>>\n\\end{document}\n";
//# sourceMappingURL=report.d.ts.map