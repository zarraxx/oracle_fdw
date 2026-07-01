#include <stdint.h>
#include <sys/types.h>

#include "oracle_fdw.h"

struct oracleSession
{
    void       *thin_conn;
    void       *thin_stmt;
    void       *thin_result;
    void       *thin_runtime;
    void       *user_data;

    int         have_nchar;
    int         server_version[5];

    unsigned int last_batch;
    unsigned int fetched_rows;
    unsigned int current_row;
};


extern oracleSession *oracleGetSession(const char *connectstring, oraIsoLevel isolation_level, char *user, char *password, const char *nls_lang, const char *timezone, int have_nchar, const char *tablename, int curlevel);
extern void oracleCloseStatement(oracleSession *session);
extern void oracleCloseConnections(void);
extern void oracleShutdown(void);
extern void oracleCancel(void);
extern void oracleEndTransaction(void *arg, int is_commit, int silent);
extern void oracleEndSubtransaction(void *arg, int nest_level, int is_commit);
extern int oracleIsStatementOpen(oracleSession *session);
extern struct oraTable *oracleDescribe(oracleSession *session, char *dblink, char *schema, char *table, char *pgname, long max_long, int *has_geometry);
extern void oracleExplain(oracleSession *session, const char *query, int *nrows, char ***plan);
extern void oraclePrepareQuery(oracleSession *session, const char *query, const struct oraTable *oraTable, unsigned int prefetch, unsigned int lob_prefetch);
extern unsigned int oracleExecuteQuery(oracleSession *session, const struct oraTable *oraTable, struct paramDesc *paramList, unsigned int prefetch);
extern unsigned int oracleFetchNext(oracleSession *session, unsigned int prefetch);
extern void oracleExecuteCall(oracleSession *session, char * const stmt);
extern void oracleGetLob(oracleSession *session, void *locptr, oraType type, char **value, long *value_len);
extern void oracleClientVersion(int *major, int *minor, int *update, int *patch, int *port_patch);
extern void oracleServerVersion(oracleSession *session, int *major, int *minor, int *update, int *patch, int *port_patch);
extern void *oracleGetGeometryType(oracleSession *session);
extern int oracleGetImportColumn(oracleSession *session, char *dblink, char *schema, char *limit_to, char **tabname, char **colname, oraType *type, int *charlen, int *typeprec, int *typescale, int *nullable, int *key, int skip_tables, int skip_views, int skip_matviews);
