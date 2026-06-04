window.chartInstances = {};

function initChart(canvasId, data, options = {}) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) return null;

    if (window.chartInstances[canvasId]) {
        window.chartInstances[canvasId].destroy();
    }

    const ctx = canvas.getContext('2d');

    const defaultOptions = {
        responsive: true,
        maintainAspectRatio: false,
        interaction: {
            mode: 'index',
            intersect: false,
        },
        plugins: {
            legend: {
                display: true,
                position: 'top',
                labels: {
                    color: '#a0aec0',
                    font: {
                        family: "'Inter', sans-serif",
                        size: 12,
                    },
                    usePointStyle: true,
                    padding: 15,
                }
            },
            tooltip: {
                backgroundColor: '#1a1f2e',
                titleColor: '#f7fafc',
                bodyColor: '#a0aec0',
                borderColor: '#374151',
                borderWidth: 1,
                padding: 12,
                cornerRadius: 6,
                titleFont: {
                    family: "'Inter', sans-serif",
                    size: 13,
                    weight: '600',
                },
                bodyFont: {
                    family: "'JetBrains Mono', monospace",
                    size: 12,
                },
                callbacks: {
                    label: function(context) {
                        let label = context.dataset.label || '';
                        if (label) {
                            label += ': ';
                        }
                        if (context.parsed.y !== null) {
                            label += context.parsed.y.toFixed(2);
                        }
                        return label;
                    }
                }
            }
        },
        scales: {
            x: {
                grid: {
                    color: 'rgba(45, 55, 72, 0.5)',
                    drawBorder: false,
                },
                ticks: {
                    color: '#718096',
                    font: {
                        family: "'JetBrains Mono', monospace",
                        size: 10,
                    },
                    maxRotation: 45,
                    minRotation: 45,
                    maxTicksLimit: 12,
                }
            },
            y: {
                grid: {
                    color: 'rgba(45, 55, 72, 0.5)',
                    drawBorder: false,
                },
                ticks: {
                    color: '#718096',
                    font: {
                        family: "'JetBrains Mono', monospace",
                        size: 10,
                    },
                }
            }
        },
        elements: {
            point: {
                radius: 0,
                hoverRadius: 5,
                hitRadius: 8,
            },
            line: {
                tension: 0.4,
            }
        },
    };

    const mergedOptions = { ...defaultOptions, ...options };

    window.chartInstances[canvasId] = new Chart(ctx, {
        type: 'line',
        data: data,
        options: mergedOptions,
    });

    return window.chartInstances[canvasId];
}

function updateChart(canvasId, data) {
    if (window.chartInstances[canvasId]) {
        window.chartInstances[canvasId].data = data;
        window.chartInstances[canvasId].update('none');
    }
}

function reloadChart(canvasId, endpoint) {
    const btn = event.target;
    if (btn) {
        btn.innerHTML = '<span class="htmx-indicator">⟳</span> 刷新中...';
        btn.disabled = true;
    }

    htmx.ajax('GET', endpoint, {
        target: `#${canvasId}-container`,
        swap: 'innerHTML',
        onComplete: function() {
            if (btn) {
                btn.innerHTML = '⟳ 刷新';
                btn.disabled = false;
            }
        }
    });
}

document.addEventListener('htmx:afterSwap', function(evt) {
    const charts = evt.detail.elt.querySelectorAll('.chart-canvas');
    charts.forEach(canvas => {
        const chartData = JSON.parse(canvas.dataset.chartData || '{}');
        if (chartData.labels && chartData.datasets) {
            initChart(canvas.id, chartData);
        }
    });

    const sqls = evt.detail.elt.querySelectorAll('.sql-code');
    sqls.forEach(block => {
        hljs.highlightElement(block);
    });
});

document.addEventListener('DOMContentLoaded', function() {
    const charts = document.querySelectorAll('.chart-canvas');
    charts.forEach(canvas => {
        const chartData = JSON.parse(canvas.dataset.chartData || '{}');
        if (chartData.labels && chartData.datasets) {
            initChart(canvas.id, chartData);
        }
    });

    setInterval(function() {
        const autoRefresh = document.querySelectorAll('[data-auto-refresh]');
        autoRefresh.forEach(el => {
            const interval = parseInt(el.dataset.autoRefresh) || 30000;
            const url = el.dataset.refreshUrl;
            if (url && Date.now() % interval < 1000) {
                htmx.trigger(el, 'refresh');
            }
        });
    }, 1000);

    document.addEventListener('keydown', function(e) {
        if (e.key === 'r' && e.ctrlKey) {
            e.preventDefault();
            const refreshBtns = document.querySelectorAll('[data-refresh-btn]');
            refreshBtns.forEach(btn => btn.click());
        }
    });

    document.querySelectorAll('.pin-btn').forEach(btn => {
        btn.addEventListener('click', function(e) {
            e.stopPropagation();
            const componentType = this.dataset.componentType;
            const componentKey = this.dataset.componentKey;
            const isPinned = this.classList.contains('pinned');

            const url = isPinned
                ? `/api/preferences/pinned/${this.dataset.componentId}`
                : '/api/preferences/pinned';

            const method = isPinned ? 'DELETE' : 'POST';
            const body = isPinned ? {} : {
                component_type: componentType,
                component_key: componentKey,
                position: 0
            };

            htmx.ajax(method, url, {
                params: body,
                onComplete: function() {
                    location.reload();
                }
            });
        });
    });
});

function showToast(message, type = 'info') {
    const colors = {
        success: 'var(--accent-green)',
        error: 'var(--accent-red)',
        warning: 'var(--accent-yellow)',
        info: 'var(--accent-blue)',
    };

    const toast = document.createElement('div');
    toast.style.cssText = `
        position: fixed;
        top: 20px;
        right: 20px;
        padding: 12px 20px;
        background-color: var(--bg-card);
        border-left: 4px solid ${colors[type]};
        border-radius: 6px;
        box-shadow: var(--shadow-lg);
        color: var(--text-primary);
        font-size: 14px;
        z-index: 9999;
        transform: translateX(400px);
        transition: transform 0.3s ease;
        max-width: 400px;
    `;
    toast.textContent = message;

    document.body.appendChild(toast);

    setTimeout(() => {
        toast.style.transform = 'translateX(0)';
    }, 10);

    setTimeout(() => {
        toast.style.transform = 'translateX(400px)';
        setTimeout(() => toast.remove(), 300);
    }, 3000);
}
