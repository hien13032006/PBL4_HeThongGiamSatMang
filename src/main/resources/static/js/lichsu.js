let historyChart = null;
let stompClient = null;

// Các biến quản lý chu kỳ chạy tự động (
let animationInterval = null;
let isHovered = false;
let globalDataList = [];
let currentStartIndex = 0;
const WINDOW_SIZE = 60;

document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();
    initHistoryChart();
    
    // Nạp danh sách Agent, sau đó tự động tra cứu 1 phút gần nhất
    fetchAgentDropdown().then(() => {
        setDefaultOneMinuteRange();
    });

    connectWebSocket();

    const searchBtn = document.getElementById('btn-search-history');
    if (searchBtn) {
        searchBtn.addEventListener('click', performQuery);
    }

    const canvas = document.getElementById('historyChart');
    if (canvas) {
        canvas.addEventListener('mouseenter', () => {
            isHovered = true; // Đánh dấu dừng animation khi di chuột vào
        });
        canvas.addEventListener('mouseleave', () => {
            isHovered = false; // Tiếp tục chạy khi rời chuột
        });
    }
});

function initThemeToggle() {
    const btn = document.getElementById('theme-toggle-btn');
    if (btn) {
        btn.addEventListener('click', () => {
            document.body.classList.toggle('dark');
        });
    }
}

// 1. Cập nhật trạng thái WebSocket trên Top Header
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

// 2. Kết nối WebSocket
function connectWebSocket() {
    updateWsStatusUI(false);

    if (typeof SockJS === 'undefined' || typeof Stomp === 'undefined') {
        console.warn("Chưa nạp thư viện SockJS/STOMP");
        return;
    }

    const socket = new SockJS('/ws-network-monitor');
    stompClient = Stomp.over(socket);
    stompClient.debug = null;

    stompClient.connect({}, function () {
        updateWsStatusUI(true);

        stompClient.subscribe('/topic/agents', function () {
            fetchAgentDropdown();
        });
    }, function () {
        updateWsStatusUI(false);
        setTimeout(connectWebSocket, 5000);
    });
}

// 3. Khởi tạo Đồ thị Chart.js
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
                    borderColor: '#2980b9',
                    backgroundColor: 'rgba(41, 128, 185, 0.15)',
                    fill: true,
                    borderWidth: 2,
                    data: [],
                    pointRadius: 1,
                    pointHoverRadius: 5,
                    tension: 0.3
                },
                {
                    label: 'TX (MB/s)',
                    borderColor: '#d97706',
                    backgroundColor: 'rgba(217, 119, 6, 0.15)',
                    fill: true,
                    borderWidth: 2,
                    data: [],
                    pointRadius: 1,
                    pointHoverRadius: 5,
                    tension: 0.3
                }
            ]
        },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            animation: {
                duration: 300 
            },
            interaction: {
                mode: 'index',
                intersect: false,
            },
            plugins: {
                legend: { display: false }, 
                tooltip: {
                    enabled: true,
                    callbacks: {
                        title: function(context) {
                            const index = context[0].dataIndex;
                            const slice = globalDataList.slice(currentStartIndex, currentStartIndex + WINDOW_SIZE);
                            if (slice[index] && slice[index].timestamp) {
                                return slice[index].timestamp.replace('T', ' ').substring(0, 19);
                            }
                            return context[0].label;
                        },
                        label: function(context) {
                            return `${context.dataset.label}: ${context.raw} MB/s`;
                        }
                    }
                }
            },
            scales: {
                x: { 
                    grid: { display: false },
                    ticks: { 
                        maxRotation: 0, 
                        minRotation: 0,
                        autoSkip: false,
                        font: { size: 11, weight: '600' },
                        callback: function(val, index) {
                            const totalLabels = this.chart.data.labels.length;
                    
                            if (index === 0 || index === totalLabels - 1) {
                                return this.getLabelForValue(val);
                            }
                            return ''; 
                        }
                    }
                },
                y: { 
                    beginAtZero: true,
                    grid: { color: 'rgba(200, 200, 200, 0.15)' },
                    ticks: {
                        font: { size: 11, weight: '600' },
                        callback: function(val) {
                            return val.toFixed(1);
                        }
                    }
                }
            }
        }
    });
}

// 4. Nạp danh sách Agent
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
    } catch (err) {
        console.error("Lỗi lấy danh sách Agent:", err);
    }
}

// 5. Mặc định chọn 1 phút gần nhất
function setDefaultOneMinuteRange() {
    const now = new Date();
    const pastOneMin = new Date(now.getTime() - 1 * 60 * 1000);

    const startElem = document.getElementById('history-start-time');
    const endElem = document.getElementById('history-end-time');

    if (startElem && endElem) {
        endElem.value = formatDateForInput(now);
        startElem.value = formatDateForInput(pastOneMin);
        performQuery();
    }
}

