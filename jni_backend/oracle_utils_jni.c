#ifndef USE_JNI_BACKEND
#include "postgres.h"
#include "utils/elog.h"
#endif

#include <jni.h>

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../oracle_utils_generic.h"

#ifndef ORACLE_FDW_JNI_BRIDGE_JAR
#define ORACLE_FDW_JNI_BRIDGE_JAR ""
#endif

#ifndef ORACLE_FDW_DEFAULT_JDBC_JAR
#define ORACLE_FDW_DEFAULT_JDBC_JAR ""
#endif

#ifdef _WIN32
#define ORACLE_FDW_CLASSPATH_SEP ";"
#else
#define ORACLE_FDW_CLASSPATH_SEP ":"
#endif

#define JNI_FIELD_SEP '\x1f'

#if defined(_MSC_VER)
#define ORACLE_FDW_THREAD_LOCAL __declspec(thread)
#elif defined(__GNUC__) || defined(__clang__)
#define ORACLE_FDW_THREAD_LOCAL __thread
#else
#define ORACLE_FDW_THREAD_LOCAL _Thread_local
#endif

typedef struct JniLobContent
{
	char *data;
	long len;
	struct JniLobContent *next;
} JniLobContent;

typedef struct JniSessionState
{
	jobject backend;
	const struct oraTable *current_table;
	unsigned int next_row;
	int import_open;
	int callback_registered;
	JniLobContent *lobs;
} JniSessionState;

static JavaVM *jvm = NULL;
static ORACLE_FDW_THREAD_LOCAL JNIEnv *jni_env = NULL;
static jclass backend_class = NULL;
static jmethodID ctor_mid = NULL;
static jmethodID client_version_mid = NULL;
static jmethodID server_version_mid = NULL;
static jmethodID close_mid = NULL;
static jmethodID close_statement_mid = NULL;
static jmethodID is_statement_open_mid = NULL;
static jmethodID describe_mid = NULL;
static jmethodID prepare_query_mid = NULL;
static jmethodID execute_query_mid = NULL;
static jmethodID execute_query_with_params_mid = NULL;
static jmethodID fetch_next_mid = NULL;
static jmethodID get_value_mid = NULL;
static jmethodID execute_call_mid = NULL;
static jmethodID end_transaction_mid = NULL;
static jmethodID rollback_to_savepoint_mid = NULL;
static jmethodID explain_mid = NULL;
static jmethodID load_import_columns_mid = NULL;
static jmethodID get_import_column_next_mid = NULL;

static JniSessionState **sessions = NULL;
static size_t session_count = 0;
static size_t session_capacity = 0;

static void
setOutputInt(int *out, int value)
{
	if (out)
		*out = value;
}

static void
jniTrace(const char *message)
{
#ifdef USE_JNI_BACKEND
	const char *enabled = getenv("ORACLE_FDW_JNI_TRACE");

	if (enabled && enabled[0] && strcmp(enabled, "0") != 0)
	{
		fprintf(stderr, "oracle_fdw_jni: %s\n", message);
		fflush(stderr);
	}
#else
	(void)message;
#endif
}

static void
jniDebug3(const char *message)
{
#ifndef USE_JNI_BACKEND
	ereport(DEBUG3, (errmsg("%s", message)));
#else
	jniTrace(message);
#endif
}

static void
setOutputVersion(int *major, int *minor, int *update, int *patch, int *port_patch, int values[5])
{
	setOutputInt(major, values[0]);
	setOutputInt(minor, values[1]);
	setOutputInt(update, values[2]);
	setOutputInt(patch, values[3]);
	setOutputInt(port_patch, values[4]);
}

static char *
oracleStrdup(const char *value)
{
	size_t len = value ? strlen(value) : 0;
	char *copy = (char *)oracleAlloc(len + 1);

	if (len)
		memcpy(copy, value, len);
	copy[len] = '\0';
	return copy;
}

static char *
mallocStrdup(const char *value)
{
	size_t len = value ? strlen(value) : 0;
	char *copy = (char *)malloc(len + 1);

	if (!copy)
		oracleError(FDW_OUT_OF_MEMORY, "out of memory in JNI backend");

	if (len)
		memcpy(copy, value, len);
	copy[len] = '\0';
	return copy;
}

static char *
errorDetailStrdup(const char *value)
{
#ifndef USE_JNI_BACKEND
	return pstrdup(value ? value : "Java exception");
#else
	return mallocStrdup(value ? value : "Java exception");
#endif
}

static void
uppercaseAscii(char *value)
{
	for (; value && *value; ++value)
	{
		if (*value >= 'a' && *value <= 'z')
			*value = (char)(*value - ('a' - 'A'));
	}
}

static int
startsWith(const char *value, const char *prefix)
{
	return strncmp(value, prefix, strlen(prefix)) == 0;
}

