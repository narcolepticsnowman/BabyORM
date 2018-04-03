package com.babyorm;

import com.babyorm.annotation.*;
import com.babyorm.util.Case;
import com.babyorm.util.ReflectiveUtils;
import com.babyorm.util.SqlGen;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.babyorm.util.ReflectiveUtils.*;

/**
 * Where the work happens for the baby repo, this exists just to keep the core logic and all the nice fluff separate
 * @param <T> The type of entity this repo likes the most
 */
public abstract class CoreRepo<T> {

    private ConnectionSupplier localConnectionSupplier;

    /**
     * the column get methods on the ResultSet
     */
    protected static final Map<Class<?>, Method> RESULTSET_COLUMN_NAME_GETTERS =
            addKeySuperTypes(
                    addPrimitivesToMap(
                            findMethods(
                                    ResultSet.class.getMethods(),
                                    Method::getReturnType,
                                    m -> m.getName().startsWith("get"),
                                    m -> !m.getName().startsWith("getN"),
                                    m -> m.getParameterCount() == 1,
                                    m -> m.getParameterTypes()[0].equals(String.class))));

    private static final Map<Class<?>, Method> RESULTSET_POSITION_GETTERS =
            addKeySuperTypes(
                    addPrimitivesToMap(
                            findMethods(
                                    ResultSet.class.getMethods(),
                                    Method::getReturnType,
                                    m -> m.getName().startsWith("get"),
                                    m -> !m.getName().startsWith("getN"),
                                    m -> m.getParameterCount() == 1,
                                    m -> m.getParameterTypes()[0].equals(Integer.TYPE))));

    /**
     * the sql bind set methods on the PreparedStatement
     */
    private static final Map<Class<?>, Method> STATEMENT_SETTERS =
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
    protected static ConnectionSupplier globalConnectionSupplier;
    private List<Field> keyFields;
    private Map<String, KeyProvider> keyProviders;
    private boolean isAutoGen;
    private Map<String, String> colNameToFieldName, fieldNameToColName;
    private String tableName;

    //TODO create keyProvider from PK annotation
    protected void init(Class<T> entityType) {
        this.entityType = entityType;
        this.fields = Arrays.asList(this.entityType.getDeclaredFields());
        this.fields.forEach(f -> f.setAccessible(true));
        this.keyFields = findFields(this.entityType, f -> f.getAnnotation(PK.class) != null);
        this.nonKeyFields = findFields(this.entityType, f -> f.getAnnotation(PK.class) == null);
        Optional<Case> columnCase = Optional.ofNullable(this.entityType.getAnnotation(ColumnCasing.class))
                .map(ColumnCasing::value);
        Map<Field, String> fieldToColName = this.fields.stream().collect(Collectors.toMap(
                f -> f,
                f->Optional.ofNullable(f.getAnnotation(ColumnName.class)).map(ColumnName::value)
                        .orElseGet(()->columnCase.map(c->Case.convert(f.getName(), c)).orElse(f.getName()))));
        this.colNameToFieldName = this.fields.stream().collect(Collectors.toMap(fieldToColName::get, Field::getName));
        this.fieldNameToColName = this.fields.stream().collect(Collectors.toMap(Field::getName, fieldToColName::get));
        this.isAutoGen = this.keyFields.stream().map(f -> f.getAnnotation(PK.class)).anyMatch(PK::autogenerated);
        if (!isAutoGen) {
            this.keyProviders = new HashMap<>();
            this.keyFields.forEach(f -> {
                try {
                    this.keyProviders.put(f.getName(), f.getAnnotation(PK.class).keyProvider().getConstructor().newInstance());
                } catch (ReflectiveOperationException e) {
                    throw new BabyDBException("Failed to instantiate a new KeyProvider for entity: "+this.entityType.getCanonicalName(), e);
                }
            });
        } else {
            if(keyFields.size()>1)
                throw new BabyDBException("Due to inconsistencies in JDBC \"getGeneratedKeys\" implementations, only 1 database generated key is supported per entity");
        }
        verifyKeyProviders(this.keyProviders);
        buildCachedSqlStatements();
    }

