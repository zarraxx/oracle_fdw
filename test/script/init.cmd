@echo off
setlocal EnableExtensions

if "%POSTGRESQL_HOME%"=="" (
  echo POSTGRESQL_HOME must be set 1>&2
  exit /b 1
)

set "PG_BIN=%POSTGRESQL_HOME%\bin"
set "INITDB=%PG_BIN%\initdb.exe"
set "PG_CTL=%PG_BIN%\pg_ctl.exe"
set "PSQL=%PG_BIN%\psql.exe"

if not exist "%INITDB%" (
  echo initdb was not found under %PG_BIN% 1>&2
  exit /b 1
)
if not exist "%PG_CTL%" (
  echo pg_ctl was not found under %PG_BIN% 1>&2
  exit /b 1
)
if not exist "%PSQL%" (
  echo psql was not found under %PG_BIN% 1>&2
  exit /b 1
)

if "%TMP%"=="" (
  set "TMP_ROOT=%TEMP%"
) else (
  set "TMP_ROOT=%TMP%"
)

if "%PGDATA%"=="" set "PGDATA=%TMP_ROOT%\oracle_fdw_pg_data"
if "%PGLOG%"=="" set "PGLOG=%TMP_ROOT%\oracle_fdw_pg.log"
if "%PG_SUPERUSER%"=="" set "PG_SUPERUSER=postgres"
if "%PGDATABASE%"=="" set "PGDATABASE=postgres"
if "%PGPORT%"=="" set "PGPORT=55432"
if "%PGHOST%"=="" set "PGHOST=127.0.0.1"

if not exist "%TMP_ROOT%" mkdir "%TMP_ROOT%"

if not exist "%PGDATA%\PG_VERSION" (
  echo initializing PostgreSQL data directory: %PGDATA%
  "%INITDB%" -D "%PGDATA%" -U "%PG_SUPERUSER%" -E UTF8 --locale=C --auth=trust
  if errorlevel 1 exit /b 1
) else (
  echo using existing PostgreSQL data directory: %PGDATA%
)

"%PG_CTL%" -D "%PGDATA%" status >nul 2>nul
if errorlevel 1 (
  echo starting PostgreSQL on %PGHOST%:%PGPORT%
  "%PG_CTL%" -D "%PGDATA%" -l "%PGLOG%" -o "-h %PGHOST% -p %PGPORT%" -w start
  if errorlevel 1 exit /b 1
) else (
  echo PostgreSQL is already running for %PGDATA%
)

echo testing PostgreSQL connection
"%PSQL%" -h "%PGHOST%" -p "%PGPORT%" -U "%PG_SUPERUSER%" -d "%PGDATABASE%" -v ON_ERROR_STOP=1 -c "SELECT current_user, current_database(), current_setting('server_encoding') AS server_encoding;"
if errorlevel 1 exit /b 1

echo PostgreSQL is running
echo PGDATA=%PGDATA%
echo PGLOG=%PGLOG%
echo PGHOST=%PGHOST%
echo PGPORT=%PGPORT%
echo PGUSER=%PG_SUPERUSER%

endlocal
