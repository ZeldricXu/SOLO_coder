import * as Y from 'yjs';

export interface TableCellData {
  id: string;
  content: string;
  isHeader: boolean;
  merged?: boolean;
  mergeTarget?: string;
  rowSpan?: number;
  colSpan?: number;
}

export interface TableRowData {
  id: string;
  cells: TableCellData[];
}

export interface TableData {
  id: string;
  rows: TableRowData[];
  hasHeader: boolean;
}

export interface YjsTableState {
  tableId: string;
  rows: Y.Array<Y.Map<any>>;
  getRow: (rowId: string) => Y.Map<any> | undefined;
  getCell: (rowId: string, cellId: string) => Y.Map<any> | undefined;
  toJSON: () => TableData;
}

export interface TableOperation {
  type: 'insertRow' | 'deleteRow' | 'insertColumn' | 'deleteColumn' | 
        'mergeCells' | 'splitCell' | 'toggleHeader' | 'updateCell';
  rowIndex?: number;
  colIndex?: number;
  cellId?: string;
  content?: string;
}

export type FormulaType = 'SUM' | 'AVERAGE' | 'MAX' | 'MIN' | 'COUNT';

export interface FormulaConfig {
  type: FormulaType;
  targetColumn: number;
  resultRowIndex?: number;
}

export interface FormulaResult {
  formula: FormulaType;
  value: number;
  displayValue: string;
}