    CoreRepo() {}
    CoreRepo(ConnectionSupplier localConnectionSupplier) {
        this.localConnectionSupplier = localConnectionSupplier;
    }

    /**
     * Set the global connection supplier to use across all repositories, probably shouldn't change this at run time,
     * but it's your life, do what you want.
     */
    public static void setGlobalConnectionSupplier(ConnectionSupplier globalConnectionSupplier) {
        CoreRepo.globalConnectionSupplier = globalConnectionSupplier;
    }

    /**
     * Set the connection provider to use for this instance
     */
    public void setLocalConnectionSupplier(ConnectionSupplier localConnectionSupplier) {
        this.localConnectionSupplier = localConnectionSupplier;
    }

    private void buildCachedSqlStatements() {
        this.tableName = determineTableName();
        List<String> orderdFields = fields.stream().map(Field::getName).map(fieldNameToColName::get).collect(Collectors.toList());
        List<String> orderedNonKeys = nonKeyFields.stream().map(Field::getName).map(fieldNameToColName::get).collect(Collectors.toList());

        this.baseSql = SqlGen.all(tableName);
        this.deleteSql = SqlGen.delete(tableName);
        this.updateSql = SqlGen.update(tableName, orderedNonKeys);
        this.insertSqlNoKey = SqlGen.insert(tableName, orderedNonKeys);
        this.insertSql = SqlGen.insert(tableName, orderdFields);
    }

    private String determineTableName() {
        String tableName = Optional.ofNullable(this.entityType.getAnnotation(SchemaName.class))
                .map(s -> s.value() + ".")
                .orElse("");
        tableName += Optional.ofNullable(this.entityType.getAnnotation(TableName.class))
                .map(TableName::value)
                .orElseGet(() -> Character.toLowerCase(entityType.getSimpleName().charAt(0)) + entityType.getSimpleName().substring(1));
        return tableName;
    }

    protected void verifyKeyProviders(Map<?, KeyProvider> keyProvider) {
        if (isMultiKey() && keyProvider.size()<2) {
            throw new BabyDBException("Your most provide a KeyProvider for each key");
        }
    }

    protected Connection getConnection() {
        if (localConnectionSupplier == null && globalConnectionSupplier == null) {
            throw new BabyDBException("You must set a connection supplier. Didn't read the class javadoc eh?");
        }
        return Optional.ofNullable(localConnectionSupplier)
                .map(ConnectionSupplier::getConnection)
                .orElseGet(()->
                        Optional.ofNullable(globalConnectionSupplier)
                                .map(ConnectionSupplier::getConnection)
                                .orElseThrow(()->new BabyDBException("Failed to get a connection."))
                );
    }

    /**
     * Get one record by it's primary key
     */
    public T get(KeyProvider keyProvider) {
        Map<String, KeyProvider> keyProviders = Collections.singletonMap(this.keyFields.get(0).getName(), keyProvider);
        verifyKeyProviders(keyProviders);
        return get(keyProviders);
    }

    /**
     * Get one record by it's set of primary keys
     */
    public T get(Map<String, KeyProvider> keyProvider) {
        verifyKeyProviders(keyProvider);
        LinkedHashMap<String, ?> key = keysToColumnNames(
                keyProvider.entrySet().stream().collect(
                        Collectors.toMap(Map.Entry::getKey, e->e.getValue().nextKey()))
        );
        return getSome(SqlGen.whereAll(key), key.keySet().stream().map(key::get).toArray(), false).get(0);
    }

    protected List<T> getSome(String where, Object[] values, boolean isMany) {
        try (Connection conn = getConnection()) {
            String sql = baseSql + Optional.ofNullable(where).orElse("");
            PreparedStatement st = prepare(conn, sql, values);
            st.execute();
            return mapResultSet(st, isMany);
        } catch (SQLException e) {
            throw new BabyDBException("Failed to execute query", e);
        }
    }

    protected List<T> execute(String sql, Object... bindVariables){
        try(Connection conn = getConnection()){
            PreparedStatement st = prepare(conn, sql, bindVariables);
            st.execute();
            return mapResultSet(st, true);
        } catch (SQLException e){
            throw new BabyDBException("Failed to execute sql: "+sql, e);
        }
    }

