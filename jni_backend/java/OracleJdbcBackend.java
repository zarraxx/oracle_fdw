import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class OracleJdbcBackend {
    private static final String SEP = "\u001f";
    private static final int INPUT_BIND_BASE = 2000;
    private static final int INPUT_BIND_SCALE = 100;
    private static final String DATE_PARSE_FORMAT = "'YYYY-MM-DD HH24:MI:SS AD', 'NLS_DATE_LANGUAGE=AMERICAN'";
    private static final String TIMESTAMP_PARSE_FORMAT = "'YYYY-MM-DD HH24:MI:SS.FF9 AD', 'NLS_DATE_LANGUAGE=AMERICAN'";
    private static final String TIMESTAMP_TZ_PARSE_FORMAT = "'YYYY-MM-DD HH24:MI:SS.FF9TZH:TZM AD', 'NLS_DATE_LANGUAGE=AMERICAN'";
    private static final String DATE_OUTPUT_FORMAT = "'YYYY-MM-DD HH24:MI:SS BC', 'NLS_DATE_LANGUAGE=AMERICAN'";
    private static final String TIMESTAMP_OUTPUT_FORMAT = "'YYYY-MM-DD HH24:MI:SS.FF9 BC', 'NLS_DATE_LANGUAGE=AMERICAN'";
    private static final String TIMESTAMP_TZ_OUTPUT_FORMAT = "'YYYY-MM-DD HH24:MI:SS.FF9TZH:TZM BC', 'NLS_DATE_LANGUAGE=AMERICAN'";
    private static final Pattern BIND_CAST_TIMESTAMPTZ = Pattern.compile(
            "(?i)CAST\\s*\\(\\s*((?::[A-Za-z][A-Za-z0-9_]*)|\\?)\\s+AS\\s+TIMESTAMP\\s+WITH\\s+TIME\\s+ZONE\\s*\\)");
    private static final Pattern BIND_CAST_TIMESTAMP = Pattern.compile(
            "(?i)CAST\\s*\\(\\s*((?::[A-Za-z][A-Za-z0-9_]*)|\\?)\\s+AS\\s+TIMESTAMP\\s*\\)");
    private static final Pattern BIND_CAST_DATE = Pattern.compile(
            "(?i)CAST\\s*\\(\\s*((?::[A-Za-z][A-Za-z0-9_]*)|\\?)\\s+AS\\s+DATE\\s*\\)");
    private static final Pattern LITERAL_CAST_TIMESTAMPTZ = Pattern.compile(
            "(?i)CAST\\s*\\(\\s*('(?:''|[^'])*')\\s+AS\\s+TIMESTAMP\\s+WITH\\s+TIME\\s+ZONE\\s*\\)");
    private static final Pattern LITERAL_CAST_TIMESTAMP = Pattern.compile(
            "(?i)CAST\\s*\\(\\s*('(?:''|[^'])*')\\s+AS\\s+TIMESTAMP\\s*\\)");
    private static final Pattern LITERAL_CAST_DATE = Pattern.compile(
            "(?i)CAST\\s*\\(\\s*('(?:''|[^'])*')\\s+AS\\s+DATE\\s*\\)");

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
    private List<Integer> bindOracleTypes;
    private List<String[]> rows;
    private int nextRow;
    private String[] currentRow;
    private List<String> importRows;
    private int nextImportRow;
    private List<String> outputNames;
    private List<Integer> outputTypes;
    private Map<String, String> outputValues;
    private String currentSql;
    private final Map<Integer, QueryState> statementHandles = new HashMap<>();
    private int nextStatementHandle = 1;

    private static final class QueryState {
        PreparedStatement preparedStatement;
        List<String> bindOrder = new ArrayList<>();
        List<Integer> bindOracleTypes = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        int nextRow;
        String[] currentRow;
        List<String> outputNames = new ArrayList<>();
        List<Integer> outputTypes = new ArrayList<>();
        Map<String, String> outputValues = new HashMap<>();
        String currentSql;
    }

    public OracleJdbcBackend(String connectString, String user, String password) throws SQLException {
        String target = (connectString == null || connectString.isBlank())
                ? defaultConnectString()
                : connectString;
        String jdbcUrl = target.startsWith("jdbc:")
                ? target
                : "jdbc:oracle:thin:@" + target;

        this.connection = DriverManager.getConnection(jdbcUrl, user, password);
        this.connection.setAutoCommit(false);
        configureSession();
        this.bindOrder = new ArrayList<>();
        this.bindOracleTypes = new ArrayList<>();
        this.rows = new ArrayList<>();
        this.importRows = null;
        this.outputNames = new ArrayList<>();
        this.outputTypes = new ArrayList<>();
        this.outputValues = new HashMap<>();
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

    private void configureSession() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER SESSION SET NLS_DATE_LANGUAGE = 'AMERICAN'");
            stmt.execute("ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY-MM-DD HH24:MI:SS BC'");
            stmt.execute("ALTER SESSION SET NLS_TIMESTAMP_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF9 BC'");
            stmt.execute("ALTER SESSION SET NLS_TIMESTAMP_TZ_FORMAT = 'YYYY-MM-DD HH24:MI:SS.FF9TZH:TZM BC'");
            stmt.execute("ALTER SESSION SET NLS_NUMERIC_CHARACTERS = '.,'");
            stmt.execute("ALTER SESSION SET NLS_CALENDAR = 'GREGORIAN'");
        }
    }

    public void setSessionTimeZone(String timezone) throws SQLException {
        if (timezone == null || timezone.isBlank()) {
            return;
        }

        String value = timezone;
        if (value.regionMatches(true, 0, "ORA_SDTZ=", 0, 9)) {
            value = value.substring(9);
        }
        if (value.isBlank()) {
            return;
        }

        try (Statement stmt = connection.createStatement()) {
            stmt.execute("ALTER SESSION SET TIME_ZONE = " + sqlLiteral(value));
        }
    }

    public void beginTransaction(int isolationLevel) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            if (isolationLevel == 1) {
                stmt.execute("SET TRANSACTION READ ONLY");
            } else if (isolationLevel == 2) {
                stmt.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE");
            }
        }
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
        SQLException failure = null;

        try {
            closeStatement();
        } catch (SQLException ex) {
            failure = ex;
        }

        try {
            connection.close();
        } catch (SQLException ex) {
            if (failure != null) {
                failure.addSuppressed(ex);
            } else {
                failure = ex;
            }
        }

        if (failure != null) {
            throw failure;
        }
    }

    public void closeStatement() throws SQLException {
        SQLException failure = null;

        currentRow = null;
        rows = new ArrayList<>();
        nextRow = 0;
        importRows = null;
        nextImportRow = 0;
        if (preparedStatement != null) {
            try {
                preparedStatement.close();
            } catch (SQLException ex) {
                failure = ex;
            } finally {
                preparedStatement = null;
            }
        }
        for (QueryState state : statementHandles.values()) {
            if (state.preparedStatement != null) {
                try {
                    state.preparedStatement.close();
                } catch (SQLException ex) {
                    if (failure != null) {
                        failure.addSuppressed(ex);
                    } else {
                        failure = ex;
                    }
                } finally {
                    state.preparedStatement = null;
                }
            }
        }
        statementHandles.clear();
        bindOrder = new ArrayList<>();
        bindOracleTypes = new ArrayList<>();
        outputNames = new ArrayList<>();
        outputTypes = new ArrayList<>();
        outputValues = new HashMap<>();

        if (failure != null) {
            throw failure;
        }
    }

    public boolean isStatementOpen() {
        return preparedStatement != null;
    }

    public int prepareQueryHandle(String sql) throws SQLException {
        ParsedSql parsedSql = parseBindParameters(rewriteDateTimeLiteralCasts(sql));
        String preparedSql = rewriteDateTimeSelectColumns(parsedSql.sql);
        QueryState state = new QueryState();
        int handle;

        state.bindOrder = parsedSql.bindOrder;
        state.bindOracleTypes = inferBindOracleTypes(preparedSql, parsedSql.bindOrder.size());
        state.currentSql = preparedSql;
        state.preparedStatement = connection.prepareStatement(preparedSql);

        handle = nextStatementHandle++;
        if (nextStatementHandle <= 0) {
            nextStatementHandle = 1;
        }
        statementHandles.put(handle, state);
        trace("prepare[" + handle + "]: " + preparedSql);
        return handle;
    }

    public void closeStatementHandle(int handle) throws SQLException {
        QueryState state = statementHandles.remove(handle);

        if (state != null && state.preparedStatement != null) {
            state.preparedStatement.close();
            state.preparedStatement = null;
        }
    }

    public boolean isStatementOpenHandle(int handle) {
        QueryState state = statementHandles.get(handle);
        return state != null && state.preparedStatement != null;
    }

    public String[] describe(String schema, String table) throws SQLException {
        if (isParenthesizedTableOption(table)) {
            return describeQuery(table);
        }

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

    private String[] describeQuery(String tableExpression) throws SQLException {
        String sql = "SELECT * FROM " + tableExpression;
        List<String> result = new ArrayList<>();

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            ResultSetMetaData meta = stmt.getMetaData();
            if (meta == null) {
                try (ResultSet rs = stmt.executeQuery()) {
                    meta = rs.getMetaData();
                }
            }

            for (int i = 1; i <= meta.getColumnCount(); i++) {
                result.add(join(
                        meta.getColumnName(i),
                        meta.getColumnTypeName(i),
                        Integer.toString(Math.max(0, meta.getColumnDisplaySize(i))),
                        Integer.toString(Math.max(0, meta.getPrecision(i))),
                        Integer.toString(Math.max(0, meta.getScale(i))),
                        meta.isNullable(i) == ResultSetMetaData.columnNoNulls ? "N" : "Y",
                        ""));
            }
        }

        return result.toArray(new String[0]);
    }

    private static boolean isParenthesizedTableOption(String table) {
        if (table == null) {
            return false;
        }

        String trimmed = table.trim();
        return trimmed.length() >= 2 && trimmed.charAt(0) == '(' && trimmed.charAt(trimmed.length() - 1) == ')';
    }

    public void prepareQuery(String sql) throws SQLException {
        ParsedSql parsedSql = parseBindParameters(rewriteDateTimeLiteralCasts(sql));
        String preparedSql = rewriteDateTimeSelectColumns(parsedSql.sql);

        closeStatement();
        bindOrder = parsedSql.bindOrder;
        bindOracleTypes = inferBindOracleTypes(preparedSql, parsedSql.bindOrder.size());
        currentSql = preparedSql;
        trace("prepare: " + preparedSql);
        preparedStatement = connection.prepareStatement(preparedSql);
    }

    public int executeQuery() throws SQLException {
        return executeQuery(null, null, null);
    }

    public int executeQueryHandle(int handle) throws SQLException {
        return executeQueryHandle(handle, null, null, null);
    }

    public int executeQueryHandle(int handle, String[] names, int[] types, Object[] values) throws SQLException {
        QueryState state = getQueryState(handle);
        int result;

        useQueryState(state);
        result = executeQuery(names, types, values);
        saveQueryState(state);
        return result;
    }

    public int executeQuery(String[] names, int[] types, Object[] values) throws SQLException {
        if (preparedStatement == null) {
            throw new SQLException("statement is not prepared");
        }

        boolean hasReturnParameters = hasReturnParameters(types);

        outputNames = new ArrayList<>();
        outputTypes = new ArrayList<>();
        outputValues = new HashMap<>();
        bindParameters(names, types, values);

        rows = new ArrayList<>();
        nextRow = 0;
        currentRow = null;

        if (hasReturnParameters) {
            int updateCount = preparedStatement.executeUpdate();

            try (ResultSet rs = getReturnResultSet()) {
                if (rs != null && rs.next()) {
                    ResultSetMetaData metaData = rs.getMetaData();
                    int columnCount = Math.min(metaData.getColumnCount(), outputNames.size());
                    int[] jdbcTypes = new int[columnCount];
                    String[] typeNames = new String[columnCount];

                    for (int i = 0; i < columnCount; i++) {
                        jdbcTypes[i] = metaData.getColumnType(i + 1);
                        typeNames[i] = metaData.getColumnTypeName(i + 1);
                    }

                    for (int i = 0; i < columnCount; i++) {
                        if (jdbcTypes[i] == Types.LONGVARBINARY) {
                            outputValues.put(outputNames.get(i), getColumnValue(rs, i + 1, jdbcTypes[i], typeNames[i]));
                        }
                    }
                    for (int i = 0; i < columnCount; i++) {
                        if (isBinaryType(jdbcTypes[i], typeNames[i]) && jdbcTypes[i] != Types.LONGVARBINARY) {
                            outputValues.put(outputNames.get(i), getColumnValue(rs, i + 1, jdbcTypes[i], typeNames[i]));
                        }
                    }
                    for (int i = 0; i < columnCount; i++) {
                        if (!isBinaryType(jdbcTypes[i], typeNames[i])) {
                            String value = getColumnValue(rs, i + 1, jdbcTypes[i], typeNames[i]);
                            if (i < outputTypes.size() && isReturningDate(outputTypes.get(i))) {
                                value = normalizeReturningDate(value);
                            }
                            trace("return " + outputNames.get(i) + " jdbcType=" + jdbcTypes[i]
                                    + " typeName=" + typeNames[i] + " value=" + value);
                            outputValues.put(outputNames.get(i), value);
                        }
                    }
                }
            }

            return updateCount;
        }

        boolean hasResultSet = preparedStatement.execute();
        if (hasResultSet) {
            try (ResultSet rs = preparedStatement.getResultSet()) {
                ResultSetMetaData metaData = rs.getMetaData();
                int columnCount = metaData.getColumnCount();
                int[] jdbcTypes = new int[columnCount];
                String[] typeNames = new String[columnCount];

                for (int i = 0; i < columnCount; i++) {
                    jdbcTypes[i] = metaData.getColumnType(i + 1);
                    typeNames[i] = metaData.getColumnTypeName(i + 1);
                }

                while (rs.next()) {
                    String[] row = new String[columnCount];
                    for (int i = 0; i < columnCount; i++) {
                        if (jdbcTypes[i] == Types.LONGVARBINARY) {
                            row[i] = getColumnValue(rs, i + 1, jdbcTypes[i], typeNames[i]);
                        }
                    }
                    for (int i = 0; i < columnCount; i++) {
                        if (isBinaryType(jdbcTypes[i], typeNames[i]) && jdbcTypes[i] != Types.LONGVARBINARY) {
                            row[i] = getColumnValue(rs, i + 1, jdbcTypes[i], typeNames[i]);
                        }
                    }
                    for (int i = 0; i < columnCount; i++) {
                        if (!isBinaryType(jdbcTypes[i], typeNames[i])) {
                            row[i] = getColumnValue(rs, i + 1, jdbcTypes[i], typeNames[i]);
                        }
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
                bindAtIndex(i + 1, bindOrder.get(i), effectiveBindType(i, types[sourceIndex]), values[sourceIndex]);
            }
            return;
        }

        for (int i = 0; i < names.length; i++) {
            int index = bindIndexOrFallback(names[i], i + 1);
            bindAtNameOrIndex(names[i], index, effectiveBindType(index - 1, types[i]), values[i]);
        }
    }

    private int effectiveBindType(int bindIndex, int type) {
        if (oracleType(type) >= 0 || bindIndex < 0 || bindIndex >= bindOracleTypes.size()) {
            return type;
        }

        int inferredOracleType = bindOracleTypes.get(bindIndex);
        if (inferredOracleType < 0) {
            return type;
        }

        return INPUT_BIND_BASE + baseBindType(type) * INPUT_BIND_SCALE + inferredOracleType;
    }

    private void bindAtNameOrIndex(String name, int index, int type, Object value) throws SQLException {
        if (isReturnParameter(type)) {
            registerReturnParameter(index, name, type);
            return;
        }
        if (value == null) {
            setNullAtNameOrIndex(name, index, jdbcType(type));
            return;
        }
        if (bindDateTimeAtIndex(index, type, value)) {
            return;
        }

        switch (baseBindType(type)) {
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

    private void bindAtIndex(int index, String name, int type, Object value) throws SQLException {
        trace("bind " + index + " " + name + " type=" + baseBindType(type)
                + " oraType=" + oracleType(type) + " value=" + traceValue(value));
        if (isReturnParameter(type)) {
            registerReturnParameter(index, name, type);
            return;
        }
        if (value == null) {
            preparedStatement.setNull(index, jdbcType(type));
            return;
        }
        if (bindDateTimeAtIndex(index, type, value)) {
            return;
        }

        switch (baseBindType(type)) {
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

    private boolean bindDateTimeAtIndex(int index, int type, Object value) throws SQLException {
        String text;

        if (!(value instanceof String)) {
            return false;
        }
        text = value.toString();

        switch (oracleType(type)) {
            case 9:
                if (isBcDateTime(text)) {
                    preparedStatement.setObject(index, oracleDateDatum(text));
                    return true;
                }
                LocalDateTime dateValue = parseAdDateTime(text);
                if (dateValue == null) {
                    return false;
                }
                preparedStatement.setTimestamp(index, Timestamp.valueOf(dateValue));
                return true;
            case 10:
                LocalDateTime timestampValue = parseAdDateTime(text);
                if (timestampValue == null) {
                    return false;
                }
                preparedStatement.setTimestamp(index, Timestamp.valueOf(timestampValue));
                return true;
            case 11:
                if (isBcDateTime(text)) {
                    preparedStatement.setObject(index, oracleTimestampTzDatum(text));
                    return true;
                }
                OffsetDateTime timestampTzValue = parseAdOffsetDateTime(text);
                if (timestampTzValue == null) {
                    return false;
                }
                preparedStatement.setObject(index, timestampTzValue);
                return true;
            case 12:
                OffsetDateTime timestampLtzValue = parseAdOffsetDateTime(text);
                if (timestampLtzValue != null) {
                    preparedStatement.setObject(index, timestampLtzValue);
                    return true;
                }
                LocalDateTime localTimestampLtzValue = parseAdDateTime(text);
                if (localTimestampLtzValue == null) {
                    return false;
                }
                preparedStatement.setTimestamp(index, Timestamp.valueOf(localTimestampLtzValue));
                return true;
            default:
                return false;
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
        switch (oracleType(bindType)) {
            case 9:
                return Types.TIMESTAMP;
            case 10:
                return Types.TIMESTAMP;
            case 11:
            case 12:
                return Types.TIMESTAMP_WITH_TIMEZONE;
            default:
                break;
        }

        switch (baseBindType(bindType)) {
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

    private static boolean hasReturnParameters(int[] types) {
        if (types == null) {
            return false;
        }
        for (int type : types) {
            if (isReturnParameter(type)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isReturnParameter(int type) {
        return type == 5 || (type >= 1000 && type < INPUT_BIND_BASE);
    }

    private static int baseBindType(int type) {
        if (type >= INPUT_BIND_BASE) {
            return (type - INPUT_BIND_BASE) / INPUT_BIND_SCALE;
        }
        return type;
    }

    private static int oracleType(int type) {
        if (type >= INPUT_BIND_BASE) {
            return (type - INPUT_BIND_BASE) % INPUT_BIND_SCALE;
        }
        if (type >= 1000 && type < INPUT_BIND_BASE) {
            return type - 1000;
        }
        return -1;
    }

    private static boolean isBcDateTime(String value) {
        return value != null && value.trim().toUpperCase().endsWith(" BC");
    }

    private static Object oracleDateDatum(String value) throws SQLException {
        return oracleDateDatum(value, "YYYY-MM-DD HH24:MI:SS BC");
    }

    private static Object oracleDateDatum(String value, String format) throws SQLException {
        try {
            Class<?> dateClass = Class.forName("oracle.sql.DATE");
            Method fromText = dateClass.getMethod("fromText", String.class, String.class, String.class);
            return fromText.invoke(null, normalizeBcOracleText(value), format, "NLS_DATE_LANGUAGE=AMERICAN");
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException ex) {
            throw new SQLException("cannot create Oracle DATE value for BC timestamp", ex);
        } catch (InvocationTargetException ex) {
            throw unwrapSqlException("cannot create Oracle DATE value for BC timestamp", ex);
        }
    }

    private static Object oracleTimestampTzDatum(String value) throws SQLException {
        ParsedOffsetDateTime parsed = parseBcOffsetDateTime(value);
        LocalDateTime utcDateTime;

        if (parsed == null) {
            throw new SQLException("cannot parse Oracle BC timestamp with time zone: " + value);
        }

        utcDateTime = parsed.localDateTime.minusSeconds(parsed.offset.getTotalSeconds());

        try {
            Class<?> dateClass = Class.forName("oracle.sql.DATE");
            Class<?> timestampTzClass = Class.forName("oracle.sql.TIMESTAMPTZ");
            Object utcDate = oracleDateDatum(formatOracleBcDateTime(utcDateTime),
                    "YYYY-MM-DD HH24:MI:SS BC");
            byte[] dateBytes = (byte[])dateClass.getMethod("toBytes").invoke(utcDate);
            byte[] timestampTzBytes = new byte[13];

            System.arraycopy(dateBytes, 0, timestampTzBytes, 0, 7);
            timestampTzBytes[7] = (byte)((parsed.localDateTime.getNano() >>> 24) & 0xff);
            timestampTzBytes[8] = (byte)((parsed.localDateTime.getNano() >>> 16) & 0xff);
            timestampTzBytes[9] = (byte)((parsed.localDateTime.getNano() >>> 8) & 0xff);
            timestampTzBytes[10] = (byte)(parsed.localDateTime.getNano() & 0xff);
            timestampTzBytes[11] = (byte)(parsed.offset.getTotalSeconds() / 3600 + 20);
            timestampTzBytes[12] = (byte)(Math.abs((parsed.offset.getTotalSeconds() % 3600) / 60) + 60);
            return timestampTzClass.getConstructor(byte[].class).newInstance((Object)timestampTzBytes);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InstantiationException ex) {
            throw new SQLException("cannot create Oracle TIMESTAMP WITH TIME ZONE value for BC timestamp", ex);
        } catch (InvocationTargetException ex) {
            throw unwrapSqlException("cannot create Oracle TIMESTAMP WITH TIME ZONE value for BC timestamp", ex);
        }
    }

    private static final class ParsedOffsetDateTime {
        final LocalDateTime localDateTime;
        final ZoneOffset offset;

        ParsedOffsetDateTime(LocalDateTime localDateTime, ZoneOffset offset) {
            this.localDateTime = localDateTime;
            this.offset = offset;
        }
    }

    private static String normalizeBcOracleText(String value) throws SQLException {
        LocalDateTime dateTime = parseBcLocalDateTime(value);

        if (dateTime == null) {
            throw new SQLException("cannot parse Oracle BC date: " + value);
        }
        return formatOracleBcDateTime(dateTime);
    }

    private static ParsedOffsetDateTime parseBcOffsetDateTime(String value) {
        String text = stripBcEra(value);
        String upper;
        String localText;
        ZoneOffset offset;
        int zonePos;

        if (text == null) {
            return null;
        }

        upper = text.toUpperCase();
        if (upper.endsWith(" UTC")) {
            localText = text.substring(0, text.length() - 4);
            offset = ZoneOffset.UTC;
        } else {
            zonePos = text.lastIndexOf('+');
            if (zonePos <= 10) {
                int lastDash = text.lastIndexOf('-');
                zonePos = lastDash > 10 ? lastDash : -1;
            }
            if (zonePos <= 10) {
                return null;
            }

            localText = text.substring(0, zonePos);
            try {
                offset = ZoneOffset.of(text.substring(zonePos));
            } catch (RuntimeException ex) {
                return null;
            }
        }

        LocalDateTime local = parseBcLocalDateTime(localText + " BC");
        return local == null ? null : new ParsedOffsetDateTime(local, offset);
    }

    private static LocalDateTime parseBcLocalDateTime(String value) {
        String text = stripBcEra(value);
        LocalDateTime parsed;

        if (text == null) {
            return null;
        }

        parsed = parseDateTimeFields(text, true);
        if (parsed == null || parsed.getYear() > 0) {
            return null;
        }
        return parsed;
    }

    private static LocalDateTime parseDateTimeFields(String text, boolean bc) {
        String[] parts = text.trim().split(" ", 2);
        String datePart;
        String timePart;

        if (parts.length == 0) {
            return null;
        }

        datePart = parts[0];
        timePart = parts.length == 2 ? parts[1] : "00:00:00";

        try {
            String[] date = datePart.split("-");
            String[] time = timePart.split(":", 3);
            if (date.length != 3) {
                return null;
            }

            int year = Integer.parseInt(date[0]);
            int month = Integer.parseInt(date[1]);
            int day = Integer.parseInt(date[2]);
            int hour;
            int minute;
            int second;
            int nano = 0;
            String secondPart;
            int dot;

            if (bc) {
                year = 1 - year;
            }

            if (time.length == 1) {
                hour = Integer.parseInt(time[0]);
                minute = 0;
                secondPart = "0";
            } else if (time.length == 2) {
                hour = Integer.parseInt(time[0]);
                minute = Integer.parseInt(time[1]);
                secondPart = "0";
            } else if (time.length == 3) {
                hour = Integer.parseInt(time[0]);
                minute = Integer.parseInt(time[1]);
                secondPart = time[2];
            } else {
                return null;
            }

            dot = secondPart.indexOf('.');
            if (dot >= 0) {
                second = Integer.parseInt(secondPart.substring(0, dot));
                nano = parseNanos(secondPart.substring(dot + 1));
            } else {
                second = Integer.parseInt(secondPart);
            }

            return LocalDateTime.of(year, month, day, hour, minute, second, nano);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String stripBcEra(String value) {
        String text = value == null ? "" : value.trim();

        return text.toUpperCase().endsWith(" BC") ? text.substring(0, text.length() - 3).trim() : null;
    }

    private static String formatOracleBcDateTime(LocalDateTime value) throws SQLException {
        if (value.getYear() > 0) {
            throw new SQLException("BC timestamp conversion crossed into AD year");
        }

        return String.format("%04d-%02d-%02d %02d:%02d:%02d BC",
                1 - value.getYear(), value.getMonthValue(), value.getDayOfMonth(),
                value.getHour(), value.getMinute(), value.getSecond());
    }

    private static LocalDateTime parseAdDateTime(String value) {
        String text = stripOptionalAdEra(value);
        String datePart;
        String timePart;
        String[] parts;

        if (text == null) {
            return null;
        }

        parts = text.split(" ", 2);
        datePart = parts[0];
        timePart = parts.length == 2 ? parts[1] : "00:00:00";

        try {
            String[] date = datePart.split("-");
            String[] time = timePart.split(":", 3);
            if (date.length != 3) {
                return null;
            }
            int year = Integer.parseInt(date[0]);
            int month = Integer.parseInt(date[1]);
            int day = Integer.parseInt(date[2]);
            int hour;
            int minute;
            int second;
            int nano = 0;
            String secondPart;
            int dot;

            if (time.length == 1) {
                hour = Integer.parseInt(time[0]);
                minute = 0;
                secondPart = "0";
            } else if (time.length == 2) {
                hour = Integer.parseInt(time[0]);
                minute = Integer.parseInt(time[1]);
                secondPart = "0";
            } else if (time.length == 3) {
                hour = Integer.parseInt(time[0]);
                minute = Integer.parseInt(time[1]);
                secondPart = time[2];
            } else {
                return null;
            }

            dot = secondPart.indexOf('.');

            if (dot >= 0) {
                second = Integer.parseInt(secondPart.substring(0, dot));
                nano = parseNanos(secondPart.substring(dot + 1));
            } else {
                second = Integer.parseInt(secondPart);
            }

            return LocalDateTime.of(year, month, day, hour, minute, second, nano);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static OffsetDateTime parseAdOffsetDateTime(String value) {
        String text = stripOptionalAdEra(value);
        String upper;
        int zonePos;

        if (text == null) {
            return null;
        }

        upper = text.toUpperCase();
        if (upper.endsWith(" UTC")) {
            LocalDateTime local = parseAdDateTime(text.substring(0, text.length() - 4));
            return local == null ? null : OffsetDateTime.of(local, ZoneOffset.UTC);
        }

        zonePos = text.lastIndexOf('+');
        if (zonePos <= 10) {
            int lastDash = text.lastIndexOf('-');
            zonePos = lastDash > 10 ? lastDash : -1;
        }
        if (zonePos <= 10) {
            return null;
        }

        LocalDateTime local = parseAdDateTime(text.substring(0, zonePos));
        if (local == null) {
            return null;
        }

        try {
            return OffsetDateTime.of(local, ZoneOffset.of(text.substring(zonePos)));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String stripOptionalAdEra(String value) {
        String text = value == null ? "" : value.trim();
        String upper = text.toUpperCase();

        if (upper.endsWith(" BC")) {
            return null;
        }
        if (upper.endsWith(" AD")) {
            return text.substring(0, text.length() - 3).trim();
        }
        return text;
    }

    private static int parseNanos(String fraction) {
        String digits = fraction.length() > 9 ? fraction.substring(0, 9) : fraction;

        while (digits.length() < 9) {
            digits += "0";
        }
        return Integer.parseInt(digits);
    }

    private void registerReturnParameter(int index, String name, int type) throws SQLException {
        try {
            Method method = preparedStatement.getClass().getMethod("registerReturnParameter", int.class, int.class);
            method.setAccessible(true);
            method.invoke(preparedStatement, index, returningSqlType(type));
            outputNames.add(bindName(name));
            outputTypes.add(type);
        } catch (NoSuchMethodException ex) {
            throw new SQLException("Oracle JDBC driver does not support RETURNING output binds", ex);
        } catch (IllegalAccessException ex) {
            throw new SQLException("cannot register RETURNING output bind", ex);
        } catch (InvocationTargetException ex) {
            throw unwrapSqlException("cannot register RETURNING output bind", ex);
        }
    }

    private ResultSet getReturnResultSet() throws SQLException {
        try {
            Method method = preparedStatement.getClass().getMethod("getReturnResultSet");
            method.setAccessible(true);
            return (ResultSet) method.invoke(preparedStatement);
        } catch (NoSuchMethodException ex) {
            throw new SQLException("Oracle JDBC driver does not expose RETURNING output result set", ex);
        } catch (IllegalAccessException ex) {
            throw new SQLException("cannot read RETURNING output result set", ex);
        } catch (InvocationTargetException ex) {
            throw unwrapSqlException("cannot read RETURNING output result set", ex);
        }
    }

    private static void trace(String message) {
        String enabled = System.getenv("ORACLE_FDW_JNI_TRACE");
        if (enabled != null && !enabled.isBlank() && !"0".equals(enabled)) {
            System.err.println("oracle_fdw_jni_java: " + message);
        }
    }

    private static String traceValue(Object value) {
        if (value == null) {
            return "<null>";
        }
        if (value instanceof byte[]) {
            return "<bytes:" + ((byte[]) value).length + ">";
        }
        return value.toString();
    }

    private QueryState getQueryState(int handle) throws SQLException {
        QueryState state = statementHandles.get(handle);
        if (state == null || state.preparedStatement == null) {
            throw new SQLException("JDBC statement handle is not open: " + handle);
        }
        return state;
    }

    private void useQueryState(QueryState state) {
        preparedStatement = state.preparedStatement;
        bindOrder = state.bindOrder;
        bindOracleTypes = state.bindOracleTypes;
        rows = state.rows;
        nextRow = state.nextRow;
        currentRow = state.currentRow;
        outputNames = state.outputNames;
        outputTypes = state.outputTypes;
        outputValues = state.outputValues;
        currentSql = state.currentSql;
    }

    private void saveQueryState(QueryState state) {
        state.preparedStatement = preparedStatement;
        state.bindOrder = bindOrder;
        state.bindOracleTypes = bindOracleTypes;
        state.rows = rows;
        state.nextRow = nextRow;
        state.currentRow = currentRow;
        state.outputNames = outputNames;
        state.outputTypes = outputTypes;
        state.outputValues = outputValues;
        state.currentSql = currentSql;
    }

    private static SQLException unwrapSqlException(String message, InvocationTargetException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof SQLException) {
            return (SQLException) cause;
        }
        return new SQLException(message, cause);
    }

    private static int returningSqlType(int type) {
        int oraType = oracleType(type);

        switch (oraType) {
            case 4:
            case 5:
            case 6:
            case 7:
                return Types.NUMERIC;
            case 8:
            case 20:
                return Types.VARBINARY;
            case 9:
                return Types.TIMESTAMP;
            case 15:
                return Types.BLOB;
            case 16:
            case 17:
                return Types.CLOB;
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

    private static List<Integer> inferBindOracleTypes(String sql, int bindCount) {
        List<Integer> inferredTypes = new ArrayList<>(bindCount);

        for (int i = 0; i < bindCount; i++) {
            inferredTypes.add(-1);
        }

        inferBindOracleType(BIND_CAST_TIMESTAMPTZ, sql, inferredTypes, 11);
        inferBindOracleType(BIND_CAST_TIMESTAMP, sql, inferredTypes, 10);
        inferBindOracleType(BIND_CAST_DATE, sql, inferredTypes, 9);
        return inferredTypes;
    }

    private static void inferBindOracleType(Pattern pattern, String sql, List<Integer> inferredTypes, int oracleType) {
        Matcher matcher = pattern.matcher(sql);

        while (matcher.find()) {
            if (!"?".equals(matcher.group(1))) {
                continue;
            }

            int bindIndex = placeholderIndex(sql, matcher.start(1));
            if (bindIndex >= 0 && bindIndex < inferredTypes.size()) {
                inferredTypes.set(bindIndex, oracleType);
            }
        }
    }

    private static int placeholderIndex(String sql, int placeholderPosition) {
        boolean inString = false;
        int index = 0;

        for (int i = 0; i <= placeholderPosition && i < sql.length(); i++) {
            char ch = sql.charAt(i);

            if (ch == '\'') {
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inString = !inString;
                }
                continue;
            }
            if (!inString && ch == '?') {
                if (i == placeholderPosition) {
                    return index;
                }
                index++;
            }
        }

        return -1;
    }

    private static String rewriteDateTimeLiteralCasts(String sql) {
        if (Boolean.getBoolean("oracle_fdw_jni_rewrite_literal_datetime")) {
            sql = replaceBindCast(LITERAL_CAST_TIMESTAMPTZ, sql, "TO_TIMESTAMP_TZ", TIMESTAMP_TZ_PARSE_FORMAT);
            sql = replaceBindCast(LITERAL_CAST_TIMESTAMP, sql, "TO_TIMESTAMP", TIMESTAMP_PARSE_FORMAT);
            sql = replaceBindCast(LITERAL_CAST_DATE, sql, "TO_DATE", DATE_PARSE_FORMAT);
            return sql;
        }

        sql = replaceLiteralDateTimeCast(LITERAL_CAST_TIMESTAMPTZ, sql, 11);
        sql = replaceLiteralDateTimeCast(LITERAL_CAST_TIMESTAMP, sql, 10);
        sql = replaceLiteralDateTimeCast(LITERAL_CAST_DATE, sql, 9);
        return sql;
    }

    private static String replaceLiteralDateTimeCast(Pattern pattern, String sql, int oracleType) {
        Matcher matcher = pattern.matcher(sql);
        StringBuffer rewritten = new StringBuffer(sql.length());

        while (matcher.find()) {
            String replacement = ansiDateTimeLiteral(sqlLiteralValue(matcher.group(1)), oracleType);
            if (replacement == null) {
                replacement = matcher.group(0);
            }
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static String ansiDateTimeLiteral(String value, int oracleType) {
        switch (oracleType) {
            case 9:
                LocalDateTime dateValue = parseAdDateTime(value);
                if (dateValue == null) {
                    return null;
                }
                return "CAST(TIMESTAMP " + sqlLiteral(formatAnsiLocalDateTime(dateValue)) + " AS DATE)";
            case 10:
                LocalDateTime timestampValue = parseAdDateTime(value);
                if (timestampValue == null) {
                    return null;
                }
                return "TIMESTAMP " + sqlLiteral(formatAnsiLocalDateTime(timestampValue));
            case 11:
                OffsetDateTime timestampTzValue = parseAdOffsetDateTime(value);
                if (timestampTzValue == null) {
                    return null;
                }
                return "TIMESTAMP " + sqlLiteral(formatAnsiLocalDateTime(timestampTzValue.toLocalDateTime()))
                        + " AT TIME ZONE " + sqlLiteral(formatZoneOffset(timestampTzValue));
            default:
                return null;
        }
    }

    private static String sqlLiteralValue(String literal) {
        if (literal == null || literal.length() < 2 || literal.charAt(0) != '\''
                || literal.charAt(literal.length() - 1) != '\'') {
            return literal;
        }
        return literal.substring(1, literal.length() - 1).replace("''", "'");
    }

    private static String formatAnsiLocalDateTime(LocalDateTime value) {
        if (value.getNano() != 0) {
            return String.format("%04d-%02d-%02d %02d:%02d:%02d.%s",
                    value.getYear(), value.getMonthValue(), value.getDayOfMonth(),
                    value.getHour(), value.getMinute(), value.getSecond(), formatNanos(value.getNano()));
        }

        return String.format("%04d-%02d-%02d %02d:%02d:%02d",
                value.getYear(), value.getMonthValue(), value.getDayOfMonth(),
                value.getHour(), value.getMinute(), value.getSecond());
    }

    private static String formatZoneOffset(OffsetDateTime value) {
        String offset = value.getOffset().getId();

        return "Z".equals(offset) ? "+00:00" : offset;
    }

    private String rewriteDateTimeSelectColumns(String sql) throws SQLException {
        if (!Boolean.getBoolean("oracle_fdw_jni_rewrite_select_datetime")) {
            return sql;
        }

        int selectEnd = topLevelSelectEnd(sql);
        if (selectEnd <= 0) {
            return sql;
        }

        List<String> selectItems = splitTopLevelComma(sql.substring(6, selectEnd));
        if (selectItems.isEmpty()) {
            return sql;
        }

        try (PreparedStatement probe = connection.prepareStatement(sql)) {
            ResultSetMetaData metaData = probe.getMetaData();
            if (metaData == null || metaData.getColumnCount() != selectItems.size()) {
                return sql;
            }

            boolean changed = false;
            for (int i = 0; i < selectItems.size(); i++) {
                String wrapped = wrapDateTimeSelectItem(selectItems.get(i), metaData, i + 1);
                if (!wrapped.equals(selectItems.get(i))) {
                    selectItems.set(i, wrapped);
                    changed = true;
                }
            }

            if (!changed) {
                return sql;
            }

            return "SELECT " + String.join(", ", selectItems) + " " + sql.substring(selectEnd);
        }
    }

    private static String wrapDateTimeSelectItem(String item, ResultSetMetaData metaData, int index)
            throws SQLException {
        String typeName = metaData.getColumnTypeName(index);
        int jdbcType = metaData.getColumnType(index);
        String trimmed = item.trim();

        if ("DATE".equalsIgnoreCase(typeName)) {
            return "TO_CHAR(" + trimmed + ", " + DATE_OUTPUT_FORMAT + ")";
        }
        if (typeName != null && typeName.toUpperCase().contains("TIMESTAMP WITH TIME ZONE")) {
            return "TO_CHAR(" + trimmed + ", " + TIMESTAMP_TZ_OUTPUT_FORMAT + ")";
        }
        if (typeName != null && typeName.toUpperCase().contains("TIMESTAMP WITH LOCAL TIME ZONE")) {
            return "TO_CHAR(" + trimmed + ", " + TIMESTAMP_TZ_OUTPUT_FORMAT + ")";
        }
        if (jdbcType == Types.TIMESTAMP || (typeName != null && typeName.toUpperCase().contains("TIMESTAMP"))) {
            return "TO_CHAR(" + trimmed + ", " + TIMESTAMP_OUTPUT_FORMAT + ")";
        }

        return item;
    }

    private static String replaceBindCast(Pattern pattern, String sql, String functionName, String format) {
        Matcher matcher = pattern.matcher(sql);
        StringBuffer rewritten = new StringBuffer(sql.length());

        while (matcher.find()) {
            matcher.appendReplacement(rewritten,
                    functionName + "(" + Matcher.quoteReplacement(matcher.group(1)) + ", " + format + ")");
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private static int topLevelSelectEnd(String sql) {
        String trimmed = sql.stripLeading();
        int leading = sql.length() - trimmed.length();

        if (!trimmed.regionMatches(true, 0, "SELECT", 0, 6)) {
            return -1;
        }
        if (trimmed.length() > 6 && isIdentifierPart(trimmed.charAt(6))) {
            return -1;
        }

        return findTopLevelKeyword(sql, leading + 6, "FROM");
    }

    private static int findTopLevelKeyword(String sql, int start, String keyword) {
        boolean inString = false;
        int depth = 0;

        for (int i = start; i <= sql.length() - keyword.length(); i++) {
            char ch = sql.charAt(i);

            if (ch == '\'') {
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inString = !inString;
                }
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '(') {
                depth++;
                continue;
            }
            if (ch == ')') {
                if (depth > 0) {
                    depth--;
                }
                continue;
            }
            if (depth == 0
                    && sql.regionMatches(true, i, keyword, 0, keyword.length())
                    && isKeywordBoundary(sql, i - 1)
                    && isKeywordBoundary(sql, i + keyword.length())) {
                return i;
            }
        }

        return -1;
    }

    private static List<String> splitTopLevelComma(String sql) {
        List<String> items = new ArrayList<>();
        boolean inString = false;
        int depth = 0;
        int start = 0;

        for (int i = 0; i < sql.length(); i++) {
            char ch = sql.charAt(i);

            if (ch == '\'') {
                if (inString && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inString = !inString;
                }
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                if (depth > 0) {
                    depth--;
                }
            } else if (ch == ',' && depth == 0) {
                items.add(sql.substring(start, i).trim());
                start = i + 1;
            }
        }

        items.add(sql.substring(start).trim());
        return items;
    }

    private static boolean isKeywordBoundary(String sql, int index) {
        return index < 0 || index >= sql.length() || !isIdentifierPart(sql.charAt(index));
    }

    private String getColumnValue(ResultSet rs, int index, int jdbcType, String typeName) throws SQLException {
        trace("column " + index + " jdbcType=" + jdbcType + " typeName=" + typeName);

        if ("DATE".equalsIgnoreCase(typeName)) {
            return formatOracleDate(rs, index);
        }
        if (typeName != null && typeName.equalsIgnoreCase("TIMESTAMP")) {
            Timestamp timestamp = rs.getTimestamp(index);
            return timestamp == null ? null : formatLocalDateTime(timestamp.toLocalDateTime(), true, true);
        }
        if (typeName != null && typeName.toUpperCase().contains("TIMESTAMP WITH TIME ZONE")) {
            OffsetDateTime timestamp = rs.getObject(index, OffsetDateTime.class);
            return timestamp == null ? null : formatOffsetDateTime(timestamp);
        }
        if (typeName != null && typeName.toUpperCase().contains("TIMESTAMP WITH LOCAL TIME ZONE")) {
            Timestamp timestamp = rs.getTimestamp(index);
            return timestamp == null ? null : formatLocalDateTime(timestamp.toLocalDateTime(), true, false);
        }
        if (jdbcType == 100 || "BINARY_FLOAT".equalsIgnoreCase(typeName)) {
            float value = rs.getFloat(index);
            return rs.wasNull() ? null : formatBinaryFloat(value);
        }
        if (jdbcType == -104 || (typeName != null && typeName.equalsIgnoreCase("INTERVALDS"))) {
            return intervalStringValue(rs.getObject(index));
        }
        if (isBinaryType(jdbcType, typeName)) {
            return bytesToHex(rs.getBytes(index));
        }
        try {
            return rs.getString(index);
        } catch (SQLException ex) {
            String message = ex.getMessage();
            if (message != null && message.contains("getString/getNString not implemented")) {
                return bytesToHex(rs.getBytes(index));
            }
            throw ex;
        }
    }

    private static boolean isBinaryType(int jdbcType) {
        return isBinaryType(jdbcType, null);
    }

    private static boolean isBinaryType(int jdbcType, String typeName) {
        String normalized = typeName == null ? "" : typeName.toUpperCase();

        return jdbcType == Types.BINARY
                || jdbcType == Types.VARBINARY
                || jdbcType == Types.LONGVARBINARY
                || jdbcType == Types.BLOB
                || normalized.contains("RAW")
                || normalized.contains("BLOB")
                || normalized.contains("BFILE");
    }

    private static boolean isReturningDate(int type) {
        return type == 1009;
    }

    private static String formatLocalDateTime(LocalDateTime value, boolean includeFraction, boolean includeEra) {
        String base;

        if (includeFraction && value.getNano() != 0) {
            base = String.format("%04d-%02d-%02d %02d:%02d:%02d.%s",
                    value.getYear(), value.getMonthValue(), value.getDayOfMonth(),
                    value.getHour(), value.getMinute(), value.getSecond(), formatNanos(value.getNano()));
        } else {
            base = String.format("%04d-%02d-%02d %02d:%02d:%02d",
                    value.getYear(), value.getMonthValue(), value.getDayOfMonth(),
                    value.getHour(), value.getMinute(), value.getSecond());
        }

        return includeEra ? base + " AD" : base;
    }

    private static String formatOracleDate(ResultSet rs, int index) throws SQLException {
        try {
            Class<?> oracleResultSetClass = Class.forName("oracle.jdbc.OracleResultSet");
            Object oracleResultSet = rs.unwrap(oracleResultSetClass);
            Object datum = oracleResultSetClass.getMethod("getOracleObject", int.class).invoke(oracleResultSet, index);

            if (datum == null) {
                return null;
            }

            return formatOracleDateBytes((byte[]) datum.getClass().getMethod("getBytes").invoke(datum));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException ex) {
            Timestamp timestamp = rs.getTimestamp(index);
            return timestamp == null ? null : formatLocalDateTime(timestamp.toLocalDateTime(), false, true);
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof SQLException) {
                throw (SQLException) ex.getCause();
            }

            Timestamp timestamp = rs.getTimestamp(index);
            return timestamp == null ? null : formatLocalDateTime(timestamp.toLocalDateTime(), false, true);
        }
    }

    private static String formatOracleDateBytes(byte[] bytes) throws SQLException {
        if (bytes == null) {
            return null;
        }
        if (bytes.length < 7) {
            throw new SQLException("invalid Oracle DATE value");
        }

        int year = (((int) bytes[0] & 0xff) - 100) * 100 + (((int) bytes[1] & 0xff) - 100);
        int month = (int) bytes[2] & 0xff;
        int day = (int) bytes[3] & 0xff;
        int hour = (((int) bytes[4] & 0xff) - 1);
        int minute = (((int) bytes[5] & 0xff) - 1);
        int second = (((int) bytes[6] & 0xff) - 1);
        String era = " AD";

        if (year < 0) {
            year += 1;
        }
        if (year <= 0) {
            year = 1 - year;
            era = " BC";
        }

        return String.format("%04d-%02d-%02d %02d:%02d:%02d%s", year, month, day, hour, minute, second, era);
    }

    private static String formatOffsetDateTime(OffsetDateTime value) {
        LocalDateTime local = value.toLocalDateTime();
        int year = local.getYear();
        String era = "";

        if (year <= 0) {
            year = 1 - year;
            era = " BC";
        }

        return String.format("%04d-%02d-%02d %02d:%02d:%02d%s%s%s",
                year, local.getMonthValue(), local.getDayOfMonth(),
                local.getHour(), local.getMinute(), local.getSecond(),
                local.getNano() == 0 ? "" : "." + formatNanos(local.getNano()),
                formatZoneOffset(value), era);
    }

    private static String formatBinaryFloat(float value) {
        return String.format(Locale.ROOT, "%.9g", value).replaceFirst("\\.0+$", "");
    }

    private static String intervalStringValue(Object value) throws SQLException {
        if (value == null) {
            return null;
        }

        try {
            return value.getClass().getMethod("stringValue").invoke(value).toString();
        } catch (NoSuchMethodException | IllegalAccessException ex) {
            return value.toString();
        } catch (InvocationTargetException ex) {
            throw unwrapSqlException("cannot read Oracle interval value", ex);
        }
    }

    private static String formatNanos(int nanos) {
        return String.format("%09d", nanos).replaceFirst("0+$", "");
    }

    private static String normalizeReturningDate(String value) {
        if (value == null || value.length() < 9) {
            return value;
        }

        String[] parts = value.trim().split("\\s+");
        String[] dateParts = parts[0].split("-");
        if (dateParts.length != 3 || dateParts[0].length() != 2 || dateParts[2].length() != 2) {
            return value;
        }

        int day;
        int year;
        int month = monthNumber(dateParts[1]);
        String era = (parts.length > 1 && "BC".equalsIgnoreCase(parts[1])) ? "BC" : "AD";

        if (month == 0) {
            return value;
        }

        try {
            day = Integer.parseInt(dateParts[0]);
            year = Integer.parseInt(dateParts[2]);
        } catch (NumberFormatException ex) {
            return value;
        }

        if ("AD".equals(era)) {
            year += (year >= 50) ? 1900 : 2000;
        }

        return String.format("%04d-%02d-%02d 00:00:00 %s", year, month, day, era);
    }

    private static int monthNumber(String value) {
        String month = value == null ? "" : value.toUpperCase();

        switch (month) {
            case "JAN": return 1;
            case "FEB": return 2;
            case "MAR": return 3;
            case "APR": return 4;
            case "MAY": return 5;
            case "JUN": return 6;
            case "JUL": return 7;
            case "AUG": return 8;
            case "SEP": return 9;
            case "OCT": return 10;
            case "NOV": return 11;
            case "DEC": return 12;
            default: return 0;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }

        char[] hex = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            hex[i * 2] = Character.forDigit(value >>> 4, 16);
            hex[i * 2 + 1] = Character.forDigit(value & 0x0f, 16);
        }
        return new String(hex);
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

    public boolean fetchNextHandle(int handle) throws SQLException {
        QueryState state = getQueryState(handle);
        boolean result;

        useQueryState(state);
        result = fetchNext();
        saveQueryState(state);
        return result;
    }

    public String getValueHandle(int handle, int columnIndex) throws SQLException {
        QueryState state = getQueryState(handle);
        String result;

        useQueryState(state);
        result = getValue(columnIndex);
        saveQueryState(state);
        return result;
    }

    public String getOutputValue(String name) {
        if (name == null) {
            return null;
        }
        return outputValues.get(bindName(name));
    }

    public String getOutputValueHandle(int handle, String name) throws SQLException {
        QueryState state = getQueryState(handle);
        String result;

        useQueryState(state);
        result = getOutputValue(name);
        saveQueryState(state);
        return result;
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
