import * as Y from 'yjs';
import { TableData, TableRowData, TableCellData, YjsTableState, FormulaConfig, FormulaResult, FormulaType } from './types';

function generateId(): string {
  return Math.random().toString(36).substring(2, 11);
}

export function createYjsTable(doc: Y.Doc, tableId: string, initialRows: number = 3, initialCols: number = 3, hasHeader: boolean = true): YjsTableState {
  const tablesMap = doc.getMap('tables');
  const tableMap = tablesMap.get(tableId) as Y.Map<any> || new Y.Map();
  
  if (!tablesMap.has(tableId)) {
    tablesMap.set(tableId, tableMap);
  }

  let rowsArray = tableMap.get('rows') as Y.Array<Y.Map<any>>;
  if (!rowsArray) {
    rowsArray = new Y.Array();
    tableMap.set('rows', rowsArray);
    tableMap.set('id', tableId);
    tableMap.set('hasHeader', hasHeader);

    for (let i = 0; i < initialRows; i++) {
      const rowMap = createYjsRow(initialCols, i === 0 && hasHeader);
      rowsArray.push([rowMap]);
    }
  }

  return {
    tableId,
    rows: rowsArray,
    getRow: (rowId: string) => rowsArray.toArray().find(r => r.get('id') === rowId),
    getCell: (rowId: string, cellId: string) => {
      const row = rowsArray.toArray().find(r => r.get('id') === rowId);
      if (!row) return undefined;
      const cells = row.get('cells') as Y.Array<Y.Map<any>>;
      return cells?.toArray().find(c => c.get('id') === cellId);
    },
    toJSON: () => yjsTableToJSON(tableMap),
  };
}

function createYjsRow(cols: number, isHeaderRow: boolean): Y.Map<any> {
  const rowMap = new Y.Map();
  const cellsArray = new Y.Array<Y.Map<any>>();
  
  rowMap.set('id', generateId());
  rowMap.set('cells', cellsArray);
  
  for (let j = 0; j < cols; j++) {
    const cellMap = createYjsCell('', isHeaderRow);
    cellsArray.push([cellMap]);
  }
  
  return rowMap;
}

function createYjsCell(content: string, isHeader: boolean): Y.Map<any> {
  const cellMap = new Y.Map();
  cellMap.set('id', generateId());
  cellMap.set('content', content);
  cellMap.set('isHeader', isHeader);
  cellMap.set('rowSpan', 1);
  cellMap.set('colSpan', 1);
  cellMap.set('merged', false);
  return cellMap;
}

export function insertRow(state: YjsTableState, index: number, after: boolean = true): void {
  const rows = state.rows;
  const targetIndex = after ? index + 1 : index;
  
  if (targetIndex < 0 || targetIndex > rows.length) {
    throw new Error('Invalid row index');
  }

  const rowCount = rows.length;
  if (rowCount === 0) {
    throw new Error('Table is empty');
  }

  const referenceRow = rows.get(Math.min(index, rowCount - 1)) as Y.Map<any>;
  const cellsCount = (referenceRow.get('cells') as Y.Array<Y.Map<any>>).length;
  const newRow = createYjsRow(cellsCount, false);
  
  rows.insert(targetIndex, [newRow]);
}

export function deleteRow(state: YjsTableState, index: number): void {
  const rows = state.rows;
  if (rows.length <= 1) {
    throw new Error('Cannot delete the last row');
  }
  if (index < 0 || index >= rows.length) {
    throw new Error('Invalid row index');
  }
  rows.delete(index);
}

export function insertColumn(state: YjsTableState, index: number, after: boolean = true): void {
  const rows = state.rows;
  const targetIndex = after ? index + 1 : index;
  
  const rowCount = rows.length;
  if (rowCount === 0) {
    throw new Error('Table is empty');
  }

  const firstRow = rows.get(0) as Y.Map<any>;
  const cellsCount = (firstRow.get('cells') as Y.Array<Y.Map<any>>).length;
  
  if (targetIndex < 0 || targetIndex > cellsCount) {
    throw new Error('Invalid column index');
  }

  for (let i = 0; i < rowCount; i++) {
    const row = rows.get(i) as Y.Map<any>;
    const cells = row.get('cells') as Y.Array<Y.Map<any>>;
    const isHeader = i === 0 && (row.parent as Y.Map<any>).get('hasHeader');
    const newCell = createYjsCell('', isHeader);
    cells.insert(targetIndex, [newCell]);
  }
}

export function deleteColumn(state: YjsTableState, index: number): void {
  const rows = state.rows;
  const rowCount = rows.length;
  
  if (rowCount === 0) {
    throw new Error('Table is empty');
  }

  const firstRow = rows.get(0) as Y.Map<any>;
  const cellsCount = (firstRow.get('cells') as Y.Array<Y.Map<any>>).length;
  
  if (cellsCount <= 1) {
    throw new Error('Cannot delete the last column');
  }
  if (index < 0 || index >= cellsCount) {
    throw new Error('Invalid column index');
  }

  for (let i = 0; i < rowCount; i++) {
    const row = rows.get(i) as Y.Map<any>;
    const cells = row.get('cells') as Y.Array<Y.Map<any>>;
    cells.delete(index);
  }
}

