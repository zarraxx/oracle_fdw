import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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
    private List<String> bindOrder;
    private List<String[]> rows;
    private int nextRow;
    private String[] currentRow;
    private List<String> importRows;
    private int nextImportRow;

    public OracleJdbcBackend(String connectString, String user, String password) throws SQLException {
        String target = (connectString == null || connectString.isBlank())
                ? defaultConnectString()
                : connectString;
        String jdbcUrl = target.startsWith("jdbc:")
                ? target
                : "jdbc:oracle:thin:@" + target;

        this.connection = DriverManager.getConnection(jdbcUrl, user, password);
        this.connection.setAutoCommit(false);
        this.bindOrder = new ArrayList<>();
        this.rows = new ArrayList<>();
        this.importRows = null;
    }

    public static int[] clientVersion() throws SQLException {
        Driver driver = DriverManager.getDriver("jdbc:oracle:thin:@");
        return new int[] {driver.getMajorVersion(), driver.getMinorVersion(), 0, 0, 0};
    }

    private static String defaultConnectString() {
        for (String name : new String[] {"LOCAL", "TWO_TASK", "ORACLE_FDW_CONNECT_STRING"}) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
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
        bindOrder = new ArrayList<>();
    }

    public boolean isStatementOpen() {
        return preparedStatement != null;
    }

    public String[] describe(String schema, String table) throws SQLException {
        String ownerClause = (schema == null || schema.isEmpty())
                ? "SYS_CONTEXT('USERENV','CURRENT_SCHEMA')"
                : sqlLiteral(schema);
        String sql = "SELECT column_name, data_type, "
                + "CASE WHEN data_type IN ('CHAR', 'NCHAR', 'VARCHAR2', 'NVARCHAR2') "
                + "     THEN NVL(char_col_decl_length, data_length) ELSE data_length END, "
                + "data_precision, data_scale, nullable, data_type_owner "
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
        ParsedSql parsedSql = parseBindParameters(sql);

        closeStatement();
        bindOrder = parsedSql.bindOrder;
        preparedStatement = connection.prepareStatement(parsedSql.sql);
    }

    public int executeQuery() throws SQLException {
        return executeQuery(null, null, null);
    }

    public int executeQuery(String[] names, int[] types, Object[] values) throws SQLException {
        if (preparedStatement == null) {
            throw new SQLException("statement is not prepared");
        }

        bindParameters(names, types, values);

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

    private void bindParameters(String[] names, int[] types, Object[] values) throws SQLException {
        if (names == null || names.length == 0) {
            return;
        }
        if (types == null || values == null || types.length != names.length || values.length != names.length) {
            throw new SQLException("invalid JDBC bind parameter arrays");
        }

        if (!bindOrder.isEmpty()) {
            for (int i = 0; i < bindOrder.size(); i++) {
                int sourceIndex = findBindParameter(names, bindOrder.get(i));
                if (sourceIndex < 0) {
                    throw new SQLException("missing JDBC bind parameter " + bindOrder.get(i));
                }
                bindAtIndex(i + 1, types[sourceIndex], values[sourceIndex]);
            }
            return;
        }

        for (int i = 0; i < names.length; i++) {
            int index = bindIndexOrFallback(names[i], i + 1);
            bindAtNameOrIndex(names[i], index, types[i], values[i]);
        }
    }

    private void bindAtNameOrIndex(String name, int index, int type, Object value) throws SQLException {
        if (type == 5) {
            throw new SQLException("RETURNING output bind is not supported by JNI backend yet");
        }
        if (value == null) {
            setNullAtNameOrIndex(name, index, jdbcType(type));
            return;
        }

        switch (type) {
            case 1:
                bindAtNameOrIndex("setBigDecimalAtName", new Class<?>[] {String.class, BigDecimal.class},
                        name, index, new BigDecimal(value.toString()));
                break;
            case 2:
                bindAtNameOrIndex("setStringAtName", new Class<?>[] {String.class, String.class},
                        name, index, longValueString((byte[]) value));
                break;
            case 3:
                bindAtNameOrIndex("setBytesAtName", new Class<?>[] {String.class, byte[].class},
                        name, index, longValueBytes((byte[]) value));
                break;
            default:
                bindAtNameOrIndex("setStringAtName", new Class<?>[] {String.class, String.class},
                        name, index, value.toString());
                break;
        }
    }

    private void bindAtIndex(int index, int type, Object value) throws SQLException {
        if (type == 5) {
            throw new SQLException("RETURNING output bind is not supported by JNI backend yet");
        }
        if (value == null) {
            preparedStatement.setNull(index, jdbcType(type));
            return;
        }

        switch (type) {
            case 1:
                preparedStatement.setBigDecimal(index, new BigDecimal(value.toString()));
                break;
            case 2:
                preparedStatement.setString(index, longValueString((byte[]) value));
                break;
            case 3:
                preparedStatement.setBytes(index, longValueBytes((byte[]) value));
                break;
            default:
                preparedStatement.setString(index, value.toString());
                break;
        }
    }

    private static int findBindParameter(String[] names, String wanted) {
        for (int i = 0; i < names.length; i++) {
            if (wanted.equals(names[i])) {
                return i;
            }
        }
        return -1;
    }

    private static int bindIndexOrFallback(String name, int fallback) {
        if (name == null || name.length() < 3 || name.charAt(0) != ':' || name.charAt(1) != 'p') {
            return fallback;
        }
        try {
            return Integer.parseInt(name.substring(2));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int jdbcType(int bindType) {
        switch (bindType) {
            case 1:
                return Types.NUMERIC;
            case 2:
                return Types.LONGVARCHAR;
            case 3:
                return Types.LONGVARBINARY;
            default:
                return Types.VARCHAR;
        }
    }

    private void bindAtNameOrIndex(String methodName, Class<?>[] parameterTypes, String name, int index, Object value)
            throws SQLException {
        if (bindAtName(methodName, parameterTypes, name, value)) {
            return;
        }

        if (value instanceof BigDecimal) {
            preparedStatement.setBigDecimal(index, (BigDecimal) value);
        } else if (value instanceof byte[]) {
            preparedStatement.setBytes(index, (byte[]) value);
        } else {
            preparedStatement.setString(index, value.toString());
        }
    }

    private void setNullAtNameOrIndex(String name, int index, int sqlType) throws SQLException {
        if (bindAtName("setNullAtName", new Class<?>[] {String.class, int.class}, name, sqlType)) {
            return;
        }

        preparedStatement.setNull(index, sqlType);
    }

    private boolean bindAtName(String methodName, Class<?>[] parameterTypes, String name, Object value) {
        try {
            Method method = preparedStatement.getClass().getMethod(methodName, parameterTypes);
            method.invoke(preparedStatement, bindName(name), value);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        } catch (IllegalAccessException | InvocationTargetException ignored) {
        }

        if (name == null || !name.startsWith(":")) {
            return false;
        }

        try {
            Method method = preparedStatement.getClass().getMethod(methodName, parameterTypes);
            method.invoke(preparedStatement, name, value);
            return true;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException ignored) {
            return false;
        }
    }

    private static String bindName(String name) {
        return name != null && name.startsWith(":") ? name.substring(1) : name;
    }

    private static byte[] longValueBytes(byte[] raw) {
        if (raw.length < 4) {
            return raw;
        }

        int len = (raw[0] & 0xff)
                | ((raw[1] & 0xff) << 8)
                | ((raw[2] & 0xff) << 16)
                | ((raw[3] & 0xff) << 24);
        if (len < 0 || len > raw.length - 4) {
            return raw;
        }

        byte[] data = new byte[len];
        System.arraycopy(raw, 4, data, 0, len);
        return data;
    }

    private static String longValueString(byte[] raw) {
        return new String(longValueBytes(raw), StandardCharsets.UTF_8);
    }

    private static ParsedSql parseBindParameters(String sql) {
        StringBuilder rewritten = new StringBuilder(sql.length());
        List<String> order = new ArrayList<>();
        boolean inString = false;

        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);
            if (ch == '\'') {
                rewritten.append(ch);
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    rewritten.append(sql.charAt(++i));
                } else {
                    inString = !inString;
                }
                continue;
            }

            if (!inString && ch == ':') {
                int end = bindNameEnd(sql, i);
                if (end > i) {
                    order.add(sql.substring(i, end));
                    rewritten.append('?');
                    i = end - 1;
                    continue;
                }
            }

            rewritten.append(ch);
        }

        return new ParsedSql(rewritten.toString(), order);
    }

    private static int bindNameEnd(String sql, int start) {
        if (sql.startsWith(":now", start)) {
            int end = start + 4;
            if (end == sql.length() || !isIdentifierPart(sql.charAt(end))) {
                return end;
            }
            return start;
        }

        int pos = start + 1;
        if (pos >= sql.length() || !isOracleFdwBindPrefix(sql.charAt(pos))) {
            return start;
        }
        pos++;
        int digitsStart = pos;
        while (pos < sql.length() && Character.isDigit(sql.charAt(pos))) {
            pos++;
        }

        return pos > digitsStart ? pos : start;
    }

    private static boolean isOracleFdwBindPrefix(char ch) {
        return ch == 'p' || ch == 'k' || ch == 'r';
    }

    private static boolean isIdentifierPart(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_' || ch == '$' || ch == '#';
    }

    private static final class ParsedSql {
        final String sql;
        final List<String> bindOrder;

        ParsedSql(String sql, List<String> bindOrder) {
            this.sql = sql;
            this.bindOrder = bindOrder;
        }
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

    public String executeCallWithError(String sql) {
        try {
            executeCall(sql);
            return null;
        } catch (SQLException ex) {
            return ex.toString();
        }
    }

    public void endTransaction(boolean commit) throws SQLException {
        if (commit) {
            connection.commit();
        } else {
            connection.rollback();
        }
    }

    public void setSavepoint(int nestLevel) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SAVEPOINT s" + nestLevel);
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
