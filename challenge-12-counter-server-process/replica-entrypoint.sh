#!/bin/bash
# replica-entrypoint.sh — startup script for Postgres read replicas.
#
# Runs INSTEAD OF the default postgres entrypoint (we override it in compose).
# Two paths:
#
#   1. First boot (data directory empty):
#      - pg_basebackup from primary into PGDATA
#      - tell Postgres "you're a standby" by creating standby.signal
#      - start the server in standby mode
#
#   2. Subsequent boots (data directory already populated):
#      - just start the server; it'll resume streaming from where it left off
#
# This is roughly what tools like Patroni do during their replica bootstrap,
# minus the orchestration / failover logic.

set -e

PGDATA="${PGDATA:-/var/lib/postgresql/data}"

# pg_basebackup writes into a directory it creates; the directory must be empty
# (or not exist). We check for the version file as a marker that the data dir
# has already been initialized.
if [ ! -s "$PGDATA/PG_VERSION" ]; then
    echo "replica: data dir is empty — bootstrapping from primary"

    # Wait for primary to be reachable. The depends_on healthcheck in compose
    # handles this in most cases, but on first init the primary is sometimes
    # still finishing initdb when the replica wakes up. A short retry loop
    # avoids spurious basebackup failures.
    until PGPASSWORD=replica_password psql -h "$PRIMARY_HOST" -U replicator -d postgres -c '\q' 2>/dev/null; do
        echo "replica: waiting for primary at $PRIMARY_HOST..."
        sleep 2
    done

    # pg_basebackup clones the entire primary data directory into PGDATA.
    # -R: write a standby.signal file + connection settings to recovery config
    # -P: print progress
    # -X stream: stream WAL during backup so the replica boots up to date
    PGPASSWORD=replica_password pg_basebackup \
        -h "$PRIMARY_HOST" \
        -U replicator \
        -D "$PGDATA" \
        -Fp -Xs -P -R

    echo "replica: basebackup complete"
    chown -R postgres:postgres "$PGDATA"
    chmod 0700 "$PGDATA"
else
    echo "replica: data dir already initialized — skipping basebackup"
fi

# Hand off to the official postgres entrypoint. Postgres will see the
# standby.signal file pg_basebackup wrote and start in standby mode.
exec docker-entrypoint.sh postgres
