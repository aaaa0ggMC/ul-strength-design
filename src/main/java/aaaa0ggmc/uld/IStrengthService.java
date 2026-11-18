package aaaa0ggmc.uld;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 强度设计服务接口（DI 契约）。
 *
 * <p>内容模组与命令应通过构造器注入 {@link IStrengthService}（或从 {@link UlStrength#service()} 获取），
 * 以便单元测试注入 Mock 实现并消除对全局静态可变状态的强耦合。</p>
 */
public interface IStrengthService {

    boolean has(String key);

    double num(String key, double def);

    int integer(String key, int def);

    boolean flag(String key, boolean def);

    String text(String key, String def);

    double tier(String tier, String level);

    default int cooldownTicks(String valueKey, String tierLevel, int fallbackTicks) {
        if (valueKey != null && !valueKey.isEmpty() && has(valueKey)) {
            return integer(valueKey, fallbackTicks);
        }
        double tierValue = tier("cooldown_ticks", tierLevel);
        return tierValue > 0.0D ? (int) Math.round(tierValue) : fallbackTicks;
    }

    Set<String> keys(String prefix);

    Set<String> tierNames();

    String source(String key);

    int size();

    String activeSet();

    default List<String> availableSets() {
        return Collections.emptyList();
    }

    default boolean setActiveSet(String name) {
        return false;
    }

    String normalizeSetName(String raw);

    Map<String, String> snapshot(String prefix);

    void reload();
}
