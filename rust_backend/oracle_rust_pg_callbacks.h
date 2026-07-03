/*-------------------------------------------------------------------------
 *
 * oracle_rust_pg_callbacks.h
 *		PostgreSQL-side callback surface needed by the Rust backend.
 *
 * The original OCI backend in oracle_utils.c and oracle_gis.c intentionally
 * avoids including postgres.h.  It reaches PostgreSQL facilities through the
 * oracle* wrapper functions declared in oracle_fdw.h.  Rust should use the
 * same boundary rather than calling PostgreSQL APIs directly.
 *
 *-------------------------------------------------------------------------
 */

#ifndef ORACLE_RUST_PG_CALLBACKS_H
#define ORACLE_RUST_PG_CALLBACKS_H

#include "oracle_fdw.h"

#ifdef __cplusplus
extern "C" {
#endif

/*
 * Core callbacks used by oracle_utils.c.
 *
 * Required for non-GIS operation:
 * - memory lifetime matching PostgreSQL palloc/repalloc/pfree
 * - error reporting with PostgreSQL SQLSTATE mapping
 * - debug logging
 * - transaction callback registration for cached remote connections
 * - PostgreSQL/Oracle signal and exit handler setup
 */
typedef struct OracleFdwPgCoreCallbacks
{
	void *(*alloc)(size_t size);
	void *(*realloc)(void *ptr, size_t size);
	void (*free)(void *ptr);

	void (*error)(oraError sqlstate, const char *message);
	void (*error_i)(oraError sqlstate, const char *message, int arg);
	void (*error_ii)(oraError sqlstate, const char *message, int arg1, int arg2);
	void (*error_d)(oraError sqlstate, const char *message, const char *detail);
	void (*error_sd)(oraError sqlstate, const char *message, const char *arg, const char *detail);
	void (*error_ssdh)(oraError sqlstate, const char *message, const char *arg1, const char *arg2, const char *detail, const char *hint);

	void (*debug2)(const char *message);
	void (*set_handlers)(void);
	void (*register_callback)(void *arg);
	void (*unregister_callback)(void *arg);
} OracleFdwPgCoreCallbacks;

/*
 * PostGIS/type callbacks used from oracle_utils.c.
 *
 * initialize_postgis resolves/caches PostGIS type OIDs before geometry columns
 * are described.  Rust needs this if it wants to preserve the existing
 * ORA_TYPE_GEOMETRY detection behavior.
 */
typedef struct OracleFdwPgPostgisCallbacks
{
	void (*initialize_postgis)(void);
} OracleFdwPgPostgisCallbacks;

/*
 * Geometry callbacks used by oracle_gis.c and by the fdw row conversion path.
 *
 * These are required only when Rust delegates EWKB <-> Oracle geometry
 * conversion to the existing C code or needs the srid.map lookup path.
 */
typedef struct OracleFdwPgGeometryCallbacks
{
	char *(*get_share_file_name)(const char *relativename);

	ora_geometry *(*ewkb_to_geom)(oracleSession *session, unsigned int ewkb_length, char *ewkb_data);
	unsigned int (*get_ewkb_len)(oracleSession *session, ora_geometry *geom);
	char *(*fill_ewkb)(oracleSession *session, ora_geometry *geom, unsigned int size, char *dest);
	void (*geometry_free)(oracleSession *session, ora_geometry *geom);
	void (*geometry_alloc)(oracleSession *session, ora_geometry *geom);
} OracleFdwPgGeometryCallbacks;

typedef struct OracleFdwPgCallbacks
{
	unsigned int version;
	OracleFdwPgCoreCallbacks core;
	OracleFdwPgPostgisCallbacks postgis;
	OracleFdwPgGeometryCallbacks geometry;
} OracleFdwPgCallbacks;

#define ORACLE_FDW_PG_CALLBACKS_VERSION 1U

/*
 * Implemented by the Rust backend static library.
 * Returns 0 on success, nonzero if the table is invalid or unsupported.
 */
extern int oracle_rs_register_pg_callbacks(const OracleFdwPgCallbacks *callbacks);

/*
 * Implemented on the C/PostgreSQL side.  It fills OracleFdwPgCallbacks with
 * the existing oracle_fdw wrapper functions and passes it to Rust.
 */
extern int oracleRegisterRustPgCallbacks(void);

#ifdef __cplusplus
}
#endif

#endif /* ORACLE_RUST_PG_CALLBACKS_H */
