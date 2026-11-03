package aaaa0ggmc.uld;

import java.util.Map;
import java.util.Set;

/**
 * 强度设计查询 API（内容/适配模组统一从这里取数值）。
 *
 * 约定：
 *  - 键名 {@code <line>.<system>.<item>}，如 {@code zombie_tide.horde.recruit_radius}；
 *  - 对齐档位用 {@link #tier}（t1..t5），改档位表全线跟随；
 *  - 查不到时代码里的兜底默认值生效，并在日志告警一次（键名拼写错误能立刻发现）。
 *
 * 例：{@code double radius = UlStrength.num("zombie_tide.horde.recruit_radius", 16.0);}
 */
public final class UlStrength {

    private UlStrength() {
    }

    public static double num(String key, double def) {
        return DesignRegistry.num(key, def);
    }

    public static int integer(String key, int def) {
        return DesignRegistry.integer(key, def);
    }

    public static boolean flag(String key, boolean def) {
        return DesignRegistry.flag(key, def);
    }

    public static String text(String key, String def) {
        return DesignRegistry.text(key, def);
    }

    public static double tier(String tier, String level) {
        return DesignRegistry.tier(tier, level);
    }

    public static boolean has(String key) {
        return DesignRegistry.has(key);
    }

    public static Set<String> keys(String prefix) {
        return DesignRegistry.keys(prefix);
    }

    /** 生效值来源（bundled:&lt;mod&gt;/… 或 config:&lt;file&gt;）。 */
    public static String source(String key) {
        return DesignRegistry.source(key);
    }

    public static void reload() {
        DesignRegistry.reload();
    }

    public static int size() {
        return DesignRegistry.size();
    }

    public static Map<String, String> snapshot(String prefix) {
        return DesignRegistry.snapshot(prefix);
    }
}
