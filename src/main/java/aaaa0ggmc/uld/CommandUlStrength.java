package aaaa0ggmc.uld;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * /ulstrength —— 数值设计层的查看与热重载（OP）。
 *
 *   /ulstrength get &lt;key&gt;     生效值 + 来源
 *   /ulstrength list [prefix]  列出键值（前缀过滤）
 *   /ulstrength where &lt;key&gt;   该键生效值来自哪个文件
 *   /ulstrength tiers          档位表全量
 *   /ulstrength reload         手动热重载
 */
public class CommandUlStrength extends CommandBase {

    @Override
    public String getName() {
        return "ulstrength";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("ulst");
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/ulstrength <get|list|where|tiers|reload> [参数]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            throw new WrongUsageException(getUsage(sender));
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "get": {
                if (args.length < 2) {
                    throw new WrongUsageException("/ulstrength get <key>");
                }
                String key = args[1];
                if (!UlStrength.has(key)) {
                    throw new CommandException("Unknown key: " + key);
                }
                sender.sendMessage(new TextComponentString(key + " = " + UlStrength.snapshot(key).get(key)
                        + "  <- " + UlStrength.source(key)));
                break;
            }
            case "where": {
                if (args.length < 2) {
                    throw new WrongUsageException("/ulstrength where <key>");
                }
                sender.sendMessage(new TextComponentString(args[1] + " <- " + UlStrength.source(args[1])));
                break;
            }
            case "list": {
                String prefix = args.length > 1 ? args[1] : "";
                Map<String, String> all = UlStrength.snapshot(prefix);
                sender.sendMessage(new TextComponentString("Strength values (" + all.size() + ")"
                        + (prefix.isEmpty() ? "" : " prefix=" + prefix) + ":"));
                for (Map.Entry<String, String> e : all.entrySet()) {
                    sender.sendMessage(new TextComponentString("  " + e.getKey() + " = " + e.getValue()));
                }
                break;
            }
            case "tiers": {
                sender.sendMessage(new TextComponentString("Tiers:"));
                for (String tier : UlStrength.keys("tier:")) {
                    // tier 键在 snapshot 里不可见，直接按约定名打表
                }
                for (String name : new String[]{"damage", "health", "cooldown_ticks", "speed"}) {
                    StringBuilder sb = new StringBuilder("  " + name + ":");
                    for (String level : new String[]{"t1", "t2", "t3", "t4", "t5"}) {
                        sb.append(' ').append(level).append('=').append(UlStrength.tier(name, level));
                    }
                    sender.sendMessage(new TextComponentString(sb.toString()));
                }
                break;
            }
            case "reload": {
                UlStrength.reload();
                sender.sendMessage(new TextComponentString("Reloaded strength design (" + UlStrength.size() + " values)."));
                break;
            }
            default:
                throw new WrongUsageException(getUsage(sender));
        }
    }
}