static oraType
oraTypeFromNames(const char *typename, const char *typeowner)
{
	char *type = mallocStrdup(typename ? typename : "");
	char *owner = mallocStrdup(typeowner ? typeowner : "");
	oraType result = ORA_TYPE_OTHER;

	uppercaseAscii(type);
	uppercaseAscii(owner);

	if (startsWith(type, "NVARCHAR"))
		result = ORA_TYPE_NVARCHAR2;
	else if (startsWith(type, "VARCHAR"))
		result = ORA_TYPE_VARCHAR2;
	else if (strcmp(type, "NUMBER") == 0)
		result = ORA_TYPE_NUMBER;
	else if (strcmp(type, "DATE") == 0)
		result = ORA_TYPE_DATE;
	else if (strcmp(type, "CHAR") == 0)
		result = ORA_TYPE_CHAR;
	else if (startsWith(type, "TIMESTAMP"))
		result = strlen(type) < 17 ? ORA_TYPE_TIMESTAMP :
			(strstr(type, "LOCAL") ? ORA_TYPE_TIMESTAMPLTZ : ORA_TYPE_TIMESTAMPTZ);
	else if (strcmp(type, "RAW") == 0)
		result = ORA_TYPE_RAW;
	else if (strcmp(type, "BLOB") == 0)
		result = ORA_TYPE_BLOB;
	else if (strcmp(type, "CLOB") == 0)
		result = ORA_TYPE_CLOB;
	else if (strcmp(type, "NCLOB") == 0)
		result = ORA_TYPE_NCLOB;
	else if (strcmp(type, "BFILE") == 0)
		result = ORA_TYPE_BFILE;
	else if (strcmp(type, "LONG") == 0)
		result = ORA_TYPE_LONG;
	else if (strcmp(type, "LONG RAW") == 0)
		result = ORA_TYPE_LONGRAW;
	else if (strcmp(type, "SDO_GEOMETRY") == 0 && strcmp(owner, "MDSYS") == 0)
		result = ORA_TYPE_GEOMETRY;
	else if (strcmp(type, "XMLTYPE") == 0 && (strcmp(owner, "PUBLIC") == 0 || strcmp(owner, "SYS") == 0))
		result = ORA_TYPE_XMLTYPE;
	else if (strcmp(type, "FLOAT") == 0)
		result = ORA_TYPE_FLOAT;
	else if (strcmp(type, "NCHAR") == 0)
		result = ORA_TYPE_NCHAR;
	else if (startsWith(type, "INTERVAL DAY"))
		result = ORA_TYPE_INTERVALD2S;
	else if (startsWith(type, "INTERVAL YEAR"))
		result = ORA_TYPE_INTERVALY2M;
	else if (strcmp(type, "BINARY_FLOAT") == 0)
		result = ORA_TYPE_BINARYFLOAT;
	else if (strcmp(type, "BINARY_DOUBLE") == 0)
		result = ORA_TYPE_BINARYDOUBLE;

	free(type);
	free(owner);
	return result;
}

static int
parseIntOrZero(const char *value)
{
	return (value && value[0]) ? atoi(value) : 0;
}

static int
splitFields(char *row, char **fields, int max_fields)
{
	int count = 0;
	char *pos = row;

	if (max_fields <= 0)
		return 0;

	fields[count++] = pos;
	while (*pos && count < max_fields)
	{
		if (*pos == JNI_FIELD_SEP)
		{
			*pos = '\0';
			fields[count++] = pos + 1;
		}
		++pos;
	}
	while (count < max_fields)
		fields[count++] = (char *)"";

	return count;
}

static char *
jstringToMalloc(JNIEnv *env, jstring value)
{
	const char *utf;
	char *copy;

	if (!value)
		return NULL;

	utf = (*env)->GetStringUTFChars(env, value, NULL);
	if (!utf)
		return NULL;

	copy = mallocStrdup(utf);
	(*env)->ReleaseStringUTFChars(env, value, utf);
	return copy;
}

static char *
jthrowableToDetail(JNIEnv *env, jthrowable throwable)
{
	jclass throwable_class;
	jmethodID to_string_mid;
	jstring text;
	char *detail;

	if (!throwable)
		return errorDetailStrdup("Java exception");

	throwable_class = (*env)->GetObjectClass(env, throwable);
	if (!throwable_class || (*env)->ExceptionCheck(env))
	{
		(*env)->ExceptionClear(env);
		return errorDetailStrdup("Java exception; could not inspect Throwable class");
	}
	to_string_mid = (*env)->GetMethodID(env, throwable_class, "toString", "()Ljava/lang/String;");
	if (!to_string_mid || (*env)->ExceptionCheck(env))
	{
		(*env)->ExceptionClear(env);
		(*env)->DeleteLocalRef(env, throwable_class);
		return errorDetailStrdup("Java exception; could not resolve Throwable.toString()");
	}
	text = (jstring)(*env)->CallObjectMethod(env, throwable, to_string_mid);
	if ((*env)->ExceptionCheck(env))
	{
		(*env)->ExceptionClear(env);
		if (throwable_class)
			(*env)->DeleteLocalRef(env, throwable_class);
		return errorDetailStrdup("Java exception; Throwable.toString() failed");
	}
	detail = jstringToMalloc(env, text);
	if (text)
		(*env)->DeleteLocalRef(env, text);
	if (throwable_class)
		(*env)->DeleteLocalRef(env, throwable_class);

	if (!detail)
		return errorDetailStrdup("Java exception");

#ifndef USE_JNI_BACKEND
	{
		char *pg_detail = pstrdup(detail);
		free(detail);
		return pg_detail;
	}
#else
	return detail;
#endif
}

