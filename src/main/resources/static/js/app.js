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

function initEventListeners() {
    const themeBtn = document.getElementById('theme-toggle-btn');
    if (themeBtn) themeBtn.addEventListener('click', toggleDarkMode);

    const ipInput = document.getElementById('filter-ip');
    if (ipInput) ipInput.addEventListener('input', renderDashboard);

    const osSelect = document.getElementById('filter-os');
    if (osSelect) osSelect.addEventListener('change', renderDashboard);

    const statusSelect = document.getElementById('filter-status');
    if (statusSelect) statusSelect.addEventListener('change', renderDashboard);

    const resetBtn = document.getElementById('reset-filter-btn');
    if (resetBtn) resetBtn.addEventListener('click', resetFilters);
}

function toggleDarkMode() {
    document.body.classList.toggle('dark');
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
    setInterval(() => {
        const timeElem = document.getElementById('ws-live-time');
        if (timeElem) {
            timeElem.innerText = new Date().toLocaleTimeString('vi-VN', { hour12: false });
        }
    }, 1000);
}

// Lấy danh sách Agent ban đầu từ CSDL
async function fetchInitialAgents() {
    try {
        const response = await fetch('/api/agents');
        if (!response.ok) return;

        const agents = await response.json();
        
        agents.forEach(agent => {
            const id = agent.agentId || agent.id;
            const existing = agentDataStore.get(id) || {};

            agentDataStore.set(id, {
                agentId: id,
                hostname: agent.hostname || id,
                ipAddress: agent.ipAddress || 'N/A',
                os: agent.os || 'N/A',
                status: agent.status || 'OFFLINE',
                rxRate: existing.rxRate || '0.0',
                txRate: existing.txRate || '0.0',
                latency: existing.latency || 0,
                packetLoss: existing.packetLoss || 0
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

    if (typeof SockJS === 'undefined' || typeof Stomp === 'undefined') return;

    const socket = new SockJS('/ws-network-monitor');
    stompClient = Stomp.over(socket);
    stompClient.debug = null; // Tắt log debug console

    stompClient.connect({}, function (frame) {
        updateWsStatusUI(true);

        //ĐĂNG KÝ KÊNH CẬP NHẬT TRẠNG THÁI AGENT (ONLINE/OFFLINE)
        stompClient.subscribe('/topic/agents', function (messageOutput) {
            try {
                const payload = JSON.parse(messageOutput.body);

                // TH1: Backend trả về MẢNG toàn bộ danh sách Agent (từ AgentStatusScheduler)
                if (Array.isArray(payload)) {
                    payload.forEach(agent => {
                        const id = agent.agentId || agent.id;
                        let existing = agentDataStore.get(id);
                        if (existing) {
                            existing.status = agent.status; // Cập nhật trạng thái mới (OFFLINE)
                        } else {
                            agentDataStore.set(id, agent);
                        }
                    });
                } 
                // TH2: Backend trả về ĐỐI TƯỢNG 1 Agent lẻ (từ AgentService/markAgentOffline)
                else if (payload && (payload.agentId || payload.id)) {
                    const id = payload.agentId || payload.id;
                    let existing = agentDataStore.get(id);
                    if (existing) {
                        existing.status = payload.status;
                    }
                }

                updateSummaryCards();
                renderDashboard(); 
            } catch (err) {
                console.error("Lỗi parse dữ liệu từ /topic/agents:", err);
                fetchInitialAgents(); // Dự phòng nếu lỗi
            }
        });

        // ĐĂNG KÝ KÊNH METRICS REALTIME CỦA CÁC AGENT
        stompClient.subscribe('/topic/metrics/*', function (messageOutput) {
            try {
                const data = JSON.parse(messageOutput.body);
                updateAgentMetric(data);
            } catch (e) {
                console.error("Lỗi parse metric WebSocket:", e);
            }
        });

    }, function (error) {
        updateWsStatusUI(false);
        setTimeout(connectWebSocket, 5000); // Tự nối lại sau 5s nếu đứt kết nối
    });
}

//Xử lý gói tin metric từ WebSocket gửi về
function updateAgentMetric(data) {
    if (!data) return;
    
    // Đảm bảo lấy đúng agentId dù định dạng JSON khác nhau
    const targetAgentId = data.agentId || (data.agent ? data.agent.agentId : null);
    if (!targetAgentId) return;

    let existing = agentDataStore.get(targetAgentId);
    if (!existing) {
        existing = {
            agentId: targetAgentId,
            hostname: targetAgentId,
            ipAddress: 'N/A',
            os: 'N/A',
            status: 'ONLINE'
        };
    }

    const m = data.metrics || data;
    if (m) {
        existing.rxRate = Number(m.rxRate || 0).toFixed(1);
        existing.txRate = Number(m.txRate || 0).toFixed(1);
        existing.latency = Math.round(m.latency || 0);
        existing.packetLoss = Number(m.packetLoss || 0).toFixed(1);
    }

    existing.status = 'ONLINE';
    agentDataStore.set(targetAgentId, existing);

    updateSingleAgentRow(existing);
    updateSummaryCards();
}

// Cập nhật trực tiếp vào 1 dòng duy nhất trên bảng DOM
function updateSingleAgentRow(agent) {
    const tr = document.getElementById(`row-agent-${agent.agentId}`);
    if (!tr) {
        renderDashboard();
        return;
    }

    const isOnline = agent.status === 'ONLINE';
    const isWarning = parseFloat(agent.latency) > 100 || parseFloat(agent.packetLoss) > 5;

    const dot = tr.querySelector('.dot-status');
    if (dot) dot.className = `dot-status ${isOnline ? 'dot-online' : 'dot-offline'}`;

    const statusText = tr.querySelector('.status-text-val');
    if (statusText) {
        statusText.innerText = agent.status;
        statusText.style.color = isOnline ? '#059669' : '#6b7280';
    }

    const rxCell = tr.querySelector('.cell-rx');
    if (rxCell) rxCell.innerText = agent.rxRate || '0.0';

    const txCell = tr.querySelector('.cell-tx');
    if (txCell) txCell.innerText = agent.txRate || '0.0';

    const latencyCell = tr.querySelector('.cell-latency');
    if (latencyCell) latencyCell.innerText = agent.latency || 0;

    const lossCell = tr.querySelector('.cell-loss');
    if (lossCell) lossCell.innerText = (agent.packetLoss || 0) + '%';

    const qualityCell = tr.querySelector('.cell-quality');
    if (qualityCell) {
        qualityCell.innerHTML = isWarning 
            ? `<span class="badge-warning">Warning</span>`
            : `<span class="badge-normal">Normal</span>`;
    }
}

// Cập nhật 5 thẻ 
function updateSummaryCards() {
    let totalCount = agentDataStore.size;
    let onlineCount = 0;
    let offlineCount = 0;
    let totalRx = 0;
    let warningCount = 0;

    agentDataStore.forEach(agent => {
        if (agent.status === 'ONLINE') onlineCount++; else offlineCount++;
        totalRx += parseFloat(agent.rxRate || 0);

        if (parseFloat(agent.latency) > 100 || parseFloat(agent.packetLoss) > 5) {
            warningCount++;
        }
    });

    const elTotal = document.getElementById('metric-total');
    if (elTotal) elTotal.innerText = totalCount;

    const elOnline = document.getElementById('metric-online');
    if (elOnline) elOnline.innerText = onlineCount;

    const elOffline = document.getElementById('metric-offline');
    if (elOffline) elOffline.innerText = offlineCount;

    const elRx = document.getElementById('metric-rx-total');
    if (elRx) elRx.innerText = totalRx.toFixed(1) + ' MB/s';

    const elWarn = document.getElementById('metric-warning');
    if (elWarn) elWarn.innerText = warningCount;

    const alertBadge = document.getElementById('alert-count-badge');
    if (alertBadge) alertBadge.innerText = warningCount;
}

//Vẽ lại toàn bộ bảng 
function renderDashboard() {
    const tbody = document.getElementById('agent-table-body');
    if (!tbody) return;

    tbody.innerHTML = '';

    const filterIpVal = document.getElementById('filter-ip');
    const filterOsVal = document.getElementById('filter-os');
    const filterStatusVal = document.getElementById('filter-status');

    const filterIp = filterIpVal ? filterIpVal.value.toLowerCase().trim() : '';
    const filterOs = filterOsVal ? filterOsVal.value.toLowerCase() : '';
    const filterStatus = filterStatusVal ? filterStatusVal.value : '';

    updateSummaryCards();

    agentDataStore.forEach(agent => {
        const isOnline = agent.status === 'ONLINE';
        const isWarning = parseFloat(agent.latency) > 100 || parseFloat(agent.packetLoss) > 5;

        if (filterIp && !agent.ipAddress.toLowerCase().includes(filterIp)) return;
        if (filterOs && !agent.os.toLowerCase().includes(filterOs)) return;
        if (filterStatus && agent.status !== filterStatus) return;

        const tr = document.createElement('tr');
        tr.id = `row-agent-${agent.agentId}`;

        const qualityBadge = isWarning 
            ? `<span class="badge-warning">Warning</span>`
            : `<span class="badge-normal">Normal</span>`;

        tr.innerHTML = `
            <td>
                <div class="status-indicator">
                    <span class="dot-status ${isOnline ? 'dot-online' : 'dot-offline'}"></span>
                    <span class="status-text-val" style="color: ${isOnline ? '#059669' : '#6b7280'}; font-weight: 600;">${agent.status}</span>
                </div>
            </td>
            <td>
                <div style="font-weight: 700;">${agent.hostname}</div>
                <div style="font-size: 12px; color: var(--text-secondary);">${agent.agentId}</div>
            </td>
            <td style="font-family: monospace;">${agent.ipAddress}</td>
            <td>${agent.os}</td>
            <td class="text-right cell-rx" style="font-weight: 600;">${agent.rxRate || '0.0'}</td>
            <td class="text-right cell-tx" style="font-weight: 600;">${agent.txRate || '0.0'}</td>
            <td class="text-right cell-latency" style="font-weight: 600;">${agent.latency || 0}</td>
            <td class="text-right cell-loss" style="font-weight: 600;">${(agent.packetLoss || 0)}%</td>
            <td class="text-center cell-quality">${qualityBadge}</td>
        `;

        tbody.appendChild(tr);
    });
}

function resetFilters() {
    const ipInput = document.getElementById('filter-ip');
    if (ipInput) ipInput.value = '';

    const osSelect = document.getElementById('filter-os');
    if (osSelect) osSelect.value = '';

    const statusSelect = document.getElementById('filter-status');
    if (statusSelect) statusSelect.value = '';

    renderDashboard();
}