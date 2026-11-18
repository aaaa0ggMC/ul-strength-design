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
import java.util.Set;
import java.util.TreeSet;

/**
 * /ulstrength —— 数值设计层的查看与热重载（OP）。
 */
public class CommandUlStrength extends CommandBase {

    private final IStrengthService strength;

    public CommandUlStrength() {
        this(UlStrength.service());
    }

    public CommandUlStrength(IStrengthService strength) {
        this.strength = strength;
    }

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
                if (!strength.has(key)) {
                    throw new CommandException("Unknown key: " + key);
                }
                sender.sendMessage(new TextComponentString(key + " = " + strength.snapshot(key).get(key)
                        + "  <- " + strength.source(key)));
                break;
            }
            case "where": {
                if (args.length < 2) {
                    throw new WrongUsageException("/ulstrength where <key>");
                }
                sender.sendMessage(new TextComponentString(args[1] + " <- " + strength.source(args[1])));
                break;
            }
            case "list": {
                String prefix = args.length > 1 ? args[1] : "";
                Map<String, String> all = strength.snapshot(prefix);
                sender.sendMessage(new TextComponentString("Strength values (" + all.size() + ")"
                        + (prefix.isEmpty() ? "" : " prefix=" + prefix) + ":"));
                for (Map.Entry<String, String> e : all.entrySet()) {
                    sender.sendMessage(new TextComponentString("  " + e.getKey() + " = " + e.getValue()));
                }
                break;
            }
            case "tiers": {
                sender.sendMessage(new TextComponentString("Tiers:"));
                Set<String> tiers = new TreeSet<>(strength.tierNames());
                if (tiers.isEmpty()) {
                    Collections.addAll(tiers, "damage", "health", "cooldown_ticks", "speed");
                }
                for (String name : tiers) {
                    StringBuilder sb = new StringBuilder("  " + name + ":");
                    for (String level : new String[]{"t1", "t2", "t3", "t4", "t5"}) {
                        sb.append(' ').append(level).append('=').append(strength.tier(name, level));
                    }
                    sender.sendMessage(new TextComponentString(sb.toString()));
                }
                break;
            }
            case "reload": {
                strength.reload();
                sender.sendMessage(new TextComponentString("Reloaded strength design (" + strength.size()
                        + " values, set: " + strength.activeSet() + ")."));
                break;
            }
            default:
                throw new WrongUsageException(getUsage(sender));
        }
    }
}
