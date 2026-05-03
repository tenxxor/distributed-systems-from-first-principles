#!/bin/bash
# start-cluster.sh — brings up 3 counter instances + 1 nginx load balancer.
#
# This is a deliberately crude way to manage a fleet of processes. In real life
# you'd use a container orchestrator (Docker Compose, Kubernetes, Nomad) or a
# process supervisor (systemd, supervisord) to handle restart-on-crash, log
# collection, health-aware routing, and graceful shutdown. We're doing none of
# that here. The point is to *feel* the awkwardness — it's the motivation for
# challenge 9.5.

set -euo pipefail

cd "$(dirname "$0")"

JAR="target/challenge-9-counter-1.0-SNAPSHOT.jar"

if [[ ! -f "$JAR" ]]; then
    echo "JAR not found at $JAR"
    echo "Build it first:  mvn -q -DskipTests package"
    exit 1
fi

mkdir -p logs .pids shared-data

start_instance() {
    local port=$1
    local config="config-${port}.yml"
    local logfile="logs/counter-${port}.log"
    local pidfile=".pids/counter-${port}.pid"

    if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        echo "counter-${port} already running (PID $(cat "$pidfile"))"
        return
    fi

    java -jar "$JAR" server "$config" > "$logfile" 2>&1 &
    echo $! > "$pidfile"
    echo "counter-${port}  started (PID $!)  logs → $logfile"
}

start_nginx() {
    local pidfile=".pids/nginx.pid"

    if [[ -f "$pidfile" ]] && kill -0 "$(cat "$pidfile")" 2>/dev/null; then
        echo "nginx already running (PID $(cat "$pidfile"))"
        return
    fi

    # Run nginx in the background too. -p pins its working prefix to this
    # directory so the logs/ path in nginx.conf resolves correctly.
    nginx -c "$(pwd)/nginx.conf" -p "$(pwd)" -g 'daemon off;' > logs/nginx.stdout.log 2>&1 &
    echo $! > "$pidfile"
    echo "nginx         started (PID $!)  listening on :9000"
}

start_instance 8080
start_instance 8082
start_instance 8084

# Give the JVMs a moment to bind their ports before nginx tries to reach them.
sleep 2
start_nginx

echo
echo "Cluster up. Hit http://localhost:9000/api/v1/counters to exercise the LB."
echo "Tail all logs with:  tail -f logs/*.log"
echo "Stop with:           ./stop-cluster.sh"
