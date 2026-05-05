export interface Field {
  field_id: string;
  field_name: string;
  field_type: 'single_choice' | 'multiple_choice' | 'numeric' | 'text' | 'date';
  options?: string[];
  range?: number[];
}

export interface SurveyData {
  survey_id: string;
  survey_name: string;
  total_responses: number;
  fields: Field[];
  imported_at: string;
  file_path: string;
}

export interface FrequencyItem {
  value: string;
  count: number;
  percentage: number;
}

export interface FrequencyResult {
  field_id: string;
  field_name: string;
  frequencies: FrequencyItem[];
  total_valid: number;
  missing_count: number;
}

export interface DescriptiveStats {
  field_id: string;
  field_name: string;
  count: number;
  mean: number;
  median: number;
  std: number;
  min: number;
  max: number;
  q25: number;
  q75: number;
}

export interface SignificanceResult {
  test_type: string;
  p_value: number;
  significant: boolean;
  details?: Record<string, any>;
}

export interface CrossTableCell {
  row: string;
  col_values: Record<string, any>;
}

export interface CrossAnalysisResult {
  analysis_id: string;
  survey_id: string;
  variables: string[];
  cross_table: CrossTableCell[];
  significance?: SignificanceResult;
  chart_config: ChartConfig;
}

export interface ChartConfig {
  type: string;
  data: any[];
  title?: string;
  subtitle?: string;
  xField?: string;
  yField?: string;
  seriesField?: string;
  colorField?: string;
  angleField?: string;
  stack?: boolean;
  style?: Record<string, any>;
  tooltip?: {
    fields?: string[];
  };
}

export interface ReportSection {
  section_type: string;
  title: string;
  content: string;
  chart_config?: ChartConfig;
  data?: Record<string, any>;
}

export interface Report {
  report_id: string;
  survey_id: string;
  title: string;
  created_at: string;
  sections: ReportSection[];
}

export interface ReportPreview {
  report_id: string;
  title: string;
  created_at: string;
  survey_id: string;
  sections: {
    section_type: string;
    title: string;
    content_preview: string;
    has_chart: boolean;
    has_data: boolean;
  }[];
  toc: {
    section_number: number;
    title: string;
    type: string;
  }[];
}

export interface StatisticsData {
  survey_id: string;
  survey_name: string;
  total_responses: number;
  statistics: {
    field_id: string;
    field_name: string;
    field_type: string;
    type: 'frequency' | 'descriptive';
    data: FrequencyResult | DescriptiveStats;
  }[];
}

export interface ApiResponse<T> {
  code: number;
  message?: string;
  data?: T;
}

export interface ImportResult {
  survey_id: string;
  survey_name: string;
  total_records: number;
  valid_records: number;
  invalid_records: number;
  fields: Field[];
  validation_errors?: ValidationError[];
}

export interface ValidationError {
  column?: string;
  field_id?: string;
  message: string;
  row: number;
}

export interface SurveyPreview {
  survey_id: string;
  fields: Field[];
  preview_data: Record<string, any>[];
  total_rows: number;
}
