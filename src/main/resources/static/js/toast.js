document.addEventListener('DOMContentLoaded', () => {
    initToastContainer();
    connectToastWebSocket();
});

function initToastContainer() {
    if (!document.getElementById('toast-container')) {
        const container = document.createElement('div');
        container.id = 'toast-container';
        document.body.appendChild(container);
    }
}

function connectToastWebSocket() {
    if (typeof SockJS === 'undefined' || typeof Stomp === 'undefined') return;

    const socket = new SockJS('/ws-network-monitor');
    const stompClient = Stomp.over(socket);
    stompClient.debug = null;

    stompClient.connect({}, () => {
        stompClient.subscribe('/topic/alerts/toast', (messageOutput) => {
            try {
                const log = JSON.parse(messageOutput.body);
                showToastNotification(log);
            } catch (e) {
                console.error("Lỗi parse gói tin Toast:", e);
            }
        });
    }, (err) => {
        setTimeout(connectToastWebSocket, 5000);
    });
}

function showToastNotification(log) {
    const logType = (log.type || '').toLowerCase();
    
    // ĐẶC BIỆT: Nếu là cảnh báo bất thường Packet Rate Anomaly -> Hiện Modal chính giữa màn hình
    if (logType.includes('packet rate')) {
        showCenterAnomalyModal(log);
        return;
    }

    // Ngược lại: Hiển thị thẻ Toast góc dưới bên phải như bình thường
    showStandardToast(log);
}

// Hàm hiển thị Cảnh báo Bất thường chính giữa màn hình
function showCenterAnomalyModal(log) {
    const overlay = document.createElement('div');
    overlay.className = 'anomaly-modal-overlay';

    const hostText = log.hostname || log.agentId || 'UNKNOWN AGENT';
    const metricVal = log.metricValue !== undefined ? log.metricValue : log.value;
    const thresholdVal = log.thresholdValue !== undefined ? log.thresholdValue : log.threshold;
    const unitText = log.unit || 'pps';

    overlay.innerHTML = `
        <div class="anomaly-modal-card">
            <div>
                <span class="anomaly-badge-critical">CẢNH BÁO</span>
            </div>
            <div class="anomaly-title">Phát hiện bất thường</div>
            <div class="anomaly-body">
                <div class="anomaly-agent-name">${hostText}</div>
                Packet Rate Anomaly = ${metricVal} ${unitText} (ngưỡng > ${thresholdVal})
            </div>
            <button class="anomaly-close-btn" onclick="closeAnomalyModal(this)">XÁC NHẬN</button>
        </div>
    `;

    document.body.appendChild(overlay);

    setTimeout(() => {
        closeAnomalyModal(overlay.querySelector('.anomaly-close-btn'));
    }, 10000);
}

// Hàm đóng Modal cảnh báo
function closeAnomalyModal(btnElem) {
    if (!btnElem) return;
    const overlay = btnElem.closest('.anomaly-modal-overlay');
    if (overlay && !overlay.classList.contains('fadeOut')) {
        overlay.classList.add('fadeOut');
        setTimeout(() => {
            if (overlay.parentNode) {
                overlay.parentNode.removeChild(overlay);
            }
        }, 250);
    }
}

// Hàm hiển thị Toast thông thường ở góc phải dưới
function showStandardToast(log) {
    initToastContainer();
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    const severityUpper = (log.severity || 'WARNING').toUpperCase();
    const isWarning = severityUpper === 'WARNING';

    toast.className = `toast-card ${isWarning ? 'warning' : 'critical'}`;

    const titleText = `${severityUpper}: ${log.type || 'Chỉ số'} vượt ngưỡng`;
    const hostText = log.hostname || log.agentId || 'Agent';

    toast.innerHTML = `
        <div class="toast-title">${titleText}</div>
        <div class="toast-body">${hostText}: ${log.type} = ${log.metricValue} ${log.unit || ''} (ngưỡng > ${log.thresholdValue})</div>
    `;

    container.appendChild(toast);

    setTimeout(() => {
        toast.classList.add('fadeOut');
        setTimeout(() => { if (toast.parentNode) toast.parentNode.removeChild(toast); }, 400);
    }, 6000);
}