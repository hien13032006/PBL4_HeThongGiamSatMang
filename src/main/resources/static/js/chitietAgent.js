let stompClient = null;
let currentAgentId = null;
let activeSubscription = null;
let targetAgentIdToDelete = null;
let bandwidthChart, latencyChart, packetRateChart, packetLossChart;
const MAX_DATA_POINTS = 30; // ~60 giây dữ liệu real-time
let currentMaxSpeedMb = 125.0; 

// Biến lưu trữ thông số ngưỡng lấy từ CSDL (gán giá trị mặc định phòng trường hợp API chưa phản hồi)
let alertThresholds = {
    bwWarnPercent: 80.0,
    bwCritPercent: 95.0,
    latencyWarnMs: 80.0,
    latencyCritMs: 150.0,
    lossWarnPercent: 1.0,
    lossCritPercent: 5.0
};

document.addEventListener('DOMContentLoaded', () => {
    startClock();
    initThemeToggle();
    initCharts();
    fetchThresholdConfigs(); // Nạp cấu hình ngưỡng từ CSDL
    fetchAgentsList();
    connectWebSocket();

    document.getElementById('agent-select').addEventListener('change', (e) => {
        selectAgent(e.target.value);
    });
});

function initThemeToggle() {
    document.getElementById('theme-toggle-btn').addEventListener('click', () => {
        document.body.classList.toggle('dark');
    });
}

// 1. Gọi API nạp thông số ngưỡng mới nhất từ CSDL MySQL
async function fetchThresholdConfigs() {
    try {
        const response = await fetch('/api/alerts/thresholds');
        if (response.ok) {
            const data = await response.json();
            alertThresholds.bwWarnPercent = data.bwWarnPercent || 80.0;
            alertThresholds.bwCritPercent = data.bwCritPercent || 95.0;
            alertThresholds.latencyWarnMs = data.latencyWarnMs || 80.0;
            alertThresholds.latencyCritMs = data.latencyCritMs || 150.0;
            alertThresholds.lossWarnPercent = data.lossWarnPercent || 1.0;
            alertThresholds.lossCritPercent = data.lossCritPercent || 5.0;

            // Cập nhật lại nhãn/đường ngưỡng trên đồ thị nếu đồ thị đã được khởi tạo
            updateChartThresholdLabels();
        }
    } catch (err) {
        console.error("Lỗi khi tải cấu hình ngưỡng từ API:", err);
    }
}

