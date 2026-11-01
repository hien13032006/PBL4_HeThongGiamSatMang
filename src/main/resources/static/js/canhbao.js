document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();
    startClock();
    fetchThresholdConfigs();
    fetchAlertLogs();

    // Lắng nghe sự kiện lưu cấu hình ngưỡng
    const thresholdForm = document.getElementById('threshold-form');
    if (thresholdForm) {
        thresholdForm.addEventListener('submit', async (e) => {
            e.preventDefault();
            await saveThresholdConfigs();
        });
    }

    // Tự động làm mới danh sách nhật ký cảnh báo mỗi 5 giây
    setInterval(fetchAlertLogs, 5000);
});

// Chức năng bật/tắt chế độ Sáng/Tối (Dark Mode)
function initThemeToggle() {
    const themeBtn = document.getElementById('theme-toggle-btn');
    if (themeBtn) {
        themeBtn.addEventListener('click', () => {
            document.body.classList.toggle('dark');
        });
    }
}

// Cập nhật đồng hồ thời gian thực ở Header
function startClock() {
    const statusContainer = document.getElementById('ws-status-container');
    if (!statusContainer) return;

    const renderTime = () => {
        const currentTime = new Date().toLocaleTimeString('vi-VN', { hour12: false });
        statusContainer.innerHTML = `
            <div class="ws-status-connected">
                <span class="dot-ping-wrapper">
                    <span class="dot-ping"></span>
                    <span class="dot-solid-green"></span>
                </span>
                <span>Hệ thống hoạt động · <span>${currentTime}</span></span>
            </div>
        `;
    };

    renderTime();
    setInterval(renderTime, 1000);
}

// 1. Nạp cấu hình ngưỡng hiện tại từ REST API
async function fetchThresholdConfigs() {
    try {
        const response = await fetch('/api/alerts/thresholds');
        if (response.ok) {
            const data = await response.json();
            
            // Warning Thresholds
            if (data.bwWarnPercent) document.getElementById('bwWarnPercent').value = data.bwWarnPercent;
            if (data.bwWarnDuration) document.getElementById('bwWarnDuration').value = data.bwWarnDuration;
            if (data.latencyWarnMs) document.getElementById('latencyWarnMs').value = data.latencyWarnMs;
            if (data.latencyWarnDuration) document.getElementById('latencyWarnDuration').value = data.latencyWarnDuration;
            if (data.lossWarnPercent) document.getElementById('lossWarnPercent').value = data.lossWarnPercent;
            if (data.lossWarnDuration) document.getElementById('lossWarnDuration').value = data.lossWarnDuration;

            // Critical Thresholds
            if (data.bwCritPercent) document.getElementById('bwCritPercent').value = data.bwCritPercent;
            if (data.bwCritDuration) document.getElementById('bwCritDuration').value = data.bwCritDuration;
            if (data.latencyCritMs) document.getElementById('latencyCritMs').value = data.latencyCritMs;
            if (data.latencyCritDuration) document.getElementById('latencyCritDuration').value = data.latencyCritDuration;
            if (data.lossCritPercent) document.getElementById('lossCritPercent').value = data.lossCritPercent;
            if (data.lossCritDuration) document.getElementById('lossCritDuration').value = data.lossCritDuration;
            if (data.lossCritImmediate) document.getElementById('lossCritImmediate').value = data.lossCritImmediate;
        }
    } catch (err) {
        console.error("Lỗi khi nạp ngưỡng cảnh báo:", err);
    }
}

// 2. Lưu toàn bộ bộ ngưỡng mới xuống CSDL qua API
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
            alert("Lưu ngưỡng cảnh báo thành công!");
            fetchAlertLogs();
        } else {
            alert("Lưu thất bại!");
        }
    } catch (err) {
        console.error("Lỗi khi lưu ngưỡng:", err);
    }
}

// 3. Tải danh sách Nhật ký cảnh báo từ REST API
async function fetchAlertLogs() {
    const container = document.getElementById('alert-log-container');
    if (!container) return;

    try {
        const response = await fetch('/api/alerts/logs');
        if (!response.ok) return;

        const logs = await response.json();
        container.innerHTML = '';

        // Nếu API trả về mảng rỗng [] -> Hiển thị "Chưa có cảnh báo nào."
        if (!logs || logs.length === 0) {
            container.innerHTML = `<div class="log-item" style="color: #64748b; font-style: italic; justify-content: center; padding: 20px 0;">Chưa có cảnh báo nào.</div>`;
            const alertBadge = document.getElementById('alert-count-badge');
            if (alertBadge) alertBadge.innerText = '0';
            return;
        }

        // Cập nhật số lượng cảnh báo lên Badge sidebar
        const alertBadge = document.getElementById('alert-count-badge');
        if (alertBadge) alertBadge.innerText = logs.length;

        // Render từng dòng nhật ký cảnh báo thực tế
        logs.forEach(log => {
            const timeStr = log.timestamp ? log.timestamp.split('T')[1].substring(0, 8) : '--:--:--';
            
            let typeClass = 'rx';
            const logTypeLower = (log.type || '').toLowerCase();
            if (logTypeLower.includes('loss')) typeClass = 'loss';
            if (logTypeLower.includes('latency')) typeClass = 'latency';

            const itemHtml = `
                <div class="log-item">
                    <span class="log-time">${timeStr}</span>
                    <span class="log-type ${typeClass}">${log.type}</span> – 
                    <span>${log.hostname || 'Agent'}: ${log.type} = ${log.value} ${log.unit || ''} (ngưỡng > ${log.threshold})</span>
                </div>
            `;
            container.innerHTML += itemHtml;
        });
    } catch (err) {
        console.error("Lỗi khi nạp nhật ký cảnh báo:", err);
        container.innerHTML = `<div class="log-item" style="color: #ef4444;">Không thể kết nối đến máy chủ API.</div>`;
    }
}