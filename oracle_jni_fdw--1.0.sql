CREATE FUNCTION oracle_jni_fdw_handler() RETURNS fdw_handler
AS 'MODULE_PATHNAME', 'oracle_fdw_handler_jni'
LANGUAGE C STRICT;

COMMENT ON FUNCTION oracle_jni_fdw_handler()
IS 'Oracle foreign data wrapper handler through JDBC/JNI';

CREATE FUNCTION oracle_jni_fdw_validator(text[], oid) RETURNS void
AS 'MODULE_PATHNAME', 'oracle_fdw_validator_jni'
LANGUAGE C STRICT;

COMMENT ON FUNCTION oracle_jni_fdw_validator(text[], oid)
IS 'Oracle foreign data wrapper options validator through JDBC/JNI';

CREATE FUNCTION oracle_jni_close_connections() RETURNS void
AS 'MODULE_PATHNAME', 'oracle_close_connections_jni'
LANGUAGE C STRICT;

COMMENT ON FUNCTION oracle_jni_close_connections()
IS 'closes all open Oracle JDBC/JNI connections';

CREATE FUNCTION oracle_jni_diag(name DEFAULT NULL) RETURNS text
AS 'MODULE_PATHNAME', 'oracle_diag_jni'
LANGUAGE C STABLE CALLED ON NULL INPUT;

COMMENT ON FUNCTION oracle_jni_diag(name)
IS 'shows the version of oracle_jni_fdw, PostgreSQL, Oracle JDBC client and Oracle server';

CREATE FUNCTION oracle_jni_execute(server name, statement text) RETURNS void
AS 'MODULE_PATHNAME', 'oracle_execute_jni'
LANGUAGE C STRICT;

COMMENT ON FUNCTION oracle_jni_execute(name, text)
IS 'executes an arbitrary SQL statement with no results on the Oracle server through JDBC/JNI';

CREATE FOREIGN DATA WRAPPER oracle_jni_fdw
  HANDLER oracle_jni_fdw_handler
  VALIDATOR oracle_jni_fdw_validator;

COMMENT ON FOREIGN DATA WRAPPER oracle_jni_fdw
IS 'Oracle foreign data wrapper through JDBC/JNI';
