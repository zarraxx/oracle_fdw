# PostgreSQL callbacks required by the Rust backend

`oracle_utils.c` and `oracle_gis.c` do not include `postgres.h` directly.
The original OCI backend reaches PostgreSQL through wrapper functions declared
in `oracle_fdw.h`.  The Rust backend should register the same surface.

The FFI shape is captured in `rust_backend/oracle_rust_pg_callbacks.h`.

## Core callbacks

These are required for normal non-GIS operation.

| Callback field | Existing C wrapper | Used by | Purpose |
| --- | --- | --- | --- |
| `core.alloc` | `oracleAlloc` | `oracle_utils.c`, `oracle_gis.c` | Allocate result/session/column/LOB buffers in PostgreSQL memory context. |
| `core.realloc` | `oracleRealloc` | `oracle_utils.c` | Grow plan and LOB buffers. |
| `core.free` | `oracleFree` | `oracle_utils.c`, `oracle_gis.c` | Free memory allocated through the PostgreSQL side. |
| `core.error` | `oracleError` | `oracle_utils.c`, `oracle_gis.c` | Raise PostgreSQL ERROR with SQLSTATE. Does not return. |
| `core.error_i` | `oracleError_i` | `oracle_utils.c`, `oracle_gis.c` | Raise formatted PostgreSQL ERROR with one integer. |
| `core.error_ii` | `oracleError_ii` | `oracle_gis.c` | Raise formatted PostgreSQL ERROR with two integers. |
| `core.error_d` | `oracleError_d` | `oracle_utils.c`, `oracle_gis.c` | Raise PostgreSQL ERROR with detail text, commonly OCI error text. |
| `core.error_sd` | `oracleError_sd` | `oracle_utils.c` | Raise PostgreSQL ERROR with one string arg and detail. |
| `core.error_ssdh` | `oracleError_ssdh` | `oracle_utils.c` | Raise PostgreSQL ERROR with two string args, detail, and hint. |
| `core.debug2` | `oracleDebug2` | `oracle_utils.c` | Emit DEBUG2 diagnostic messages. |
| `core.set_handlers` | `oracleSetHandlers` | `oracle_utils.c` | Install PostgreSQL-aware signal/exit handlers before backend work. |
| `core.register_callback` | `oracleRegisterCallback` | `oracle_utils.c` | Register connection resources for transaction callbacks. |
| `core.unregister_callback` | `oracleUnregisterCallback` | `oracle_utils.c` | Unregister connection resources when sessions are closed. |

## PostGIS callback

| Callback field | Existing C wrapper | Used by | Purpose |
| --- | --- | --- | --- |
| `postgis.initialize_postgis` | `initializePostGIS` | `oracle_utils.c` | Resolve/cache PostGIS geometry OID before describing Oracle tables. |

## Geometry callbacks

These are only required when Rust delegates geometry conversion or `srid.map`
lookup to the existing C code.

| Callback field | Existing C wrapper | Used by | Purpose |
| --- | --- | --- | --- |
| `geometry.get_share_file_name` | `oracleGetShareFileName` | `oracle_gis.c` | Locate files under PostgreSQL share dir, currently `srid.map`. |
| `geometry.ewkb_to_geom` | `oracleEWKBToGeom` | `oracle_fdw.c` row write path | Convert PostGIS EWKB into Oracle SDO geometry object. |
| `geometry.get_ewkb_len` | `oracleGetEWKBLen` | `oracle_fdw.c` row read path | Compute EWKB output length for Oracle SDO geometry. |
| `geometry.fill_ewkb` | `oracleFillEWKB` | `oracle_fdw.c` row read path | Fill PostgreSQL EWKB buffer from Oracle SDO geometry. |
| `geometry.geometry_free` | `oracleGeometryFree` | `oracle_fdw.c`, `oracle_gis.c` | Release Oracle object-cache geometry resources. |
| `geometry.geometry_alloc` | `oracleGeometryAlloc` | `oracle_utils.c`, `oracle_gis.c` | Allocate Oracle SDO geometry resources. |

## Notes for Rust

- Treat all `core.error*` callbacks as non-returning in normal PostgreSQL use.
- Memory returned to PostgreSQL data structures should come from `core.alloc`
  or `core.realloc`, not Rust's global allocator.
- `register_callback` and `unregister_callback` currently pass opaque
  connection/session resource pointers.  Rust can pass an opaque handle, but
  PostgreSQL-side cleanup must understand how to call back into Rust before
  this is useful.
- `postgis.initialize_postgis` is harmless for non-geometry schemas, but it is
  required for behavior parity when `SDO_GEOMETRY` is encountered.
