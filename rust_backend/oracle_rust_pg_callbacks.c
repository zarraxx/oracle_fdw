/*-------------------------------------------------------------------------
 *
 * oracle_rust_pg_callbacks.c
 *		Register oracle_fdw's PostgreSQL wrapper surface with Rust.
 *
 *-------------------------------------------------------------------------
 */

#include "oracle_rust_pg_callbacks.h"

#include <string.h>

int
oracleRegisterRustPgCallbacks(void)
{
	static int registered = 0;
	OracleFdwPgCallbacks callbacks;
	int result;

	if (registered)
		return 0;

	memset(&callbacks, 0, sizeof(callbacks));
	callbacks.version = ORACLE_FDW_PG_CALLBACKS_VERSION;

	callbacks.core.alloc = oracleAlloc;
	callbacks.core.realloc = oracleRealloc;
	callbacks.core.free = oracleFree;
	callbacks.core.error = oracleError;
	callbacks.core.error_i = oracleError_i;
	callbacks.core.error_ii = oracleError_ii;
	callbacks.core.error_d = oracleError_d;
	callbacks.core.error_sd = oracleError_sd;
	callbacks.core.error_ssdh = oracleError_ssdh;
	callbacks.core.debug2 = oracleDebug2;
	callbacks.core.set_handlers = oracleSetHandlers;
	callbacks.core.register_callback = oracleRegisterCallback;
	callbacks.core.unregister_callback = oracleUnregisterCallback;

	callbacks.postgis.initialize_postgis = initializePostGIS;

#ifndef ORACLE_RUST_PG_CALLBACKS_OMIT_GEOMETRY
	callbacks.geometry.get_share_file_name = oracleGetShareFileName;
	callbacks.geometry.ewkb_to_geom = oracleEWKBToGeom;
	callbacks.geometry.get_ewkb_len = oracleGetEWKBLen;
	callbacks.geometry.fill_ewkb = oracleFillEWKB;
	callbacks.geometry.geometry_free = oracleGeometryFree;
	callbacks.geometry.geometry_alloc = oracleGeometryAlloc;
#endif

	result = oracle_rs_register_pg_callbacks(&callbacks);
	if (result == 0)
		registered = 1;

	return result;
}
