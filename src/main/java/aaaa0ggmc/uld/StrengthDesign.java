package aaaa0ggmc.uld;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * UL Strength Design —— 整合包数值设计层。
 *
 * 所有内容/适配模组（ul-modpack、morphext-patch-*、ul-finders…）通过
 * {@link UlStrength} 取数值；对齐时只改 config/ul_strength_design/*.json，
 * 本模组每 5 秒检测 mtime 自动热重载，也可 /ulstrength reload。
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

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        DesignRegistry.init(event.getModConfigurationDirectory());
        DesignRegistry.writeOverrideTemplate();
        LOGGER.info("[uld] strength design online: {} values", UlStrength.size());
    }

    @Mod.EventHandler
    public void serverStarting(net.minecraftforge.fml.common.event.FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandUlStrength());
    }

    /** config 覆盖文件改动自动热重载（5 秒轮询 mtime，改 JSON 不用重启）。 */
    @Mod.EventBusSubscriber(modid = MODID)
    public static final class ReloadWatcher {
        private static long lastSignature = Long.MIN_VALUE;
        private static int tickCounter;

        private ReloadWatcher() {
        }

        @SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.START) {
                return;
            }
            if (++tickCounter % 100 != 0) { // 100 tick = 5 秒
                return;
            }
            long sig = DesignRegistry.configSignature();
            if (sig != lastSignature) {
                if (lastSignature != Long.MIN_VALUE) {
                    LOGGER.info("[uld] strength config changed — reloading");
                    DesignRegistry.reload();
                }
                lastSignature = sig;
            }
        }
    }
}
