const CrossOriginIsolated = typeof SharedArrayBuffer !== 'undefined';
if (!CrossOriginIsolated) {
    console.warn('[DataExplorer] SharedArrayBuffer not available - multi-threading disabled');
    console.warn('[DataExplorer] Ensure COOP/COEP headers are set: Cross-Origin-Opener-Policy: same-origin, Cross-Origin-Embedder-Policy: require-corp');
}

const App = {
    tableData: null,
    currentTab: 'data',
    currentPage: 0,
    pageSize: 100,
    filters: [],
    charts: [],
    queryHistory: [],
    wasmReady: false,
    originalTableData: null,
    multiThreading: CrossOriginIsolated,

    init() {
        this.setupDropZone();
        this.setupTabs();
        this.setupQueryInput();
        this.setupIndexedDB();
        this.checkWasmReady();
        this.checkCrossOriginIsolation();
    },

    checkWasmReady() {
        const check = setInterval(() => {
            if (typeof wasmReady !== 'undefined' && wasmReady) {
                this.wasmReady = true;
                clearInterval(check);
                document.getElementById('loadingOverlay').classList.add('hidden');
                this.log('WASM module ready');
            }
        }, 100);
    },

    checkCrossOriginIsolation() {
        if (!this.multiThreading) {
            const el = document.getElementById('statusBar');
            if (el) {
                el.innerHTML += '<span class="status-item" style="color:var(--warning)">⚠️ Single-thread mode</span>';
            }
        }
    },

    log(msg) {
        console.log('[DataExplorer]', msg);
    },

    setupDropZone() {
        const zone = document.getElementById('dropZone');
        if (!zone) return;
        zone.addEventListener('dragover', (e) => { e.preventDefault(); zone.classList.add('dragover'); });
        zone.addEventListener('dragleave', () => zone.classList.remove('dragover'));
        zone.addEventListener('drop', (e) => {
            e.preventDefault();
            zone.classList.remove('dragover');
            const file = e.dataTransfer.files[0];
            if (file) this.loadFile(file);
        });
        zone.addEventListener('click', () => {
            const input = document.createElement('input');
            input.type = 'file';
            input.accept = '.csv,.json,.parquet';
            input.onchange = (e) => {
                if (e.target.files[0]) this.loadFile(e.target.files[0]);
            };
            input.click();
        });
    },

    setupTabs() {
        document.querySelectorAll('.tab-item').forEach(tab => {
            tab.addEventListener('click', () => {
                document.querySelectorAll('.tab-item').forEach(t => t.classList.remove('active'));
                document.querySelectorAll('.tab-panel').forEach(p => p.classList.add('hidden'));
                tab.classList.add('active');
                const panelId = tab.dataset.panel;
                document.getElementById(panelId).classList.remove('hidden');
                this.currentTab = tab.dataset.tab;
            });
        });
    },

    setupQueryInput() {
        const input = document.getElementById('queryInput');
        if (!input) return;
        input.addEventListener('keydown', (e) => {
            if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                this.executeNonQuery();
            }
        });
    },

    setupIndexedDB() {
        const request = indexedDB.open('DataExplorerDB', 1);
        request.onupgradeneeded = (e) => {
            const db = e.target.result;
            if (!db.objectStoreNames.contains('projects')) {
                db.createObjectStore('projects', { keyPath: 'id' });
            }
            if (!db.objectStoreNames.contains('files')) {
                db.createObjectStore('files', { keyPath: 'id' });
            }
        };
        request.onsuccess = (e) => {
            this.db = e.target.result;
            this.log('IndexedDB initialized');
        };
    },

    async loadFile(file) {
        this.showLoading('Parsing ' + file.name + '...');
        const ext = file.name.split('.').pop().toLowerCase();
        const format = ext === 'json' ? 'json' : ext === 'parquet' ? 'parquet' : 'csv';

        const reader = new FileReader();
        reader.onload = (e) => {
            const data = e.target.result;
            const result = parseData(data, format);
            const parsed = JSON.parse(result);

            if (parsed.success) {
                this.tableData = parsed;
                this.originalTableData = parsed;
                this.currentPage = 0;
                this.filters = [];
                this.updateUI();
                this.hideLoading();
                this.log(`Loaded ${parsed.rowCount} rows, ${parsed.colCount} columns`);
            } else {
                this.hideLoading();
                alert('Parse error: ' + (parsed.error || 'Unknown error'));
            }
        };
        reader.readAsText(file);
    },

    updateUI() {
        if (!this.tableData) return;
        this.renderDataPreview();
        this.renderColumnList();
        this.renderFilterList();
        this.renderChartConfig();
        this.renderPivotConfig();
        this.renderAnomalyConfig();
        this.updateStatusBar();
    },

    renderDataPreview() {
        const container = document.getElementById('dataTableBody');
        if (!container) return;

        const data = JSON.parse(getData(this.currentPage * this.pageSize, this.pageSize));
        const columns = this.tableData.columns;

        let html = '';
        data.forEach((row, idx) => {
            html += '<tr>';
            columns.forEach(col => {
                const val = row[col];
                let cls = '';
                if (val === null || val === undefined) cls = 'null-val';
                html += `<td class="${cls}" title="${val !== null && val !== undefined ? String(val) : 'null'}">${val !== null && val !== undefined ? val : 'NULL'}</td>`;
            });
            html += '</tr>';
        });
        container.innerHTML = html;

        const headerRow = document.getElementById('dataTableHeader');
        if (headerRow) {
            headerRow.innerHTML = columns.map((c, i) => {
                const type = this.tableData.types[i];
                const filtered = this.isColumnFiltered(c);
                const filteredClass = filtered ? ' filtered' : '';
                return `<th class="${filteredClass}" data-column="${c}" data-type="${type}" onclick="App.showFilterPanel('${c}', '${type}')">
                    ${c}
                    <span class="filter-icon">${filtered ? '●' : '⚙'}</span>
                </th>`;
            }).join('');
        }

        this.renderPagination();
    },

    isColumnFiltered(columnName) {
        return this.filters.some(f => f.column === columnName);
    },

    async showFilterPanel(columnName, columnType) {
        this.activeFilterColumn = columnName;
        this.activeFilterType = columnType;
        this.panelFilterState = {
            type: columnType,
            selectedValues: new Set(),
            rangeMin: null,
            rangeMax: null,
            includeNull: false,
            dateStart: null,
            dateEnd: null
        };

        const titleEl = document.getElementById('filterPanelTitle');
        if (titleEl) {
            titleEl.textContent = `Filter: ${columnName}`;
        }

        const overlay = document.getElementById('filterPanelOverlay');
        if (overlay) {
            overlay.classList.remove('hidden');
        }

        const body = document.getElementById('filterPanelBody');
        if (body) {
            body.innerHTML = '<div style="text-align:center;padding:20px;color:var(--text-muted)">Loading...</div>';
        }

        await this.loadFilterPanelContent(columnName, columnType);
    },

    async loadFilterPanelContent(columnName, columnType) {
        const body = document.getElementById('filterPanelBody');
        if (!body) return;

        try {
            const stats = JSON.parse(getColumnStats(columnName));
            
            if (columnType === 'int' || columnType === 'float') {
                const histogram = JSON.parse(getHistogram(columnName, 20));
                this.currentHistogram = histogram;
                this.renderNumericFilterPanel(body, columnName, stats, histogram);
            } else if (columnType === 'string' || columnType === 'bool') {
                const distribution = JSON.parse(getValueDistribution(columnName, 20));
                this.renderTextFilterPanel(body, columnName, stats, distribution);
            } else if (columnType === 'date') {
                this.renderDateFilterPanel(body, columnName, stats);
            } else {
                body.innerHTML = '<div style="padding:20px;color:var(--text-muted)">Filtering not supported for this column type</div>';
            }

            this.updateFilterPreview();
        } catch (e) {
            body.innerHTML = `<div style="padding:20px;color:var(--danger)">Error loading filter panel: ${e.message}</div>`;
        }
    },

    renderNumericFilterPanel(container, columnName, stats, histogram) {
        const minVal = histogram.min;
        const maxVal = histogram.max;
        
        this.panelFilterState.rangeMin = minVal;
        this.panelFilterState.rangeMax = maxVal;

        const maxCount = Math.max(...histogram.counts, 1);
        const barHeights = histogram.counts.map(c => (c / maxCount) * 100);

        let histogramHtml = '<div class="filter-histogram">';
        histogram.counts.forEach((count, i) => {
            const height = barHeights[i];
            histogramHtml += `<div class="filter-histogram-bar" style="height:${height}%" title="Bin ${i}: ${count} values"></div>`;
        });
        histogramHtml += '</div>';

        let html = `
            <div class="filter-section-title">Value Range</div>
            ${histogramHtml}
            <div class="filter-range-labels">
                <span>${this.formatNumber(minVal)}</span>
                <span>${this.formatNumber(maxVal)}</span>
            </div>
            <div class="filter-slider-container">
                <div style="position:relative">
                    <input type="range" id="filterRangeMin" min="${minVal}" max="${maxVal}" value="${minVal}" step="${this.calculateStep(minVal, maxVal)}" oninput="App.updateFilterRangeMin(this.value)">
                    <input type="range" id="filterRangeMax" min="${minVal}" max="${maxVal}" value="${maxVal}" step="${this.calculateStep(minVal, maxVal)}" oninput="App.updateFilterRangeMax(this.value)" style="position:absolute;top:0;left:0;pointer-events:none;background:transparent">
                </div>
                <div class="filter-slider-values">
                    <span class="filter-slider-value" id="filterRangeMinDisplay">${this.formatNumber(minVal)}</span>
                    <span class="filter-slider-value" id="filterRangeMaxDisplay">${this.formatNumber(maxVal)}</span>
                </div>
            </div>
            <div class="filter-null-option">
                <label>
                    <input type="checkbox" id="filterIncludeNull" onchange="App.toggleFilterIncludeNull(this.checked)">
                    Include NULL values
                </label>
            </div>
            <div class="filter-query-preview" id="filterQueryPreview"></div>
        `;

        container.innerHTML = html;
    },

    renderTextFilterPanel(container, columnName, stats, distribution) {
        const existingFilter = this.filters.find(f => f.column === columnName);
        const selected = new Set();
        
        if (existingFilter && existingFilter.operator === 'IN') {
            existingFilter.values.forEach(v => selected.add(v));
        }

        this.panelFilterState.selectedValues = selected;

        let html = `
            <div class="filter-section-title">Select Values (Top ${distribution.length})</div>
            <input type="text" class="filter-search-input" placeholder="Search values..." oninput="App.filterValueList(this.value)">
            <span class="filter-select-all" onclick="App.selectAllFilterValues()">Select All</span>
            <span class="filter-select-all" style="margin-left:12px" onclick="App.clearAllFilterValues()">Clear All</span>
            <div class="filter-value-list" id="filterValueList">
        `;

        distribution.forEach(item => {
            const displayValue = item.value === '__NULL__' ? 'NULL' : item.value;
            const isChecked = selected.has(item.value);
            html += `
                <label class="filter-value-item" data-value="${item.value}">
                    <input type="checkbox" ${isChecked ? 'checked' : ''} onchange="App.toggleFilterValue('${item.value.replace(/'/g, "\\'")}', this.checked)">
                    <span>${displayValue}</span>
                    <span class="filter-value-item-count">${item.count}</span>
                </label>
            `;
        });

        html += `
            </div>
            <div class="filter-query-preview" id="filterQueryPreview"></div>
        `;

        container.innerHTML = html;
    },

    renderDateFilterPanel(container, columnName, stats) {
        const minDate = stats.min ? new Date(stats.min * 1000).toISOString().split('T')[0] : '';
        const maxDate = stats.max ? new Date(stats.max * 1000).toISOString().split('T')[0] : '';

        this.panelFilterState.dateStart = minDate;
        this.panelFilterState.dateEnd = maxDate;

        let html = `
            <div class="filter-section-title">Date Range</div>
            <div class="filter-date-range">
                <input type="date" id="filterDateStart" value="${minDate}" min="${minDate}" max="${maxDate}" onchange="App.updateFilterDateStart(this.value)">
                <span class="filter-date-separator">to</span>
                <input type="date" id="filterDateEnd" value="${maxDate}" min="${minDate}" max="${maxDate}" onchange="App.updateFilterDateEnd(this.value)">
            </div>
            <div class="filter-null-option">
                <label>
                    <input type="checkbox" id="filterIncludeNull" onchange="App.toggleFilterIncludeNull(this.checked)">
                    Include NULL values
                </label>
            </div>
            <div class="filter-query-preview" id="filterQueryPreview"></div>
        `;

        container.innerHTML = html;
    },

    updateFilterRangeMin(value) {
        const numVal = parseFloat(value);
        const maxSlider = document.getElementById('filterRangeMax');
        const maxVal = parseFloat(maxSlider.value);
        
        if (numVal > maxVal) {
            document.getElementById('filterRangeMin').value = maxVal;
            this.panelFilterState.rangeMin = maxVal;
        } else {
            this.panelFilterState.rangeMin = numVal;
        }
        
        document.getElementById('filterRangeMinDisplay').textContent = this.formatNumber(this.panelFilterState.rangeMin);
        this.updateHistogramHighlight();
        this.updateFilterPreview();
    },

    updateFilterRangeMax(value) {
        const numVal = parseFloat(value);
        const minSlider = document.getElementById('filterRangeMin');
        const minVal = parseFloat(minSlider.value);
        
        if (numVal < minVal) {
            document.getElementById('filterRangeMax').value = minVal;
            this.panelFilterState.rangeMax = minVal;
        } else {
            this.panelFilterState.rangeMax = numVal;
        }
        
        document.getElementById('filterRangeMaxDisplay').textContent = this.formatNumber(this.panelFilterState.rangeMax);
        this.updateHistogramHighlight();
        this.updateFilterPreview();
    },

    updateHistogramHighlight() {
        const bars = document.querySelectorAll('.filter-histogram-bar');
        const histogram = this.currentHistogram;
        if (!histogram || bars.length === 0) return;

        bars.forEach((bar, i) => {
            const binStart = histogram.binEdges[i];
            const binEnd = histogram.binEdges[i + 1];
            const inRange = binEnd >= this.panelFilterState.rangeMin && binStart <= this.panelFilterState.rangeMax;
            bar.classList.toggle('in-range', inRange);
        });
    },

    toggleFilterValue(value, checked) {
        if (checked) {
            this.panelFilterState.selectedValues.add(value);
        } else {
            this.panelFilterState.selectedValues.delete(value);
        }
        this.updateFilterPreview();
    },

    selectAllFilterValues() {
        const items = document.querySelectorAll('.filter-value-item');
        items.forEach(item => {
            const checkbox = item.querySelector('input[type="checkbox"]');
            if (checkbox) {
                checkbox.checked = true;
                this.panelFilterState.selectedValues.add(item.dataset.value);
            }
        });
        this.updateFilterPreview();
    },

    clearAllFilterValues() {
        const items = document.querySelectorAll('.filter-value-item');
        items.forEach(item => {
            const checkbox = item.querySelector('input[type="checkbox"]');
            if (checkbox) {
                checkbox.checked = false;
            }
        });
        this.panelFilterState.selectedValues.clear();
        this.updateFilterPreview();
    },

    filterValueList(search) {
        const items = document.querySelectorAll('.filter-value-item');
        const searchLower = search.toLowerCase();
        items.forEach(item => {
            const value = item.dataset.value.toLowerCase();
            item.style.display = value.includes(searchLower) ? '' : 'none';
        });
    },

    toggleFilterIncludeNull(checked) {
        this.panelFilterState.includeNull = checked;
        this.updateFilterPreview();
    },

    updateFilterDateStart(value) {
        this.panelFilterState.dateStart = value;
        this.updateFilterPreview();
    },

    updateFilterDateEnd(value) {
        this.panelFilterState.dateEnd = value;
        this.updateFilterPreview();
    },

    updateFilterPreview() {
        const preview = document.getElementById('filterQueryPreview');
        if (!preview) return;

        const condition = this.buildFilterCondition();
        if (condition) {
            preview.textContent = `WHERE ${condition}`;
        } else {
            preview.textContent = 'No filter selected';
        }
    },

    buildFilterCondition() {
        const column = this.activeFilterColumn;
        const state = this.panelFilterState;

        if (!column || !state) return '';

        let conditions = [];

        if (state.type === 'int' || state.type === 'float') {
            if (state.rangeMin !== null && state.rangeMax !== null) {
                const min = this.formatNumber(state.rangeMin);
                const max = this.formatNumber(state.rangeMax);
                conditions.push(`\`${column}\` BETWEEN ${min} AND ${max}`);
            }
        } else if (state.type === 'string' || state.type === 'bool') {
            if (state.selectedValues.size > 0) {
                const values = Array.from(state.selectedValues).map(v => {
                    if (v === '__NULL__') return 'NULL';
                    return `'${v.replace(/'/g, "''")}'`;
                });
                conditions.push(`\`${column}\` IN (${values.join(', ')})`);
            }
        } else if (state.type === 'date') {
            if (state.dateStart && state.dateEnd) {
                const startUnix = Math.floor(new Date(state.dateStart).getTime() / 1000);
                const endUnix = Math.floor(new Date(state.dateEnd + ' 23:59:59').getTime() / 1000);
                conditions.push(`\`${column}\` BETWEEN ${startUnix} AND ${endUnix}`);
            }
        }

        if (state.includeNull) {
            conditions.push(`\`${column}\` IS NULL`);
            return `(${conditions.join(' AND ')})`;
        }

        return conditions.join(' AND ');
    },

    applyFilterFromPanel() {
        const column = this.activeFilterColumn;
        const state = this.panelFilterState;

        if (!column || !state) return;

        this.filters = this.filters.filter(f => f.column !== column);

        if (state.type === 'int' || state.type === 'float') {
            this.filters.push({
                column,
                operator: 'BETWEEN',
                value: state.rangeMin,
                value2: state.rangeMax
            });
        } else if (state.type === 'string' || state.type === 'bool') {
            if (state.selectedValues.size > 0) {
                const values = Array.from(state.selectedValues);
                const hasNull = values.includes('__NULL__');
                const cleanValues = values.filter(v => v !== '__NULL__');
                
                if (cleanValues.length > 0) {
                    this.filters.push({
                        column,
                        operator: 'IN',
                        values: cleanValues
                    });
                }
                
                if (hasNull) {
                    this.filters.push({
                        column,
                        operator: 'IS_NULL'
                    });
                }
            }
        } else if (state.type === 'date') {
            if (state.dateStart && state.dateEnd) {
                const startUnix = Math.floor(new Date(state.dateStart).getTime() / 1000);
                const endUnix = Math.floor(new Date(state.dateEnd + ' 23:59:59').getTime() / 1000);
                this.filters.push({
                    column,
                    operator: 'BETWEEN',
                    value: startUnix,
                    value2: endUnix
                });
            }
            
            if (state.includeNull) {
                this.filters.push({
                    column,
                    operator: 'IS_NULL'
                });
            }
        }

        this.closeFilterPanel();
        this.applyFilters();
        this.renderDataPreview();
        this.renderFilterList();
        this.updateQueryInputWithFilters();
    },

    clearFilterForColumn() {
        if (this.activeFilterColumn) {
            this.filters = this.filters.filter(f => f.column !== this.activeFilterColumn);
        }
        this.closeFilterPanel();
        this.reloadData();
        this.renderDataPreview();
        this.renderFilterList();
        this.updateQueryInputWithFilters();
    },

    closeFilterPanel() {
        const overlay = document.getElementById('filterPanelOverlay');
        if (overlay) {
            overlay.classList.add('hidden');
        }
        this.activeFilterColumn = null;
        this.panelFilterState = null;
    },

    updateQueryInputWithFilters() {
        const queryInput = document.getElementById('queryInput');
        if (!queryInput || !this.tableData) return;

        const whereClause = this.buildWhereClause();
        if (whereClause) {
            const tableName = this.tableData.name || 'data';
            queryInput.value = `SELECT * FROM ${tableName} WHERE ${whereClause}`;
        } else {
            const tableName = this.tableData.name || 'data';
            queryInput.value = `SELECT * FROM ${tableName}`;
        }
    },

    buildWhereClause() {
        if (this.filters.length === 0) return '';
        
        const result = JSON.parse(filtersToQuery(JSON.stringify(this.filters)));
        return result.whereClause || '';
    },

    formatNumber(val) {
        if (typeof val !== 'number') return val;
        if (Math.abs(val) >= 1000 || Math.abs(val) < 0.01) {
            return val.toExponential(3);
        }
        return val.toFixed(2).replace(/\.?0+$/, '');
    },

    calculateStep(min, max) {
        const range = max - min;
        if (range <= 0) return 1;
        const magnitude = Math.pow(10, Math.floor(Math.log10(range / 100)));
        return Math.max(magnitude, 0.01);
    },

    renderColumnList() {
        const container = document.getElementById('columnList');
        if (!container) return;

        let html = '';
        this.tableData.columns.forEach((col, i) => {
            const type = this.tableData.types[i];
            html += `<div class="column-item" data-column="${col}" data-type="${type}" draggable="true">
                <span class="column-type-badge ${type}">${type}</span>
                <span>${col}</span>
            </div>`;
        });
        container.innerHTML = html;

        container.querySelectorAll('.column-item').forEach(item => {
            item.addEventListener('click', () => {
                container.querySelectorAll('.column-item').forEach(i => i.classList.remove('selected'));
                item.classList.add('selected');
                this.selectColumn(item.dataset.column, item.dataset.type);
            });
            item.addEventListener('dragstart', (e) => {
                e.dataTransfer.setData('text/plain', JSON.stringify({
                    name: item.dataset.column,
                    type: item.dataset.type
                }));
            });
        });
    },

    selectColumn(name, type) {
        const values = JSON.parse(getFilterValues(name));
        this.showColumnValues(name, type, values);
    },

    showColumnValues(name, type, values) {
        const container = document.getElementById('columnValues');
        if (!container) return;

        let html = `<div class="filter-item">
            <div class="filter-item-header">
                <span class="filter-item-col">${name} (${type})</span>
            </div>
            <div style="margin-top:4px">
                <select class="form-select" id="filterOp-${name}" style="width:45%;display:inline-block">
                    <option value="=">=</option>
                    <option value="!=">!=</option>
                    <option value=">">></option>
                    <option value="<"><</option>
                    <option value=">=">>=</option>
                    <option value="<="><=</option>
                    <option value="IN">IN</option>
                    <option value="BETWEEN">BETWEEN</option>
                    <option value="IS_NULL">IS NULL</option>
                    <option value="IS_NOT_NULL">IS NOT NULL</option>
                </select>
                <input class="form-input" id="filterVal-${name}" placeholder="Value" style="width:50%;display:inline-block;margin-left:2%">
            </div>
            <button class="toolbar-btn primary" style="margin-top:6px;width:100%;font-size:11px" onclick="App.addFilter('${name}')">Apply Filter</button>
        </div>`;

        const uniqueCount = values.length;
        html += `<div style="margin-top:8px;font-size:11px;color:var(--text-muted)">${uniqueCount} unique values</div>`;

        if (type === 'string' && uniqueCount <= 50) {
            html += '<div style="margin-top:4px;max-height:150px;overflow-y:auto">';
            values.slice(0, 50).forEach(v => {
                html += `<div style="font-size:11px;padding:2px 6px;cursor:pointer" onclick="document.getElementById('filterVal-${name}').value='${v}'">${v}</div>`;
            });
            html += '</div>';
        }

        container.innerHTML = html;
    },

    addFilter(column) {
        const opEl = document.getElementById(`filterOp-${column}`);
        const valEl = document.getElementById(`filterVal-${column}`);
        if (!opEl || !valEl) return;

        const operator = opEl.value;
        const value = valEl.value;

        const filter = { column, operator, value };
        if (operator === 'BETWEEN') {
            const parts = value.split(',');
            filter.value = parts[0]?.trim() || '';
            filter.value2 = parts[1]?.trim() || '';
        } else if (operator === 'IN') {
            filter.values = value.split(',').map(v => v.trim());
        }

        this.filters.push(filter);
        this.applyFilters();
        this.renderFilterList();
    },

    removeFilter(index) {
        this.filters.splice(index, 1);
        if (this.filters.length === 0 && this.originalTableData) {
            this.reloadData();
        } else {
            this.applyFilters();
        }
        this.renderFilterList();
    },

    applyFilters() {
        if (!this.tableData) return;

        this.filters.forEach(filter => {
            let filterObj = { operator: filter.operator };
            if (filter.operator === 'BETWEEN') {
                filterObj.value = filter.value;
                filterObj.value2 = filter.value2;
            } else if (filter.operator === 'IN') {
                filterObj.values = filter.values;
            } else if (filter.operator === 'IS_NULL' || filter.operator === 'IS_NOT_NULL') {
                filterObj.value = null;
            } else {
                const colType = this.getColumnType(filter.column);
                if (colType === 'int' || colType === 'float') {
                    filterObj.value = parseFloat(filter.value);
                } else {
                    filterObj.value = filter.value;
                }
            }
            const result = JSON.parse(applyFilter(filter.column, JSON.stringify(filterObj)));
            if (result.success) {
                this.tableData = { ...this.tableData, rowCount: result.rowCount, columns: result.columns };
            }
        });

        this.currentPage = 0;
        this.renderDataPreview();
        this.updateStatusBar();
    },

    reloadData() {
        if (!this.originalTableData) return;
        const format = this.originalTableData.fileType || 'csv';
        const result = JSON.parse(parseData(this.originalTableData.rawData || '', format));
        if (result.success) {
            this.tableData = result;
        }
    },

    getColumnType(colName) {
        if (!this.tableData) return 'string';
        const idx = this.tableData.columns.indexOf(colName);
        if (idx >= 0) return this.tableData.types[idx];
        return 'string';
    },

    renderFilterList() {
        const container = document.getElementById('filterList');
        if (!container) return;

        let html = '';
        this.filters.forEach((f, i) => {
            let valDisplay = f.value || '';
            if (f.operator === 'IN') valDisplay = f.values?.join(', ') || '';
            if (f.operator === 'BETWEEN') valDisplay = `${f.value} ~ ${f.value2}`;
            if (f.operator === 'IS_NULL') valDisplay = '';
            if (f.operator === 'IS_NOT_NULL') valDisplay = '';

            html += `<div class="filter-item">
                <div class="filter-item-header">
                    <span class="filter-item-col">${f.column} ${f.operator} ${valDisplay}</span>
                    <span class="filter-item-remove" onclick="App.removeFilter(${i})">×</span>
                </div>
            </div>`;
        });
        container.innerHTML = html || '<div style="padding:8px;font-size:12px;color:var(--text-muted)">No filters applied</div>';
    },

    executeNonQuery() {
        const input = document.getElementById('queryInput');
        if (!input || !input.value.trim()) return;

        const queryStr = input.value.trim();
        this.queryHistory.unshift(queryStr);
        if (this.queryHistory.length > 20) this.queryHistory.pop();

        const result = JSON.parse(executeQuery(queryStr));
        const resultContainer = document.getElementById('queryResult');

        if (result.success) {
            const data = JSON.parse(result.data);
            let html = `<div style="margin-bottom:8px;font-size:12px;color:var(--text-secondary)">${result.rowCount} rows returned</div>`;
            html += '<table class="data-table"><thead><tr>';
            result.columns.forEach(c => html += `<th>${c}</th>`);
            html += '</tr></thead><tbody>';
            data.slice(0, 500).forEach(row => {
                html += '<tr>';
                result.columns.forEach(c => {
                    const v = row[c];
                    html += `<td>${v !== null && v !== undefined ? v : 'NULL'}</td>`;
                });
                html += '</tr>';
            });
            html += '</tbody></table>';
            resultContainer.innerHTML = html;
        } else {
            resultContainer.innerHTML = `<div style="color:var(--danger);padding:12px">${result.error}</div>`;
        }
    },

    renderChartConfig() {
        if (!this.tableData) return;

        const xSelect = document.getElementById('chartXField');
        const ySelect = document.getElementById('chartYField');
        const colorSelect = document.getElementById('chartColorField');
        const chartTypeSelect = document.getElementById('chartType');

        if (!xSelect || !ySelect) return;

        const cols = this.tableData.columns;
        const types = this.tableData.types;
        const makeOptions = (selected) => {
            let html = '<option value="">-- Select --</option>';
            cols.forEach((c, i) => html += `<option value="${c}" ${c === selected ? 'selected' : ''}>${c} (${types[i]})</option>`);
            return html;
        };

        xSelect.innerHTML = makeOptions('');
        ySelect.innerHTML = makeOptions('');
        if (colorSelect) colorSelect.innerHTML = makeOptions('');

        const updateChartOptions = () => {
            const type = chartTypeSelect?.value || 'bar';
            const histGroup = document.getElementById('histogramBinGroup');
            const heatGroup = document.getElementById('heatmapAggGroup');
            const yFieldGroup = ySelect?.closest('.form-group');
            
            if (histGroup) histGroup.style.display = type === 'histogram' ? '' : 'none';
            if (heatGroup) heatGroup.style.display = type === 'heatmap' ? '' : 'none';
            if (yFieldGroup) yFieldGroup.style.display = type === 'histogram' ? 'none' : '';
        };

        if (chartTypeSelect) {
            chartTypeSelect.onchange = updateChartOptions;
            updateChartOptions();
        }
    },

    generateChart() {
        const type = document.getElementById('chartType')?.value || 'bar';
        const xField = document.getElementById('chartXField')?.value;
        const yField = document.getElementById('chartYField')?.value;
        const colorField = document.getElementById('chartColorField')?.value;
        const aggregate = document.getElementById('chartAggregate')?.value || '';
        const brushEnabled = document.getElementById('chartBrush')?.checked || false;
        const histogramBins = parseInt(document.getElementById('histogramBins')?.value || '10');
        const heatmapAggregate = document.getElementById('heatmapAggregate')?.value || 'count';

        if (type === 'histogram') {
            if (!xField) {
                alert('Please select X field for histogram');
                return;
            }
        } else if (!xField || !yField) {
            alert('Please select X and Y fields');
            return;
        }

        const config = {
            type: this.chartTypeToInt(type),
            x_field: xField,
            y_field: yField || '',
            color_field: colorField || '',
            title: type === 'histogram' ? `Distribution of ${xField}` : `${yField} by ${xField}`,
            aggregate: aggregate,
            bin_count: histogramBins,
            color_aggregate: heatmapAggregate,
            width: 0,
            height: 0
        };

        let spec;
        if (brushEnabled && type !== 'heatmap' && type !== 'histogram') {
            spec = generateBrushChart(JSON.stringify(config));
        } else {
            spec = generateChart(JSON.stringify(config));
        }

        this.renderVegaLite(spec, 'chartContainer');
    },

    chartTypeToInt(type) {
        switch (type) {
            case 'bar': return 0;
            case 'line': return 1;
            case 'scatter': return 2;
            case 'boxplot': return 3;
            case 'heatmap': return 4;
            case 'histogram': return 5;
            default: return 0;
        }
    },

    renderVegaLite(specJson, containerId) {
        const container = document.getElementById(containerId);
        if (!container) return;

        try {
            const spec = typeof specJson === 'string' ? JSON.parse(specJson) : specJson;
            if (typeof vegaEmbed !== 'undefined') {
                vegaEmbed(container, spec, { actions: true, renderer: 'svg' }).catch(err => {
                    container.innerHTML = `<div style="color:var(--danger);padding:12px">Chart error: ${err.message}</div>`;
                });
            } else {
                container.innerHTML = '<pre style="font-size:11px;overflow:auto;max-height:400px">' + JSON.stringify(spec, null, 2) + '</pre>';
            }
        } catch (e) {
            container.innerHTML = `<div style="color:var(--danger)">Parse error: ${e.message}</div>`;
        }
    },

    renderPivotConfig() {
        if (!this.tableData) return;
        const valSelect = document.getElementById('pivotValueField');
        if (!valSelect) return;

        let html = '<option value="">-- Select --</option>';
        this.tableData.columns.forEach((c, i) => {
            if (this.tableData.types[i] === 'int' || this.tableData.types[i] === 'float') {
                html += `<option value="${c}">${c}</option>`;
            }
        });
        valSelect.innerHTML = html;

        ['pivotRowDims', 'pivotColDims'].forEach(zoneId => {
            const zone = document.getElementById(zoneId);
            if (zone) {
                zone.addEventListener('dragover', (e) => { e.preventDefault(); zone.style.borderColor = 'var(--accent)'; });
                zone.addEventListener('dragleave', () => { zone.style.borderColor = ''; });
                zone.addEventListener('drop', (e) => {
                    e.preventDefault();
                    zone.style.borderColor = '';
                    try {
                        const data = JSON.parse(e.dataTransfer.getData('text/plain'));
                        this.addPivotDimension(zoneId, data.name, data.type);
                    } catch (err) {}
                });
            }
        });
    },

    pivotRowDims: [],
    pivotColDims: [],

    addPivotDimension(zoneId, name, type) {
        const list = zoneId === 'pivotRowDims' ? this.pivotRowDims : this.pivotColDims;
        if (!list.find(d => d.name === name)) {
            list.push({ name, type });
        }
        this.renderPivotDimensions();
    },

    removePivotDimension(zoneId, name) {
        const list = zoneId === 'pivotRowDims' ? this.pivotRowDims : this.pivotColDims;
        const idx = list.findIndex(d => d.name === name);
        if (idx >= 0) list.splice(idx, 1);
        this.renderPivotDimensions();
    },

    renderPivotDimensions() {
        ['pivotRowDims', 'pivotColDims'].forEach(zoneId => {
            const zone = document.getElementById(zoneId);
            if (!zone) return;
            const list = zoneId === 'pivotRowDims' ? this.pivotRowDims : this.pivotColDims;
            zone.innerHTML = list.map(d =>
                `<span class="pivot-dim-tag">${d.name} <span class="remove" onclick="App.removePivotDimension('${zoneId}','${d.name}')">×</span></span>`
            ).join('') || '<span style="color:var(--text-muted);font-size:11px">Drag columns here</span>';
        });
    },

    buildPivot() {
        const valueField = document.getElementById('pivotValueField')?.value;
        const aggMethodStr = document.getElementById('pivotAggMethod')?.value || '0';

        if (!valueField) {
            alert('Please select a value field');
            return;
        }
        if (this.pivotRowDims.length === 0 && this.pivotColDims.length === 0) {
            alert('Please add at least one dimension');
            return;
        }

        let agg_method, percentile_value = null;
        if (aggMethodStr.startsWith('7.')) {
            agg_method = 7;
            const percentileMap = { '7': 50, '7.5': 90, '7.95': 95, '7.99': 99 };
            percentile_value = percentileMap[aggMethodStr] || 50;
        } else {
            agg_method = parseInt(aggMethodStr);
        }

        const config = {
            row_dims: this.pivotRowDims.map(d => d.name),
            col_dims: this.pivotColDims.map(d => d.name),
            value_field: valueField,
            agg_method: agg_method,
            percentile_value: percentile_value
        };

        const result = buildPivotTable(JSON.stringify(config));
        const container = document.getElementById('pivotResult');
        if (container) {
            try {
                const parsed = JSON.parse(result);
                this.renderPivotTable(parsed, container);
            } catch (e) {
                container.innerHTML = `<div style="color:var(--danger)">${e.message}</div>`;
            }
        }
    },

    renderPivotTable(data, container) {
        if (!data.rows || !data.cells) {
            container.innerHTML = '<div style="padding:12px;color:var(--text-muted)">No pivot data</div>';
            return;
        }

        const colHeaders = data.cols || [];
        let html = '<table class="data-table"><thead><tr><th></th>';
        colHeaders.forEach(c => {
            const label = Object.values(c).join(' / ');
            html += `<th>${label}</th>`;
        });
        html += '<th>Total</th></tr></thead><tbody>';

        data.rows.forEach((row, i) => {
            const rowLabel = Object.values(row).join(' / ');
            const rowKey = Object.entries(row).map(([k, v]) => `${k}:${v}`).join('|');
            html += `<tr><td><strong>${rowLabel}</strong></td>`;

            colHeaders.forEach(c => {
                const colKey = Object.entries(c).map(([k, v]) => `${k}:${v}`).join('|');
                const cell = data.cells?.[rowKey]?.[colKey];
                if (cell && cell.HasValue) {
                    html += `<td>${cell.Value.toFixed(2)}</td>`;
                } else {
                    html += '<td class="null-val">-</td>';
                }
            });

            const total = data.row_totals?.[rowKey];
            if (total && total.HasValue) {
                html += `<td><strong>${total.Value.toFixed(2)}</strong></td>`;
            } else {
                html += '<td class="null-val">-</td>';
            }
            html += '</tr>';
        });

        html += '</tbody></table>';
        container.innerHTML = html;
    },

    renderAnomalyConfig() {
        if (!this.tableData) return;
        const colSelect = document.getElementById('anomalyColumn');
        if (!colSelect) return;

        let html = '<option value="">-- Select Column --</option>';
        this.tableData.columns.forEach((c, i) => {
            if (this.tableData.types[i] === 'int' || this.tableData.types[i] === 'float') {
                html += `<option value="${c}">${c}</option>`;
            }
        });
        colSelect.innerHTML = html;
    },

    detectAnomalies() {
        const column = document.getElementById('anomalyColumn')?.value;
        const method = document.getElementById('anomalyMethod')?.value || '0';
        const threshold = parseFloat(document.getElementById('anomalyThreshold')?.value || '0');

        if (!column) {
            alert('Please select a column');
            return;
        }

        const config = {
            column: column,
            method: parseInt(method),
            threshold: threshold
        };

        const result = JSON.parse(detectAnomalies(JSON.stringify(config)));

        const container = document.getElementById('anomalyResult');
        if (container) {
            if (result.anomaly_count > 0) {
                let html = `<div style="padding:8px;font-size:13px;color:var(--danger);margin-bottom:8px">
                    <strong>${result.anomaly_count}</strong> anomalies detected out of ${result.total_checked} values
                </div>`;
                html += `<div style="font-size:11px;color:var(--text-secondary);margin-bottom:8px">
                    Range: [${result.lower_bound.toFixed(2)}, ${result.upper_bound.toFixed(2)}]
                </div>`;
                html += '<div class="anomaly-result-list">';
                result.anomaly_values.slice(0, 50).forEach((v, i) => {
                    html += `<div class="anomaly-item">Row ${result.anomaly_indices[i]}: <strong>${v}</strong></div>`;
                });
                if (result.anomaly_count > 50) {
                    html += `<div style="padding:8px;font-size:11px;color:var(--text-muted)">... and ${result.anomaly_count - 50} more</div>`;
                }
                html += '</div>';
                html += `<button class="toolbar-btn" style="width:100%;margin-top:8px;font-size:11px" onclick="App.highlightAnomalies()">Highlight in Table</button>`;
                container.innerHTML = html;
            } else {
                container.innerHTML = '<div style="padding:12px;color:var(--success)">No anomalies detected</div>';
            }
        }
    },

    highlightAnomalies() {
        const column = document.getElementById('anomalyColumn')?.value;
        const method = document.getElementById('anomalyMethod')?.value || '0';
        const threshold = parseFloat(document.getElementById('anomalyThreshold')?.value || '0');

        if (!column) return;

        const config = { column, method: parseInt(method), threshold };
        const spec = getAnomalyHighlight(JSON.stringify(config), 'scatter');
        this.renderVegaLite(spec, 'chartContainer');
    },

    renderPagination() {
        const container = document.getElementById('pagination');
        if (!container || !this.tableData) return;

        const total = this.tableData.rowCount;
        const pages = Math.ceil(total / this.pageSize);
        const current = this.currentPage + 1;

        container.innerHTML = `
            <button class="pagination-btn" ${this.currentPage <= 0 ? 'disabled' : ''} onclick="App.prevPage()">← Prev</button>
            <span>Page ${current} of ${pages} (${total} rows)</span>
            <button class="pagination-btn" ${current >= pages ? 'disabled' : ''} onclick="App.nextPage()">Next →</button>
        `;
    },

    nextPage() {
        const maxPage = Math.ceil(this.tableData.rowCount / this.pageSize) - 1;
        if (this.currentPage < maxPage) {
            this.currentPage++;
            this.renderDataPreview();
        }
    },

    prevPage() {
        if (this.currentPage > 0) {
            this.currentPage--;
            this.renderDataPreview();
        }
    },

    updateStatusBar() {
        const el = document.getElementById('statusBar');
        if (!el || !this.tableData) return;

        el.innerHTML = `
            <span class="status-item">📊 ${this.tableData.rowCount} rows</span>
            <span class="status-item">📋 ${this.tableData.colCount} columns</span>
            <span class="status-item">✅ ${this.tableData.validRows} valid</span>
            <span class="status-item">⚠️ ${this.tableData.dirtyRows} dirty</span>
            <span class="status-item">🔍 ${this.filters.length} filters</span>
        `;
    },

    exportData(format) {
        switch (format) {
            case 'csv': exportCSV(); break;
            case 'json': exportJSON(); break;
            case 'excel': exportExcel(); break;
        }
    },

    saveCurrentProject() {
        if (!this.tableData) return;

        const state = {
            id: 'project_' + Date.now(),
            name: 'Analysis ' + new Date().toLocaleDateString(),
            createdAt: Date.now(),
            updatedAt: Date.now(),
            data_source: { file_type: 'csv' },
            filters: this.filters,
            charts: this.charts,
            pivot_tables: [],
            query_history: this.queryHistory,
            selected_columns: [],
            sort_column: '',
            sort_ascending: true,
            limit_value: 0
        };

        saveProject(JSON.stringify(state));
        this.log('Project saved');
    },

    showLoading(msg) {
        const el = document.getElementById('loadingOverlay');
        const text = document.getElementById('loadingText');
        if (el) {
            el.classList.remove('hidden');
            if (text) text.textContent = msg || 'Loading...';
        }
    },

    hideLoading() {
        const el = document.getElementById('loadingOverlay');
        if (el) el.classList.add('hidden');
    }
};

