#include <ctype.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef USE_OCI_BACKEND
#include <oci.h>
#include "../oracle_fdw.h"
#elif defined USE_RUST_BACKEND
#include "../oracle_utils_generic.h"
#include "../rust_backend/oracle_rust_pg_callbacks.h"
#elif defined USE_JNI_BACKEND
#include "../oracle_utils_generic.h"
#else
#include "../oracle_fdw.h"
#endif

#define DEFAULT_ORACLE_HOST "192.168.11.24"
#define DEFAULT_ORACLE_PORT "1521"
#define DEFAULT_ORACLE_DATABASE "FREEPDB1"
#define DEFAULT_ORACLE_USER "SCOTT"
#define DEFAULT_ORACLE_PASSWORD "tiger"
#define DEFAULT_DEMO_TABLE "EMP"
#define DEMO_PREFETCH 2

static const char *
envOrDefault(const char *name, const char *default_value)
{
	const char *value = getenv(name);

	return (value && value[0]) ? value : default_value;
}

static int
envIsEnabled(const char *name)
{
	const char *value = getenv(name);

	return value && (strcmp(value, "1") == 0 || strcmp(value, "true") == 0 || strcmp(value, "TRUE") == 0);
}

static char *
xstrdup(const char *value)
{
	size_t len = strlen(value);
	char *copy = (char *)malloc(len + 1);

	if (!copy)
	{
		fprintf(stderr, "oracle_utils_demo: out of memory\n");
		exit(2);
	}

	memcpy(copy, value, len + 1);
	return copy;
}

static char *
uppercaseCopy(const char *value)
{
	char *copy = xstrdup(value);
	char *pos;

	for (pos = copy; *pos; ++pos)
		*pos = (char)toupper((unsigned char)*pos);

	return copy;
}

static char *
buildConnectString(const char *host, const char *port, const char *database)
{
	size_t len = strlen(host) + strlen(port) + strlen(database) + 5;
	char *connectstring = (char *)malloc(len);

	if (!connectstring)
	{
		fprintf(stderr, "oracle_utils_demo: out of memory\n");
		exit(2);
	}

	snprintf(connectstring, len, "//%s:%s/%s", host, port, database);
	return connectstring;
}

static char *
buildEnvAssignment(const char *name, const char *value)
{
	size_t len;
	char *assignment;

	if (!value || value[0] == '\0')
		return xstrdup("");

	len = strlen(name) + strlen(value) + 2;
	assignment = (char *)malloc(len);
	if (!assignment)
	{
		fprintf(stderr, "oracle_utils_demo: out of memory\n");
		exit(2);
	}

	snprintf(assignment, len, "%s=%s", name, value);
	return assignment;
}

static char *
buildLimitTo(const char *table)
{
	size_t len = strlen(table) + 3;
	char *limit_to = (char *)malloc(len);
	char *upper_table = uppercaseCopy(table);

	if (!limit_to)
	{
		fprintf(stderr, "oracle_utils_demo: out of memory\n");
		exit(2);
	}

	snprintf(limit_to, len, "'%s'", upper_table);
	free(upper_table);
	return limit_to;
}

