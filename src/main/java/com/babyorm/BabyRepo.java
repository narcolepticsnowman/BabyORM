package com.babyorm;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.babyorm.ReflectiveUtils.*;

/**
 * A repo, baby
 * <p>
 * To make a new repo, try one of: new BabyRepo<Foo>(){}; or new BabyRepo<Foo>(Foo.class); or BabyRepo.forType(Foo.class);
 * <p>
 * You must call {@link #setGlobalConnectionSupplier(ConnectionSupplier)} or provide a ConnectionSupplier via Constructor or setter
 * If you don't, shit's gonna throw errors telling you to do this.
 * <p>
 * You may also want to provide a {@link KeyProvider} if your {@link PK} is not autogenerated.
 * we'll remind you if we need to.
 *
 * @param <T> The type of entity this repo likes the most
 */
public class BabyRepo<T> {

    /**
     * the column getter methods on the ResultSet class
     */
    private static final Map<Class<?>, Method> GETTERS =
            addKeySuperTypes(
                    addPrimitivesToMap(
                            findMethods(
                                    ResultSet.class.getMethods(),
                                    Method::getReturnType,
                                    m -> m.getName().startsWith("get"),
                                    m -> !m.getName().startsWith("getN"),
                                    m -> m.getParameterCount() == 1,
                                    m -> m.getParameterTypes()[0].equals(String.class))));

    /**
     * the parameter setter methods on the PreparedStatement class
     */
    private static final Map<Class<?>, Method> SETTERS =
            addKeySuperTypes(
                    addPrimitivesToMap(
                            findMethods(
                                    PreparedStatement.class.getMethods(),
                                    m -> m.getParameterTypes()[1],
                                    m -> m.getName().startsWith("set"),
                                    m -> !m.getName().startsWith("setN"),
                                    m -> m.getParameterCount() == 2,
                                    m -> m.getParameterTypes()[0].equals(Integer.TYPE))));

    private Class<T> entityType;
    private List<Field> fields, nonKeyFields;
    private String baseSql, updateSql, insertSqlNoKey, insertSql, deleteSql;
    private static ConnectionSupplier globalConnectionSupplier;
    private ConnectionSupplier localConnectionSupplier;
    private Field keyField;
    private KeyProvider keyProvider;

    /**
     * Pretty straight forward, can't really screw this one up.
     */
    public BabyRepo(Class<T> entityType) {
        init(entityType, null);
    }

    /**
     * @param keyProvider The key provider for the associated entity.
     */
    public BabyRepo(Class<T> entityType, KeyProvider keyProvider) {
        init(entityType, keyProvider);
    }

    /**
     * You MUST extend this class and specify your entity type on the class that directly extends
     * this class. try: new BabyRepo<Foo>(){};
     */
    public BabyRepo() {
        this((KeyProvider) null);
    }

    /**
     * You MUST extend this class and specify your entity type on the class that directly extends
     * this class. try: new BabyRepo<Foo>(){};
     */
    public BabyRepo(KeyProvider keyProvider) {
        Type genericSuperclass = this.getClass().getGenericSuperclass();
        if (genericSuperclass == null || !(genericSuperclass instanceof ParameterizedType)) {
            throw new BabyDBException("You must extend BabyRepo to use the no-arg constructor.");
        }
        init((Class<T>) ((ParameterizedType) genericSuperclass).getActualTypeArguments()[0], keyProvider);
    }

    /**
     * Use a local connection supplier instead of the global connection supplier
     */
    public BabyRepo(ConnectionSupplier connectionSupplier) {
        this();
        this.localConnectionSupplier = connectionSupplier;
    }

    /**
     * Use a local connection supplier instead of the global connection supplier
     */
    public BabyRepo(ConnectionSupplier connectionSupplier, KeyProvider keyProvider) {
        this(keyProvider);
        this.localConnectionSupplier = connectionSupplier;
    }

    /**
     * Use a local connection supplier instead of the global connection supplier
     */
    public BabyRepo(Class<T> entityType, ConnectionSupplier connectionSupplier) {
        this(entityType);
        this.localConnectionSupplier = connectionSupplier;
    }

    /**
     * Use a local connection supplier instead of the global connection supplier
     */
    public BabyRepo(Class<T> entityType, ConnectionSupplier connectionSupplier, KeyProvider keyProvider) {
        this(entityType, keyProvider);
        this.localConnectionSupplier = connectionSupplier;
    }

