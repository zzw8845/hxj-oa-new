package com.hxj.oa.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.hxj.oa.common.security.DataScopeType;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * JSON 列（MySQL JSON 类型）的轻量读取工具。
 * 约定：JSON 列在实体里统一映射为 String，避免引入 typeHandler。
 * 解析失败一律降级为空集合，不让脏数据打断主流程。
 */
public final class JsonColumn {

    private JsonColumn() {
    }

    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return JsonUtils.mapper().readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public static List<Map<String, Object>> toList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JsonUtils.mapper().readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /** @return null 表示不存在 */
    public static String str(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static Long toLong(Map<String, Object> map, String key) {
        String s = str(map, key);
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Boolean toBool(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? null : Boolean.valueOf(String.valueOf(v));
    }

    public static List<Long> toLongList(Map<String, Object> map, String key) {
        Object v = map == null ? null : map.get(key);
        if (!(v instanceof List<?> list)) {
            return Collections.emptyList();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(x -> {
                    try {
                        return Long.valueOf(String.valueOf(x).trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public static DataScopeType toScope(String code) {
        return DataScopeType.of(code);
    }

    /** 解析形如 [1,2,3] 的 JSON 数组为 Long 列表，失败降级为空集合 */
    public static List<Long> jsonArrayToLongList(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<Long> ids = JsonUtils.mapper().readValue(json, new TypeReference<List<Long>>() {
            });
            return ids == null ? Collections.emptyList() : ids;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