static const char *
oraTypeName(oraType type)
{
	switch (type)
	{
		case ORA_TYPE_VARCHAR2: return "VARCHAR2";
		case ORA_TYPE_CHAR: return "CHAR";
		case ORA_TYPE_NVARCHAR2: return "NVARCHAR2";
		case ORA_TYPE_NCHAR: return "NCHAR";
		case ORA_TYPE_NUMBER: return "NUMBER";
		case ORA_TYPE_FLOAT: return "FLOAT";
		case ORA_TYPE_BINARYFLOAT: return "BINARY_FLOAT";
		case ORA_TYPE_BINARYDOUBLE: return "BINARY_DOUBLE";
		case ORA_TYPE_RAW: return "RAW";
		case ORA_TYPE_DATE: return "DATE";
		case ORA_TYPE_TIMESTAMP: return "TIMESTAMP";
		case ORA_TYPE_TIMESTAMPTZ: return "TIMESTAMP WITH TIME ZONE";
		case ORA_TYPE_TIMESTAMPLTZ: return "TIMESTAMP WITH LOCAL TIME ZONE";
		case ORA_TYPE_INTERVALY2M: return "INTERVAL YEAR TO MONTH";
		case ORA_TYPE_INTERVALD2S: return "INTERVAL DAY TO SECOND";
		case ORA_TYPE_BLOB: return "BLOB";
		case ORA_TYPE_CLOB: return "CLOB";
		case ORA_TYPE_NCLOB: return "NCLOB";
		case ORA_TYPE_BFILE: return "BFILE";
		case ORA_TYPE_LONG: return "LONG";
		case ORA_TYPE_LONGRAW: return "LONG RAW";
		case ORA_TYPE_GEOMETRY: return "SDO_GEOMETRY";
		case ORA_TYPE_XMLTYPE: return "XMLTYPE";
		case ORA_TYPE_OTHER: return "OTHER";
	}

	return "UNKNOWN";
}

static void
printVersion(const char *label, int major, int minor, int update, int patch, int port_patch)
{
	printf("%s=%d.%d.%d.%d.%d\n", label, major, minor, update, patch, port_patch);
}

static void
initSingleColumnTable(struct oraTable *table, struct oraColumn *column, struct oraColumn **columns,
					  char *value, int32_t value_size, uint16_t *value_len, int16_t *value_null,
					  oraType type)
{
	memset(table, 0, sizeof(*table));
	memset(column, 0, sizeof(*column));

	column->name = (char *)"VALUE";
	column->pgname = (char *)"value";
	column->oratype = type;
	column->used = 1;
	column->val = value;
	column->val_size = value_size;
	column->val_len = value_len;
	column->val_null = value_null;

	columns[0] = column;
	table->name = (char *)"DEMO";
	table->pgname = (char *)"demo";
	table->ncols = 1;
	table->npgcols = 1;
	table->cols = columns;
}

static void
printDescribeResult(const struct oraTable *table, int has_geometry)
{
	int i;

	if (!table)
	{
		printf("oracleDescribe.table=NULL has_geometry=%d\n", has_geometry);
		return;
	}

	printf("oracleDescribe.table=%s columns=%d has_geometry=%d\n",
		   table->name ? table->name : "(null)", table->ncols, has_geometry);

	for (i = 0; i < table->ncols; ++i)
	{
		const struct oraColumn *column = table->cols[i];

		printf("  column[%d]=%s type=%s scale=%d\n",
			   i,
			   column && column->name ? column->name : "(null)",
			   column ? oraTypeName(column->oratype) : "(null)",
			   column ? column->scale : 0);
	}
}

static void
runSimpleQuery(oracleSession *session)
{
	struct oraTable table;
	struct oraColumn column;
	struct oraColumn *columns[1];
	char values[DEMO_PREFETCH][256];
	uint16_t value_len[DEMO_PREFETCH];
	int16_t value_null[DEMO_PREFETCH];
	unsigned int processed;
	unsigned int row;

	memset(values, 0, sizeof(values));
	memset(value_len, 0, sizeof(value_len));
	memset(value_null, 0, sizeof(value_null));

	initSingleColumnTable(&table, &column, columns, (char *)values, sizeof(values[0]),
						  value_len, value_null, ORA_TYPE_VARCHAR2);

	printf("oraclePrepareQuery.simple\n");
	oraclePrepareQuery(session, "SELECT USER FROM DUAL", &table, DEMO_PREFETCH, 0);
	printf("oracleIsStatementOpen.after_prepare=%d\n", oracleIsStatementOpen(session));

	processed = oracleExecuteQuery(session, &table, NULL, DEMO_PREFETCH);
	printf("oracleExecuteQuery.simple.processed=%u\n", processed);

	row = oracleFetchNext(session, DEMO_PREFETCH);
	while (row != 0)
	{
		char *value = values[row - 1];

		printf("  row[%u]=%s len=%u null=%d\n", row, value, value_len[row - 1], value_null[row - 1]);
		row = oracleFetchNext(session, DEMO_PREFETCH);
	}

	printf("oracleFetchNext.simple.done\n");
	oracleCloseStatement(session);
	printf("oracleCloseStatement.simple open=%d\n", oracleIsStatementOpen(session));
}