export function updateCell(state: YjsTableState, rowIndex: number, colIndex: number, content: string): void {
  const rows = state.rows;
  
  if (rowIndex < 0 || rowIndex >= rows.length) {
    throw new Error('Invalid row index');
  }

  const row = rows.get(rowIndex) as Y.Map<any>;
  const cells = row.get('cells') as Y.Array<Y.Map<any>>;
  
  if (colIndex < 0 || colIndex >= cells.length) {
    throw new Error('Invalid column index');
  }

  const cell = cells.get(colIndex) as Y.Map<any>;
  cell.set('content', content);
}

export function mergeCells(state: YjsTableState, startRow: number, startCol: number, endRow: number, endCol: number): void {
  const rows = state.rows;
  
  if (startRow < 0 || startCol < 0 || endRow >= rows.length || startRow > endRow || startCol > endCol) {
    throw new Error('Invalid merge range');
  }

  const firstRow = rows.get(0) as Y.Map<any>;
  const cellsCount = (firstRow.get('cells') as Y.Array<Y.Map<any>>).length;
  
  if (endCol >= cellsCount) {
    throw new Error('Invalid end column');
  }

  const mainCell = (rows.get(startRow).get('cells') as Y.Array<Y.Map<any>>).get(startCol) as Y.Map<any>;
  
  let mergedContent = '';
  for (let r = startRow; r <= endRow; r++) {
    const row = rows.get(r) as Y.Map<any>;
    const cells = row.get('cells') as Y.Array<Y.Map<any>>;
    for (let c = startCol; c <= endCol; c++) {
      const cell = cells.get(c) as Y.Map<any>;
      const content = cell.get('content') as string;
      if (content) {
        mergedContent += (mergedContent ? ' ' : '') + content;
      }
    }
  }

  mainCell.set('content', mergedContent);
  mainCell.set('rowSpan', endRow - startRow + 1);
  mainCell.set('colSpan', endCol - startCol + 1);
  mainCell.set('merged', false);

  for (let r = startRow; r <= endRow; r++) {
    const row = rows.get(r) as Y.Map<any>;
    const cells = row.get('cells') as Y.Array<Y.Map<any>>;
    for (let c = startCol; c <= endCol; c++) {
      if (!(r === startRow && c === startCol)) {
        const cell = cells.get(c) as Y.Map<any>;
        cell.set('merged', true);
        cell.set('mergeTarget', mainCell.get('id'));
        cell.set('content', '');
      }
    }
  }
}

export function splitCell(state: YjsTableState, rowIndex: number, colIndex: number): void {
  const rows = state.rows;
  const row = rows.get(rowIndex) as Y.Map<any>;
  const cells = row.get('cells') as Y.Array<Y.Map<any>>;
  const cell = cells.get(colIndex) as Y.Map<any>;

  if (!cell) {
    throw new Error('Cell not found');
  }

  const rowSpan = cell.get('rowSpan') as number || 1;
  const colSpan = cell.get('colSpan') as number || 1;

  if (rowSpan === 1 && colSpan === 1) {
    return;
  }

  const endRow = rowIndex + rowSpan - 1;
  const endCol = colIndex + colSpan - 1;

  for (let r = rowIndex; r <= endRow; r++) {
    const rRow = rows.get(r) as Y.Map<any>;
    const rCells = rRow.get('cells') as Y.Array<Y.Map<any>>;
    for (let c = colIndex; c <= endCol; c++) {
      const rCell = rCells.get(c) as Y.Map<any>;
      rCell.set('rowSpan', 1);
      rCell.set('colSpan', 1);
      rCell.set('merged', false);
      rCell.set('mergeTarget', null);
    }
  }
}

export function toggleHeader(state: YjsTableState, tableMap: Y.Map<any>): void {
  const hasHeader = tableMap.get('hasHeader') as boolean;
  const newHasHeader = !hasHeader;
  tableMap.set('hasHeader', newHasHeader);

  const rows = state.rows;
  if (rows.length === 0) return;

  const firstRow = rows.get(0) as Y.Map<any>;
  const cells = firstRow.get('cells') as Y.Array<Y.Map<any>>;
  
  for (let i = 0; i < cells.length; i++) {
    const cell = cells.get(i) as Y.Map<any>;
    cell.set('isHeader', newHasHeader);
  }
}

