package aaaa0ggmc.uld;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

/**
 * DesignRegistry 并发 reload() + snapshot() 压测（无 Forge 依赖）。
 */
public class DesignRegistryConcurrencyTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void concurrentReloadAndSnapshotIsAtomicAndNpeFree() throws Exception {
        File configDir = temp.newFolder("config");
        File modCfg = new File(configDir, "ul_strength_design");
        assertTrue(modCfg.mkdirs());
        File overrides = new File(modCfg, "overrides.json");
        Files.write(overrides.toPath(),
                "{\"values\":{\"zombie_tide.horde.persuasion_start\": 120}}".getBytes(StandardCharsets.UTF_8));

        DesignRegistry registry = new DesignRegistry();
        registry.initialize(configDir);

        assertEquals(120.0D, registry.num("zombie_tide.horde.persuasion_start", 100.0D), 0.001D);

        final AtomicBoolean stop = new AtomicBoolean(false);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        List<Callable<Void>> tasks = new ArrayList<>();

        final Object writeLock = new Object();
        for (int w = 0; w < 2; w++) {
            final int id = w;
            tasks.add(() -> {
                int iter = 0;
                while (!stop.get()) {
                    double val = 100.0D + ((iter + id) % 50);
                    String json = "{\"values\":{\"zombie_tide.horde.persuasion_start\": " + val + "}}";
                    synchronized (writeLock) {
                        Files.write(overrides.toPath(), json.getBytes(StandardCharsets.UTF_8));
                        registry.reload();
                    }
                    iter++;
                }
                return null;
            });
        }

        for (int r = 0; r < 4; r++) {
            tasks.add(() -> {
                while (!stop.get()) {
                    java.util.Map<String, String> snap = registry.snapshot("");
                    assertNotNull(snap);
                    assertTrue(snap.containsKey("zombie_tide.horde.persuasion_start"));
                    double v = registry.num("zombie_tide.horde.persuasion_start", 100.0D);
                    assertTrue("Unexpected value: " + v, v >= 100.0D && v <= 150.0D);
                }
                return null;
            });
        }

        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> task : tasks) {
            futures.add(pool.submit(task));
        }

        Thread.sleep(400L);
        stop.set(true);

        for (Future<Void> f : futures) {
            f.get();
        }
        pool.shutdownNow();
    }
}