function formatDateForInput(date) {
    const pad = (n) => n < 10 ? '0' + n : n;
    return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

// 6. Gọi API tra cứu
async function performQuery() {
    const select = document.getElementById('history-agent-select');
    const startElem = document.getElementById('history-start-time');
    const endElem = document.getElementById('history-end-time');

    if (!select || !startElem || !endElem) return;

    const agentId = select.value;
    const startTimeVal = startElem.value;
    const endTimeVal = endElem.value;

    if (!agentId || !startTimeVal || !endTimeVal) return;

    const startIso = startTimeVal + ":00";
    const endIso = endTimeVal + ":00";

    try {
        const res = await fetch(`/api/history?agentId=${encodeURIComponent(agentId)}&start=${encodeURIComponent(startIso)}&end=${encodeURIComponent(endIso)}`);
        if (!res.ok) {
            console.error("API trả về lỗi:", res.status);
            return;
        }

        const result = await res.json();
        renderHistoryData(result);
    } catch (err) {
        console.error("Lỗi tra cứu lịch sử:", err);
    }
}

// 7. Đổ dữ liệu & Khởi chạy Chu kỳ lướt sóng (Sliding Animation)
function renderHistoryData(result) {
    globalDataList = result.data || [];
    const tbody = document.getElementById('history-table-body');
    const summaryInfo = document.getElementById('table-summary-info');

    if (!tbody || !summaryInfo) return;

    tbody.innerHTML = '';
    const startStr = document.getElementById('history-start-time').value.replace('T', ' ');
    const endStr = document.getElementById('history-end-time').value.replace('T', ' ');
    summaryInfo.innerText = `Agent ${result.agentId || ''} · ${globalDataList.length} bản ghi từ ${startStr} đến ${endStr}`;

    if (animationInterval) {
        clearInterval(animationInterval);
        animationInterval = null;
    }

    if (globalDataList.length === 0) {
        tbody.innerHTML = `<tr><td colspan="8" style="text-align: center; color: var(--text-secondary); padding: 20px;">Không có dữ liệu lịch sử trong khoảng thời gian này</td></tr>`;
        if (historyChart) {
            historyChart.data.labels = [];
            historyChart.data.datasets[0].data = [];
            historyChart.data.datasets[1].data = [];
            historyChart.update();
        }
        return;
    }

    // A. BẢNG DỮ LIỆU: Đổ toàn bộ bản ghi (Mới nhất nằm trên)
    [...globalDataList].reverse().forEach(item => {
        const timeFormatted = item.timestamp ? item.timestamp.replace('T', ' ').substring(0, 19) : '--';
        const rxMb = Number(item.rxRate || 0).toFixed(3);
        const txMb = Number(item.txRate || 0).toFixed(3);

        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td class="font-mono">${timeFormatted}</td>
            <td class="font-bold">${result.agentId}</td>
            <td class="text-right font-semibold" style="color: #2980b9;">${rxMb}</td>
            <td class="text-right font-semibold" style="color: #d97706;">${txMb}</td>
            <td class="text-right">${item.rxPacketRate || 0}</td>
            <td class="text-right">${item.txPacketRate || 0}</td>
            <td class="text-right">${item.latency ? item.latency.toFixed(1) : 0}</td>
            <td class="text-right">${(item.packetLoss || 0).toFixed(1)}%</td>
        `;
        tbody.appendChild(tr);
    });

    // B. ĐỒ THỊ SLIDING WINDOW
    currentStartIndex = 0;
    updateChartWindow(); // Nạp ô cửa sổ dữ liệu đầu tiên

    // Nếu số lượng bản ghi lớn hơn kích thước cửa sổ (60 mẫu), kích hoạt chu kỳ chạy trượt qua lại mỗi 1 giây
    if (globalDataList.length > WINDOW_SIZE) {
        animationInterval = setInterval(() => {
            // Nếu người dùng ĐANG rê chuột vào biểu đồ -> Tạm dừng không trượt
            if (isHovered) return;

            currentStartIndex++;
            // Khi trượt tới bản ghi cuối cùng, tự động quay lại đầu chu kỳ
            if (currentStartIndex > globalDataList.length - WINDOW_SIZE) {
                currentStartIndex = 0;
            }
            updateChartWindow();
        }, 1000); 
    }
}

function updateChartWindow() {
    if (!historyChart || globalDataList.length === 0) return;

    const currentSlice = globalDataList.slice(currentStartIndex, currentStartIndex + WINDOW_SIZE);
    const labels = [];
    const rxData = [];
    const txData = [];

    currentSlice.forEach(item => {
        if (!item.timestamp) return;
        let timeLabel = '';
        if (item.timestamp.includes('T')) {
            const parts = item.timestamp.split('T');
            const datePart = parts[0].substring(5); // MM-DD
            const timePart = parts[1].substring(0, 8); // HH:mm:ss
            timeLabel = globalDataList.length > 500 ? `${datePart} ${timePart.substring(0, 5)}` : timePart;
        }

        labels.push(timeLabel);
        rxData.push(Number(item.rxRate || 0).toFixed(3));
        txData.push(Number(item.txRate || 0).toFixed(3));
    });

    historyChart.data.labels = labels;
    historyChart.data.datasets[0].data = rxData;
    historyChart.data.datasets[1].data = txData;
    
    // Cập nhật biểu đồ
    historyChart.update();
}