function updateChartThresholdLabels() {
    if (bandwidthChart) {
        bandwidthChart.data.datasets[2].label = `Warning`;
        bandwidthChart.data.datasets[3].label = `Critical`;
        bandwidthChart.update();
    }
    if (latencyChart) {
        latencyChart.data.datasets[1].label = `Warning`;
        latencyChart.data.datasets[2].label = `Critical`;
        latencyChart.update();
    }
    if (packetLossChart) {
        packetLossChart.data.datasets[1].label = `Warning`;
        packetLossChart.data.datasets[2].label = `Critical`;
        packetLossChart.update();
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


function startClock() {
    updateWsStatusUI(true); 
    
    setInterval(() => {
        const timeElem = document.getElementById('ws-live-time');
        if (timeElem) {
            timeElem.innerText = new Date().toLocaleTimeString('vi-VN', { hour12: false });
        }
    }, 1000);
}

// Khởi tạo 4 Đồ thị Chart.js
function initCharts() {
    const commonOptions = {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        plugins: {
            legend: { position: 'bottom', labels: { boxWidth: 12, fontSize: 11 } }
        },
        scales: {
            x: { display: false },
            y: { beginAtZero: true, grid: { color: 'rgba(200, 200, 200, 0.15)' } }
        }
    };

    // Chart 1: Bandwidth RX / TX (Bổ sung 2 đường Warning & Critical dựa vào % maxSpeed)
    bandwidthChart = new Chart(document.getElementById('bandwidthChart'), {
        type: 'line',
        data: {
            labels: [],
            datasets: [
                { label: 'RX', borderColor: '#2980b9', borderWidth: 2, data: [], pointRadius: 0, tension: 0.2 },
                { label: 'TX', borderColor: '#d97706', borderWidth: 2, data: [], pointRadius: 0, tension: 0.2 },
                { label: `Warning`, borderColor: '#f59e0b', borderWidth: 1.5, borderDash: [4, 4], data: [], pointRadius: 0 },
                { label: `Critical`, borderColor: '#ef4444', borderWidth: 1.5, borderDash: [4, 4], data: [], pointRadius: 0 }
            ]
        },
        options: commonOptions
    });

    // Chart 2: Latency
    latencyChart = new Chart(document.getElementById('latencyChart'), {
        type: 'line',
        data: {
            labels: [],
            datasets: [
                { label: 'Latency', borderColor: '#2980b9', borderWidth: 2, data: [], pointRadius: 0, tension: 0.2 },
                { label: `Warning`, borderColor: '#f59e0b', borderWidth: 1.5, borderDash: [4, 4], data: [], pointRadius: 0 },
                { label: `Critical`, borderColor: '#ef4444', borderWidth: 1.5, borderDash: [4, 4], data: [], pointRadius: 0 }
            ]
        },
        options: commonOptions
    });

    // Chart 3: Packet Rate
    packetRateChart = new Chart(document.getElementById('packetRateChart'), {
        type: 'line',
        data: {
            labels: [],
            datasets: [
                { label: 'RX packets', borderColor: '#2980b9', borderWidth: 2, data: [], pointRadius: 0, tension: 0.2 },
                { label: 'TX packets', borderColor: '#d97706', borderWidth: 2, data: [], pointRadius: 0, tension: 0.2 }
            ]
        },
        options: commonOptions
    });

    // Chart 4: Packet Loss
    packetLossChart = new Chart(document.getElementById('packetLossChart'), {
        type: 'line',
        data: {
            labels: [],
            datasets: [
                { label: 'Packet Loss', borderColor: '#2980b9', borderWidth: 2, data: [], pointRadius: 0, tension: 0.2 },
                { label: `Warning`, borderColor: '#f59e0b', borderWidth: 1.5, borderDash: [4, 4], data: [], pointRadius: 0 },
                { label: `Critical`, borderColor: '#ef4444', borderWidth: 1.5, borderDash: [4, 4], data: [], pointRadius: 0 }
            ]
        },
        options: commonOptions
    });
}

// Tải danh sách Agent đổ vào Dropdown
async function fetchAgentsList() {
    try {
        const response = await fetch('/api/agents');
        const agents = await response.json();
        const selectElem = document.getElementById('agent-select');
        
        if (agents.length === 0) {
            selectElem.innerHTML = `<option value="">Chưa có Agent nào</option>`;
            return;
        }

        selectElem.innerHTML = agents.map(a => `<option value="${a.agentId}">${a.hostname || a.agentId} (${a.ipAddress})</option>`).join('');
        
        // Tự động chọn Agent đầu tiên
        selectAgent(agents[0].agentId);
    } catch (err) {
        console.error("Lỗi lấy danh sách agent:", err);
    }
}

// Gọi API lấy chỉ số từ Database khi chọn Agent
async function selectAgent(agentId) {
    if (!agentId) return;
    currentAgentId = agentId;

    try {
        const response = await fetch(`/api/agents/${agentId}/summary`);
        if (response.ok) {
            const data = await response.json();
            
            // Cập nhật maxSpeedMBps của Agent hiện tại
            if (data.maxSpeedMBps && data.maxSpeedMBps > 0) {
                currentMaxSpeedMb = data.maxSpeedMBps;
            } else if (data.agent && data.agent.maxSpeed) {
                currentMaxSpeedMb = data.agent.maxSpeed;
            }
            
            renderAgentDataFromDB(data);
        }
    } catch (err) {
        console.error("Lỗi lấy chỉ số Database:", err);
    }

    resetCharts();
    subscribeAgentTopic(agentId);
}

// Đổ dữ liệu CSDL lên Banner & Thẻ Thống Kê
function renderAgentDataFromDB(data) {
    if (!data) return;

    const agent = data.agent;
    const m = data.latestMetrics;

    // Kiểm tra trạng thái Agent
    const isOnline = agent && agent.status === 'ONLINE';

    if (agent) {
        document.getElementById('info-hostname').innerText = agent.hostname || 'N/A';
        document.getElementById('info-agent-id').innerText = agent.agentId || 'N/A';
        document.getElementById('info-ip').innerText = agent.ipAddress || '127.0.0.1';
        document.getElementById('info-mac').innerText = agent.macAddress || '00:00:00:00:00:00';
        document.getElementById('info-os').innerText = agent.os || 'N/A';
        document.getElementById('info-nic').innerText = agent.nicName || 'Wi-Fi';
        document.getElementById('info-last-seen').innerText = agent.lastSeen ? agent.lastSeen.replace('T', ' ') : '--';

        const dot = document.getElementById('info-status-dot');
        if (dot) {
            dot.className = `profile-badge-status ${isOnline ? 'online' : 'offline'}`;
        }
    }

    // 1. Nếu Agent OFFLINE -> Ép các chỉ số tổng về 0.00 GB
    document.getElementById('metric-total-rx').innerText = (isOnline ? (data.totalRxGB || '0.00') : '0.00') + ' GB';
    document.getElementById('metric-total-tx').innerText = (isOnline ? (data.totalTxGB || '0.00') : '0.00') + ' GB';

    // 2. Xử lý các thẻ metric chi tiết
    if (isOnline && m) {
        // ONLINE: Hiển thị chỉ số thực tế từ latestMetrics
        const rxMb = Number(m.rxRate || 0).toFixed(2);
        const txMb = Number(m.txRate || 0).toFixed(2);
        document.getElementById('metric-rx-rate').innerText = `${rxMb} MB/s`;
        document.getElementById('metric-tx-rate').innerText = `${txMb} MB/s`;
        document.getElementById('metric-rx-packets').innerHTML = `${m.rxPacketRate || 0} <span class="unit">packets/s</span>`;
        document.getElementById('metric-tx-packets').innerHTML = `${m.txPacketRate || 0} <span class="unit">packets/s</span>`;
        const latencyFormatted = Number(m.latency || 0).toFixed(2);
        document.getElementById('metric-latency').innerHTML = `${latencyFormatted} <span class="unit">ms</span>`;
        if (document.getElementById('metric-loss-probe')) {
            document.getElementById('metric-loss-probe').innerHTML = `${(m.packetLoss || 0).toFixed(1)}% <span class="sub-probe">(20/20)</span>`;
        }
    } else {
        // OFFLINE: Đưa tất cả các thẻ chỉ số về 0 hết
        document.getElementById('metric-rx-rate').innerText = `0.00 MB/s`;
        document.getElementById('metric-tx-rate').innerText = `0.00 MB/s`;
        document.getElementById('metric-rx-packets').innerHTML = `0 <span class="unit">packets/s</span>`;
        document.getElementById('metric-tx-packets').innerHTML = `0 <span class="unit">packets/s</span>`;
        document.getElementById('metric-latency').innerHTML = `0.00 <span class="unit">ms</span>`;
        if (document.getElementById('metric-loss-probe')) {
            document.getElementById('metric-loss-probe').innerHTML = `0.0% <span class="sub-probe">(0/20)</span>`;
        }
    }
}

// Kết nối WebSocket
function connectWebSocket() {
    updateWsStatusUI(false);

    const socket = new SockJS('/ws-network-monitor');
    stompClient = Stomp.over(socket);
    stompClient.debug = null;

    stompClient.connect({}, function () {
        updateWsStatusUI(true);

        if (currentAgentId) {
            subscribeAgentTopic(currentAgentId);
        }
    }, function () {
        updateWsStatusUI(false);
        setTimeout(connectWebSocket, 5000);
    });
}

function subscribeAgentTopic(agentId) {
    if (!stompClient || !stompClient.connected) return;

    if (activeSubscription) {
        activeSubscription.unsubscribe();
    }

    activeSubscription = stompClient.subscribe('/topic/metrics/' + agentId, function (output) {
        const data = JSON.parse(output.body);
        pushMetricToCharts(data);
    });
}

// Nhận dữ liệu real-time và vẽ lên 4 đồ thị
function pushMetricToCharts(data) {
    if (!data || !data.metrics) return;
    const m = data.metrics;
    const timeLabel = new Date().toLocaleTimeString('vi-VN', { hour12: false });

    if (m.maxSpeed && m.maxSpeed > 0) {
        currentMaxSpeedMb = m.maxSpeed;
    }

    const rxMb = Number(m.rxRate || 0).toFixed(2);
    const txMb = Number(m.txRate || 0).toFixed(2);

    document.getElementById('metric-rx-rate').innerText = `${rxMb} MB/s`;
    document.getElementById('metric-tx-rate').innerText = `${txMb} MB/s`;
    document.getElementById('metric-rx-packets').innerHTML = `${m.rxPacketRate || 0} <span class="unit">packets/s</span>`;
    document.getElementById('metric-tx-packets').innerHTML = `${m.txPacketRate || 0} <span class="unit">packets/s</span>`;
    
    const latencyFormatted = Number(m.latency || 0).toFixed(2);
    document.getElementById('metric-latency').innerHTML = `${latencyFormatted} <span class="unit">ms</span>`;
    if (document.getElementById('metric-loss-probe')) {
        document.getElementById('metric-loss-probe').innerHTML = `${(m.packetLoss || 0).toFixed(1)}% <span class="sub-probe">(20/20)</span>`;
    }

    // TÍNH TOÁN CÁC ĐƯỜNG NGƯỠNG ĐỘNG DỰA TRÊN CSDL VÀ MAX SPEED CARD MẠNG
    const bwWarnVal = Number((currentMaxSpeedMb * (alertThresholds.bwWarnPercent / 100.0)).toFixed(2));
    const bwCritVal = Number((currentMaxSpeedMb * (alertThresholds.bwCritPercent / 100.0)).toFixed(2));
    addPoint(bandwidthChart, timeLabel, [rxMb, txMb, bwWarnVal, bwCritVal]);
    addPoint(latencyChart, timeLabel, [m.latency || 0, alertThresholds.latencyWarnMs, alertThresholds.latencyCritMs]);
    addPoint(packetRateChart, timeLabel, [m.rxPacketRate || 0, m.txPacketRate || 0]);
    addPoint(packetLossChart, timeLabel, [m.packetLoss || 0, alertThresholds.lossWarnPercent, alertThresholds.lossCritPercent]);
}

function addPoint(chart, label, values) {
    if (chart.data.labels.length >= MAX_DATA_POINTS) {
        chart.data.labels.shift();
        chart.data.datasets.forEach(ds => ds.data.shift());
    }
    chart.data.labels.push(label);
    chart.data.datasets.forEach((ds, index) => {
        if (values[index] !== undefined) {
            ds.data.push(values[index]);
        }
    });
    chart.update('none');
}

function resetCharts() {
    [bandwidthChart, latencyChart, packetRateChart, packetLossChart].forEach(chart => {
        if (chart) {
            chart.data.labels = [];
            chart.data.datasets.forEach(ds => ds.data = []);
            chart.update();
        }
    });
}

function deleteCurrentAgent() {
    const agentSelect = document.getElementById("agent-select");
    targetAgentIdToDelete = agentSelect ? agentSelect.value : null;

    if (!targetAgentIdToDelete) {
        if (typeof showToast === "function") {
            showToast("Vui lòng chọn một Agent để xóa!", "warning");
        } else {
            console.warn("Chưa có Agent nào được chọn.");
        }
        return;
    }

    const messageElem = document.getElementById("modal-delete-message");
    if (messageElem) {
        messageElem.innerText = `Bạn có chắc chắn muốn xóa Agent "${targetAgentIdToDelete}" và toàn bộ dữ liệu liên quan khỏi hệ thống?`;
    }

    const confirmBtn = document.getElementById("btn-modal-confirm-delete");
    if (confirmBtn) {
        confirmBtn.disabled = false;
        confirmBtn.innerText = "Xác nhận";
        confirmBtn.onclick = executeDeleteAgent;
    }

    const modalOverlay = document.getElementById("delete-modal-overlay");
    if (modalOverlay) {
        modalOverlay.style.display = "flex";
    }
}

// 2. Đóng Modal khi bấm Hủy
function closeDeleteModal() {
    const modalOverlay = document.getElementById("delete-modal-overlay");
    if (modalOverlay) {
        modalOverlay.style.display = "none";
    }
    targetAgentIdToDelete = null;
}

// 3. Thực thi gửi request DELETE tới Backend Server (Tuyệt đối /api/agents/)
function executeDeleteAgent() {
    if (!targetAgentIdToDelete) return;

    const confirmBtn = document.getElementById("btn-modal-confirm-delete");
    if (confirmBtn) {
        confirmBtn.disabled = true;
        confirmBtn.innerText = "Đang xóa...";
    }

    fetch(`/api/agents/${targetAgentIdToDelete}`, {
        method: 'DELETE',
        headers: {
            'Content-Type': 'application/json'
        }
    })
    .then(response => {
        if (response.ok) {
            closeDeleteModal();
            // Lưu thông báo vào sessionStorage trước khi chuyển trang
            sessionStorage.setItem("toast_msg", `Đã xóa Agent ${targetAgentIdToDelete} thành công!`);
            sessionStorage.setItem("toast_type", "success");
            
            // Chuyển hướng ngay lập tức về trang Tổng quan
            window.location.href = "index.html";
        } else {
            return response.json().then(data => {
                throw new Error(data.message || data.error || "Xóa Agent thất bại!");
            });
        }
    })
    .catch(error => {
        console.error("Error deleting agent:", error);
        if (confirmBtn) {
            confirmBtn.disabled = false;
            confirmBtn.innerText = "Xác nhận";
        }
        if (typeof showToast === "function") {
            showToast("Lỗi khi xóa Agent: " + error.message, "error");
        } else {
            closeDeleteModal();
        }
    });
}