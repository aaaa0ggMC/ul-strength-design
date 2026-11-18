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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 数值设计注册表（ul-strength-design 的核心），实现 {@link IStrengthService}。
 *
 * <p>通过不可变 {@link StrengthSnapshot} 原子替换保证并发重载安全：读取方永远看到一致快照，
 * 不会因重载清空出现 NPE 或半加载状态。</p>
 */
public final class DesignRegistry implements IStrengthService {
    private static final Logger LOGGER = LogManager.getLogger("ul-strength-design");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final String SETS_DIR_NAME = "strengths";
    public static final String ACTIVE_CONFIG_NAME = "strengths.json";
    public static final String KEY_ACTIVE = "active";
    public static final String DEFAULT_SET = "normal";
    private static final int SET_SCAN_MAX_DEPTH = 8;

    private static final DesignRegistry DEFAULT_INSTANCE = new DesignRegistry();

    private final Object reloadLock = new Object();
    private volatile File configDir;
    private volatile File gameDir;
    private volatile File setsRoot;
    private volatile File activeConfigFile;
    private volatile StrengthSnapshot currentSnapshot = StrengthSnapshot.EMPTY;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public DesignRegistry() {
    }

    public static DesignRegistry defaultInstance() {
        return DEFAULT_INSTANCE;
    }

    public static void init(File configDirIn) {
        DEFAULT_INSTANCE.initialize(configDirIn);
    }

    public void initialize(File configDirIn) {
        synchronized (reloadLock) {
            this.configDir = new File(configDirIn, "ul_strength_design");
            this.gameDir = configDirIn.getParentFile();
            if (this.gameDir != null) {
                this.setsRoot = new File(this.gameDir, SETS_DIR_NAME);
                this.activeConfigFile = new File(configDirIn, ACTIVE_CONFIG_NAME);
            } else {
                LOGGER.warn("[uld] Cannot resolve game dir from {} — strengths/ layer disabled, using bundled fallback", configDirIn);
            }
            if (!this.configDir.exists() && !this.configDir.mkdirs()) {
                LOGGER.warn("[uld] Cannot create config dir {}", this.configDir);
            }
        }
        reload();
    }

    @Override
    public void reload() {
        synchronized (reloadLock) {
            Map<String, JsonElement> newValues = new HashMap<>();
            Map<String, Map<String, JsonElement>> newTiers = new HashMap<>();
            Map<String, String> newSources = new HashMap<>();

            // 1. 本模组包内默认
            mergeFile("bundled:defaults", DesignRegistry.class.getClassLoader()
                    .getResourceAsStream("assets/ul_strength_design/ul_strength/defaults.json"),
                    newValues, newTiers, newSources);

            // 2. 各模组自带默认（按 modid 扫描；无 Forge 环境如单元测试时安全跳过）
            try {
                for (ModContainer mod : Loader.instance().getActiveModList()) {
                    String modId = mod.getModId().toLowerCase(Locale.ROOT);
                    if (modId.equals(StrengthDesign.MODID)) {
                        continue;
                    }
                    mergeModAssets(modId, mod.getSource(), newValues, newTiers, newSources);
                }
            } catch (Throwable ignored) {
            }

            // 3. 外置难度袋 strengths/<当前难度>/<modid>/**/*.json
            String newActiveSet = mergeStrengthSet(newValues, newTiers, newSources);

            // 4. 用户最高优先级覆盖层 config/ul_strength_design/*.json（按文件名排序保证确定性）
            File cfgDir = this.configDir;
            File[] overrides = cfgDir == null ? new File[0] : cfgDir.listFiles((d, name) -> name.endsWith(".json"));
            if (overrides != null && overrides.length > 0) {
                Arrays.sort(overrides, Comparator.comparing(File::getName));
                for (File file : overrides) {
                    try (InputStream is = new FileInputStream(file)) {
                        mergeFile("config:" + file.getName(), is, newValues, newTiers, newSources);
                    } catch (Throwable t) {
                        LOGGER.warn("[uld] Failed to read override {}", file.getName(), t);
                    }
                }
            }

            // 原子替换不可变快照
            this.currentSnapshot = new StrengthSnapshot(newActiveSet, newValues, newTiers, newSources);

            LOGGER.info("[uld] Loaded {} strength values, {} tiers (set: {})",
                    newValues.size(), newTiers.size(), newActiveSet);
        }
    }

