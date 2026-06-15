package com.causal.eventstore.util;

import java.util.Map;

public class ProjectionSchemaUtil {

    public enum ColumnType {
        VARCHAR, NUMERIC, BOOLEAN, JSONB
    }

    public static ColumnType mapType(String type) {
        if (type == null) return ColumnType.JSONB;
        return switch (type.toLowerCase()) {
            case "string", "str" -> ColumnType.VARCHAR;
            case "number", "int", "integer", "float", "double", "long" -> ColumnType.NUMERIC;
            case "boolean", "bool" -> ColumnType.BOOLEAN;
            case "object", "array", "list", "map" -> ColumnType.JSONB;
            default -> ColumnType.JSONB;
        };
    }

    public static String getColumnTypeSql(String type) {
        ColumnType columnType = mapType(type);
        return switch (columnType) {
            case VARCHAR -> "VARCHAR";
            case NUMERIC -> "NUMERIC";
            case BOOLEAN -> "BOOLEAN";
            case JSONB -> "JSONB";
        };
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getFieldsFromSchema(Map<String, Object> schema) {
        if (schema == null) return Map.of();
        Object fields = schema.get("fields");
        if (fields instanceof Map) {
            return (Map<String, Object>) fields;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    public static String getTypeFromFieldDef(Object fieldDef) {
        if (fieldDef instanceof Map) {
            Map<String, Object> def = (Map<String, Object>) fieldDef;
            Object type = def.get("type");
            if (type != null) return type.toString();
        } else if (fieldDef instanceof String) {
            return (String) fieldDef;
        }
        return "string";
    }
}
