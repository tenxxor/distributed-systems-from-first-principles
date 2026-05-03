#!/bin/bash
# primary-init.sh — runs once on Postgres primary's first boot, before the
# server starts accepting external connections. Two jobs:
#
#   1. Create a replication-only role (`replicator`) with the REPLICATION
#      privilege so replicas can stream WAL.
#   2. Append a pg_hba.conf rule allowing replication connections from the
#      cluster network. The default pg_hba doesn't include a `replication`
#      line; without one, pg_basebackup fails with "no pg_hba.conf entry
#      for replication connection".
#
# This script is mounted into /docker-entrypoint-initdb.d/ inside the primary
# container; the official postgres image runs every *.sh and *.sql file in
# that directory in alphabetical order on first init, AFTER initdb but BEFORE
# the server is exposed to clients.

set -e

# Create the replication role.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE ROLE replicator WITH REPLICATION LOGIN PASSWORD 'replica_password';
EOSQL

# Append a replication rule to pg_hba.conf. PGDATA is set by the official
# image entrypoint. We add a single line that allows the `replicator` role to
# connect for replication from any host on the docker network with trust
# auth (private network — safe for a demo, not for production).
echo "host  replication  replicator  0.0.0.0/0  trust" >> "$PGDATA/pg_hba.conf"

# Reload pg_hba.conf so the change takes effect without restarting the server.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    SELECT pg_reload_conf();
EOSQL

echo "primary-init: replication role 'replicator' created + pg_hba.conf updated"
