export const EXPORT_FORMAT = {
  CSV: 'csv',
  JSON: 'json',
};

class ExportModule {
  constructor() {
    this.encoding = 'utf-8';
  }

  escapeCSVField(field) {
    if (field === null || field === undefined) {
      return '';
    }

    const strValue = String(field);

    if (
      strValue.includes(',') ||
      strValue.includes('"') ||
      strValue.includes('\n') ||
      strValue.includes('\r')
    ) {
      return `"${strValue.replace(/"/g, '""')}"`;
    }

    return strValue;
  }

  generateCSV(submissions, formConfig = null) {
    if (!submissions || submissions.length === 0) {
      return '';
    }

    const fieldMap = {};
    if (formConfig) {
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
        fieldMap[comp.component_id] = comp.label || comp.component_id;
      });
    }

    const allFieldIds = new Set();
    submissions.forEach(submission => {
      if (submission.data) {
        Object.keys(submission.data).forEach(key => {
          allFieldIds.add(key);
        });
      }
    });

    const fieldIds = Array.from(allFieldIds);

    const headers = ['提交ID', '提交时间', ...fieldIds.map(id => fieldMap[id] || id)];

    const rows = [];
    rows.push(headers.join(','));

    submissions.forEach(submission => {
      const row = [
        this.escapeCSVField(submission.submission_id),
        this.escapeCSVField(submission.submitted_at ? new Date(submission.submitted_at).toLocaleString('zh-CN') : ''),
      ];

      fieldIds.forEach(fieldId => {
        const value = submission.data?.[fieldId];
        let displayValue = '';

        if (value !== null && value !== undefined) {
          if (Array.isArray(value)) {
            displayValue = value.join('; ');
          } else if (typeof value === 'object') {
            displayValue = JSON.stringify(value);
          } else {
            displayValue = String(value);
          }
        }

        row.push(this.escapeCSVField(displayValue));
      });

      rows.push(row.join(','));
    });

    return rows.join('\n');
  }

  generateJSON(submissions, prettyPrint = true) {
    if (prettyPrint) {
      return JSON.stringify(submissions, null, 2);
    }
    return JSON.stringify(submissions);
  }

  downloadFile(content, filename, mimeType) {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);

    const link = document.createElement('a');
    link.href = url;
    link.download = filename;

    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);

    URL.revokeObjectURL(url);
  }

  exportCSV(submissions, formConfig = null, filename = 'data.csv') {
    const csvContent = this.generateCSV(submissions, formConfig);
    const bom = '\uFEFF';
    this.downloadFile(
      bom + csvContent,
      filename.endsWith('.csv') ? filename : `${filename}.csv`,
      'text/csv;charset=utf-8'
    );
    return true;
  }

  exportJSON(submissions, filename = 'data.json', prettyPrint = true) {
    const jsonContent = this.generateJSON(submissions, prettyPrint);
    this.downloadFile(
      jsonContent,
      filename.endsWith('.json') ? filename : `${filename}.json`,
      'application/json;charset=utf-8'
    );
    return true;
  }

  export(submissions, format, options = {}) {
    const { formConfig = null, filename = 'data', prettyPrint = true } = options;

    const timestamp = new Date().toISOString().slice(0, 10);
    const finalFilename = `${filename}_${timestamp}`;

    switch (format) {
      case EXPORT_FORMAT.CSV:
        return this.exportCSV(submissions, formConfig, `${finalFilename}.csv`);
      case EXPORT_FORMAT.JSON:
        return this.exportJSON(submissions, `${finalFilename}.json`, prettyPrint);
      default:
        throw new Error(`Unsupported export format: ${format}`);
    }
  }
}

export const exportModule = new ExportModule();

export default ExportModule;
