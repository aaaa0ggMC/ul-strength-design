package aaaa0ggmc.uld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   1. 本模组包内默认 assets/ul_strength_design/ul_strength/defaults.json（档位表）；
 *   2. 所有已安装模组的 assets/&lt;modid&gt;/ul_strength/*.json（各内容模组自带默认值）；
 *   3. config/ul_strength_design/*.json（实例侧覆盖——对齐数值就改这里，不动仓库）；
 *   4. <b>strengths/&lt;当前难度&gt;/&lt;modid&gt;/</b> 下递归收集到的所有 json（外置难度袋，优先级最高）。
 *
 * <p><b>第四层（难度袋）为什么在 config 之上</b>：难度袋按设计是<b>整套数据集</b>
 * （不是增量补丁），玩家显式切难度就必须整套生效，否则 {@code /ulpack strength hard}
 * 会「写配置成功但数值不变」——这正是审计 E-4 的失效形态。ul_modpack 的
 * {@code CommandUlPack.strength()} 用 {@code UlStrength.source(...)} 探一个键、
 * 要求出现 {@code strengths:} 前缀才回报生效；若 config 压在难度袋之上，该探针会在
 * 第四层明明生效时也误报「loader does not read strengths/ yet」。故第四层最后加载。
 * 实例侧微调仍走 config 层（它依旧覆盖第 1、2 层），默认 {@code overrides.json} 是空模板，
 * 正常不会与难度袋冲突。</p>
 *
 * <p><b>难度来源</b>：与 ul_modpack 的 {@code StrengthSets} 读同一个文件
 * {@code <gameDir>/config/strengths.json} 的 {@code active} 键（该模组在
 * {@code /ulpack strength <name>} 时写它并立即调 {@code UlStrength.reload()}）。
 * 这里刻意重复那几个常量而不反向依赖 ul_modpack——ul_modpack 已经依赖本模组的
 * {@link UlStrength}，反向依赖会成循环依赖（审计 E-4 记下的阻塞点）。</p>
 *
 * <p><b>兜底</b>：难度袋缺失（没跑 {@code scripts/sync-strengths.sh} / 该难度没装 /
 * 配置文件缺失）时<b>静默回退</b>到前 1-3 层，不抛异常。此时生效的是 jar 内烘焙副本：
 * ul_modpack 的 build.gradle 只把 {@code strengths/normal/} 烘进
 * {@code assets/<modid>/ul_strength/}，所以<b>「jar 里那份 = normal 难度」</b>，
 * 未安装外置袋的客户端/专用服务器行为与加第四层之前逐字节一致。</p>
 *
 * 每次 reload 记录每个键的来源文件，{@code /ulstrength where} 可查。
 * 设计意图：整合包做大幅度数值对齐时，只改 config 覆盖层 + reload，不重新构建。
 */
public final class DesignRegistry {
    private static final Logger LOGGER = LogManager.getLogger("ul-strength-design");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 游戏目录下的外置难度袋根目录名（与 ul_modpack StrengthSets.DIR_NAME 一致）。 */
    private static final String SETS_DIR_NAME = "strengths";
    /** 记录当前难度的配置文件名（{@code <gameDir>/config/strengths.json}，主 config 目录直下）。 */
    private static final String ACTIVE_CONFIG_NAME = "strengths.json";
    /** 配置里记录当前难度的键名。 */
    private static final String KEY_ACTIVE = "active";
    /** 默认难度：与 jar 内烘焙副本（= strengths/normal/）同一份数据。 */
    private static final String DEFAULT_SET = "normal";
    /** 难度袋递归扫描深度上限（防软链成环；实际结构只有 <难度>/<modid>/<文件> 三层）。 */
    private static final int SET_SCAN_MAX_DEPTH = 8;

    private static File configDir;
    /** 游戏目录（= 主 config 目录的上一级）；取不到时为 null，第四层整体关闭。 */
    private static File gameDir;
    /** 外置难度袋根目录：{@code <gameDir>/strengths}。 */
    private static File setsRoot;
    /** 当前难度配置文件：{@code <gameDir>/config/strengths.json}。 */
    private static File activeConfigFile;
    /** 当前生效难度名（已归一化；缺袋时也是配置里的值）。 */
    private static volatile String activeSet = DEFAULT_SET;
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
        // 外置难度袋定位：入参是【主 config 目录】（<gameDir>/config，Forge 的
        // getModConfigurationDirectory() 语义），其上一级即游戏目录。
        gameDir = configDirIn.getParentFile();
        if (gameDir != null) {
            setsRoot = new File(gameDir, SETS_DIR_NAME);
            activeConfigFile = new File(configDirIn, ACTIVE_CONFIG_NAME);
        } else {
            LOGGER.warn("[uld] Cannot resolve game dir from {} — strengths/ layer disabled, using bundled fallback", configDirIn);
        }
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

