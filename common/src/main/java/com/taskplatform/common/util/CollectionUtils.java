package com.taskplatform.common.util;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class CollectionUtils {

    private CollectionUtils() {}

    public static <T> Map<String, T> toMapByKey(Collection<T> collection, Function<T, String> keyExtractor) {
        if (collection == null || collection.isEmpty()) {
            return new ConcurrentHashMap<>();
        }
        Map<String, T> map = new ConcurrentHashMap<>(collection.size());
        for (T item : collection) {
            String key = keyExtractor.apply(item);
            if (key != null) {
                map.put(key, item);
            }
        }
        return map;
    }

    public static <K, V> Map<K, V> newHashMap(int expectedSize) {
        return new HashMap<>(capacityFor(expectedSize));
    }

    public static <K, V> ConcurrentHashMap<K, V> newConcurrentHashMap(int expectedSize) {
        return new ConcurrentHashMap<>(capacityFor(expectedSize));
    }

    public static <T> List<T> newArrayList(int expectedSize) {
        return new ArrayList<>(expectedSize);
    }

    public static <T> Set<T> newHashSet(int expectedSize) {
        return new HashSet<>(capacityFor(expectedSize));
    }

    private static int capacityFor(int expectedSize) {
        if (expectedSize <= 0) {
            return 16;
        }
        return (int) Math.ceil(expectedSize / 0.75) + 1;
    }

    public static <T> T firstOrNull(List<T> list) {
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    public static <T> boolean isEmpty(Collection<T> collection) {
        return collection == null || collection.isEmpty();
    }

    public static <T> boolean isNotEmpty(Collection<T> collection) {
        return !isEmpty(collection);
    }

    public static <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    public static <K, V> Map<K, V> safeMap(Map<K, V> map) {
        return map == null ? Collections.emptyMap() : map;
    }

    @SafeVarargs
    public static <T> List<T> concat(List<T>... lists) {
        if (lists == null || lists.length == 0) {
            return Collections.emptyList();
        }
        int totalSize = 0;
        for (List<T> list : lists) {
            if (list != null) {
                totalSize += list.size();
            }
        }
        List<T> result = new ArrayList<>(totalSize);
        for (List<T> list : lists) {
            if (list != null) {
                result.addAll(list);
            }
        }
        return result;
    }

    public static <T, R> List<R> mapToList(Collection<T> collection, Function<T, R> mapper) {
        if (isEmpty(collection)) {
            return Collections.emptyList();
        }
        return collection.stream()
                .map(mapper)
                .collect(Collectors.toList());
    }
}
