#!/bin/bash
# DEMO_SCENARIO: normal | high-pps | packet-loss | latency | overload | upload | lag | loss | lag_loss
SERVER_HOST="${SERVER_HOST:-host.docker.internal}"
SCENARIO="${DEMO_SCENARIO:-normal}"
TRAFFIC_TARGET="${DEMO_TRAFFIC_TARGET:-$SERVER_HOST}"
DELAY_MS="${DEMO_DELAY_MS:-200}"
LOSS_PCT="${DEMO_LOSS_PCT:-8}"

echo "[demo] scenario=$SCENARIO target=$TRAFFIC_TARGET server=$SERVER_HOST"

apply_netem() {
  if ! tc qdisc replace dev eth0 root netem "$@" 2>/dev/null; then
    echo "[demo] WARNING: khong bat duoc tc netem (thieu NET_ADMIN hoac kernel khong ho tro)" >&2
  else
    echo "[demo] netem: $*"
  fi
}

apply_required_netem() {
  if ! tc qdisc replace dev eth0 root netem "$@"; then
    echo "[demo] ERROR: khong the ap dung netem cho scenario $SCENARIO" >&2
    exit 1
  fi
}

normal_traffic() {
  while true; do
    ping -c 5 -i 0.2 -s $((200 + RANDOM % 1000)) "$TRAFFIC_TARGET" >/dev/null 2>&1
    curl -s -o /dev/null "http://$TRAFFIC_TARGET:8080/" 2>/dev/null
    sleep $((1 + RANDOM % 3))
  done
}

# Legacy bandwidth scenarios; these are not packet-rate scenarios.
flood() {
  local interval="$1"
  while true; do
    ping -c 200 -i "$interval" -s 60000 -q "$TRAFFIC_TARGET" >/dev/null 2>&1
    sleep 1
  done
}

case "$SCENARIO" in
  high-pps)
    echo "[demo] METRICS pps override is configured in the agent environment"
    ;;
  packet-loss)
    apply_required_netem loss "${LOSS_PCT}%"
    echo "[demo] applied packet loss ${LOSS_PCT}% to this container's egress"
    ;;
  latency)
    apply_required_netem delay "${DELAY_MS}ms" 5ms
    echo "[demo] applied ${DELAY_MS}ms egress delay to this container"
    ;;
  overload)
    flood 0.01 &            # ~10+ MB/s moi chieu, vuot xa maxSpeed da cau hinh
    ;;
  upload)
    flood 0.03 &            # TX > 1.875 MB/s keo dai -> canh bao bat thuong
    ;;
  lag)
    apply_netem delay "${DELAY_MS}ms" 10ms
    normal_traffic &
    ;;
  loss)
    apply_netem loss "${LOSS_PCT}%"
    normal_traffic &
    ;;
  lag_loss)
    apply_netem delay "${DELAY_MS}ms" 10ms loss "${LOSS_PCT}%"
    normal_traffic &
    ;;
  *)
    normal_traffic &
    ;;
esac

exec java -Dserver.host="$SERVER_HOST" -Dserver.port=9999 -jar /app/agent.jar
