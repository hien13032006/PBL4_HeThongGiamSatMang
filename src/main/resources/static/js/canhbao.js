document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();
    startClock();
    fetchThresholdConfigs();
    fetchAlertLogs();

    // Lắng nghe sự kiện submit form lưu cấu hình ngưỡng
    const thresholdForm = document.getElementById('threshold-form');
    if (thresholdForm) {
        thresholdForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            await saveThresholdConfigs();
        });
    }

    // Tự động làm mới nhật ký cảnh báo mỗi 5 giây
    setInterval(fetchAlertLogs, 5000);
});

function initThemeToggle() {
    const themeBtn = document.getElementById('theme-toggle-btn');
    if (themeBtn) {
        themeBtn.addEventListener('click', () => {
            document.body.classList.toggle('dark');
        });
    }
}

function updateWsStatusUI(isConnected) {
    const container = document.getElementById('ws-status-container');
    if (!container) return;

    const currentTime = new Date().toLocaleTimeString('vi-VN', { hour12: false });

    if (isConnected) {
        container.innerHTML = `
            <div class="ws-status-connected">
                <span class="dot-ping-wrapper">
                    <span class="dot-ping"></span>
                    <span class="dot-solid-green"></span>
                </span>
                <span>WebSocket · TCP :9999 · <span id="ws-live-time">${currentTime}</span></span>
            </div>
        `;
    } else {
        container.innerHTML = `
            <div class="ws-status-disconnected">
                <span class="dot-solid-red"></span>
                <span>Mất kết nối WebSocket</span>
            </div>
        `;
    }
}

// Cập nhật đồng hồ từng giây cho nhãn WebSocket
function startClock() {
    updateWsStatusUI(true); // Hiển thị mặc định trạng thái connected với nhãn WebSocket · TCP :9999
    
    setInterval(() => {
        const timeElem = document.getElementById('ws-live-time');
        if (timeElem) {
            timeElem.innerText = new Date().toLocaleTimeString('vi-VN', { hour12: false });
        }
    }, 1000);
}

// 1. Nạp toàn bộ ngưỡng cấu hình từ REST API và đổ vào các ô input
async function fetchThresholdConfigs() {
    try {
        const response = await fetch('/api/alerts/thresholds');
        if (response.ok) {
            const data = await response.json();

            // Mức độ Warning
            if (data.bwWarnPercent !== undefined) document.getElementById('bwWarnPercent').value = data.bwWarnPercent;
            if (data.bwWarnDuration !== undefined) document.getElementById('bwWarnDuration').value = data.bwWarnDuration;
            if (data.latencyWarnMs !== undefined) document.getElementById('latencyWarnMs').value = data.latencyWarnMs;
            if (data.latencyWarnDuration !== undefined) document.getElementById('latencyWarnDuration').value = data.latencyWarnDuration;
            if (data.lossWarnPercent !== undefined) document.getElementById('lossWarnPercent').value = data.lossWarnPercent;
            if (data.lossWarnDuration !== undefined) document.getElementById('lossWarnDuration').value = data.lossWarnDuration;

            // Mức độ Critical
            if (data.bwCritPercent !== undefined) document.getElementById('bwCritPercent').value = data.bwCritPercent;
            if (data.bwCritDuration !== undefined) document.getElementById('bwCritDuration').value = data.bwCritDuration;
            if (data.latencyCritMs !== undefined) document.getElementById('latencyCritMs').value = data.latencyCritMs;
            if (data.latencyCritDuration !== undefined) document.getElementById('latencyCritDuration').value = data.latencyCritDuration;
            if (data.lossCritPercent !== undefined) document.getElementById('lossCritPercent').value = data.lossCritPercent;
            if (data.lossCritDuration !== undefined) document.getElementById('lossCritDuration').value = data.lossCritDuration;
            if (data.lossCritImmediate !== undefined) document.getElementById('lossCritImmediate').value = data.lossCritImmediate;
        }
    } catch (err) {
        console.error("Lỗi khi nạp ngưỡng cảnh báo từ API:", err);
    }
}