    /**
     * Factory method to get a new repository
     */
    public static <E> BabyRepo<E> forType(Class<E> type) {
        return new BabyRepo<>(type);
    }

    /**
     * Set the global connection supplier to use across all repositories, probably shouldn't change this at run time,
     * but it's your life, do what you want.
     */
    public static void setGlobalConnectionSupplier(ConnectionSupplier globalConnectionSupplier) {
        BabyRepo.globalConnectionSupplier = globalConnectionSupplier;
    }

    /**
     * Set the connection provider to use for this instance
     */
    public void setLocalConnectionSupplier(ConnectionSupplier localConnectionSupplier) {
        this.localConnectionSupplier = localConnectionSupplier;
    }

    private void init(Class<T> entityType, KeyProvider keyProvider) {
        this.entityType = entityType;
        this.fields = Arrays.asList(this.entityType.getDeclaredFields());
        this.fields.forEach(f -> f.setAccessible(true));
        this.keyField = findKeyField();
        this.keyField.setAccessible(true);
        this.keyProvider = keyProvider;
        if (!this.keyField.getAnnotation(PK.class).autogenerated() && keyProvider == null) {
            throw new BabyDBException("You must provide a KeyProvider if your entity's PK is not autogenerated. I forgot too. :|");
        }
        this.nonKeyFields = fields.stream()
                .filter(f -> !keyField.equals(f))
                .collect(Collectors.toList());
        buildCachedSqlStatements();
    }

    private Field findKeyField() {
        List<Field> idFIelds = findFields(this.entityType, f -> f.getAnnotation(PK.class) != null);
        if (idFIelds == null || idFIelds.size() < 1) {
            throw new BabyDBException("No field labeled as PK for " + this.entityType.getCanonicalName());
        } else if (idFIelds.size() > 1) {
            throw new BabyDBException("Multiple PK fields found on " + this.entityType.getCanonicalName());
        } else {
            return idFIelds.get(0);
        }
    }

    private void buildCachedSqlStatements() {
        String tableName = determineTableName();

        this.baseSql = "select * from " + tableName;
        this.deleteSql = "delete from " + tableName ;
        this.updateSql = "update " + tableName
                + " set " + nonKeyFields.stream().map(f -> colName(f) + "=?").collect(Collectors.joining(","))
                + " where " + colName(keyField) + "=?";
        this.insertSqlNoKey = "insert into " + tableName
                + "(" + nonKeyFields.stream()
                .map(this::colName)
                .collect(Collectors.joining(",")) + ")"
                + " values (" + nonKeyFields.stream().map(f -> "?").collect(Collectors.joining(",")) + ")";
        this.insertSql = "insert into " + tableName
                + "(" + fields.stream()
                .map(this::colName)
                .collect(Collectors.joining(",")) + ")"
                + " values (" + fields.stream().map(f -> "?").collect(Collectors.joining(",")) + ")";
    }

    private String colName(Field f) {
        return Optional.ofNullable(f.getAnnotation(ColumnName.class)).map(ColumnName::value).orElseGet(f::getName);
    }

    private String determineTableName() {
        String tableName = Optional.ofNullable(this.entityType.getAnnotation(SchemaName.class))
                .map(s -> s.value() + ".")
                .orElse("");
        tableName += Optional.ofNullable(this.entityType.getAnnotation(TableName.class))
                .map(TableName::value)
                .orElseGet(() -> camelCase(entityType.getSimpleName()));
        return tableName;
    }

