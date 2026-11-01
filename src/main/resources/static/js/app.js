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

/**
 * Hàm phân loại 3 mức độ chất lượng Agent: CRITICAL, WARNING, NORMAL
 */
function checkAgentQualityStatus(agent) {
    if (!agent) return 'NORMAL';

    const latency = parseFloat(agent.latency || 0);
    const packetLoss = parseFloat(agent.packetLoss || 0);
    const rxRate = parseFloat(agent.rxRate || 0);
    const txRate = parseFloat(agent.txRate || 0);
    const maxSpeedMb = parseFloat(agent.maxSpeed || 125.0);
    const totalTraffic = rxRate + txRate;

    // 1. NGƯỠNG CRITICAL (Mất kết nối nghiêm trọng, cạn kiệt băng thông)
    const isCriticalLatency = latency > 150.0;
    const isCriticalLoss = packetLoss > 5.0;
    const isCriticalBandwidth = totalTraffic > (maxSpeedMb * 0.95);

    if (isCriticalLatency || isCriticalLoss || isCriticalBandwidth) {
        return 'CRITICAL';
    }

    // 2. NGƯỠNG WARNING (Suy hao đường truyền, lag nhẹ, tải cao)
    const isWarningLatency = latency > 80.0;
    const isWarningLoss = packetLoss > 1.0;
    const isWarningBandwidth = totalTraffic > (maxSpeedMb * 0.8);

    if (isWarningLatency || isWarningLoss || isWarningBandwidth) {
        return 'WARNING';
    }

    // 3. MỨC BÌNH THƯỜNG
    return 'NORMAL';
}

// Lấy danh sách Agent ban đầu từ CSDL qua API
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
                maxSpeed: agent.maxSpeed || existing.maxSpeed || 125.0,
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
    stompClient.debug = null;

    stompClient.connect({}, function (frame) {
        updateWsStatusUI(true);

        stompClient.subscribe('/topic/agents', function (messageOutput) {
            try {
                const payload = JSON.parse(messageOutput.body);

                if (Array.isArray(payload)) {
                    payload.forEach(agent => {
                        const id = agent.agentId || agent.id;
                        let existing = agentDataStore.get(id);
                        if (existing) {
                            existing.status = agent.status;
                            if (agent.maxSpeed) existing.maxSpeed = agent.maxSpeed;
                        } else {
                            agentDataStore.set(id, agent);
                        }
                    });
                } else if (payload && (payload.agentId || payload.id)) {
                    const id = payload.agentId || payload.id;
                    let existing = agentDataStore.get(id);
                    if (existing) {
                        existing.status = payload.status;
                        if (payload.maxSpeed) existing.maxSpeed = payload.maxSpeed;
                    }
                }

                updateSummaryCards();
                renderDashboard(); 
            } catch (err) {
                console.error("Lỗi parse dữ liệu từ /topic/agents:", err);
                fetchInitialAgents();
            }
        });

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
        setTimeout(connectWebSocket, 5000);
    });
}

// Xử lý gói tin metric từ WebSocket
function updateAgentMetric(data) {
    if (!data) return;
    
    const targetAgentId = data.agentId || (data.agent ? data.agent.agentId : null);
    if (!targetAgentId) return;

    let existing = agentDataStore.get(targetAgentId);
    if (!existing) {
        existing = {
            agentId: targetAgentId,
            hostname: targetAgentId,
            ipAddress: 'N/A',
            os: 'N/A',
            status: 'ONLINE',
            maxSpeed: 125.0
        };
    }

    if (data.agent && data.agent.maxSpeed) {
        existing.maxSpeed = data.agent.maxSpeed;
    }

    const m = data.metrics || data;
    if (m) {
        existing.rxRate = Number(m.rxRate || 0).toFixed(1);
        existing.txRate = Number(m.txRate || 0).toFixed(1);
        existing.latency = Math.round(m.latency || 0);
        existing.packetLoss = Number(m.packetLoss || 0).toFixed(1);
        if (m.maxSpeed) existing.maxSpeed = m.maxSpeed;
    }

    existing.status = 'ONLINE';
    agentDataStore.set(targetAgentId, existing);

    updateSingleAgentRow(existing);
    updateSummaryCards();
}

// Cập nhật trực tiếp 1 dòng trên bảng DOM
function updateSingleAgentRow(agent) {
    const tr = document.getElementById(`row-agent-${agent.agentId}`);
    if (!tr) {
        renderDashboard();
        return;
    }

    const isOnline = agent.status === 'ONLINE';
    const qualityStatus = checkAgentQualityStatus(agent);

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
        if (qualityStatus === 'CRITICAL') {
            qualityCell.innerHTML = `<span class="badge-critical">Critical</span>`;
        } else if (qualityStatus === 'WARNING') {
            qualityCell.innerHTML = `<span class="badge-warning">Warning</span>`;
        } else {
            qualityCell.innerHTML = `<span class="badge-normal">Normal</span>`;
        }
    }
}

// Cập nhật các thẻ thống kê tổng quan
function updateSummaryCards() {
    let totalCount = agentDataStore.size;
    let onlineCount = 0;
    let offlineCount = 0;
    let totalRx = 0;
    let warningCount = 0;
    let criticalCount = 0;

    agentDataStore.forEach(agent => {
        if (agent.status === 'ONLINE') onlineCount++; else offlineCount++;
        totalRx += parseFloat(agent.rxRate || 0);

        const status = checkAgentQualityStatus(agent);
        if (status === 'CRITICAL') {
            criticalCount++;
        } else if (status === 'WARNING') {
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

    const elCritical = document.getElementById('metric-critical');
    if (elCritical) elCritical.innerText = criticalCount;

    const alertBadge = document.getElementById('alert-count-badge');
    if (alertBadge) alertBadge.innerText = warningCount + criticalCount;
}

// Vẽ lại toàn bộ bảng danh sách
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
        const qualityStatus = checkAgentQualityStatus(agent);

        if (filterIp && !agent.ipAddress.toLowerCase().includes(filterIp)) return;
        if (filterOs && !agent.os.toLowerCase().includes(filterOs)) return;
        if (filterStatus && agent.status !== filterStatus) return;

        const tr = document.createElement('tr');
        tr.id = `row-agent-${agent.agentId}`;

        let qualityBadgeHtml = `<span class="badge-normal">Normal</span>`;
        if (qualityStatus === 'CRITICAL') {
            qualityBadgeHtml = `<span class="badge-critical">Critical</span>`;
        } else if (qualityStatus === 'WARNING') {
            qualityBadgeHtml = `<span class="badge-warning">Warning</span>`;
        }

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
            <td class="text-center cell-quality">${qualityBadgeHtml}</td>
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