static void
oracleError_d_jni(oraError sqlstate, const char *message, const char *detail)
{
#ifndef USE_JNI_BACKEND
	(void)sqlstate;
	ereport(ERROR,
			(errcode(ERRCODE_FDW_ERROR),
			 errmsg("%s", message),
			 errdetail("%s", detail ? detail : "")));
#else
	oracleError_d(sqlstate, message, detail);
#endif
}

static void
checkJava(JNIEnv *env, const char *message)
{
	if ((*env)->ExceptionCheck(env))
	{
		jthrowable throwable = (*env)->ExceptionOccurred(env);
		char *detail;

		(*env)->ExceptionClear(env);
		detail = jthrowableToDetail(env, throwable);
		if (throwable)
			(*env)->DeleteLocalRef(env, throwable);

		jniDebug3(detail);
		oracleError_d_jni(FDW_ERROR, message, detail);
#ifdef USE_JNI_BACKEND
		free(detail);
#endif
	}
}

static jstring
newJavaString(JNIEnv *env, const char *value)
{
	jstring result = (*env)->NewStringUTF(env, value ? value : "");

	checkJava(env, "error creating Java string in JNI backend");
	return result;
}

static jbyteArray
newJavaLengthPrefixedBytes(JNIEnv *env, const char *value)
{
	int32_t len;
	jbyteArray result;

	if (!value)
		return NULL;

	memcpy(&len, value, sizeof(len));
	if (len < 0)
		oracleError(FDW_ERROR, "JNI backend internal error: invalid length-prefixed bind value");

	result = (*env)->NewByteArray(env, len + (jsize)sizeof(len));
	checkJava(env, "error creating Java byte array in JNI backend");
	(*env)->SetByteArrayRegion(env, result, 0, len + (jsize)sizeof(len), (const jbyte *)value);
	checkJava(env, "error filling Java byte array in JNI backend");
	return result;
}

static jobject
newJavaBindValue(JNIEnv *env, struct paramDesc *param)
{
	if (!param->value || param->bindType == BIND_OUTPUT)
		return NULL;

	switch (param->bindType)
	{
		case BIND_LONG:
		case BIND_LONGRAW:
			return (jobject)newJavaLengthPrefixedBytes(env, param->value);
		case BIND_GEOMETRY:
			oracleError(FDW_ERROR, "JNI backend does not support binding Oracle geometry parameters yet");
			break;
		default:
			return (jobject)newJavaString(env, param->value);
	}

	return NULL;
}

static char *
buildClasspath(void)
{
	const char *override = getenv("ORACLE_FDW_JDBC_CLASSPATH");
	const char *bridge = getenv("ORACLE_FDW_JNI_BRIDGE_JAR");
	const char *jdbc = getenv("ORACLE_FDW_JDBC_JAR");
	size_t len;
	char *classpath;

	if (override && override[0])
		return mallocStrdup(override);

	if (!bridge || !bridge[0])
		bridge = ORACLE_FDW_JNI_BRIDGE_JAR;
	if (!jdbc || !jdbc[0])
		jdbc = ORACLE_FDW_DEFAULT_JDBC_JAR;

	len = strlen(bridge) + strlen(jdbc) + strlen(ORACLE_FDW_CLASSPATH_SEP) + 1;
	classpath = (char *)malloc(len);
	if (!classpath)
		oracleError(FDW_OUT_OF_MEMORY, "out of memory building JNI classpath");

	snprintf(classpath, len, "%s%s%s", bridge, ORACLE_FDW_CLASSPATH_SEP, jdbc);
	return classpath;
}

static JNIEnv *
getEnv(void)
{
	jint status;

	if (!jvm)
		return NULL;

	status = (*jvm)->GetEnv(jvm, (void **)&jni_env, JNI_VERSION_1_8);
	if (status == JNI_EDETACHED)
	{
		jniDebug3("oracle_fdw_jni: In jdbc_attach_jvm");
		if ((*jvm)->AttachCurrentThread(jvm, (void **)&jni_env, NULL) != JNI_OK)
			oracleError(FDW_ERROR, "could not attach current thread to JVM");
	}
	else if (status == JNI_OK)
		jniDebug3("oracle_fdw_jni: JVMEnvStat: JNI_OK");
	else if (status != JNI_OK)
		oracleError(FDW_ERROR, "could not get JNI environment");

	return jni_env;
}

static void
loadMethod(JNIEnv *env, jmethodID *target, const char *name, const char *sig, int is_static)
{
	*target = is_static
		? (*env)->GetStaticMethodID(env, backend_class, name, sig)
		: (*env)->GetMethodID(env, backend_class, name, sig);
	checkJava(env, "error resolving Java backend method");
	if (!*target)
		oracleError(FDW_ERROR, "Java backend method was not found");
}

