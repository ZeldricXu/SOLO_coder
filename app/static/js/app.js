document.addEventListener('DOMContentLoaded', function() {
    document.body.addEventListener('htmx:beforeRequest', function(evt) {
        const elt = evt.detail.elt;
        if (elt.hasAttribute('hx-confirm')) {
            return;
        }
    });

    document.body.addEventListener('htmx:afterRequest', function(evt) {
        if (evt.detail.successful) {
            try {
                const data = JSON.parse(evt.detail.xhr.responseText);
                if (data && data.message) {
                    showToast(data.message, data.success ? 'success' : 'danger');
                }
            } catch (e) {
                // Ignore JSON parse errors for HTML responses
            }
        }
    });

    document.body.addEventListener('htmx:error', function(evt) {
        showToast('请求失败，请稍后重试', 'danger');
    });

    document.body.addEventListener('htmx:confirm', function(evt) {
        evt.preventDefault();
        const message = evt.detail.question;
        if (confirm(message)) {
            evt.detail.issueRequest();
        }
    });

    document.body.addEventListener('click', function(e) {
        if (e.target.matches('[data-bs-toggle="dropdown"]')) {
            const dropdown = new bootstrap.Dropdown(e.target);
            dropdown.show();
        }
    });
});

function showToast(message, type = 'info', duration = 3000) {
    const toastId = 'toast-' + Date.now();
    const bgColor = type === 'success' ? 'bg-success' : 
                    type === 'danger' ? 'bg-danger' : 
                    type === 'warning' ? 'bg-warning' : 'bg-primary';
    
    const toast = document.createElement('div');
    toast.id = toastId;
    toast.className = `toast align-items-center text-white ${bgColor} border-0 position-fixed bottom-0 end-0 m-3`;
    toast.style.zIndex = '9999';
    toast.innerHTML = `
        <div class="d-flex">
            <div class="toast-body">
                ${type === 'success' ? '<i class="bi bi-check-circle-fill me-2"></i>' : ''}
                ${type === 'danger' ? '<i class="bi bi-exclamation-triangle-fill me-2"></i>' : ''}
                ${type === 'warning' ? '<i class="bi bi-exclamation-circle-fill me-2"></i>' : ''}
                ${type === 'info' ? '<i class="bi bi-info-circle-fill me-2"></i>' : ''}
                ${message}
            </div>
            <button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast"></button>
        </div>
    `;
    
    document.body.appendChild(toast);
    
    const bsToast = new bootstrap.Toast(toast, { delay: duration });
    bsToast.show();
    
    toast.addEventListener('hidden.bs.toast', function() {
        toast.remove();
    });
}

function showLoadingButton(btn) {
    const originalText = btn.innerHTML;
    btn.setAttribute('data-original-text', originalText);
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-2" role="status"></span>加载中...';
    return function reset() {
        btn.disabled = false;
        btn.innerHTML = originalText;
    };
}

function copyToClipboard(text, successMessage = '已复制到剪贴板') {
    if (navigator.clipboard) {
        navigator.clipboard.writeText(text).then(function() {
            showToast(successMessage, 'success');
        }).catch(function() {
            fallbackCopy(text, successMessage);
        });
    } else {
        fallbackCopy(text, successMessage);
    }
}

function fallbackCopy(text, successMessage) {
    const textArea = document.createElement('textarea');
    textArea.value = text;
    textArea.style.position = 'fixed';
    textArea.style.left = '-9999px';
    document.body.appendChild(textArea);
    textArea.select();
    try {
        document.execCommand('copy');
        showToast(successMessage, 'success');
    } catch (err) {
        showToast('复制失败', 'danger');
    }
    document.body.removeChild(textArea);
}

function formatNumber(num) {
    if (num >= 1000000) {
        return (num / 1000000).toFixed(1) + 'M';
    }
    if (num >= 1000) {
        return (num / 1000).toFixed(1) + 'K';
    }
    return num.toString();
}

