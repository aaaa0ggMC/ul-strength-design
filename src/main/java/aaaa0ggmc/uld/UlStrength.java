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

    /**
     * 冷却取值（tick）——档位机制的统一入口。
     *
     * <p>优先级：具体值键 {@code valueKey}（config/模组可按能力覆盖）&gt; 档位表
     * {@code cooldown_ticks.<tierLevel>}（t1..t5，改档位表全线跟随）&gt; {@code fallbackTicks} 兜底。</p>
     *
     * <p>单位契约：档位表与全部 {@code *_cooldown_ticks} 键均为 tick（20t=1s），本方法不做
     * 秒换算；历史上以秒计的 {@code cooldown_seconds} 档位已改名换算为 {@code cooldown_ticks}
     * （审计 M8：统一 *_ticks 命名、消除秒/tick 单位地雷），勿再引入秒单位冷却键。</p>
     *
     * 例：{@code int cd = UlStrength.cooldownTicks("zombie_tide.horde.recruit_cooldown_ticks", "t1", 160);}
     */
    public static int cooldownTicks(String valueKey, String tierLevel, int fallbackTicks) {
        if (valueKey != null && !valueKey.isEmpty() && DesignRegistry.has(valueKey)) {
            return DesignRegistry.integer(valueKey, fallbackTicks);
        }
        double tierValue = DesignRegistry.tier("cooldown_ticks", tierLevel);
        return tierValue > 0.0D ? (int) Math.round(tierValue) : fallbackTicks;
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

    /**
     * 当前生效的外置难度名（{@code strengths/<返回值>/}）；袋子没装上时是配置里记的值，
     * 实际生效的是 jar 内烘焙副本（= normal）。
     */
    public static String activeSet() {
        return DesignRegistry.activeSet();
    }

    public static Map<String, String> snapshot(String prefix) {
        return DesignRegistry.snapshot(prefix);
    }
}
