package aaaa0ggmc.uld;

import com.google.gson.JsonElement;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 强度设计数据的不可变快照，用于 {@link DesignRegistry#reload()} 的原子替换。
 * 并发读取方始终看到完整一致的一份数据，不会因重载清空出现半加载状态或 NPE。
 */
final class StrengthSnapshot {

    static final StrengthSnapshot EMPTY = new StrengthSnapshot(
            "normal",
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap()
    );

    final String activeSet;
    final Map<String, JsonElement> values;
    final Map<String, Map<String, JsonElement>> tiers;
    final Map<String, String> sources;

    StrengthSnapshot(
            String activeSet,
            Map<String, JsonElement> values,
            Map<String, Map<String, JsonElement>> tiers,
            Map<String, String> sources
    ) {
        this.activeSet = activeSet;
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
        Map<String, Map<String, JsonElement>> frozenTiers = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, JsonElement>> e : tiers.entrySet()) {
            frozenTiers.put(e.getKey(), Collections.unmodifiableMap(new HashMap<>(e.getValue())));
        }
        this.tiers = Collections.unmodifiableMap(frozenTiers);
        this.sources = Collections.unmodifiableMap(new HashMap<>(sources));
    }
}
