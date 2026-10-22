// Quản lý Dữ liệu & Kết nối
let agentDataStore = new Map();
let stompClient = null;

// Khởi chạy khi DOM tải xong
document.addEventListener('DOMContentLoaded', () => {
    initEventListeners();
    fetchInitialAgents();
    connectWebSocket();
    startClock();
});

// Đăng ký Event Listeners
function initEventListeners() {
    document.getElementById('theme-toggle-btn').addEventListener('click', toggleDarkMode);
    document.getElementById('filter-ip').addEventListener('keyup', renderDashboard);
    document.getElementById('filter-os').addEventListener('change', renderDashboard);
    document.getElementById('filter-status').addEventListener('change', renderDashboard);
    document.getElementById('reset-filter-btn').addEventListener('click', resetFilters);
}

// Chuyển đổi giao diện Sáng/Tối
function toggleDarkMode() {
    document.body.classList.toggle('dark');
}

// Cập nhật giao diện trạng thái kết nối WebSocket
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

// Chạy đồng hồ đếm giây cho WebSocket
function startClock() {
    setInterval(() => {
        const timeElem = document.getElementById('ws-live-time');
        if (timeElem) {
            timeElem.innerText = new Date().toLocaleTimeString('vi-VN', { hour12: false });
        }
    }, 1000);
}

// Tải danh sách Agent ban đầu từ REST API
async function fetchInitialAgents() {
    try {
        const response = await fetch('/api/agents');
        const agents = await response.json();
        
        agents.forEach(agent => {
            agentDataStore.set(agent.agentId, {
                agentId: agent.agentId,
                hostname: agent.hostname || agent.agentId,
                ipAddress: agent.ipAddress || 'N/A',
                os: agent.os || 'N/A',
                status: agent.status || 'OFFLINE',
                rxRate: 0,
                txRate: 0,
                latency: 0,
                packetLoss: 0
            });
        });

        renderDashboard();
    } catch (err) {
        console.error("Lỗi kết nối REST API agents:", err);
    }
}

// Kết nối WebSocket STOMP
function connectWebSocket() {
    updateWsStatusUI(false);

    const socket = new SockJS('/ws-network-monitor');
    stompClient = Stomp.over(socket);
    stompClient.debug = null;

    stompClient.connect({}, function (frame) {
        updateWsStatusUI(true);

        stompClient.subscribe('/topic/agents', function (message) {
            fetchInitialAgents();
        });

        stompClient.subscribe('/topic/metrics/*', function (messageOutput) {
            const data = JSON.parse(messageOutput.body);
            updateAgentMetric(data);
        });

    }, function (error) {
        updateWsStatusUI(false);
        setTimeout(connectWebSocket, 5000);
    });
}

// Cập nhật gói tin metric mới
function updateAgentMetric(data) {
    if (!data || !data.agentId) return;

    let existing = agentDataStore.get(data.agentId) || {
        agentId: data.agentId,
        hostname: data.agentId,
        ipAddress: 'N/A',
        os: 'N/A',
        status: 'ONLINE'
    };

    if (data.metrics) {
        existing.rxRate = ((data.metrics.rxRate || 0) / 1024).toFixed(1);
        existing.txRate = ((data.metrics.txRate || 0) / 1024).toFixed(1);
        existing.latency = data.metrics.latency || 0;
        existing.packetLoss = data.metrics.packetLoss || 0;
    }

    existing.status = 'ONLINE';
    agentDataStore.set(data.agentId, existing);
    renderDashboard();
}

// Đẩy dữ liệu ra bảng
function renderDashboard() {
    const tbody = document.getElementById('agent-table-body');
    tbody.innerHTML = '';

    const filterIp = document.getElementById('filter-ip').value.toLowerCase();
    const filterOs = document.getElementById('filter-os').value;
    const filterStatus = document.getElementById('filter-status').value;

    let totalCount = agentDataStore.size;
    let onlineCount = 0;
    let offlineCount = 0;
    let totalRx = 0;
    let warningCount = 0;

    agentDataStore.forEach(agent => {
        const isOnline = agent.status === 'ONLINE';
        if (isOnline) onlineCount++; else offlineCount++;
        totalRx += parseFloat(agent.rxRate || 0);

        const isWarning = agent.latency > 100 || agent.packetLoss > 5;
        if (isWarning) warningCount++;

        if (filterIp && !agent.ipAddress.toLowerCase().includes(filterIp)) return;
        if (filterOs && !agent.os.toLowerCase().includes(filterOs.toLowerCase())) return;
        if (filterStatus && agent.status !== filterStatus) return;

        const tr = document.createElement('tr');

        const qualityBadge = isWarning 
            ? `<span class="badge-warning">Warning</span>`
            : `<span class="badge-normal">Normal</span>`;

        tr.innerHTML = `
            <td>
                <div class="status-indicator">
                    <span class="dot-status ${isOnline ? 'dot-online' : 'dot-offline'}"></span>
                    <span style="color: ${isOnline ? '#059669' : '#6b7280'}; font-weight: 600;">${agent.status}</span>
                </div>
            </td>
            <td>
                <div style="font-weight: 700;">${agent.hostname}</div>
                <div style="font-size: 12px; color: var(--text-secondary);">${agent.agentId}</div>
            </td>
            <td style="font-family: monospace;">${agent.ipAddress}</td>
            <td>${agent.os}</td>
            <td class="text-right" style="font-weight: 600;">${agent.rxRate || '0.0'}</td>
            <td class="text-right" style="font-weight: 600;">${agent.txRate || '0.0'}</td>
            <td class="text-right" style="font-weight: 600;">${agent.latency || 0}</td>
            <td class="text-right" style="font-weight: 600;">${agent.packetLoss || 0}%</td>
            <td class="text-center">${qualityBadge}</td>
        `;

        tbody.appendChild(tr);
    });

    document.getElementById('metric-total').innerText = totalCount;
    document.getElementById('metric-online').innerText = onlineCount;
    document.getElementById('metric-offline').innerText = offlineCount;
    document.getElementById('metric-rx-total').innerText = totalRx.toFixed(1) + ' MB/s';
    document.getElementById('metric-warning').innerText = warningCount;
    document.getElementById('alert-count-badge').innerText = warningCount;
}

// Reset bộ lọc
function resetFilters() {
    document.getElementById('filter-ip').value = '';
    document.getElementById('filter-os').value = '';
    document.getElementById('filter-status').value = '';
    renderDashboard();
}