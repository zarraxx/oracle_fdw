#include <stddef.h>

#include "../oracle_fdw.h"

ora_geometry *
oracleEWKBToGeom(oracleSession *session, unsigned int ewkb_length, char *ewkb_data)
{
	(void)session;
	(void)ewkb_length;
	(void)ewkb_data;
	oracleError(FDW_ERROR, "Oracle geometry is not supported by the JDBC/JNI backend");
	return NULL;
}

unsigned int
oracleGetEWKBLen(oracleSession *session, ora_geometry *geom)
{
	(void)session;
	(void)geom;
	oracleError(FDW_ERROR, "Oracle geometry is not supported by the JDBC/JNI backend");
	return 0;
}

char *
oracleFillEWKB(oracleSession *session, ora_geometry *geom, unsigned int size, char *dest)
{
	(void)session;
	(void)geom;
	(void)size;
	(void)dest;
	oracleError(FDW_ERROR, "Oracle geometry is not supported by the JDBC/JNI backend");
	return NULL;
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
	oracleError(FDW_ERROR, "Oracle geometry is not supported by the JDBC/JNI backend");
}