    /**
     * Update the given record
     */
    public T update(T record) {
        try (Connection conn = getConnection()) {
            LinkedHashMap<String, Object> key = new LinkedHashMap<>(keyFields.size());
            keyFields.forEach(f->{
                Object val = getSafe(f, record);
                if(val == null){
                    throw new BabyDBException("Cannot perform an update on an entity with a null key");
                }
                key.put(f.getName(), val);
            });
            String updateSql = this.updateSql + SqlGen.whereAll(key);
            PreparedStatement st = prepare(conn, updateSql, Stream.concat(nonKeyFields.stream().map(f -> getSafe(f, record)), key.values().stream()).toArray());
            st.executeUpdate();
            return st.getUpdateCount() == 0 ? null : get(key.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e-> e::getValue)));
        } catch (SQLException e) {
            throw new BabyDBException("Update failed", e);
        }
    }

    /**
     * perform an arbitrary update on the database.
     *
     * @param fieldsToUpdate The fields to update with their new values
     * @param whereFields    The fields and values to use in the where clause of the query
     * @return The count of records that were updated
     */
    public int updateMany(Map<String, ?> fieldsToUpdate, Map<String, ?> whereFields) {
        try (Connection conn = getConnection()) {
            LinkedHashMap<String, ?> key = new LinkedHashMap<>(whereFields);
            String updateSql = SqlGen.update(this.tableName, new ArrayList<>(key.keySet())) + SqlGen.whereAll(key);
            PreparedStatement st = prepare(conn, updateSql, key.values().toArray());
            st.executeUpdate();
            if(!conn.getAutoCommit()){
                conn.commit();
            }
            return st.getUpdateCount();
        } catch (SQLException e) {
            throw new BabyDBException("Update failed", e);
        }
    }

    /**
     * Insert the given record into the database
     *
     * @param record The record to insert
     */
    public T insert(T record) {
        Objects.requireNonNull(record, "Can't save a null record");
        Map<String, ?> keyValue = keyValueFromRecord(record);
        boolean hasKey = keyValue != null && keyValue.size() > 0;
        final Map<String, KeyProvider> lookupKeyProvider;

        try (Connection conn = getConnection()) {
            final Map<String, Object> generatedKey = new HashMap<>();
            if(!isAutoGen){
                generatedKey.putAll(this.keyProviders.entrySet().stream()
                        .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().nextKey())));
                keyFields.forEach(f -> setSafe(f, record, generatedKey.get(f.getName())));
            }

            PreparedStatement st = prepare(
                    conn,
                    hasKey || !this.isAutoGen ? insertSql : insertSqlNoKey,
                    getFieldValues(record, hasKey ? fields : nonKeyFields).toArray());
            st.executeUpdate();

            if (!hasKey && this.isAutoGen) {
                ResultSet keys = st.getGeneratedKeys();
                if (!keys.next()) {
                    throw new BabyDBException("No key was returned from the db on insert for " + this.entityType.getCanonicalName());

                }
                lookupKeyProvider = keyFields.stream().collect(Collectors.toMap(Field::getName, f->{ Object k = getResultValue(f, keys, RESULTSET_POSITION_GETTERS, 1); return () -> k;}));
            } else {
                lookupKeyProvider = generatedKey.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e->()->e));
            }
            if(!conn.getAutoCommit()){
                conn.commit();
            }
        } catch (SQLException e) {
            throw new BabyDBException("Insert failed", e);
        }

        return get(lookupKeyProvider);
    }

    /**
     * Delete a record by it's {@link PK}
     *
     * @param key The pk of the record to delete
     * @return whether a record was deleted or not
     */
    public boolean delete(KeyProvider key) {
        Map<String, ?> map = keyValue(key.nextKey());
        return delete(map, SqlGen.whereAll(keysToColumnNames(map))) > 0;
    }

    protected int delete(Map<String, ?> columnValueMap, String where) {
        if (columnValueMap == null || columnValueMap.size() < 1) {
            return 0;
        }
        try (Connection conn = getConnection()) {
            PreparedStatement st = prepare(conn, this.deleteSql + where, columnValueMap.values().toArray());
            int count = st.executeUpdate();
            if(!conn.getAutoCommit()){
                conn.commit();
            }
            return count;
        } catch (SQLException e) {
            throw new BabyDBException("Delete failed", e);
        }
    }

    private PreparedStatement prepare(Connection conn, String sql, Object... args) {
        try {
            PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            if (args != null && args.length > 0) {
                int[] pos = new int[]{1};
                Arrays.stream(args)
                        .flatMap(o -> o instanceof Collection ? ((Collection) o).stream() : Stream.of(o))
                        .forEach(o -> invokeSafe(Optional.ofNullable(o).map(Object::getClass).map(STATEMENT_SETTERS::get).orElse(STATEMENT_SETTERS.get(Object.class)), ps, pos[0]++, o));
            }
            return ps;
        } catch (SQLException e) {
            throw new BabyDBException("Failed to prepare statement", e);
        }
    }

    private List<T> mapResultSet(PreparedStatement st, boolean isMany) {
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
                    f.set(model, getResultValue(f, rs, RESULTSET_COLUMN_NAME_GETTERS, fieldNameToColName.get(f.getName())));
                }
                if (isMany) {
                    many.add(model);
                }
            }
            return isMany ? many : Collections.singletonList(model);
        } catch (ReflectiveOperationException | SQLException e) {
            throw new BabyDBException("Failed to map resultSet to object", e);
        }
    }

    private Object getResultValue(Field field, ResultSet resultSet, Map<Class<?>, Method> getters, Object getterArg) {
        Class<?> type = field.getType();
        Method getter = Optional.ofNullable(getters.get(type)).orElse(getters.get(Object.class));

        Object result = ReflectiveUtils.invokeSafe(getter, resultSet, getterArg);
        if (result == null) {
            return null;
        } else if (PRIMITIVE_INVERSE.containsKey(type)) {
            if (!result.getClass().isAssignableFrom(type) && !result.getClass().isAssignableFrom(PRIMITIVE_INVERSE.get(type))) {
                throw new BabyDBException("Incompatible types for field: " + field.getDeclaringClass().getCanonicalName() + "." + field.getName() + ".  " +
                        "Wanted a " + type.getCanonicalName() + " but got a " + result.getClass().getCanonicalName());
            }
        } else if (!result.getClass().isAssignableFrom(type)) {
            throw new BabyDBException("Incompatible types for field: " + field.getDeclaringClass().getCanonicalName() + "." + field.getName() + ".  " +
                    "Wanted a " + type.getCanonicalName() + " but got a " + result.getClass().getCanonicalName());
        }
        return result;
    }

    protected LinkedHashMap<String, ?> keysToColumnNames(Map<String, ?> map) {
        LinkedHashMap<String, Object> colNameValueMap = map instanceof LinkedHashMap ? (LinkedHashMap<String, Object>) map : new LinkedHashMap<>(map.size());
        map.forEach((s, o) -> colNameValueMap.put(colNameToFieldName.containsKey(s) ? s : fieldNameToColName.get(s), o));
        return colNameValueMap;
    }

    protected Map<String, ?> keyValueFromRecord(T record) {
        Map<String, Object> keyValues = new HashMap<>(this.keyFields.size());
        this.keyFields.forEach(f -> {
            Object v = getSafe(f, record);
            if (v != null) keyValues.put(f.getName(), v);
        });
        return keyValues;
    }

    protected Map<String, ?> keyValue(Object id) {
        if (keyFields.size() > 1) {
            try {
                if (!(id instanceof Map)) {
                    throw new BabyDBException("You have multiple PKs on your entity. You must provide a Map<String,?> of Name to Value mappings when querying by id.");
                }
                Map<String, ?> keyValues = (Map) id;
                return keyFields.stream().map(Field::getName).map(fieldNameToColName::get).collect(Collectors.toMap(c->c, keyValues::get));
            } catch (ClassCastException e) {
                throw new BabyDBException("You must provide a Map<String,?> of Name to Value mappings when querying by id.", e);
            }
        } else {
            return Collections.singletonMap(fieldNameToColName.get(keyFields.get(0).getName()), id);
        }
    }

    protected boolean isMultiKey() {
        return this.keyFields.size()>1;
    }

    protected List<Field> getKeyFields() {
        return keyFields;
    }
}
