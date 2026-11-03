let agentDataStore = new Map();
let stompClient = null;

let globalThresholds = {
    bwWarnPercent: 80.0,
    bwCritPercent: 95.0,
    latencyWarnMs: 80.0,
    latencyCritMs: 150.0,
    lossWarnPercent: 1.0,
    lossCritPercent: 5.0
};

// Khởi chạy khi DOM tải xong
document.addEventListener('DOMContentLoaded', () => {
    initEventListeners();
    fetchThresholdsConfig().then(() => {
        fetchInitialAgents();
    });
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

// 1. Nạp bộ ngưỡng cấu hình mới nhất từ CSDL qua REST API
async function fetchThresholdsConfig() {
    try {
        const response = await fetch('/api/alerts/thresholds');
        if (response.ok) {
            const data = await response.json();
            globalThresholds = {
                bwWarnPercent: data.bwWarnPercent ?? 80.0,
                bwCritPercent: data.bwCritPercent ?? 95.0,
                latencyWarnMs: data.latencyWarnMs ?? 80.0,
                latencyCritMs: data.latencyCritMs ?? 150.0,
                lossWarnPercent: data.lossWarnPercent ?? 1.0,
                lossCritPercent: data.lossCritPercent ?? 5.0
            };
        }
    } catch (err) {
        console.error("Lỗi nạp cấu hình ngưỡng từ REST API:", err);
    }
}

/**
 * Phân loại chất lượng Agent sử dụng qualityStatus từ CSDL 
 * hoặc so sánh động với bộ ngưỡng CSDL (CRITICAL > WARNING > NORMAL)
 */
function checkAgentQualityStatus(agent) {
    if (!agent) return 'NORMAL';

    //Đọc thuộc tính qualityStatus trực tiếp từ CSDL/Backend
    if (agent.qualityStatus) {
        return agent.qualityStatus.toUpperCase();
    }

    //Tính toán động theo đúng ngưỡng lấy từ CSDL 
    const latency = parseFloat(agent.latency || 0);
    const packetLoss = parseFloat(agent.packetLoss || 0);
    const rxRate = parseFloat(agent.rxRate || 0);
    const txRate = parseFloat(agent.txRate || 0);
    const maxSpeedMb = parseFloat(agent.maxSpeed || 125.0);
    const usagePercent = ((rxRate + txRate) / maxSpeedMb) * 100.0;

    // KIỂM TRA MỨC CRITICAL
    const isCriticalLatency = latency >= globalThresholds.latencyCritMs;
    const isCriticalLoss = packetLoss >= globalThresholds.lossCritPercent;
    const isCriticalBandwidth = usagePercent >= globalThresholds.bwCritPercent;

    if (isCriticalLatency || isCriticalLoss || isCriticalBandwidth) {
        return 'CRITICAL';
    }

    // KIỂM TRA MỨC WARNING 
    const isWarningLatency = latency >= globalThresholds.latencyWarnMs;
    const isWarningLoss = packetLoss >= globalThresholds.lossWarnPercent;
    const isWarningBandwidth = usagePercent >= globalThresholds.bwWarnPercent;

    if (isWarningLatency || isWarningLoss || isWarningBandwidth) {
        return 'WARNING';
    }

    return 'NORMAL';
}

// 2. Lấy danh sách Agent ban đầu từ CSDL qua REST API
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
                qualityStatus: agent.qualityStatus || existing.qualityStatus || 'NORMAL',
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
                            if (agent.qualityStatus) existing.qualityStatus = agent.qualityStatus;
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
                        if (payload.qualityStatus) existing.qualityStatus = payload.qualityStatus;
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
            maxSpeed: 125.0,
            qualityStatus: 'NORMAL'
        };
    }

    if (data.agent) {
        if (data.agent.maxSpeed) existing.maxSpeed = data.agent.maxSpeed;
        if (data.agent.qualityStatus) existing.qualityStatus = data.agent.qualityStatus;
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
    let exceededCount = 0; // Tổng số Agent vượt ngưỡng (CRITICAL + WARNING)

    agentDataStore.forEach(agent => {
        if (agent.status === 'ONLINE') {
            onlineCount++;
        } else {
            offlineCount++;
        }
        
        totalRx += parseFloat(agent.rxRate || 0);

        // Đọc chất lượng từ CSDL REST API hoặc hàm kiểm tra
        const quality = (agent.qualityStatus || checkAgentQualityStatus(agent) || 'NORMAL').toUpperCase();

        // Đếm tổng nếu chất lượng ở mức WARNING hoặc CRITICAL
        if (quality === 'CRITICAL' || quality === 'WARNING') {
            exceededCount++;
        }
    });

    // 1. Tổng số Agent
    const elTotal = document.getElementById('metric-total');
    if (elTotal) elTotal.innerText = totalCount;

    // 2. Số Agent Online
    const elOnline = document.getElementById('metric-online');
    if (elOnline) elOnline.innerText = onlineCount;

    // 3. Số Agent Offline
    const elOffline = document.getElementById('metric-offline');
    if (elOffline) elOffline.innerText = offlineCount;

    // 4. Băng thông RX tổng
    const elRx = document.getElementById('metric-rx-total');
    if (elRx) elRx.innerText = totalRx.toFixed(1) + ' MB/s';

    // 5. Cập nhật thẻ Agent vượt ngưỡng
    const elExceeded = document.getElementById('metric-warning');
    if (elExceeded) {
        elExceeded.innerText = exceededCount;
    }
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

        // Lọc theo bộ lọc giao diện
        if (filterIp && !agent.ipAddress.toLowerCase().includes(filterIp)) return;
        if (filterOs && !agent.os.toLowerCase().includes(filterOs)) return;
        if (filterStatus && agent.status !== filterStatus) return;

        const tr = document.createElement('tr');
        tr.id = `row-agent-${agent.agentId}`;

        // 1. Xử lý các chỉ số số liệu 
        const rxText = isOnline ? (agent.rxRate !== undefined ? agent.rxRate : '0.0') : '-';
        const txText = isOnline ? (agent.txRate !== undefined ? agent.txRate : '0.0') : '-';
        const latencyText = isOnline ? (agent.latency !== undefined ? agent.latency : 0) : '-';
        const lossText = isOnline ? `${agent.packetLoss !== undefined ? agent.packetLoss : 0}%` : '-';

        // 2. Xử lý cột Chất lượng
        let qualityBadgeHtml = `-`;

        if (isOnline) {
            // Lấy qualityStatus trực tiếp từ CSDL hoặc dùng hàm kiểm tra
            const qualityStatus = (agent.qualityStatus || checkAgentQualityStatus(agent) || 'NORMAL').toUpperCase();

            if (qualityStatus === 'CRITICAL') {
                qualityBadgeHtml = `<span class="badge-critical">Critical</span>`;
            } else if (qualityStatus === 'WARNING') {
                qualityBadgeHtml = `<span class="badge-warning">Warning</span>`;
            } else {
                qualityBadgeHtml = `<span class="badge-normal">Normal</span>`;
            }
        }

        tr.innerHTML = `
            <td>
                <div class="status-indicator">
                    <span class="dot-status ${isOnline ? 'dot-online' : 'dot-offline'}"></span>
                    <span class="status-text-val" style="color: ${isOnline ? '#059669' : '#6b7280'}; font-weight: 600;">${agent.status}</span>
                </div>
            </td>
            <td>
                <div style="font-weight: 700;">${agent.hostname || 'Unknown'}</div>
                <div style="font-size: 12px; color: var(--text-secondary);">${agent.agentId}</div>
            </td>
            <td style="font-family: monospace;">${agent.ipAddress || '-'}</td>
            <td>${agent.os || '-'}</td>
            <td class="text-right cell-rx" style="font-weight: 600;">${rxText}</td>
            <td class="text-right cell-tx" style="font-weight: 600;">${txText}</td>
            <td class="text-right cell-latency" style="font-weight: 600;">${latencyText}</td>
            <td class="text-right cell-loss" style="font-weight: 600;">${lossText}</td>
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