    private String mergeStrengthSet(
            Map<String, JsonElement> values,
            Map<String, Map<String, JsonElement>> tiers,
            Map<String, String> sources
    ) {
        String setName = readActiveSet();
        File dir = setDir(setName);
        if (dir == null) {
            LOGGER.info("[uld] Strength set '{}' not installed under {} (game dir {})"
                    + " — using bundled fallback (jar bakes normal)", setName, setsRoot, gameDir);
            return setName;
        }
        List<File> files = listJsonRecursive(dir);
        for (File file : files) {
            String rel = relativeToSetsRoot(file);
            try (InputStream is = new FileInputStream(file)) {
                mergeFile("strengths:" + rel, is, values, tiers, sources);
            } catch (Throwable t) {
                LOGGER.warn("[uld] Failed to read strength set file {}", rel, t);
            }
        }
        LOGGER.info("[uld] Strength set '{}': {} file(s) from {} (overrides bundled + config layers)",
                setName, files.size(), dir);
        return setName;
    }

    private String readActiveSet() {
        File cfgFile = this.activeConfigFile;
        if (cfgFile == null || !cfgFile.isFile()) {
            return DEFAULT_SET;
        }
        try {
            JsonObject root = new JsonParser()
                    .parse(new String(Files.readAllBytes(cfgFile.toPath()), StandardCharsets.UTF_8))
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

    @Override
    public String normalizeSetName(String name) {
        if (name == null) {
            return DEFAULT_SET;
        }
        String out = name.trim().toLowerCase(Locale.ROOT);
        if (out.isEmpty() || !out.matches("[a-z0-9][a-z0-9._-]*")) {
            return DEFAULT_SET;
        }
        return out;
    }

    @Override
    public List<String> availableSets() {
        List<String> out = new ArrayList<>();
        File root = this.setsRoot;
        File[] dirs = root == null ? null : root.listFiles(File::isDirectory);
        if (dirs != null) {
            for (File d : dirs) {
                out.add(d.getName());
            }
        }
        Collections.sort(out);
        return out;
    }

    @Override
    public boolean setActiveSet(String name) {
        String normalized = normalizeSetName(name);
        if (setDir(normalized) == null || this.activeConfigFile == null) {
            return false;
        }
        try {
            JsonObject root = new JsonObject();
            root.addProperty(KEY_ACTIVE, normalized);
            root.addProperty("_doc", "当前强度难度（strengths/<difficulty>/）。可用 /ulpack strength <name> 切换并立即热重载。");
            if (this.activeConfigFile.getParentFile() != null) {
                this.activeConfigFile.getParentFile().mkdirs();
            }
            Files.write(this.activeConfigFile.toPath(),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create()
                            .toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            LOGGER.warn("[uld] Failed to write {}: {}", ACTIVE_CONFIG_NAME, t.toString());
            return false;
        }
        reload();
        return true;
    }

    public File setDir(String name) {
        File root = this.setsRoot;
        if (name == null || name.isEmpty() || root == null) {
            return null;
        }
        File dir = new File(root, normalizeSetName(name));
        return dir.isDirectory() ? dir : null;
    }

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

    private String relativeToSetsRoot(File file) {
        File rootFile = this.setsRoot;
        if (rootFile == null) {
            return file.getName();
        }
        String root = rootFile.getAbsolutePath();
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

    private void mergeModAssets(
            String modId,
            File source,
            Map<String, JsonElement> values,
            Map<String, Map<String, JsonElement>> tiers,
            Map<String, String> sources
    ) {
        if (source == null || !source.exists()) {
            return;
        }
        String prefix = "assets/" + modId + "/ul_strength/";
        List<String> names = new ArrayList<>();
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
                        mergeFile("bundled:" + modId + "/" + name, is, values, tiers, sources);
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
                            mergeFile("bundled:" + modId + "/" + name, is, values, tiers, sources);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[uld] Failed to scan mod assets {} from {}", modId, source, t);
        }
    }

    private void mergeFile(
            String source,
            InputStream is,
            Map<String, JsonElement> values,
            Map<String, Map<String, JsonElement>> tiers,
            Map<String, String> sources
    ) {
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
                        continue;
                    }
                    values.put(e.getKey(), e.getValue());
                    sources.put(e.getKey(), source);
                }
            }
            if (root.has("tiers") && root.get("tiers").isJsonObject()) {
                for (Map.Entry<String, JsonElement> te : root.getAsJsonObject("tiers").entrySet()) {
                    if (!te.getValue().isJsonObject()) {
                        continue;
                    }
                    Map<String, JsonElement> levels = tiers.computeIfAbsent(te.getKey(), k -> new HashMap<>());
                    for (Map.Entry<String, JsonElement> le : te.getValue().getAsJsonObject().entrySet()) {
                        if (le.getKey().startsWith("_")) {
                            continue;
                        }
                        levels.put(le.getKey(), le.getValue());
                        sources.put("tier:" + te.getKey() + "." + le.getKey(), source);
                    }
                }
            }
        } catch (Throwable t) {
            LOGGER.warn("[uld] Failed to parse strength file {}", source, t);
        }
    }

    // ---------------------------------------------------------------- 查询 (IStrengthService)

    @Override
    public boolean has(String key) {
        return currentSnapshot.values.containsKey(key);
    }

    @Override
    public double num(String key, double def) {
        JsonElement e = currentSnapshot.values.get(key);
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

    @Override
    public int integer(String key, int def) {
        return (int) Math.round(num(key, def));
    }

    @Override
    public boolean flag(String key, boolean def) {
        JsonElement e = currentSnapshot.values.get(key);
        if (e == null) {
            warnMissing(key);
            return def;
        }
        try {
            return e.getAsBoolean();
        } catch (Throwable t) {
            warnMissing(key);
            return def;
        }
    }

    @Override
    public String text(String key, String def) {
        JsonElement e = currentSnapshot.values.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            warnMissing(key);
            return def;
        }
        return e.getAsString();
    }

    @Override
    public double tier(String tier, String level) {
        Map<String, JsonElement> levels = currentSnapshot.tiers.get(tier);
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
            warnMissing("tier:" + tier + "." + level);
            return 0.0D;
        }
    }

    @Override
    public Set<String> keys(String prefix) {
        Set<String> allKeys = currentSnapshot.values.keySet();
        if (prefix == null || prefix.isEmpty()) {
            return allKeys;
        }
        Set<String> out = new TreeSet<>();
        for (String key : allKeys) {
            if (key.startsWith(prefix)) {
                out.add(key);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    @Override
    public Set<String> tierNames() {
        return currentSnapshot.tiers.keySet();
    }

    @Override
    public String source(String key) {
        return currentSnapshot.sources.getOrDefault(key, "<default-in-code>");
    }

    @Override
    public int size() {
        return currentSnapshot.values.size();
    }

    @Override
    public String activeSet() {
        return currentSnapshot.activeSet;
    }

    @Override
    public Map<String, String> snapshot(String prefix) {
        StrengthSnapshot snap = currentSnapshot;
        Map<String, String> out = new LinkedHashMap<>();
        for (String key : new TreeSet<>(snap.values.keySet())) {
            if (prefix == null || prefix.isEmpty() || key.startsWith(prefix)) {
                JsonElement val = snap.values.get(key);
                if (val != null) {
                    out.put(key, val.toString());
                }
            }
        }
        return out;
    }

    private void warnMissing(String key) {
        if (warned.add(key)) {
            LOGGER.warn("[uld] Missing strength key {} — using in-code default", key);
        }
    }

    public JsonObject rawSnapshotJson() {
        StrengthSnapshot snap = currentSnapshot;
        JsonObject root = new JsonObject();
        JsonObject values = new JsonObject();
        for (Map.Entry<String, JsonElement> e : snap.values.entrySet()) {
            values.add(e.getKey(), e.getValue());
        }
        root.add("values", values);
        JsonObject tiers = new JsonObject();
        for (Map.Entry<String, Map<String, JsonElement>> te : snap.tiers.entrySet()) {
            JsonObject levels = new JsonObject();
            for (Map.Entry<String, JsonElement> le : te.getValue().entrySet()) {
                levels.add(le.getKey(), le.getValue());
            }
            tiers.add(te.getKey(), levels);
        }
        root.add("tiers", tiers);
        return root;
    }

    public long computeConfigSignature() {
        long sig = 0L;
        File cfgDir = this.configDir;
        if (cfgDir != null) {
            File[] files = cfgDir.listFiles((d, name) -> name.endsWith(".json"));
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                for (File f : files) {
                    sig = sig * 31 + f.lastModified() + f.getName().hashCode();
                }
            }
        }
        File cfgFile = this.activeConfigFile;
        if (cfgFile != null && cfgFile.isFile()) {
            sig = sig * 31 + cfgFile.lastModified() + ACTIVE_CONFIG_NAME.hashCode();
        }
        File root = this.setsRoot;
        if (root != null) {
            for (File f : listJsonRecursive(root)) {
                sig = sig * 31 + f.lastModified() + f.getAbsolutePath().hashCode();
            }
        }
        return sig;
    }

    public void createOverrideTemplateIfMissing() {
        try {
            File cfgDir = this.configDir;
            if (cfgDir == null) {
                return;
            }
            File target = new File(cfgDir, "overrides.json");
            if (target.exists()) {
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

    // ---------------------------------------------------------------- 静态兼容垫片

    public static boolean hasKey(String key) {
        return DEFAULT_INSTANCE.has(key);
    }

    public static double numVal(String key, double def) {
        return DEFAULT_INSTANCE.num(key, def);
    }

    public static long configSignature() {
        return DEFAULT_INSTANCE.computeConfigSignature();
    }

    public static void writeOverrideTemplate() {
        DEFAULT_INSTANCE.createOverrideTemplateIfMissing();
    }

    public static JsonObject raw() {
        return DEFAULT_INSTANCE.rawSnapshotJson();
    }
}