// 2. Gửi bộ thông số ngưỡng mới lên REST API để lưu vào CSDL 
async function saveThresholdConfigs() {
    const payload = {
        bwWarnPercent: parseFloat(document.getElementById('bwWarnPercent').value),
        bwWarnDuration: parseInt(document.getElementById('bwWarnDuration').value),
        latencyWarnMs: parseFloat(document.getElementById('latencyWarnMs').value),
        latencyWarnDuration: parseInt(document.getElementById('latencyWarnDuration').value),
        lossWarnPercent: parseFloat(document.getElementById('lossWarnPercent').value),
        lossWarnDuration: parseInt(document.getElementById('lossWarnDuration').value),

        bwCritPercent: parseFloat(document.getElementById('bwCritPercent').value),
        bwCritDuration: parseInt(document.getElementById('bwCritDuration').value),
        latencyCritMs: parseFloat(document.getElementById('latencyCritMs').value),
        latencyCritDuration: parseInt(document.getElementById('latencyCritDuration').value),
        lossCritPercent: parseFloat(document.getElementById('lossCritPercent').value),
        lossCritDuration: parseInt(document.getElementById('lossCritDuration').value),
        lossCritImmediate: parseFloat(document.getElementById('lossCritImmediate').value)
    };

    try {
        const response = await fetch('/api/alerts/thresholds', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });

        if (response.ok) {
            showCustomAlert("Đã lưu cấu hình ngưỡng cảnh báo thành công!", "THÀNH CÔNG");
            fetchAlertLogs(); 
        } else {
            alert("Lưu thất bại! Vui lòng kiểm tra lại phía Server.");
        }
    } catch (err) {
        console.error("Lỗi khi kết nối REST API lưu ngưỡng:", err);
        alert("Không thể kết nối đến máy chủ!");
    }
}

// 3. Tải danh sách Nhật ký cảnh báo thực tế từ REST API
async function fetchAlertLogs() {
    const container = document.getElementById('alert-log-container');
    if (!container) return;

    try {
        const response = await fetch('/api/alerts/logs');
        if (!response.ok) return;

        const logs = await response.json();
        container.innerHTML = '';

        if (!logs || logs.length === 0) {
            container.innerHTML = `<div class="log-item" style="color: #64748b; font-style: italic; justify-content: center; padding: 20px 0;">Chưa có cảnh báo nào.</div>`;
            const alertBadge = document.getElementById('alert-count-badge');
            if (alertBadge) alertBadge.innerText = '0';
            return;
        }

        const alertBadge = document.getElementById('alert-count-badge');
        if (alertBadge) alertBadge.innerText = logs.length;

        // Render danh sách cảnh báo thực tế
        logs.forEach(log => {
            const rawTime = log.createdAt || log.timestamp || '';
            const timeStr = rawTime.includes('T') ? rawTime.split('T')[1].substring(0, 8) : (rawTime.substring(11, 19) || '--:--:--');
            
            const logValue = (log.metricValue !== undefined) ? log.metricValue : log.value;
            const thresholdVal = (log.thresholdValue !== undefined) ? log.thresholdValue : log.threshold;
            
            const severityUpper = (log.severity || 'WARNING').toUpperCase();
            const isWarning = severityUpper === 'WARNING';
            
            const titleClass = isWarning ? 'alert-title-warning' : 'alert-title-critical';

            const itemHtml = `
                <div class="log-item">
                    <span class="log-time">${timeStr}</span>
                    
                    <div class="${titleClass} log-header-block">
                        [${severityUpper}]<br>
                        ${log.type || 'Alert'}
                    </div>

                    <span style="color: #94a3b8; margin-right: 2px;">–</span>

                    <span class="log-detail-block">
                        ${log.hostname || log.agentId || 'Agent'}:<br>
                        ${log.type} = ${logValue} ${log.unit || ''} (ngưỡng > ${thresholdVal})
                    </span>
                </div>
            `;
            container.innerHTML += itemHtml;
        });
    } catch (err) {
        console.error("Lỗi khi nạp nhật ký cảnh báo:", err);
        container.innerHTML = `<div class="log-item" style="color: #ef4444;">Không thể kết nối đến máy chủ API.</div>`;
    }
}

/**
 * Hàm hiển thị Modal thông báo 
 */
function showCustomAlert(message, title = 'THÔNG BÁO') {
    const existingOverlay = document.querySelector('.custom-alert-overlay');
    if (existingOverlay) existingOverlay.remove();

    const overlay = document.createElement('div');
    overlay.className = 'custom-alert-overlay';

    overlay.innerHTML = `
        <div class="custom-alert-card">
            <div class="custom-alert-icon">✓</div>
            <div class="custom-alert-title">${title}</div>
            <div class="custom-alert-message">${message}</div>
            <button class="custom-alert-btn" onclick="closeCustomAlert(this)">Đồng ý</button>
        </div>
    `;

    document.body.appendChild(overlay);
}

// Hàm đóng Modal
function closeCustomAlert(btnElem) {
    if (!btnElem) return;
    const overlay = btnElem.closest('.custom-alert-overlay');
    if (overlay && !overlay.classList.contains('fadeOut')) {
        overlay.classList.add('fadeOut');
        setTimeout(() => {
            if (overlay.parentNode) {
                overlay.parentNode.removeChild(overlay);
            }
        }, 200);
    }
}