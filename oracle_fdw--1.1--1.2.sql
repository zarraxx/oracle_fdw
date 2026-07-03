CREATE FUNCTION oracle_execute(server name, statement text) RETURNS void
AS 'MODULE_PATHNAME', 'oracle_execute_oci'
LANGUAGE C STRICT;

COMMENT ON FUNCTION oracle_execute(name, text)
IS 'executes an arbitrary SQL statement with no results on the Oracle server';
