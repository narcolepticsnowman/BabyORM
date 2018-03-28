package com.babyorm.util;

import java.util.*;
import java.util.stream.Collectors;

public class SqlGen {

    /**
     * Insert the one thing!
     * @param columnNames The list of columns to include on the insert
     */
    public static String insert(String tableName, List<String> columnNames) {
        return "insert into " + tableName
                + "(" + columnNames.stream().collect(Collectors.joining(",")) + ")"
                + " values (" + columnNames.stream().map(f -> "?").collect(Collectors.joining(",")) + ")";
    }

    /**
     * Update all the things!
     * @param columnNames The list of columns that will be included in the update
     */
    public static String update(String tableName, List<String> columnNames) {
        return "update " + tableName
                + " set " + columnNames.stream().map(n -> n + "=?").collect(Collectors.joining(","));
    }

    /**
     * Delete all the things!
     */
    public static String delete(String tableName) {
        return "delete from " + tableName;
    }

    /**
     * Select all the things!
     */
    public static String all(String tableName) {
        return "select * from " + tableName;
    }

    /**
     * Build a where statement for a query, ANDing all the fields together
     * @param columnValueMap A map of column names with their values. The values are used to determine whether we should
     *                       build an in list or not. It's a LinkedHashMap so we can guarantee order of the elements so
     *                       we can later set them appropriately by position.
     */
    public static String whereAll(LinkedHashMap<String, Object> columnValueMap){
        return where(columnValueMap, " AND ");
    }

    /**
     * Build a where statement for a query, ORing all the fields together
     * @param columnValueMap A map of column names with their values. The values are used to determine whether we should
     *                       build an in list or not. It's a LinkedHashMap so we can guarantee order of the elements so
     *                       we can later set them appropriately by position.
     */
    public static String whereAny(LinkedHashMap<String, Object> columnValueMap){
        return where(columnValueMap, " OR ");
    }

    private static String where(LinkedHashMap<String, Object> columnValueMap, String operator){
        StringBuilder sb = new StringBuilder(" where ");
        columnValueMap.forEach((s,o)->{
            if (o instanceof Collection) {
                sb.append(s).append(" in (")
                        .append(((Collection<?>) o).stream().map(i -> "?").collect(Collectors.joining(",")))
                        .append(")");
            } else {
                sb.append(s).append("=?");
            }
        });
        return sb.toString();
    }
}