    private String camelCase(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    public T get(Object id) {
        return (T) getSome(Collections.singletonMap(colName(keyField), id), null, false);
    }

    public List<T> getAll() {
        return (List<T>) getSome(null, null, true);
    }

    /**
     * Get one record by a single column value. this is either the database column name or the field name.
     * We'll figure it out.
     *
     * @param field The field name/column name you want to look up the record by.
     * @param value The value you're searching for.
     *              If the value is a collection, an in list will be created.
     * @return The found record if any
     */
    public T getOneBy(String field, Object value) {
        return getOneByAll(Collections.singletonMap(field, value));
    }

    /**
     * Find a single record that matches ALL of the columns
     *
     * @param columnValueMap A map of column names and the values to look up by.
     *                       If the value is a collection, an in list will be created.
     * @return The found record, if any
     */
    public T getOneByAll(Map<String, Object> columnValueMap) {
        return (T) getSome(columnValueMap, " AND ", false);
    }

    /**
     * Find a single record that matches ANY of the columns.
     *
     * @param columnValueMap A map of column names and the values to look up by.
     *                       If the value is a collection, an in list will be created.
     * @return The found record, if any
     */
    public T getOneByAny(Map<String, Object> columnValueMap) {
        return (T) getSome(columnValueMap, " OR ", false);
    }

    /**
     * Find a many records that match a single column.
     *
     * @param field The field name/column name you want to look up the record by.
     * @param value The value you're searching for.
     *              If the value is a collection, an in list will be created.
     */
    public List<T> getManyBy(String field, Object value) {
        return getManyByAll(Collections.singletonMap(field, value));
    }

    /**
     * Find a many records that match ALL of the columns.
     *
     * @param columnValueMap A map of column names and the values to look up by.
     *                       If the value is a collection, an in list will be created.
     * @return The found records, if any
     */
    public List<T> getManyByAll(Map<String, Object> columnValueMap) {
        return (List<T>) getSome(columnValueMap, " AND ", true);
    }

    /**
     * Find a many records that match ANY of the columns.
     *
     * @param columnValueMap A map of column names and the values to look up by.
     *                       If the value is a collection, an in list will be created.
     * @return The found records, if any
     */
    public List<T> getManyByAny(Map<String, Object> columnValueMap) {
        return (List<T>) getSome(columnValueMap, " OR ", true);
    }


    private Object getSome(Map<String, Object> map, String operator, boolean isMany) {
        try (Connection conn = getConnection()) {
            PreparedStatement st;
            String sql = baseSql;
            if (map != null) {
                baseSql += buildWhere(map, operator);
                st = prepare(conn, sql, map.values().toArray());
            } else {
                st = prepare(conn, sql);
            }
            st.execute();
            return mapResultSet(st, isMany);
        } catch (SQLException e) {
            throw new BabyDBException("Failed to execute query", e);
        }

    }

    private String buildWhere(Map<String, Object> map, String operator) {
        return " where " + map.entrySet().stream()
                .map(e -> {
                    String columnName = getField(entityType, e.getKey())
                            .map(this::colName)
                            .orElse(e.getKey());

                    if (e.getValue() instanceof Collection) {
                        return columnName + " in (" +
                                ((Collection<?>) e.getValue()).stream()
                                        .map(o -> "?")
                                        .collect(Collectors.joining(",")) + ")";
                    } else {
                        return columnName + "=?";
                    }
                })
                .collect(Collectors.joining(Optional.ofNullable(operator).orElse("")));
    }

    private PreparedStatement prepare(Connection conn, String sql, Object... args) {
        try {
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            int[] pos = new int[]{1};
            Arrays.stream(args)
                    .flatMap(o -> o instanceof Collection ? ((Collection) o).stream() : Stream.of(o))
                    .forEach(o -> Optional.ofNullable(SETTERS.get(o.getClass()))
                            .map(s -> invokeSafe(s, ps, pos[0]++, o))
                            .orElseThrow(() -> new BabyDBException("Unsupported property type: " + o.getClass().getCanonicalName()))
                    );
            return ps;
        } catch (SQLException e) {
            throw new BabyDBException("Failed to prepare statement", e);
        }
    }

    private Connection getConnection() {
        if (localConnectionSupplier == null && globalConnectionSupplier == null) {
            throw new BabyDBException("You must set a connection supplier. Didn't read the class javadoc eh?");
        }
        return Optional.ofNullable(localConnectionSupplier)
                .map(ConnectionSupplier::getConnection)
                .orElseGet(globalConnectionSupplier::getConnection);
    }

    private Object mapResultSet(PreparedStatement st, boolean isMany) {
        try {
            ResultSet rs = st.getResultSet();
            List<T> many = isMany ? new ArrayList<>() : null;
            boolean hasOne = false;
            T model = null;
            while (rs.next()) {
                if (hasOne && !isMany) {
                    throw new BabyDBException("Multiple rows found for single row query");
                }
                hasOne = true;
                model = entityType.getConstructor().newInstance();
                for (Field f : fields) {
                    f.set(model, getResultValue(f, rs));
                }
                if (isMany) {
                    many.add(model);
                }
            }
            return isMany ? many : model;
        } catch (ReflectiveOperationException | SQLException e) {
            throw new BabyDBException("Failed to map resultSet to object", e);
        }
    }

    private Object getResultValue(Field field, ResultSet resultSet) {
        Class<?> type = field.getType();
        if (!GETTERS.containsKey(type)) {
            throw new BabyDBException("Unsupported model property type:" + type.getCanonicalName());
        }
        return ReflectiveUtils.invokeSafe(GETTERS.get(type), resultSet, colName(field));
    }

    /**
     * Insert or update the given record
     *
     * @param record The record to save
     * @return The saved record. The record is retrieved from the database after saving to guarantee generated values are retrieved.
     */
    public T save(T record) {
        Object o = getSafe(keyField, Objects.requireNonNull(record, "Can't save a null record"));
        if (o == null) {
            return insert(record);
        } else {
            T saved = get(o);
            return saved != null ? update(record) : insert(record);
        }
    }

    /**
     * Update the given record
     *
     * @param record
     * @return
     */
    public T update(T record) {
        try (Connection conn = getConnection()) {
            List<Object> values = getFieldValues(record, nonKeyFields);
            //always add the key last to match the sql
            Object keyValue = getSafe(keyField, record);
            values.add(keyValue);
            PreparedStatement st = prepare(conn, updateSql, values.toArray());
            st.executeUpdate();
            return st.getUpdateCount() == 0 ? null : get(keyValue);
        } catch (SQLException e) {
            throw new BabyDBException("Update failed", e);
        }

    }

    /**
     * Insert the given record into the database
     *
     * @param record The record to insert
     * @return
     */
    public T insert(T record) {
        boolean hasKey = getSafe(keyField, Objects.requireNonNull(record, "Can't save a null record")) != null;
        try (Connection conn = getConnection()) {
            boolean isAutogen = keyField.getAnnotation(PK.class).autogenerated();
            Object generatedKey = null;
            if (isAutogen) {
                generatedKey = this.keyProvider.nextKey();
                setSafe(keyField, record, generatedKey);
            }
            PreparedStatement st = prepare(
                    conn,
                    hasKey || !isAutogen ? insertSql : insertSqlNoKey,
                    getFieldValues(record, hasKey ? fields : nonKeyFields).toArray());
            st.executeUpdate();
            if (hasKey || !isAutogen) {
                return get(isAutogen ? generatedKey : getFieldValues(record, Collections.singletonList(keyField)).get(0));
            } else {
                ResultSet keys;
                keys = st.getGeneratedKeys();
                if (keys.next()) {
                    return get(getResultValue(keyField, keys));
                } else {
                    throw new BabyDBException("No key was returned from the db on insert for " + this.entityType.getCanonicalName());
                }
            }
        } catch (SQLException e) {
            throw new BabyDBException("Insert failed", e);
        }
    }

    /**
     * Delete a record by it's {@link PK}
     *
     * @param key The pk of the record to delete
     * @return whether a record was deleted or not
     */
    public boolean deleteByPK(Object key) {
        return deleteBy(colName(keyField), key);
    }

    /**
     * Delete a record by it's {@link PK}
     *
     * @param entity The record to delete, the PK will be retrieved and used in the delete statement
     * @return whether a record was deleted or not
     */
    public boolean delete(T entity) {
        return deleteBy(colName(keyField), getSafe(keyField, entity));
    }

    /**
     * Delete by a specific column
     *
     * @param field The field/column name to delete by
     * @param value The value to search by
     * @return Whether any records were deleted or not
     */
    public boolean deleteBy(String field, Object value) {
        try (Connection conn = getConnection()) {
            PreparedStatement st = prepare(conn, this.deleteSql + buildWhere(Collections.singletonMap(field, value), null));
            return st.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new BabyDBException("Delete failed", e);
        }
    }

    /**
     * delete records that match ALL of the columns.
     *
     * @param columnValueMap A map of column names and the values to look up by.
     *                       If the value is a collection, an in list will be created.
     * @return whether any records were deleted
     */
    public boolean deleteByAll(Map<String, Object> columnValueMap) {
        try (Connection conn = getConnection()) {
            PreparedStatement st = prepare(conn, this.deleteSql + buildWhere(columnValueMap, " OR "));
            return st.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new BabyDBException("Delete failed", e);
        }
    }

    /**
     * delete records that match ANY of the columns.
     *
     * @param columnValueMap A map of column names and the values to look up by.
     *                       If the value is a collection, an in list will be created.
     * @return whether any records were deleted
     */
    public boolean deleteByAny(Map<String, Object> columnValueMap) {
        try (Connection conn = getConnection()) {
            PreparedStatement st = prepare(conn, this.deleteSql + buildWhere(columnValueMap, " AND "));
            return st.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new BabyDBException("Delete failed", e);
        }
    }


}