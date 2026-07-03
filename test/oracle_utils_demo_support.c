#include <stdio.h>
#include <stdlib.h>

#include "../oracle_fdw.h"

void
initializePostGIS(void)
{
}

void
oracleRegisterCallback(void *arg)
{
	(void)arg;
}

void
oracleUnregisterCallback(void *arg)
{
	(void)arg;
}

void *
oracleAlloc(size_t size)
{
	void *ptr = malloc(size ? size : 1);

	if (!ptr)
	{
		fprintf(stderr, "oracle_utils_demo: out of memory\n");
		abort();
	}

	return ptr;
}

void *
oracleRealloc(void *p, size_t size)
{
	void *ptr = realloc(p, size ? size : 1);

	if (!ptr)
	{
		fprintf(stderr, "oracle_utils_demo: out of memory\n");
		abort();
	}

	return ptr;
}

void
oracleFree(void *p)
{
	free(p);
}

void
oracleSetHandlers(void)
{
}

static void
demoError(const char *message, const char *detail)
{
	fprintf(stderr, "oracle_utils_demo: %s\n", message ? message : "oracle error");
	if (detail && detail[0])
		fprintf(stderr, "oracle_utils_demo detail: %s\n", detail);
	abort();
}

void
oracleError_d(oraError sqlstate, const char *message, const char *detail)
{
	(void)sqlstate;
	demoError(message, detail);
}

void
oracleError_sd(oraError sqlstate, const char *message, const char *arg, const char *detail)
{
	(void)sqlstate;
	fprintf(stderr, "oracle_utils_demo arg: %s\n", arg ? arg : "(null)");
	demoError(message, detail);
}

void
oracleError_ssdh(oraError sqlstate, const char *message, const char *arg1, const char *arg2, const char *detail, const char *hint)
{
	(void)sqlstate;
	(void)arg1;
	(void)arg2;
	(void)hint;
	fprintf(stderr, "oracle_utils_demo arg1: %s\n", arg1 ? arg1 : "(null)");
	fprintf(stderr, "oracle_utils_demo arg2: %s\n", arg2 ? arg2 : "(null)");
	demoError(message, detail);
}

void
oracleError_ii(oraError sqlstate, const char *message, int arg1, int arg2)
{
	(void)sqlstate;
	(void)arg1;
	(void)arg2;
	demoError(message, NULL);
}

void
oracleError_i(oraError sqlstate, const char *message, int arg)
{
	(void)sqlstate;
	(void)arg;
	demoError(message, NULL);
}

void
oracleError(oraError sqlstate, const char *message)
{
	(void)sqlstate;
	demoError(message, NULL);
}

void  
oracleDebug2(const char *message)
{
	(void)message;
}

void
oracleGeometryFree(oracleSession *session, ora_geometry *geom)
{
	(void)session;
	(void)geom;
}

void
oracleGeometryAlloc(oracleSession *session, ora_geometry *geom)
{
	(void)session;
	(void)geom;
}
