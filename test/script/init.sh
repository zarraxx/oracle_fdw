#!/usr/bin/env sh
set -eu

if [ -z "${POSTGRESQL_HOME:-}" ]; then
  echo "POSTGRESQL_HOME must be set" >&2
  exit 1
fi

PG_BIN="${POSTGRESQL_HOME}/bin"
INITDB="${PG_BIN}/initdb"
PG_CTL="${PG_BIN}/pg_ctl"
PSQL="${PG_BIN}/psql"

if [ ! -x "${INITDB}" ] || [ ! -x "${PG_CTL}" ] || [ ! -x "${PSQL}" ]; then
  echo "initdb, pg_ctl and psql must exist under ${PG_BIN}" >&2
  exit 1
fi

TMP_ROOT="${TMPDIR:-${TMP:-/tmp}}"
PGDATA="${PGDATA:-${TMP_ROOT}/oracle_fdw_pg_data}"
PGLOG="${PGLOG:-${TMP_ROOT}/oracle_fdw_pg.log}"
PG_SUPERUSER="${PG_SUPERUSER:-postgres}"
PGDATABASE="${PGDATABASE:-postgres}"
PGPORT="${PGPORT:-55432}"
PGHOST="${PGHOST:-127.0.0.1}"

mkdir -p "${TMP_ROOT}"

if [ ! -f "${PGDATA}/PG_VERSION" ]; then
  echo "initializing PostgreSQL data directory: ${PGDATA}"
  "${INITDB}" -D "${PGDATA}" -U "${PG_SUPERUSER}" -E UTF8 --locale=C --auth=trust
else
  echo "using existing PostgreSQL data directory: ${PGDATA}"
fi

if "${PG_CTL}" -D "${PGDATA}" status >/dev/null 2>&1; then
  echo "PostgreSQL is already running for ${PGDATA}"
else
  echo "starting PostgreSQL on ${PGHOST}:${PGPORT}"
  "${PG_CTL}" -D "${PGDATA}" -l "${PGLOG}" -o "-h ${PGHOST} -p ${PGPORT}" -w start
fi

echo "testing PostgreSQL connection"
"${PSQL}" -h "${PGHOST}" -p "${PGPORT}" -U "${PG_SUPERUSER}" -d "${PGDATABASE}" \
  -v ON_ERROR_STOP=1 \
  -c "SELECT current_user, current_database(), current_setting('server_encoding') AS server_encoding;"

echo "PostgreSQL is running"
echo "PGDATA=${PGDATA}"
echo "PGLOG=${PGLOG}"
echo "PGHOST=${PGHOST}"
echo "PGPORT=${PGPORT}"
echo "PGUSER=${PG_SUPERUSER}"
