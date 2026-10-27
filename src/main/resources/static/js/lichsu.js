let historyChart = null;
let stompClient = null;

document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();
    initHistoryChart();
    fetchAgentDropdown();
    connectWebSocket(); 

    // Mặc định chọn 5 phút gần nhất khi mở trang
    setQuickTime(5);

    document.getElementById('btn-search-history').addEventListener('click', performQuery);
});

function initThemeToggle() {
    const btn = document.getElementById('theme-toggle-btn');
    if (btn) {
        btn.addEventListener('click', () => {
            document.body.classList.toggle('dark');
        });
    }
}

// 1. Hàm cập nhật giao diện WebSocket Status Component trên Top Header
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

// 2. Khởi tạo kết nối WebSocket với Spring Boot Backend
function connectWebSocket() {
    updateWsStatusUI(false);

    if (typeof SockJS === 'undefined' || typeof Stomp === 'undefined') {
        console.warn("Chưa nạp thư viện SockJS/STOMP");
        return;
    }

    const socket = new SockJS('/ws-network-monitor');
    stompClient = Stomp.over(socket);
    stompClient.debug = null; // Tắt log debug trên Console

    stompClient.connect({}, function () {
        updateWsStatusUI(true);

        // Đăng ký nhận thông báo thay đổi Agent hoặc Alert nếu cần
        stompClient.subscribe('/topic/agents', function () {
            // Khi có sự kiện kết nối mới, tự động nạp lại danh sách Dropdown
            fetchAgentDropdown();
        });
    }, function () {
        updateWsStatusUI(false);
        // Tự động thử kết nối lại sau 5 giây nếu mất kết nối
        setTimeout(connectWebSocket, 5000);
    });
}

// 3. Khởi tạo Đồ thị Lịch sử (Chart.js)
function initHistoryChart() {
    const canvas = document.getElementById('historyChart');
    if (!canvas) return;

    const ctx = canvas.getContext('2d');
    historyChart = new Chart(ctx, {
        type: 'line',
        data: {
            labels: [],
            datasets: [
                {
                    label: 'RX (MB/s)',
                    borderColor: '#0d9488',
                    borderWidth: 2,
                    data: [],
                    pointRadius: 2,
                    tension: 0.2
                },
                {
                    label: 'TX (MB/s)',
                    borderColor: '#d97706',
                    borderWidth: 2,
                    data: [],
                    pointRadius: 2,
                    tension: 0.2
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            scales: {
                x: { grid: { display: false } },
                y: { beginAtZero: true, grid: { color: 'rgba(200, 200, 200, 0.15)' } }
            }
        }
    });
}

// 4. Tải danh sách Agent từ DB nạp vào Dropdown
async function fetchAgentDropdown() {
    try {
        const response = await fetch('/api/agents');
        if (!response.ok) return;

        const agents = await response.json();
        const select = document.getElementById('history-agent-select');
        if (!select) return;

        const currentVal = select.value;

        if (agents.length === 0) {
            select.innerHTML = `<option value="">Không có Agent trong CSDL</option>`;
            return;
        }

        select.innerHTML = agents.map(a => 
            `<option value="${a.agentId}">${a.hostname || a.agentId} (${a.agentId})</option>`
        ).join('');

        if (currentVal && Array.from(select.options).some(opt => opt.value === currentVal)) {
            select.value = currentVal;
        }

        performQuery();
    } catch (err) {
        console.error("Lỗi lấy danh sách Agent:", err);
    }
}

// 5. Nút chọn nhanh mốc thời gian (1 phút, 5 phút, 15 phút)
function setQuickTime(minutes) {
    const now = new Date();
    const past = new Date(now.getTime() - minutes * 60 * 1000);

    const startElem = document.getElementById('history-start-time');
    const endElem = document.getElementById('history-end-time');

    if (startElem && endElem) {
        endElem.value = formatDateForInput(now);
        startElem.value = formatDateForInput(past);
        performQuery();
    }
}

function formatDateForInput(date) {
    const pad = (n) => n < 10 ? '0' + n : n;
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

// 6. Thực hiện truy vấn dữ liệu từ MySQL qua API
async function performQuery() {
    const select = document.getElementById('history-agent-select');
    const startElem = document.getElementById('history-start-time');
    const endElem = document.getElementById('history-end-time');

    if (!select || !startElem || !endElem) return;

    const agentId = select.value;
    const startTimeVal = startElem.value;
    const endTimeVal = endElem.value;

    if (!agentId || !startTimeVal || !endTimeVal) return;

    const startIso = new Date(startTimeVal).toISOString().slice(0, 19);
    const endIso = new Date(endTimeVal).toISOString().slice(0, 19);

    try {
        const res = await fetch(`/api/history?agentId=${agentId}&start=${startIso}&end=${endIso}`);
        if (!res.ok) return;

        const result = await res.json();
        renderHistoryData(result);
    } catch (err) {
        console.error("Lỗi tra cứu lịch sử:", err);
    }
}

// 7. Đổ dữ liệu CSDL lên Đồ thị & Bảng dữ liệu
function renderHistoryData(result) {
    const dataList = result.data || [];
    const tbody = document.getElementById('history-table-body');
    const summaryInfo = document.getElementById('table-summary-info');

    if (!tbody || !summaryInfo) return;

    tbody.innerHTML = '';

    const startStr = document.getElementById('history-start-time').value.replace('T', ' ');
    const endStr = document.getElementById('history-end-time').value.replace('T', ' ');
    summaryInfo.innerText = `Agent ${result.agentId} · ${dataList.length} bản ghi từ ${startStr} đến ${endStr}`;

    const labels = [];
    const rxData = [];
    const txData = [];

    // Nạp dữ liệu vào bảng (Mới nhất nằm trên cùng)
    [...dataList].reverse().forEach(item => {
        const timeFormatted = item.timestamp ? item.timestamp.replace('T', ' ') : '--';
        const rxMb = ((item.rxRate || 0) / 1024).toFixed(1);
        const txMb = ((item.txRate || 0) / 1024).toFixed(1);

        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td class="font-mono">${timeFormatted}</td>
            <td class="font-bold">${item.agent ? item.agent.agentId : result.agentId}</td>
            <td class="text-right font-semibold">${rxMb}</td>
            <td class="text-right font-semibold">${txMb}</td>
            <td class="text-right">${item.rxPacketRate || 0}</td>
            <td class="text-right">${item.txPacketRate || 0}</td>
            <td class="text-right">${item.latency || 0}</td>
            <td class="text-right">${(item.packetLoss || 0).toFixed(1)}</td>
        `;
        tbody.appendChild(tr);
    });

    // Nạp dữ liệu vào Đồ thị
    dataList.forEach(item => {
        const timeShort = item.timestamp ? item.timestamp.split('T')[1].substring(0, 8) : '';
        labels.push(timeShort);
        rxData.push(((item.rxRate || 0) / 1024).toFixed(1));
        txData.push(((item.txRate || 0) / 1024).toFixed(1));
    });

    if (historyChart) {
        historyChart.data.labels = labels;
        historyChart.data.datasets[0].data = rxData;
        historyChart.data.datasets[1].data = txData;
        historyChart.update();
    }
}