export function calculateFormula(data: TableData, config: FormulaConfig): FormulaResult {
  const { type, targetColumn } = config;
  const dataRows = data.hasHeader ? data.rows.slice(1) : data.rows;
  
  const values = dataRows
    .map(row => parseFloat(row.cells[targetColumn]?.content || ''))
    .filter(v => !isNaN(v));

  let result = 0;
  switch (type) {
    case 'SUM':
      result = values.reduce((a, b) => a + b, 0);
      break;
    case 'AVERAGE':
      result = values.length > 0 ? values.reduce((a, b) => a + b, 0) / values.length : 0;
      break;
    case 'MAX':
      result = values.length > 0 ? Math.max(...values) : 0;
      break;
    case 'MIN':
      result = values.length > 0 ? Math.min(...values) : 0;
      break;
    case 'COUNT':
      result = values.length;
      break;
  }

  return {
    formula: type,
    value: result,
    displayValue: type === 'AVERAGE' ? result.toFixed(2) : result.toString(),
  };
}

export function applyFormula(state: YjsTableState, tableMap: Y.Map<any>, config: FormulaConfig): void {
  const data = yjsTableToJSON(tableMap);
  const result = calculateFormula(data, config);
  
  const rows = state.rows;
  const targetRowIndex = config.resultRowIndex ?? rows.length - 1;
  
  if (targetRowIndex < 0 || targetRowIndex >= rows.length) {
    throw new Error('Invalid result row index');
  }

  const targetRow = rows.get(targetRowIndex) as Y.Map<any>;
  const cells = targetRow.get('cells') as Y.Array<Y.Map<any>>;
  
  if (config.targetColumn >= cells.length) {
    throw new Error('Invalid target column');
  }

  const targetCell = cells.get(config.targetColumn) as Y.Map<any>;
  targetCell.set('content', result.displayValue);
}

export function yjsTableToJSON(tableMap: Y.Map<any>): TableData {
  const rowsArray = tableMap.get('rows') as Y.Array<Y.Map<any>>;
  const rows: TableRowData[] = [];
  
  rowsArray.forEach((rowMap: Y.Map<any>) => {
    const cellsArray = rowMap.get('cells') as Y.Array<Y.Map<any>>;
    const cells: TableCellData[] = [];
    
    cellsArray.forEach((cellMap: Y.Map<any>) => {
      cells.push({
        id: cellMap.get('id'),
        content: cellMap.get('content'),
        isHeader: cellMap.get('isHeader'),
        merged: cellMap.get('merged'),
        mergeTarget: cellMap.get('mergeTarget'),
        rowSpan: cellMap.get('rowSpan'),
        colSpan: cellMap.get('colSpan'),
      });
    });
    
    rows.push({
      id: rowMap.get('id'),
      cells,
    });
  });

  return {
    id: tableMap.get('id'),
    rows,
    hasHeader: tableMap.get('hasHeader') ?? false,
  };
}

export function tableDataToYjsTable(doc: Y.Doc, tableId: string, data: TableData): YjsTableState {
  const tablesMap = doc.getMap('tables');
  const tableMap = new Y.Map();
  
  tablesMap.set(tableId, tableMap);
  tableMap.set('id', tableId);
  tableMap.set('hasHeader', data.hasHeader);
  
  const rowsArray = new Y.Array<Y.Map<any>>();
  tableMap.set('rows', rowsArray);
  
  for (const rowData of data.rows) {
    const rowMap = new Y.Map();
    rowMap.set('id', rowData.id);
    
    const cellsArray = new Y.Array<Y.Map<any>>();
    rowMap.set('cells', cellsArray);
    
    for (const cellData of rowData.cells) {
      const cellMap = createYjsCell(cellData.content, cellData.isHeader);
      if (cellData.rowSpan) cellMap.set('rowSpan', cellData.rowSpan);
      if (cellData.colSpan) cellMap.set('colSpan', cellData.colSpan);
      if (cellData.merged) cellMap.set('merged', cellData.merged);
      if (cellData.mergeTarget) cellMap.set('mergeTarget', cellData.mergeTarget);
      cellsArray.push([cellMap]);
    }
    
    rowsArray.push([rowMap]);
  }
  
  return {
    tableId,
    rows: rowsArray,
    getRow: (rowId: string) => rowsArray.toArray().find(r => r.get('id') === rowId),
    getCell: (rowId: string, cellId: string) => {
      const row = rowsArray.toArray().find(r => r.get('id') === rowId);
      if (!row) return undefined;
      const cells = row.get('cells') as Y.Array<Y.Map<any>>;
      return cells?.toArray().find(c => c.get('id') === cellId);
    },
    toJSON: () => yjsTableToJSON(tableMap),
  };
}

export function observeTable(state: YjsTableState, callback: (data: TableData) => void): () => void {
  const handler = () => {
    const tablesMap = (state.rows.parent as Y.Map<any>).parent as Y.Map<any>;
    const tableMap = tablesMap.get(state.tableId) as Y.Map<any>;
    callback(yjsTableToJSON(tableMap));
  };
  
  state.rows.observeDeep(handler);
  return () => state.rows.unobserveDeep(handler);
}
