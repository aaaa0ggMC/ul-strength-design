package aaaa0ggmc.uld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 数值设计注册表（ul-strength-design 的核心）。
 *
 * 分层合并，后加载赢（覆盖）：
 *   1. 本模组包内默认 assets/ul_strength_design/ul_strength/defaults.json；
 *   2. 所有已安装模组的 assets/&lt;modid&gt;/ul_strength/*.json（各内容模组自带默认值）；
 *   3. config/ul_strength_design/*.json（实例侧覆盖——对齐数值就改这里，不动仓库）。
 *
 * 每次 reload 记录每个键的来源文件，{@code /ulstrength where} 可查。
 * 设计意图：整合包做大幅度数值对齐时，只改 config 覆盖层 + reload，不重新构建。
 */
public final class DesignRegistry {
    private static final Logger LOGGER = LogManager.getLogger("ul-strength-design");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static File configDir;
    /** key -&gt; 数值（JsonElement，类型由查询方决定）。 */
    private static final Map<String, JsonElement> VALUES = new ConcurrentHashMap<>();
    /** tier 名 -&gt; level -&gt; 数值。 */
    private static final Map<String, Map<String, JsonElement>> TIERS = new ConcurrentHashMap<>();
    /** key -&gt; 来源文件路径（覆盖可见性）。 */
    private static final Map<String, String> SOURCES = new ConcurrentHashMap<>();
    /** 已告警过的缺失键（每个键只吵一次）。 */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private DesignRegistry() {
    }

    public static void init(File configDirIn) {
        configDir = new File(configDirIn, "ul_strength_design");
        if (!configDir.exists() && !configDir.mkdirs()) {
            LOGGER.warn("[uld] Cannot create config dir {}", configDir);
        }
        reload();
    }

    public static void reload() {
        VALUES.clear();
        TIERS.clear();
        SOURCES.clear();

        // 1. 本模组包内默认
        mergeFile("bundled:defaults", DesignRegistry.class.getClassLoader()
                .getResourceAsStream("assets/ul_strength_design/ul_strength/defaults.json"));

        // 2. 各模组自带默认（扫 mod 源 jar/classes 目录下的 assets/<modid>/ul_strength/*.json，任意文件名）
        //    自身模组的 defaults.json 已在第 1 步加载，跳过避免重复
        for (ModContainer mod : Loader.instance().getActiveModList()) {
            String modId = mod.getModId().toLowerCase(Locale.ROOT);
            if (modId.equals(StrengthDesign.MODID)) {
                continue;
            }
            mergeModAssets(modId, mod.getSource());
        }

        // 3. config 覆盖（实例侧对齐入口）
        File[] overrides = configDir == null ? new File[0] : configDir.listFiles((d, name) -> name.endsWith(".json"));
        if (overrides != null) {
            for (File file : overrides) {
                try (InputStream is = new FileInputStream(file)) {
                    mergeFile("config:" + file.getName(), is);
                } catch (Throwable t) {
                    LOGGER.warn("[uld] Failed to read override {}", file.getName(), t);
                }
            }
        }

        LOGGER.info("[uld] Loaded {} strength values, {} tiers", VALUES.size(), TIERS.size());
    }