static void
runLobQuery(oracleSession *session)
{
	struct oraTable table;
	struct oraColumn column;
	struct oraColumn *columns[1];
	void *lob_values[1];
	uint16_t value_len[1];
	int16_t value_null[1];
	char *lob = NULL;
	long lob_len = 0;
	unsigned int processed;
	unsigned int row;

	memset(lob_values, 0, sizeof(lob_values));
	memset(value_len, 0, sizeof(value_len));
	memset(value_null, 0, sizeof(value_null));

	initSingleColumnTable(&table, &column, columns, (char *)lob_values, (int32_t)sizeof(void *),
						  value_len, value_null, ORA_TYPE_CLOB);

	printf("oraclePrepareQuery.lob\n");
	oraclePrepareQuery(session, "SELECT TO_CLOB('oracle_fdw_demo') FROM DUAL", &table, 1, 0);
	processed = oracleExecuteQuery(session, &table, NULL, 1);
	printf("oracleExecuteQuery.lob.processed=%u\n", processed);

	row = oracleFetchNext(session, 1);
	if (row != 0 && value_null[row - 1] == 0)
	{
		oracleGetLob(session, &lob_values[row - 1], ORA_TYPE_CLOB, &lob, &lob_len);
		printf("oracleGetLob.len=%ld value=%.*s\n", lob_len, (int)lob_len, lob ? lob : "");
	}
	else
	{
		printf("oracleGetLob.skipped=NULL_LOB\n");
	}

	oracleCloseStatement(session);
}

static void
runExplain(oracleSession *session)
{
	int nrows = 0;
	char **plan = NULL;
	int i;

	oracleExplain(session, "SELECT USER FROM DUAL", &nrows, &plan);
	printf("oracleExplain.rows=%d\n", nrows);

	for (i = 0; plan && i < nrows && i < 5; ++i)
		printf("  plan[%d]=%s\n", i, plan[i] ? plan[i] : "(null)");
}

static void
runImportColumns(oracleSession *session, const char *schema, const char *table)
{
	char *tabname = NULL;
	char *colname = NULL;
	char *limit_to = buildLimitTo(table);
	oraType type = ORA_TYPE_OTHER;
	int charlen = 0;
	int typeprec = 0;
	int typescale = 0;
	int nullable = 0;
	int key = 0;
	int result;

	result = oracleGetImportColumn(session, NULL, (char *)schema, limit_to,
								   &tabname, &colname, &type, &charlen,
								   &typeprec, &typescale, &nullable, &key,
								   0, 1, 1);

	printf("oracleGetImportColumn.result=%d schema=%s table_filter=%s\n", result, schema, limit_to);
	if (result == 1)
	{
		printf("  import_column table=%s column=%s type=%s charlen=%d precision=%d scale=%d nullable=%d key=%d\n",
			   tabname ? tabname : "(null)",
			   colname ? colname : "(null)",
			   oraTypeName(type), charlen, typeprec, typescale, nullable, key);
		oracleCloseStatement(session);
	}

	free(limit_to);
}

static void
endTransaction(oracleSession *session)
{
#ifdef USE_OCI_BACKEND
	void *transaction_arg = session->connp;
#else
	void *transaction_arg = session;
#endif

	oracleEndSubtransaction(transaction_arg, 2, 1);
	oracleEndTransaction(transaction_arg, 1, 1);
	printf("oracleEndSubtransaction.done\n");
	printf("oracleEndTransaction.commit.done\n");
}