        // 4. 外置难度袋 strengths/<当前难度>/<modid>/**/*.json —— 优先级最高，最后加载。
        //    缺袋 / 缺配置时静默跳过，前三层原样生效（见 mergeStrengthSet 的兜底日志）。
        mergeStrengthSet();

        LOGGER.info("[uld] Loaded {} strength values, {} tiers (set: {})",
                VALUES.size(), TIERS.size(), activeSet);
    }

    // ---------------------------------------------------------------- 第四层：外置难度袋

    /**
     * 合并 {@code <gameDir>/strengths/<当前难度>/<modid>/} 下递归收集到的所有 json
     * （整袋、递归、优先级最高）。
     *
     * <p>难度名每次 reload 都从 {@code config/strengths.json} 重读，因此
     * {@code /ulpack strength <name>}（写配置 + UlStrength.reload()）与手工改配置都能切。</p>
     *
     * <p><b>兼容与兜底</b>：袋子不存在、目录不可读、单个文件解析失败，全部只记日志不抛异常，
     * 前三层（bundled → config）原样生效；此时 jar 内烘焙副本 = normal 难度那份数据。</p>
     */
    private static void mergeStrengthSet() {
        activeSet = readActiveSet();
        File dir = setDir(activeSet);
        if (dir == null) {
            // 静默回退：没装外置袋 / 该难度没装。jar 内 assets/<modid>/ul_strength/ 副本
            // （= strengths/normal/）继续生效，行为与加第四层之前逐字节一致。
            LOGGER.info("[uld] Strength set '{}' not installed under {} (game dir {})"
                    + " — using bundled fallback (jar bakes normal)", activeSet, setsRoot, gameDir);
            return;
        }
        List<File> files = listJsonRecursive(dir);
        for (File file : files) {
            String rel = relativeToSetsRoot(file);
            try (InputStream is = new FileInputStream(file)) {
                // 来源标签以 "strengths:" 开头——ul_modpack 的 CommandUlPack 靠这个前缀
                // 判断第四层是否真的生效（startsWith("strengths")）。
                mergeFile("strengths:" + rel, is);
            } catch (Throwable t) {
                LOGGER.warn("[uld] Failed to read strength set file {}", rel, t);
            }
        }
        LOGGER.info("[uld] Strength set '{}': {} file(s) from {} (overrides bundled + config layers)",
                activeSet, files.size(), dir);
    }

    /**
     * 读当前难度：{@code <gameDir>/config/strengths.json} 的 {@code active} 键。
     * 与 ul_modpack {@code StrengthSets} 读同一个文件、同一个键、同一套归一规则
     * （去空白 + 小写 + 只允许 {@code [a-z0-9][a-z0-9._-]*}，首字符必须字母数字 ⇒ 天然挡掉 {@code ..}）。
     * 任何异常/缺失/非法值都回退 {@link #DEFAULT_SET}，不抛。
     */
    private static String readActiveSet() {
        if (activeConfigFile == null || !activeConfigFile.isFile()) {
            return DEFAULT_SET;
        }
        try {
            JsonObject root = new JsonParser()
                    .parse(new String(Files.readAllBytes(activeConfigFile.toPath()), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (root != null && root.has(KEY_ACTIVE)) {
                String raw = root.get(KEY_ACTIVE).getAsString();
                if (raw != null && !raw.trim().isEmpty()) {
                    return normalizeSetName(raw);
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[uld] Failed to read {} — using '{}'", ACTIVE_CONFIG_NAME, DEFAULT_SET);
        }
        return DEFAULT_SET;
    }

    /** 难度名归一：去空白、小写；只允许字母数字与 -_.（首字符必须字母数字，防目录穿越）。 */
    private static String normalizeSetName(String name) {
        if (name == null) {
            return DEFAULT_SET;
        }
        String out = name.trim().toLowerCase(Locale.ROOT);
        if (out.isEmpty()) {
            return DEFAULT_SET;
        }
        if (!out.matches("[a-z0-9][a-z0-9._-]*")) {
            return DEFAULT_SET;
        }
        return out;
    }

    /** 指定难度袋目录；根目录未定位或该难度没装返回 null。 */
    private static File setDir(String name) {
        if (name == null || name.isEmpty() || setsRoot == null) {
            return null;
        }
        File dir = new File(setsRoot, name);
        return dir.isDirectory() ? dir : null;
    }

    /**
     * 递归收集目录下所有 .json（{@code java.io.File} 天然跟随软链——实例里
     * {@code strengths/<难度>} 正是指向 dev 仓库的软链，必须跟下去才看得到文件）。
     * 按绝对路径排序保证合并顺序稳定（后加载赢）。
     */
    private static List<File> listJsonRecursive(File dir) {
        List<File> out = new ArrayList<>();
        collectJson(dir, out, 0);
        out.sort(Comparator.comparing(File::getAbsolutePath));
        return out;
    }

    private static void collectJson(File dir, List<File> out, int depth) {
        if (dir == null || !dir.isDirectory() || depth > SET_SCAN_MAX_DEPTH) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectJson(child, out, depth + 1);
            } else if (child.isFile() && child.getName().endsWith(".json")) {
                out.add(child);
            }
        }
    }

    /** 文件相对 {@link #setsRoot} 的路径（用于来源标签，如 {@code hard/aaaa0ggmc_ul_modpack/zombie_tide.json}）。 */
    private static String relativeToSetsRoot(File file) {
        if (setsRoot == null) {
            return file.getName();
        }
        String root = setsRoot.getAbsolutePath();
        String path = file.getAbsolutePath();
        if (path.startsWith(root)) {
            String rel = path.substring(root.length());
            while (rel.startsWith(File.separator)) {
                rel = rel.substring(1);
            }
            if (!rel.isEmpty()) {
                return rel;
            }
        }
        return file.getName();
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

    /**
     * 当前生效的外置难度名（{@code strengths/<返回值>/}）；袋子没装上时返回配置里记的值，
     * 实际生效的是 jar 内烘焙副本（= normal）。供命令回报与调试。
     */
    public static String activeSet() {
        return activeSet;
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

    /**
     * 供自动重载：config 覆盖文件 + 难度配置 + 外置难度袋的 mtime 签名。
     *
     * <p>三处都纳入，5 秒轮询才能覆盖第四层：改 {@code strengths/<难度>/...} 的数值、
     * 或手工改 {@code config/strengths.json} 的 active，都能触发热重载
     * （{@code /ulpack strength <name>} 则是命令直接调 {@code reload()}，不等轮询）。</p>
     */
    public static long configSignature() {
        long sig = 0L;
        if (configDir != null) {
            File[] files = configDir.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null) {
                for (File f : files) {
                    sig = sig * 31 + f.lastModified() + f.getName().hashCode();
                }
            }
        }
        if (activeConfigFile != null && activeConfigFile.isFile()) {
            sig = sig * 31 + activeConfigFile.lastModified() + ACTIVE_CONFIG_NAME.hashCode();
        }
        if (setsRoot != null) {
            for (File f : listJsonRecursive(setsRoot)) {
                sig = sig * 31 + f.lastModified() + f.getAbsolutePath().hashCode();
            }
        }
        return sig;
    }

    /**
     * 首运写 config 覆盖模板（只在文件不存在时）。
     *
     * 只写空结构 + 用法说明，**不预填当前值**：预填会让每个键的生效来源都变成
     * config 覆盖（{@code where} 失去意义），而且仓库默认值日后调整会被旧模板
     * 静默挡住。可用键与当前生效值看 {@code /ulstrength list}。
     */
    public static void writeOverrideTemplate() {
        try {
            File target = new File(configDir, "overrides.json");
            if (configDir == null || target.exists()) {
                return;
            }
            JsonObject template = new JsonObject();
            template.addProperty("_doc", "实例侧覆盖：只写要改的键，值即覆盖包内/模组默认。"
                    + "可用键与当前生效值用 /ulstrength list 查看，来源用 /ulstrength where <key>。"
                    + "保存后 5 秒内自动热重载，或 /ulstrength reload。");
            template.add("values", new JsonObject());
            template.add("tiers", new JsonObject());
            Files.write(target.toPath(), GSON.toJson(template).getBytes(StandardCharsets.UTF_8));
            LOGGER.info("[uld] Wrote override template {}", target.getName());
        } catch (Throwable t) {
            LOGGER.warn("[uld] Failed to write override template: {}", t.toString());
        }
    }
}