    /**
     * 扫某个 mod 源（jar 或 dev 环境 classes 目录）的 assets/&lt;modid&gt;/ul_strength/ 下所有 json。
     * 任意文件名都收，按名字排序保证合并顺序稳定（后加载赢）。
     */
    private static void mergeModAssets(String modId, File source) {
        if (source == null || !source.exists()) {
            return;
        }
        String prefix = "assets/" + modId + "/ul_strength/";
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            if (source.isDirectory()) {
                File dir = new File(source, prefix);
                File[] files = dir.listFiles((d, name) -> name.endsWith(".json"));
                if (files != null) {
                    for (File f : files) {
                        names.add(f.getName());
                    }
                }
                names.sort(String::compareTo);
                for (String name : names) {
                    try (InputStream is = new FileInputStream(new File(dir, name))) {
                        mergeFile("bundled:" + modId + "/" + name, is);
                    }
                }
            } else {
                try (ZipFile zip = new ZipFile(source)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String entryName = entry.getName();
                        if (!entry.isDirectory() && entryName.startsWith(prefix) && entryName.endsWith(".json")) {
                            names.add(entryName.substring(prefix.length()));
                        }
                    }
                    names.sort(String::compareTo);
                    for (String name : names) {
                        try (InputStream is = zip.getInputStream(zip.getEntry(prefix + name))) {
                            mergeFile("bundled:" + modId + "/" + name, is);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[uld] Failed to scan mod assets {} from {}", modId, source, t);
        }
    }

    private static void mergeFile(String source, InputStream is) {
        if (is == null) {
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) {
                return;
            }
            if (root.has("values") && root.get("values").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("values").entrySet()) {
                    if (e.getKey().startsWith("_")) {
                        continue; // _doc 等注释键
                    }
                    VALUES.put(e.getKey(), e.getValue());
                    SOURCES.put(e.getKey(), source);
                }
            }
            if (root.has("tiers") && root.get("tiers").isJsonObject()) {
                for (Map.Entry<String, JsonElement> te : root.getAsJsonObject("tiers").entrySet()) {
                    if (!te.getValue().isJsonObject()) {
                        continue;
                    }
                    Map<String, JsonElement> levels = TIERS.computeIfAbsent(te.getKey(), k -> new ConcurrentHashMap<>());
                    for (Map.Entry<String, JsonElement> le : te.getValue().getAsJsonObject().entrySet()) {
                        if (le.getKey().startsWith("_")) {
                            continue;
                        }
                        levels.put(le.getKey(), le.getValue());
                        SOURCES.put("tier:" + te.getKey() + "." + le.getKey(), source);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[uld] Failed to parse strength file {}", source, t);
        }
    }

    // ---------------------------------------------------------------- 查询

    public static boolean has(String key) {
        return VALUES.containsKey(key);
    }

    public static double num(String key, double def) {
        JsonElement e = VALUES.get(key);
        if (e == null) {
            warnMissing(key);
            return def;
        }
        try {
            return e.getAsDouble();
        } catch (Throwable t) {
            warnMissing(key);
            return def;
        }
    }

    public static int integer(String key, int def) {
        return (int) Math.round(num(key, def));
    }

    public static boolean flag(String key, boolean def) {
        JsonElement e = VALUES.get(key);
        if (e == null) {
            warnMissing(key);
            return def;
        }
        try {
            return e.getAsBoolean();
        } catch (Throwable t) {
            return def;
        }
    }

    public static String text(String key, String def) {
        JsonElement e = VALUES.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            warnMissing(key);
            return def;
        }
        return e.getAsString();
    }

    /** 取档位表数值（t1..t5）。缺档返回 0 并告警。 */
    public static double tier(String tier, String level) {
        Map<String, JsonElement> levels = TIERS.get(tier);
        if (levels == null) {
            warnMissing("tier:" + tier);
            return 0.0D;
        }
        JsonElement e = levels.get(level);
        if (e == null) {
            warnMissing("tier:" + tier + "." + level);
            return 0.0D;
        }
        try {
            return e.getAsDouble();
        } catch (Throwable t) {
            return 0.0D;
        }
    }

    public static Set<String> keys(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return Collections.unmodifiableSet(VALUES.keySet());
        }
        Set<String> out = ConcurrentHashMap.newKeySet();
        for (String key : VALUES.keySet()) {
            if (key.startsWith(prefix)) {
                out.add(key);
            }
        }
        return out;
    }

    /** 该键当前生效值来自哪个文件（bundled:... / config:...）。 */
    public static String source(String key) {
        return SOURCES.getOrDefault(key, "<default-in-code>");
    }

    public static int size() {
        return VALUES.size();
    }

    /** 快照（/ulstrength list 用）：key -&gt; 生效值（原样字符串）。 */
    public static Map<String, String> snapshot(String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : new java.util.TreeSet<>(keys(prefix))) {
            out.put(key, VALUES.get(key).toString());
        }
        return out;
    }

    private static void warnMissing(String key) {
        if (WARNED.add(key)) {
            LOGGER.warn("[uld] Missing strength key {} — using in-code default", key);
        }
    }

    /** 单元/调试用：当前合并出的原始 JSON。 */
    public static JsonObject raw() {
        JsonObject root = new JsonObject();
        JsonObject values = new JsonObject();
        for (Map.Entry<String, JsonElement> e : VALUES.entrySet()) {
            values.add(e.getKey(), e.getValue());
        }
        root.add("values", values);
        JsonObject tiers = new JsonObject();
        for (Map.Entry<String, Map<String, JsonElement>> te : TIERS.entrySet()) {
            JsonObject levels = new JsonObject();
            for (Map.Entry<String, JsonElement> le : te.getValue().entrySet()) {
                levels.add(le.getKey(), le.getValue());
            }
            tiers.add(te.getKey(), levels);
        }
        root.add("tiers", tiers);
        return root;
    }

    /** 供自动重载：config 目录下文件的 mtime 签名。 */
    public static long configSignature() {
        if (configDir == null) {
            return 0L;
        }
        File[] files = configDir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) {
            return 0L;
        }
        long sig = 0L;
        for (File f : files) {
            sig = sig * 31 + f.lastModified() + f.getName().hashCode();
        }
        return sig;
    }

    /** 首运把包内默认写成 config 覆盖模板（只在目录为空时）。 */
    public static void writeOverrideTemplate() {
        try {
            File target = new File(configDir, "overrides.json");
            if (configDir == null || target.exists()) {
                return;
            }
            Files.write(target.toPath(), GSON.toJson(raw()).getBytes(StandardCharsets.UTF_8));
            LOGGER.info("[uld] Wrote override template {}", target.getName());
        } catch (Throwable t) {
            LOGGER.warn("[uld] Failed to write override template: {}", t.toString());
        }
    }
}
