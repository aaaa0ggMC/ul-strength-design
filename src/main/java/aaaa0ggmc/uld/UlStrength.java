package aaaa0ggmc.uld;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 强度设计查询 API（门面 + DI 服务定位入口）。
 *
 * <p>新代码建议通过构造器注入 {@link IStrengthService}；既有调用可继续使用静态方法，
 * 在单元测试中可通过 {@link #setService(IStrengthService)} 注入 Mock 实现。</p>
 */
public final class UlStrength {

    private static volatile IStrengthService service = DesignRegistry.defaultInstance();

    private UlStrength() {
    }

    public static IStrengthService service() {
        return service;
    }

    public static void setService(IStrengthService customService) {
        service = Objects.requireNonNull(customService, "customService");
    }

    public static void resetService() {
        service = DesignRegistry.defaultInstance();
    }

    public static double num(String key, double def) {
        return service.num(key, def);
    }

    public static int integer(String key, int def) {
        return service.integer(key, def);
    }

    public static boolean flag(String key, boolean def) {
        return service.flag(key, def);
    }

    public static String text(String key, String def) {
        return service.text(key, def);
    }

    public static double tier(String tier, String level) {
        return service.tier(tier, level);
    }

    public static int cooldownTicks(String valueKey, String tierLevel, int fallbackTicks) {
        return service.cooldownTicks(valueKey, tierLevel, fallbackTicks);
    }

    public static boolean has(String key) {
        return service.has(key);
    }

    public static Set<String> keys(String prefix) {
        return service.keys(prefix);
    }

    public static Set<String> tierNames() {
        return service.tierNames();
    }

    public static String source(String key) {
        return service.source(key);
    }

    public static void reload() {
        service.reload();
    }

    public static int size() {
        return service.size();
    }

    public static String activeSet() {
        return service.activeSet();
    }

    public static Map<String, String> snapshot(String prefix) {
        return service.snapshot(prefix);
    }
}
