package aaaa0ggmc.uld;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * UL Strength Design —— 整合包数值设计层（组合根）。
 */
@Mod(
        modid = StrengthDesign.MODID,
        name = StrengthDesign.NAME,
        version = StrengthDesign.VERSION,
        acceptableRemoteVersions = "*"
)
public class StrengthDesign {
    public static final String MODID = "ul_strength_design";
    public static final String NAME = "UL Strength Design";
    public static final String VERSION = "1.0.0";
    public static final Logger LOGGER = LogManager.getLogger(NAME);

    @Mod.Instance(MODID)
    public static StrengthDesign INSTANCE;

    private final DesignRegistry strengthService = DesignRegistry.defaultInstance();
    private ScheduledExecutorService reloadWatcher;
    private volatile long lastSignature = Long.MIN_VALUE;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        strengthService.initialize(event.getModConfigurationDirectory());
        strengthService.createOverrideTemplateIfMissing();
        UlStrength.setService(strengthService);
        LOGGER.info("[uld] strength design online: {} values", strengthService.size());
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandUlStrength(strengthService));
        startReloadWatcher();
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        stopReloadWatcher();
    }

    private synchronized void startReloadWatcher() {
        stopReloadWatcher();
        lastSignature = strengthService.computeConfigSignature();
        reloadWatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ul-strength-reload-watcher");
            t.setDaemon(true);
            return t;
        });
        reloadWatcher.scheduleWithFixedDelay(() -> {
            try {
                long sig = strengthService.computeConfigSignature();
                if (sig != lastSignature) {
                    if (lastSignature != Long.MIN_VALUE) {
                        LOGGER.info("[uld] strength config changed — reloading");
                        strengthService.reload();
                    }
                    lastSignature = sig;
                }
            } catch (Throwable t) {
                LOGGER.warn("[uld] reload watcher error", t);
            }
        }, 5L, 5L, TimeUnit.SECONDS);
    }

    private synchronized void stopReloadWatcher() {
        if (reloadWatcher != null) {
            reloadWatcher.shutdownNow();
            reloadWatcher = null;
        }
    }
}