function downloadFile(filename, mimeType, content) {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
}

function saveToIndexedDB(jsonString) {
    if (!App.db) return;
    const data = JSON.parse(jsonString);
    const tx = App.db.transaction('projects', 'readwrite');
    tx.objectStore('projects').put(data);
}

function requestLoadFromIndexedDB(id) {
    if (!App.db) return;
    const tx = App.db.transaction('projects', 'readonly');
    const req = tx.objectStore('projects').get(id);
    req.onsuccess = () => {
        if (req.result) {
            window._lastLoadedProject = JSON.stringify(req.result);
        } else {
            window._lastLoadedProject = '';
        }
    };
}

function listProjectsFromDB() {
    return new Promise((resolve) => {
        if (!App.db) { resolve('[]'); return; }
        const tx = App.db.transaction('projects', 'readonly');
        const req = tx.objectStore('projects').getAll();
        req.onsuccess = () => {
            window._projectsList = JSON.stringify(req.result || []);
            resolve(window._projectsList);
        };
        req.onerror = () => resolve('[]');
    });
}

function deleteProject(id) {
    if (!App.db) return;
    const tx = App.db.transaction('projects', 'readwrite');
    tx.objectStore('projects').delete(id);
}

document.addEventListener('DOMContentLoaded', () => App.init());
