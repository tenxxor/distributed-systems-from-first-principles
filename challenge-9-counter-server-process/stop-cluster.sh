#!/bin/bash
# stop-cluster.sh — stops everything start-cluster.sh started.

set -u

cd "$(dirname "$0")"

stop() {
    local name=$1
    local pidfile=".pids/${name}.pid"

    if [[ ! -f "$pidfile" ]]; then
        echo "$name  no pidfile, skipping"
        return
    fi

    local pid
    pid=$(cat "$pidfile")

    if kill -0 "$pid" 2>/dev/null; then
        kill "$pid"
        echo "$name  stopped (PID $pid)"
    else
        echo "$name  was not running (stale pidfile)"
    fi

    rm -f "$pidfile"
}

stop nginx
stop counter-8080
stop counter-8082
stop counter-8084

echo
echo "Cluster down."