int
main(int argc, char **argv)
{
	const char *host = envOrDefault("ORACLE_HOST", DEFAULT_ORACLE_HOST);
	const char *port = envOrDefault("ORACLE_PORT", DEFAULT_ORACLE_PORT);
	const char *database = envOrDefault("ORACLE_DATABASE", DEFAULT_ORACLE_DATABASE);
	const char *user_env = envOrDefault("ORACLE_USER", DEFAULT_ORACLE_USER);
	const char *password_env = envOrDefault("ORACLE_PASSWORD", DEFAULT_ORACLE_PASSWORD);
	const char *describe_table_env = envOrDefault("ORACLE_DEMO_TABLE", DEFAULT_DEMO_TABLE);
	const char *nls_lang_env = envOrDefault("ORACLE_NLS_LANG", "AMERICAN_AMERICA.AL32UTF8");
	const char *timezone_env = envOrDefault("ORACLE_TIMEZONE", "");
	char *connectstring = buildConnectString(host, port, database);
	char *user = xstrdup(user_env);
	char *password = xstrdup(password_env);
	char *schema = uppercaseCopy(envOrDefault("ORACLE_DEMO_SCHEMA", user_env));
	char *describe_table = uppercaseCopy(describe_table_env);
	char *nls_lang = buildEnvAssignment("NLS_LANG", nls_lang_env);
	char *timezone = buildEnvAssignment("ORA_SDTZ", timezone_env);
	oracleSession *session;
	struct oraTable *described;
	int has_geometry = 0;
	int major = 0, minor = 0, update = 0, patch = 0, port_patch = 0;
	void *geometry_type;

	(void)argc;
	(void)argv;

	setvbuf(stdout, NULL, _IONBF, 0);

	printf("jdbc=jdbc:oracle:thin:@%s\n", connectstring);
	printf("connectstring=%s\n", connectstring);
	printf("user=%s\n", user);

#ifdef USE_RUST_BACKEND
	{
		int register_result = oracleRegisterRustPgCallbacks();

		if (register_result != 0)
		{
			fprintf(stderr, "oracle_utils_demo: oracleRegisterRustPgCallbacks failed: %d\n", register_result);
			return 2;
		}
	}
#endif

	oracleClientVersion(&major, &minor, &update, &patch, &port_patch);
	printVersion("oracleClientVersion", major, minor, update, patch, port_patch);

	session = oracleGetSession(connectstring, ORA_TRANS_READ_COMMITTED, user, password,
							   nls_lang, timezone, 0, describe_table, 1);
	printf("oracleGetSession=%p\n", (void *)session);

	oracleServerVersion(session, &major, &minor, &update, &patch, &port_patch);
	printVersion("oracleServerVersion", major, minor, update, patch, port_patch);

	described = oracleDescribe(session, NULL, NULL, describe_table, describe_table, 1024, &has_geometry);
	printDescribeResult(described, has_geometry);

	runSimpleQuery(session);
	runLobQuery(session);
	oracleExecuteCall(session, "BEGIN NULL; END;");
	printf("oracleExecuteCall.done\n");
	if (envIsEnabled("ORACLE_DEMO_ERROR_PATH"))
	{
		oracleExecuteCall(session, "DROP TABLE scott.codex_jni_missing_table PURGE");
		printf("oracleExecuteCall.error_path.unexpected_success\n");
	}

	runImportColumns(session, schema, describe_table);

	if (envIsEnabled("ORACLE_DEMO_EXPLAIN"))
		runExplain(session);
	else
		printf("oracleExplain.skipped set ORACLE_DEMO_EXPLAIN=1 to enable\n");

	if (envIsEnabled("ORACLE_DEMO_GEOMETRY"))
	{
		geometry_type = oracleGetGeometryType(session);
		printf("oracleGetGeometryType=%p\n", geometry_type);
	}
	else
	{
		printf("oracleGetGeometryType.skipped set ORACLE_DEMO_GEOMETRY=1 to enable\n");
	}

	oracleCancel();
	printf("oracleCancel.done\n");

	endTransaction(session);
	oracleCloseConnections();
	printf("oracleCloseConnections.done\n");
	oracleShutdown();
	printf("oracleShutdown.done\n");

	free(connectstring);
	free(user);
	free(password);
	free(schema);
	free(describe_table);
	free(nls_lang);
	free(timezone);

	return 0;
}
