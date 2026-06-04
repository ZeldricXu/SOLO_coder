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

export const REPORT_TEMPLATE = `
\\documentclass{article}
\\usepackage{amsmath}
\\usepackage{graphicx}
\\usepackage{booktabs}
\\usepackage{siunitx}
\\title{<<title>>}
\\author{<<author>>}
\\date{<<date>>}
\\begin{document}
\\maketitle
\\begin{abstract}
<<abstract>>
\\end{abstract}
<<sections>>
<<figures>>
<<tables>>
\\section{Conclusion}
<<conclusion>>
\\end{document}
`;