static JNIEnv *
ensureJvm(void)
{
	JNIEnv *env = getEnv();

	jniDebug3("oracle_fdw_jni: In ensureJvm");
	if (env)
		return env;

	{
		JavaVMInitArgs args;
		JavaVMOption options[3];
		char *classpath = buildClasspath();
		size_t cp_option_len = strlen("-Djava.class.path=") + strlen(classpath) + 1;
		char *cp_option = (char *)malloc(cp_option_len);
		jclass local_class;
		jint rc;

		if (!cp_option)
			oracleError(FDW_OUT_OF_MEMORY, "out of memory building JVM options");
		snprintf(cp_option, cp_option_len, "-Djava.class.path=%s", classpath);

		options[0].optionString = cp_option;
		options[1].optionString = "-Dfile.encoding=UTF-8";
		options[2].optionString = "-Xrs";

		memset(&args, 0, sizeof(args));
		args.version = JNI_VERSION_1_8;
		args.nOptions = 3;
		args.options = options;
		args.ignoreUnrecognized = JNI_FALSE;

		jniDebug3("oracle_fdw_jni: In jdbc_jvm_init");
		jniDebug3("oracle_fdw_jni: In JNI_CreateJavaVM");
		rc = JNI_CreateJavaVM(&jvm, (void **)&env, &args);
		if (rc != JNI_OK)
		{
			char detail[2048];

			snprintf(detail, sizeof(detail), "JNI_CreateJavaVM failed with code %d; classpath=%s", (int)rc, classpath);
			free(classpath);
			free(cp_option);
			oracleError_d_jni(FDW_ERROR, "could not create JVM for JDBC backend", detail);
		}
		jni_env = env;
		jniDebug3("oracle_fdw_jni: In jdbc_attach_jvm");
		if ((*jvm)->AttachCurrentThread(jvm, (void **)&jni_env, NULL) != JNI_OK)
			oracleError(FDW_ERROR, "could not attach current thread to JVM");

		local_class = (*jni_env)->FindClass(jni_env, "OracleJdbcBackend");
		checkJava(jni_env, "could not load OracleJdbcBackend Java class");
		if (!local_class)
			oracleError(FDW_ERROR, "OracleJdbcBackend Java class was not found");
		backend_class = (jclass)(*jni_env)->NewGlobalRef(jni_env, local_class);
		(*jni_env)->DeleteLocalRef(jni_env, local_class);
		checkJava(jni_env, "could not retain OracleJdbcBackend Java class");

		loadMethod(jni_env, &ctor_mid, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V", 0);
		loadMethod(jni_env, &client_version_mid, "clientVersion", "()[I", 1);
		loadMethod(jni_env, &server_version_mid, "serverVersion", "()[I", 0);
		loadMethod(jni_env, &close_mid, "close", "()V", 0);
		loadMethod(jni_env, &close_statement_mid, "closeStatement", "()V", 0);
		loadMethod(jni_env, &is_statement_open_mid, "isStatementOpen", "()Z", 0);
		loadMethod(jni_env, &describe_mid, "describe", "(Ljava/lang/String;Ljava/lang/String;)[Ljava/lang/String;", 0);
		loadMethod(jni_env, &prepare_query_mid, "prepareQuery", "(Ljava/lang/String;)V", 0);
		loadMethod(jni_env, &execute_query_mid, "executeQuery", "()I", 0);
		loadMethod(jni_env, &execute_query_with_params_mid, "executeQuery", "([Ljava/lang/String;[I[Ljava/lang/Object;)I", 0);
		loadMethod(jni_env, &fetch_next_mid, "fetchNext", "()Z", 0);
		loadMethod(jni_env, &get_value_mid, "getValue", "(I)Ljava/lang/String;", 0);
		loadMethod(jni_env, &execute_call_mid, "executeCall", "(Ljava/lang/String;)V", 0);
		loadMethod(jni_env, &end_transaction_mid, "endTransaction", "(Z)V", 0);
		loadMethod(jni_env, &rollback_to_savepoint_mid, "rollbackToSavepoint", "(I)V", 0);
		loadMethod(jni_env, &explain_mid, "explain", "(Ljava/lang/String;)[Ljava/lang/String;", 0);
		loadMethod(jni_env, &load_import_columns_mid, "loadImportColumns", "(Ljava/lang/String;Ljava/lang/String;III)V", 0);
		loadMethod(jni_env, &get_import_column_next_mid, "getImportColumnNext", "()Ljava/lang/String;", 0);

		free(classpath);
		free(cp_option);
	}

	return jni_env;
}

static void
registerSession(JniSessionState *state)
{
	if (session_count == session_capacity)
	{
		void *new_sessions;

		session_capacity = session_capacity ? session_capacity * 2 : 8;
		new_sessions = realloc(sessions, session_capacity * sizeof(*sessions));
		if (!new_sessions)
			oracleError(FDW_OUT_OF_MEMORY, "out of memory growing JNI connection cache");
		sessions = (JniSessionState **)new_sessions;
	}
	sessions[session_count++] = state;
}

static void
unregisterSession(JniSessionState *state)
{
	size_t i;

	for (i = 0; i < session_count; ++i)
	{
		if (sessions[i] == state)
		{
			sessions[i] = sessions[session_count - 1];
			--session_count;
			return;
		}
	}
}

static JniSessionState *
getState(oracleSession *session)
{
	if (!session || !session->thin_runtime)
		oracleError(FDW_ERROR, "JNI backend internal error: invalid Oracle session");

	return (JniSessionState *)session->thin_runtime;
}

static void
freeLobs(JniSessionState *state)
{
	JniLobContent *lob = state ? state->lobs : NULL;

	while (lob)
	{
		JniLobContent *next = lob->next;

		oracleFree(lob->data);
		oracleFree(lob);
		lob = next;
	}
	if (state)
		state->lobs = NULL;
}

static void
dropState(JniSessionState *state)
{
	JNIEnv *env;

	if (!state)
		return;

	if (state->callback_registered)
	{
		oracleUnregisterCallback(state);
		state->callback_registered = 0;
	}

	env = getEnv();
	if (env && state->backend)
	{
		(*env)->CallVoidMethod(env, state->backend, close_mid);
		checkJava(env, "error closing JDBC backend connection");
		(*env)->DeleteGlobalRef(env, state->backend);
		state->backend = NULL;
	}

	freeLobs(state);
	unregisterSession(state);
	free(state);
}

static void
readIntArray(JNIEnv *env, jintArray array, int values[5])
{
	jsize len;
	jint tmp[5] = {0, 0, 0, 0, 0};
	int i;

	memset(values, 0, sizeof(int) * 5);
	if (!array)
		return;

	len = (*env)->GetArrayLength(env, array);
	if (len > 5)
		len = 5;
	(*env)->GetIntArrayRegion(env, array, 0, len, tmp);
	checkJava(env, "error reading Java version array");

	for (i = 0; i < len; ++i)
		values[i] = (int)tmp[i];
}

static int
valSizeForType(oraType type, int data_length, long max_long)
{
	switch (type)
	{
		case ORA_TYPE_NUMBER:
			return 64;
		case ORA_TYPE_DATE:
			return 32;
		case ORA_TYPE_TIMESTAMP:
		case ORA_TYPE_TIMESTAMPTZ:
		case ORA_TYPE_TIMESTAMPLTZ:
			return 64;
		case ORA_TYPE_RAW:
		case ORA_TYPE_LONGRAW:
			return data_length > 0 ? data_length : 1;
		case ORA_TYPE_BLOB:
		case ORA_TYPE_CLOB:
		case ORA_TYPE_NCLOB:
		case ORA_TYPE_BFILE:
			return (int)sizeof(void *);
		case ORA_TYPE_LONG:
		case ORA_TYPE_XMLTYPE:
			return (int)max_long + 4;
		case ORA_TYPE_GEOMETRY:
			return (int)sizeof(ora_geometry);
		default:
			return data_length > 0 ? data_length + 1 : 1;
	}
}

static void
copyValueToColumn(oracleSession *session, struct oraColumn *column, unsigned int slot, const char *value)
{
	JniSessionState *state = getState(session);

	if (!column)
		return;

	if (!value)
	{
		if (column->val_null)
			column->val_null[slot] = -1;
		if (column->val_len)
			column->val_len[slot] = 0;
		return;
	}

	if (column->val_null)
		column->val_null[slot] = 0;

	if (column->oratype == ORA_TYPE_BLOB || column->oratype == ORA_TYPE_CLOB ||
		column->oratype == ORA_TYPE_NCLOB || column->oratype == ORA_TYPE_BFILE)
	{
		JniLobContent *lob = (JniLobContent *)oracleAlloc(sizeof(*lob));
		size_t len = strlen(value);
		void **slot_ptr = (void **)column->val;

		memset(lob, 0, sizeof(*lob));
		lob->data = (char *)oracleAlloc(len + 1);
		memcpy(lob->data, value, len + 1);
		lob->len = (long)len;
		lob->next = state->lobs;
		state->lobs = lob;

		if (slot_ptr)
			slot_ptr[slot] = lob;
		if (column->val_len)
			column->val_len[slot] = (uint16_t)sizeof(void *);
		return;
	}

	if (column->val && column->val_size > 0)
	{
		size_t max_len = (size_t)column->val_size;
		size_t copy_len = strlen(value);
		char *dest = column->val + slot * max_len;

		if (copy_len >= max_len)
			copy_len = max_len - 1;
		memcpy(dest, value, copy_len);
		dest[copy_len] = '\0';

		if (column->val_len)
			column->val_len[slot] = (uint16_t)copy_len;
	}
}

oracleSession *
oracleGetSession(const char *connectstring, oraIsoLevel isolation_level, char *user, char *password,
				 const char *nls_lang, const char *timezone, int have_nchar,
				 const char *tablename, int curlevel)
{
	JNIEnv *env = ensureJvm();
	jstring j_connect = newJavaString(env, connectstring);
	jstring j_user = newJavaString(env, user);
	jstring j_password = newJavaString(env, password);
	jobject local_backend;
	oracleSession *session;
	JniSessionState *state;
	jintArray version;
	int version_values[5];

	(void)isolation_level;
	(void)nls_lang;
	(void)timezone;
	(void)tablename;
	(void)curlevel;

	local_backend = (*env)->NewObject(env, backend_class, ctor_mid, j_connect, j_user, j_password);
	checkJava(env, "error connecting to Oracle through JDBC backend");

	session = (oracleSession *)oracleAlloc(sizeof(*session));
	memset(session, 0, sizeof(*session));
	state = (JniSessionState *)malloc(sizeof(*state));
	if (!state)
		oracleError(FDW_OUT_OF_MEMORY, "out of memory allocating JNI session state");
	memset(state, 0, sizeof(*state));

	state->backend = (*env)->NewGlobalRef(env, local_backend);
	checkJava(env, "error retaining JDBC backend connection");
	session->thin_conn = state->backend;
	session->thin_runtime = state;
	session->have_nchar = have_nchar;

	version = (jintArray)(*env)->CallObjectMethod(env, state->backend, server_version_mid);
	checkJava(env, "error querying Oracle server version through JDBC backend");
	readIntArray(env, version, version_values);
	memcpy(session->server_version, version_values, sizeof(version_values));
	if (version)
		(*env)->DeleteLocalRef(env, version);

	(*env)->DeleteLocalRef(env, local_backend);
	(*env)->DeleteLocalRef(env, j_connect);
	(*env)->DeleteLocalRef(env, j_user);
	(*env)->DeleteLocalRef(env, j_password);

	registerSession(state);
	oracleRegisterCallback(state);
	state->callback_registered = 1;
	return session;
}

void
oracleCloseStatement(oracleSession *session)
{
	JNIEnv *env;
	JniSessionState *state;

	if (!session || !session->thin_runtime)
		return;

	env = ensureJvm();
	state = getState(session);
	if (state->backend)
	{
		(*env)->CallVoidMethod(env, state->backend, close_statement_mid);
		checkJava(env, "error closing JDBC backend statement");
	}

	freeLobs(state);
	state->current_table = NULL;
	state->next_row = 0;
	state->import_open = 0;
	session->thin_stmt = NULL;
	session->thin_result = NULL;
	session->last_batch = 0;
	session->fetched_rows = 0;
	session->current_row = 0;
}

void
oracleCloseConnections(void)
{
	while (session_count > 0)
		dropState(sessions[session_count - 1]);
	free(sessions);
	sessions = NULL;
	session_capacity = 0;
}

void
oracleShutdown(void)
{
	oracleCloseConnections();
}

void
oracleCancel(void)
{
}

void
oracleEndTransaction(void *arg, int is_commit, int silent)
{
	(void)arg;
	(void)is_commit;
	(void)silent;

	jniTrace("oracleEndTransaction callback no-op for JNI backend");
}

void
oracleEndSubtransaction(void *arg, int nest_level, int is_commit)
{
	(void)arg;
	(void)nest_level;
	(void)is_commit;

	jniTrace("oracleEndSubtransaction callback no-op for JNI backend");
}

int
oracleIsStatementOpen(oracleSession *session)
{
	JNIEnv *env;
	JniSessionState *state;
	jboolean result;

	if (!session || !session->thin_runtime)
		return 0;

	env = ensureJvm();
	state = getState(session);
	result = (*env)->CallBooleanMethod(env, state->backend, is_statement_open_mid);
	checkJava(env, "error checking JDBC backend statement state");
	return result == JNI_TRUE;
}

struct oraTable *
oracleDescribe(oracleSession *session, char *dblink, char *schema, char *table, char *pgname,
			   long max_long, int *has_geometry)
{
	JNIEnv *env = ensureJvm();
	JniSessionState *state = getState(session);
	jstring j_schema = schema ? newJavaString(env, schema) : NULL;
	jstring j_table = newJavaString(env, table);
	jobjectArray rows;
	jsize row_count = 0;
	struct oraTable *result;
	int i;

	(void)dblink;
	setOutputInt(has_geometry, 0);

	rows = (jobjectArray)(*env)->CallObjectMethod(env, state->backend, describe_mid, j_schema, j_table);
	checkJava(env, "error describing Oracle table through JDBC backend");
	if (!rows || (row_count = (*env)->GetArrayLength(env, rows)) == 0)
		oracleError_sd(FDW_TABLE_NOT_FOUND, "Oracle table was not found", table, NULL);

	result = (struct oraTable *)oracleAlloc(sizeof(*result));
	memset(result, 0, sizeof(*result));
	result->name = oracleStrdup(table);
	result->pgname = oracleStrdup(pgname && pgname[0] ? pgname : table);
	result->ncols = (int)row_count;
	result->npgcols = 0;
	result->cols = (struct oraColumn **)oracleAlloc(sizeof(struct oraColumn *) * (size_t)row_count);

	for (i = 0; i < row_count; ++i)
	{
		jstring j_row = (jstring)(*env)->GetObjectArrayElement(env, rows, i);
		char *row = jstringToMalloc(env, j_row);
		char *fields[7];
		struct oraColumn *column;
		oraType type;
		int data_length;

		checkJava(env, "error reading JDBC describe row");
		splitFields(row, fields, 7);

		type = oraTypeFromNames(fields[1], fields[6]);
		data_length = parseIntOrZero(fields[2]);
		if (type == ORA_TYPE_GEOMETRY)
			setOutputInt(has_geometry, 1);

		column = (struct oraColumn *)oracleAlloc(sizeof(*column));
		memset(column, 0, sizeof(*column));
		column->name = oracleStrdup(fields[0]);
		column->pgname = NULL;
		column->oratype = type;
		column->scale = parseIntOrZero(fields[4]);
		column->pgattnum = 0;
		column->pgtypmod = 0;
		column->used = 0;
		column->val_size = valSizeForType(type, data_length, max_long);
		result->cols[i] = column;

		free(row);
		if (j_row)
			(*env)->DeleteLocalRef(env, j_row);
	}

	if (j_schema)
		(*env)->DeleteLocalRef(env, j_schema);
	(*env)->DeleteLocalRef(env, j_table);
	(*env)->DeleteLocalRef(env, rows);

	return result;
}

void
oracleExplain(oracleSession *session, const char *query, int *nrows, char ***plan)
{
	JNIEnv *env = ensureJvm();
	JniSessionState *state = getState(session);
	jstring j_query = newJavaString(env, query);
	jobjectArray rows;
	jsize row_count;
	int i;

	rows = (jobjectArray)(*env)->CallObjectMethod(env, state->backend, explain_mid, j_query);
	checkJava(env, "error explaining Oracle query through JDBC backend");
	row_count = rows ? (*env)->GetArrayLength(env, rows) : 0;

	*nrows = (int)row_count;
	*plan = (char **)oracleAlloc(sizeof(char *) * (size_t)row_count);
	for (i = 0; i < row_count; ++i)
	{
		jstring j_row = (jstring)(*env)->GetObjectArrayElement(env, rows, i);
		char *row = jstringToMalloc(env, j_row);

		(*plan)[i] = oracleStrdup(row ? row : "");
		free(row);
		if (j_row)
			(*env)->DeleteLocalRef(env, j_row);
	}

	(*env)->DeleteLocalRef(env, j_query);
	if (rows)
		(*env)->DeleteLocalRef(env, rows);
}

void
oraclePrepareQuery(oracleSession *session, const char *query, const struct oraTable *oraTable,
				   unsigned int prefetch, unsigned int lob_prefetch)
{
	JNIEnv *env = ensureJvm();
	JniSessionState *state = getState(session);
	jstring j_query = newJavaString(env, query);

	(void)prefetch;
	(void)lob_prefetch;

	(*env)->CallVoidMethod(env, state->backend, prepare_query_mid, j_query);
	checkJava(env, "error preparing Oracle query through JDBC backend");

	freeLobs(state);
	state->current_table = oraTable;
	state->next_row = 0;
	session->thin_stmt = state->backend;
	session->thin_result = NULL;
	session->last_batch = 0;
	session->fetched_rows = 0;
	session->current_row = 0;

	(*env)->DeleteLocalRef(env, j_query);
}

unsigned int
oracleExecuteQuery(oracleSession *session, const struct oraTable *oraTable, struct paramDesc *paramList,
				   unsigned int prefetch)
{
	JNIEnv *env = ensureJvm();
	JniSessionState *state = getState(session);
	struct paramDesc *param;
	jobjectArray names = NULL;
	jintArray types = NULL;
	jobjectArray values = NULL;
	jclass string_class = NULL;
	jclass object_class = NULL;
	jint *type_values = NULL;
	int param_count = 0;
	int param_index = 0;
	jint processed;

	(void)prefetch;

	for (param = paramList; param; param = param->next)
		++param_count;

	state->current_table = oraTable;
	state->next_row = 0;

	if (param_count == 0)
	{
		processed = (*env)->CallIntMethod(env, state->backend, execute_query_mid);
	}
	else
	{
		string_class = (*env)->FindClass(env, "java/lang/String");
		checkJava(env, "error loading Java String class in JNI backend");
		object_class = (*env)->FindClass(env, "java/lang/Object");
		checkJava(env, "error loading Java Object class in JNI backend");

		names = (*env)->NewObjectArray(env, param_count, string_class, NULL);
		checkJava(env, "error creating JDBC bind name array");
		types = (*env)->NewIntArray(env, param_count);
		checkJava(env, "error creating JDBC bind type array");
		values = (*env)->NewObjectArray(env, param_count, object_class, NULL);
		checkJava(env, "error creating JDBC bind value array");

		type_values = (jint *)oracleAlloc((size_t)param_count * sizeof(jint));
		for (param = paramList; param; param = param->next)
		{
			jstring name;
			jobject value;

			name = newJavaString(env, param->name);
			(*env)->SetObjectArrayElement(env, names, param_index, name);
			checkJava(env, "error setting JDBC bind name");
			(*env)->DeleteLocalRef(env, name);

			type_values[param_index] = (jint)param->bindType;

			value = newJavaBindValue(env, param);
			if (value)
			{
				(*env)->SetObjectArrayElement(env, values, param_index, value);
				checkJava(env, "error setting JDBC bind value");
				(*env)->DeleteLocalRef(env, value);
			}

			++param_index;
		}

		(*env)->SetIntArrayRegion(env, types, 0, param_count, type_values);
		checkJava(env, "error setting JDBC bind type array");

		processed = (*env)->CallIntMethod(env, state->backend, execute_query_with_params_mid,
										  names, types, values);
	}
	checkJava(env, "error executing Oracle query through JDBC backend");

	if (type_values)
		oracleFree(type_values);
	if (values)
		(*env)->DeleteLocalRef(env, values);
	if (types)
		(*env)->DeleteLocalRef(env, types);
	if (names)
		(*env)->DeleteLocalRef(env, names);
	if (object_class)
		(*env)->DeleteLocalRef(env, object_class);
	if (string_class)
		(*env)->DeleteLocalRef(env, string_class);

	session->thin_result = state->backend;
	session->fetched_rows = (unsigned int)(processed < 0 ? 0 : processed);
	session->current_row = 0;
	return (unsigned int)(processed < 0 ? 0 : processed);
}

unsigned int
oracleFetchNext(oracleSession *session, unsigned int prefetch)
{
	JNIEnv *env = ensureJvm();
	JniSessionState *state = getState(session);
	jboolean has_row;
	unsigned int slot;
	int i;

	has_row = (*env)->CallBooleanMethod(env, state->backend, fetch_next_mid);
	checkJava(env, "error fetching Oracle row through JDBC backend");
	if (has_row != JNI_TRUE)
	{
		session->last_batch = 1;
		session->current_row = 0;
		return 0;
	}

	slot = prefetch ? (state->next_row % prefetch) : 0;
	if (state->current_table)
	{
		for (i = 0; i < state->current_table->ncols; ++i)
		{
			struct oraColumn *column = state->current_table->cols[i];
			jstring j_value;
			char *value;

			if (!column || !column->used)
				continue;

			j_value = (jstring)(*env)->CallObjectMethod(env, state->backend, get_value_mid, (jint)i);
			checkJava(env, "error reading Oracle column value through JDBC backend");
			value = jstringToMalloc(env, j_value);
			copyValueToColumn(session, column, slot, value);
			free(value);
			if (j_value)
				(*env)->DeleteLocalRef(env, j_value);
		}
	}

	++state->next_row;
	session->current_row = slot + 1;
	return session->current_row;
}

void
oracleExecuteCall(oracleSession *session, char * const stmt)
{
	JNIEnv *env = ensureJvm();
	JniSessionState *state = getState(session);
	jstring j_stmt = newJavaString(env, stmt);

	if ((*env)->ExceptionCheck(env))
		(*env)->ExceptionClear(env);
	(*env)->CallVoidMethod(env, state->backend, execute_call_mid, j_stmt);
	checkJava(env, "error executing Oracle statement through JDBC backend");
	(*env)->DeleteLocalRef(env, j_stmt);
}

void
oracleGetLob(oracleSession *session, void *locptr, oraType type, char **value, long *value_len)
{
	JniLobContent *lob;

	(void)session;
	(void)type;

	*value = NULL;
	*value_len = 0;

	if (!locptr)
		return;

	lob = *(JniLobContent **)locptr;
	if (!lob)
		return;

	*value = (char *)oracleAlloc((size_t)lob->len + 1);
	memcpy(*value, lob->data, (size_t)lob->len);
	(*value)[lob->len] = '\0';
	*value_len = lob->len;
}

void
oracleClientVersion(int *major, int *minor, int *update, int *patch, int *port_patch)
{
	JNIEnv *env = ensureJvm();
	jintArray version = (jintArray)(*env)->CallStaticObjectMethod(env, backend_class, client_version_mid);
	int values[5];

	checkJava(env, "error querying Oracle JDBC client version");
	readIntArray(env, version, values);
	setOutputVersion(major, minor, update, patch, port_patch, values);
	if (version)
		(*env)->DeleteLocalRef(env, version);
}

void
oracleServerVersion(oracleSession *session, int *major, int *minor, int *update, int *patch, int *port_patch)
{
	if (!session)
	{
		int values[5] = {0, 0, 0, 0, 0};
		setOutputVersion(major, minor, update, patch, port_patch, values);
		return;
	}

	setOutputVersion(major, minor, update, patch, port_patch, session->server_version);
}

void *
oracleGetGeometryType(oracleSession *session)
{
	(void)session;
	return NULL;
}

int
oracleGetImportColumn(oracleSession *session, char *dblink, char *schema, char *limit_to,
					  char **tabname, char **colname, oraType *type, int *charlen,
					  int *typeprec, int *typescale, int *nullable, int *key,
					  int skip_tables, int skip_views, int skip_matviews)
{
	JNIEnv *env = ensureJvm();
	JniSessionState *state = getState(session);
	jstring j_row;
	char *row;
	char *fields[9];

	(void)dblink;

	if (!state->import_open)
	{
		jstring j_schema = schema ? newJavaString(env, schema) : NULL;
		jstring j_limit_to = limit_to ? newJavaString(env, limit_to) : NULL;

		(*env)->CallVoidMethod(env, state->backend, load_import_columns_mid, j_schema, j_limit_to,
							   (jint)skip_tables, (jint)skip_views, (jint)skip_matviews);
		checkJava(env, "error loading Oracle import columns through JDBC backend");
		state->import_open = 1;

		if (j_schema)
			(*env)->DeleteLocalRef(env, j_schema);
		if (j_limit_to)
			(*env)->DeleteLocalRef(env, j_limit_to);
	}

	j_row = (jstring)(*env)->CallObjectMethod(env, state->backend, get_import_column_next_mid);
	checkJava(env, "error fetching Oracle import column through JDBC backend");
	if (!j_row)
	{
		state->import_open = 0;
		return 0;
	}

	row = jstringToMalloc(env, j_row);
	splitFields(row, fields, 9);

	*tabname = oracleStrdup(fields[0]);
	*colname = oracleStrdup(fields[1]);
	*type = oraTypeFromNames(fields[2], fields[3]);
	*charlen = parseIntOrZero(fields[4]);
	*typeprec = parseIntOrZero(fields[5]);
	*typescale = parseIntOrZero(fields[6]);
	*nullable = fields[7][0] == 'Y';
	*key = parseIntOrZero(fields[8]);

	free(row);
	(*env)->DeleteLocalRef(env, j_row);
	return 1;
}
