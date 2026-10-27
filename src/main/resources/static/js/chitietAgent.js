let stompClient = null;
let currentAgentId = null;
let activeSubscription = null;

let bandwidthChart, latencyChart, packetRateChart, packetLossChart;
const MAX_DATA_POINTS = 30; // ~60 giây dữ liệu

document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();
    initCharts();
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

function updateWsStatusUI(isConnected) {
    const container = document.getElementById('ws-status-container');
    const currentTime = new Date().toLocaleTimeString('vi-VN', { hour12: false });

    if (isConnected) {
        container.innerHTML = `
            <div class="ws-status-connected">
                <span class="dot-ping-wrapper">
                    <span class="dot-ping"></span>
                    <span class="dot-solid-green"></span>
                </span>
                <span>WebSocket · TCP :9999 · <span>${currentTime}</span></span>
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

    // Chart 1: Bandwidth RX / TX
    bandwidthChart = new Chart(document.getElementById('bandwidthChart'), {
        type: 'line',
        data: {
            labels: [],
            datasets: [
                { label: 'RX', borderColor: '#2980b9', borderWidth: 2, data: [], pointRadius: 0, tension: 0.2 },
                { label: 'TX', borderColor: '#d97706', borderWidth: 2, data: [], pointRadius: 0, tension: 0.2 },
                { label: 'Ngưỡng', borderColor: '#ef4444', borderWidth: 1, borderDash: [4, 4], data: [], pointRadius: 0 }
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
                { label: 'Ngưỡng', borderColor: '#ef4444', borderWidth: 1, borderDash: [4, 4], data: [], pointRadius: 0 }
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
                { label: 'Ngưỡng', borderColor: '#ef4444', borderWidth: 1, borderDash: [4, 4], data: [], pointRadius: 0 }
            ]
        },
        options: commonOptions
    });
}

//Tải danh sách Agent đổ vào Dropdown
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
            renderAgentDataFromDB(data);
        }
    } catch (err) {
        console.error("Lỗi lấy chỉ số Database:", err);
    }

    resetCharts();
    subscribeAgentTopic(agentId);
}

// Đổ dữ liệu CSDL lên Banner & 8 Thẻ Thống Kê
function renderAgentDataFromDB(data) {
    const agent = data.agent;
    const m = data.latestMetrics;

    // Nạp thông tin Agent Banner
    if (agent) {
        document.getElementById('info-hostname').innerText = agent.hostname || 'N/A';
        document.getElementById('info-agent-id').innerText = agent.agentId || 'N/A';
        document.getElementById('info-ip').innerText = agent.ipAddress || '127.0.0.1';
        document.getElementById('info-mac').innerText = agent.macAddress || '00:00:00:00:00:00';
        document.getElementById('info-os').innerText = agent.os || 'N/A';
        document.getElementById('info-nic').innerText = agent.nicName || 'Wi-Fi';
        document.getElementById('info-last-seen').innerText = agent.lastSeen ? agent.lastSeen.replace('T', ' ') : '--';

        const dot = document.getElementById('info-status-dot');
        dot.className = `profile-badge-status ${agent.status === 'ONLINE' ? 'online' : 'offline'}`;
    }

    // Nạp tổng RX / TX tính từ DB
    document.getElementById('metric-total-rx').innerText = (data.totalRxGB || '0.00') + ' GB';
    document.getElementById('metric-total-tx').innerText = (data.totalTxGB || '0.00') + ' GB';

    // Nạp thông số bản ghi gần nhất trong DB
    if (m) {
        const rxMb = Number(m.rxRate || 0).toFixed(2);
        const txMb = Number(m.txRate || 0).toFixed(2);
        document.getElementById('metric-rx-rate').innerText = `${rxMb} MB/s`;
        document.getElementById('metric-tx-rate').innerText = `${txMb} MB/s`;
        document.getElementById('metric-rx-packets').innerHTML = `${m.rxPacketRate || 0} <span class="unit">packets/s</span>`;
        document.getElementById('metric-tx-packets').innerHTML = `${m.txPacketRate || 0} <span class="unit">packets/s</span>`;
        const latencyFormatted = Number(m.latency || 0).toFixed(2);
        document.getElementById('metric-latency').innerHTML = `${latencyFormatted} <span class="unit">ms</span>`;
        document.getElementById('metric-loss-probe').innerHTML = `${(m.packetLoss || 0).toFixed(1)}% <span class="sub-probe">(20/20)</span>`;
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

    const rxMb = Number(m.rxRate || 0).toFixed(2);
    const txMb = Number(m.txRate || 0).toFixed(2);

    document.getElementById('metric-rx-rate').innerText = `${rxMb} MB/s`;
    document.getElementById('metric-tx-rate').innerText = `${txMb} MB/s`;
    document.getElementById('metric-rx-packets').innerHTML = `${m.rxPacketRate || 0} <span class="unit">packets/s</span>`;
    document.getElementById('metric-tx-packets').innerHTML = `${m.txPacketRate || 0} <span class="unit">packets/s</span>`;
    
    const latencyFormatted = Number(m.latency || 0).toFixed(2);
    document.getElementById('metric-latency').innerHTML = `${latencyFormatted} <span class="unit">ms</span>`;

    // 1. Biểu đồ Băng thông RX / TX
    addPoint(bandwidthChart, timeLabel, [rxMb, txMb, 80]);

    // 2. Biểu đồ Latency
    addPoint(latencyChart, timeLabel, [m.latency || 0, 100]);

    // 3. Biểu đồ Packet Rate
    addPoint(packetRateChart, timeLabel, [m.rxPacketRate || 0, m.txPacketRate || 0]);

    // 4. Biểu đồ Packet Loss
    addPoint(packetLossChart, timeLabel, [m.packetLoss || 0, 5]);
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