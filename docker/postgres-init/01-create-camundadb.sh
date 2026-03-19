#!/bin/bash
# Creates camundadb if it does not already exist.
# Using a shell script instead of .sql so the CREATE is idempotent
# (init scripts only run on an empty data directory, but this pattern
#  is also safe to run manually with: docker exec notes-postgres bash /docker-entrypoint-initdb.d/01-create-camundadb.sh)
set -e

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname   "$POSTGRES_DB"   \
     <<-EOSQL
        SELECT 'CREATE DATABASE camundadb OWNER $POSTGRES_USER'
        WHERE NOT EXISTS (
            SELECT FROM pg_database WHERE datname = 'camundadb'
        )\gexec
EOSQL

echo "camundadb is ready"