function formatDate(date) {
    if (!date) return '';
    const d = new Date(date);
    return d.toLocaleDateString('zh-CN', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit'
    });
}

function debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

function throttle(func, limit) {
    let inThrottle;
    return function(...args) {
        if (!inThrottle) {
            func.apply(this, args);
            inThrottle = true;
            setTimeout(() => inThrottle = false, limit);
        }
    };
}

const ChartColors = {
    primary: '#667eea',
    secondary: '#764ba2',
    success: '#10b981',
    warning: '#f59e0b',
    danger: '#ef4444',
    info: '#3b82f6',
    purple: '#8b5cf6',
    pink: '#ec4899',
    cyan: '#06b6d4',
    
    palette: [
        '#667eea', '#764ba2', '#10b981', '#f59e0b', '#ef4444',
        '#3b82f6', '#8b5cf6', '#ec4899', '#06b6d4', '#84cc16'
    ],
    
    getColor(index) {
        return this.palette[index % this.palette.length];
    }
};

const EChartsTheme = {
    color: ChartColors.palette,
    backgroundColor: 'transparent',
    tooltip: {
        trigger: 'axis',
        backgroundColor: 'rgba(0, 0, 0, 0.8)',
        borderColor: 'transparent',
        textStyle: { color: '#fff', fontSize: 12 },
        axisPointer: {
            type: 'cross',
            label: { backgroundColor: '#667eea' }
        }
    },
    grid: {
        left: '3%',
        right: '4%',
        bottom: '3%',
        top: '15%',
        containLabel: true
    },
    legend: {
        textStyle: { color: '#6c757d', fontSize: 12 },
        top: 0
    },
    xAxis: {
        axisLine: { lineStyle: { color: '#dee2e6' } },
        axisLabel: { color: '#6c757d', fontSize: 11 },
        splitLine: { show: false }
    },
    yAxis: {
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#6c757d', fontSize: 11 },
        splitLine: { lineStyle: { color: '#f0f0f0', type: 'dashed' } }
    }
};

function initChart(container, option) {
    if (!container) return null;
    
    const chart = echarts.init(container);
    const mergedOption = { ...EChartsTheme, ...option };
    chart.setOption(mergedOption);
    
    const resizeObserver = new ResizeObserver(debounce(function() {
        chart.resize();
    }, 200));
    resizeObserver.observe(container);
    
    return chart;
}

function validateEmail(email) {
    return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);
}

function validatePassword(password) {
    return password && password.length >= 6;
}

function validateRequired(value) {
    return value && value.toString().trim() !== '';
}

function showModal(content) {
    const modalContainer = document.getElementById('modal-container');
    if (modalContainer) {
        modalContainer.innerHTML = content;
        const modal = new bootstrap.Modal(modalContainer.querySelector('.modal'));
        modal.show();
        return modal;
    }
    return null;
}

function closeModal() {
    const openModals = document.querySelectorAll('.modal.show');
    openModals.forEach(modal => {
        const bsModal = bootstrap.Modal.getInstance(modal);
        if (bsModal) {
            bsModal.hide();
        }
    });
}

function refreshPage() {
    window.location.reload();
}

function navigateTo(url) {
    window.location.href = url;
}

window.showToast = showToast;
window.copyToClipboard = copyToClipboard;
window.formatNumber = formatNumber;
window.formatDate = formatDate;
window.debounce = debounce;
window.throttle = throttle;
window.initChart = initChart;
window.ChartColors = ChartColors;
window.EChartsTheme = EChartsTheme;
window.showModal = showModal;
window.closeModal = closeModal;
window.refreshPage = refreshPage;
window.navigateTo = navigateTo;
window.validateEmail = validateEmail;
window.validatePassword = validatePassword;
window.validateRequired = validateRequired;

document.addEventListener('alpine:init', function() {
    Alpine.magic('chart', function() {
        return {
            init: initChart,
            colors: ChartColors,
            theme: EChartsTheme
        };
    });
    
    Alpine.magic('toast', function() {
        return showToast;
    });
});
