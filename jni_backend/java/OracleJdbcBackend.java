import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class OracleJdbcBackend {
    private static final String SEP = "\u001f";

    static {
        try {
            Class.forName("oracle.jdbc.OracleDriver");
        } catch (ClassNotFoundException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    private final Connection connection;
    private PreparedStatement preparedStatement;
    private List<String[]> rows;
    private int nextRow;
    private String[] currentRow;
    private List<String> importRows;
    private int nextImportRow;

    public OracleJdbcBackend(String connectString, String user, String password) throws SQLException {
        String jdbcUrl = connectString.startsWith("jdbc:")
                ? connectString
                : "jdbc:oracle:thin:@" + connectString;

        this.connection = DriverManager.getConnection(jdbcUrl, user, password);
        this.connection.setAutoCommit(false);
        this.rows = new ArrayList<>();
        this.importRows = null;
    }

    public static int[] clientVersion() throws SQLException {
        Driver driver = DriverManager.getDriver("jdbc:oracle:thin:@");
        return new int[] {driver.getMajorVersion(), driver.getMinorVersion(), 0, 0, 0};
    }

    public int[] serverVersion() throws SQLException {
        for (String sql : new String[] {
                "SELECT version_full FROM product_component_version WHERE product LIKE 'Oracle Database%'",
                "SELECT version FROM product_component_version WHERE product LIKE 'Oracle Database%'",
                "SELECT banner FROM v$version WHERE banner LIKE 'Oracle Database%'"
        }) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                if (rs.next()) {
                    int[] parsed = parseVersion(rs.getString(1));
                    if (parsed[0] != 0) {
                        return parsed;
                    }
                }
            } catch (SQLException ignored) {
            }
        }

        DatabaseMetaData metaData = connection.getMetaData();
        return new int[] {metaData.getDatabaseMajorVersion(), metaData.getDatabaseMinorVersion(), 0, 0, 0};
    }

    public void close() throws SQLException {
        closeStatement();
        connection.close();
    }

    public void closeStatement() throws SQLException {
        currentRow = null;
        rows = new ArrayList<>();
        nextRow = 0;
        importRows = null;
        nextImportRow = 0;
        if (preparedStatement != null) {
            preparedStatement.close();
            preparedStatement = null;
        }
    }

    public boolean isStatementOpen() {
        return preparedStatement != null;
    }

    public String[] describe(String schema, String table) throws SQLException {
        String ownerClause = (schema == null || schema.isEmpty())
                ? "SYS_CONTEXT('USERENV','CURRENT_SCHEMA')"
                : sqlLiteral(schema);
        String sql = "SELECT column_name, data_type, data_length, data_precision, data_scale, nullable, data_type_owner "
                + "FROM all_tab_cols "
                + "WHERE owner = " + ownerClause + " AND table_name = " + sqlLiteral(table)
                + " AND hidden_column = 'NO' "
                + "ORDER BY column_id";

        List<String> result = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(join(
                        rs.getString(1),
                        rs.getString(2),
                        intString(rs, 3),
                        intString(rs, 4),
                        intString(rs, 5),
                        rs.getString(6),
                        rs.getString(7)));
            }
        }
        return result.toArray(new String[0]);
    }

    public void prepareQuery(String sql) throws SQLException {
        closeStatement();
        preparedStatement = connection.prepareStatement(sql);
    }

    public int executeQuery() throws SQLException {
        if (preparedStatement == null) {
            throw new SQLException("statement is not prepared");
        }

        rows = new ArrayList<>();
        nextRow = 0;
        currentRow = null;

        boolean hasResultSet = preparedStatement.execute();
        if (hasResultSet) {
            try (ResultSet rs = preparedStatement.getResultSet()) {
                int columnCount = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    String[] row = new String[columnCount];
                    for (int i = 0; i < columnCount; i++) {
                        row[i] = rs.getString(i + 1);
                    }
                    rows.add(row);
                }
            }
            return rows.size();
        }

        return preparedStatement.getUpdateCount();
    }

    public boolean fetchNext() {
        if (nextRow >= rows.size()) {
            currentRow = null;
            return false;
        }
        currentRow = rows.get(nextRow++);
        return true;
    }

    public String getValue(int columnIndex) {
        if (currentRow == null || columnIndex < 0 || columnIndex >= currentRow.length) {
            return null;
        }
        return currentRow[columnIndex];
    }

    public void executeCall(String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    public void endTransaction(boolean commit) throws SQLException {
        if (commit) {
            connection.commit();
        } else {
            connection.rollback();
        }
    }

    public void rollbackToSavepoint(int nestLevel) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ROLLBACK TO SAVEPOINT s" + nestLevel);
        }
    }

    public String[] explain(String query) throws SQLException {
        String statementId = "ORACLE_FDW_JNI_" + ProcessHandle.current().pid();
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("EXPLAIN PLAN SET STATEMENT_ID = " + sqlLiteral(statementId) + " FOR " + query);
        }

        String sql = "SELECT LPAD(' ', 2 * LEVEL - 2) || operation || "
                + "CASE WHEN options IS NULL THEN '' ELSE ' ' || options END || "
                + "CASE WHEN object_name IS NULL THEN '' ELSE ' ' || object_name END AS plan_line "
                + "FROM plan_table "
                + "WHERE statement_id = " + sqlLiteral(statementId) + " "
                + "START WITH id = 0 "
                + "CONNECT BY PRIOR id = parent_id AND statement_id = " + sqlLiteral(statementId) + " "
                + "ORDER SIBLINGS BY position";

        List<String> result = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                result.add(rs.getString(1));
            }
        }

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("DELETE FROM plan_table WHERE statement_id = " + sqlLiteral(statementId));
        }

        return result.toArray(new String[0]);
    }

    public void loadImportColumns(String schema, String limitTo, int skipTables, int skipViews, int skipMatviews)
            throws SQLException {
        String ownerClause = (schema == null || schema.isEmpty())
                ? "SYS_CONTEXT('USERENV','CURRENT_SCHEMA')"
                : sqlLiteral(schema);
        List<String> objectTypes = new ArrayList<>();
        if (skipTables == 0) {
            objectTypes.add("'TABLE'");
        }
        if (skipViews == 0) {
            objectTypes.add("'VIEW'");
        }
        if (skipMatviews == 0) {
            objectTypes.add("'MATERIALIZED VIEW'");
        }
        if (objectTypes.isEmpty()) {
            objectTypes.add("'TABLE'");
        }
        String tableFilter = limitTo == null || limitTo.trim().isEmpty()
                ? ""
                : "AND c.table_name IN (" + limitTo + ") ";

        String sql = "SELECT c.table_name, c.column_name, c.data_type, c.data_type_owner, "
                + "NVL(c.char_col_decl_length, 0), NVL(c.data_precision, 0), NVL(c.data_scale, 0), "
                + "c.nullable, CASE WHEN pk.column_name IS NULL THEN 0 ELSE 1 END "
                + "FROM all_tab_cols c "
                + "JOIN all_objects o ON o.owner = c.owner AND o.object_name = c.table_name "
                + "LEFT JOIN ("
                + "  SELECT acc.owner, acc.table_name, acc.column_name "
                + "  FROM all_constraints ac "
                + "  JOIN all_cons_columns acc ON acc.owner = ac.owner "
                + "    AND acc.constraint_name = ac.constraint_name "
                + "    AND acc.table_name = ac.table_name "
                + "  WHERE ac.constraint_type = 'P'"
                + ") pk ON pk.owner = c.owner AND pk.table_name = c.table_name AND pk.column_name = c.column_name "
                + "WHERE c.owner = " + ownerClause + " "
                + "AND c.hidden_column = 'NO' "
                + "AND o.object_type IN (" + String.join(", ", objectTypes) + ") "
                + tableFilter
                + "ORDER BY c.table_name, c.column_id";

        importRows = new ArrayList<>();
        nextImportRow = 0;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                importRows.add(join(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        intString(rs, 5),
                        intString(rs, 6),
                        intString(rs, 7),
                        rs.getString(8),
                        intString(rs, 9)));
            }
        }
    }

    public String getImportColumnNext() {
        if (importRows == null || nextImportRow >= importRows.size()) {
            importRows = null;
            nextImportRow = 0;
            return null;
        }
        return importRows.get(nextImportRow++);
    }

    private static String intString(ResultSet rs, int index) throws SQLException {
        int value = rs.getInt(index);
        return rs.wasNull() ? "" : Integer.toString(value);
    }

    private static String join(String... fields) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                builder.append(SEP);
            }
            if (fields[i] != null) {
                builder.append(fields[i]);
            }
        }
        return builder.toString();
    }

    private static String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static int[] parseVersion(String value) {
        int[] parsed = new int[] {0, 0, 0, 0, 0};
        if (value == null) {
            return parsed;
        }

        String[] parts = value.split("[^0-9]+");
        int out = 0;
        for (String part : parts) {
            if (!part.isEmpty()) {
                parsed[out++] = Integer.parseInt(part);
                if (out >= parsed.length) {
                    break;
                }
            }
        }
        return parsed;
    }
}
