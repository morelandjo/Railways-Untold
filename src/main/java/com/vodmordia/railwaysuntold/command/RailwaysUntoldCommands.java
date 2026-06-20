package com.vodmordia.railwaysuntold.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.vodmordia.railwaysuntold.RailwaysUntold;
import com.vodmordia.railwaysuntold.worldgen.placement.AutoLoadController;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code /railways autoload <duration|off|status>} - drives the track-following chunk loader
 * ({@link AutoLoadController}) for a bounded session so generation proceeds hands-off without
 * brute-forcing a Chunky square. Op-only.
 */
@EventBusSubscriber(modid = RailwaysUntold.MODID)
public final class RailwaysUntoldCommands {

    private static final Pattern HMS = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$");

    private RailwaysUntoldCommands() {}

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("railways")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("autoload")
                        .then(Commands.literal("off").executes(RailwaysUntoldCommands::off))
                        .then(Commands.literal("status").executes(RailwaysUntoldCommands::status))
                        .then(Commands.argument("duration", StringArgumentType.word())
                                .executes(RailwaysUntoldCommands::enable))));
    }

    private static int off(CommandContext<CommandSourceStack> ctx) {
        AutoLoadController.disable(ctx.getSource().getServer(), "command");
        ctx.getSource().sendSuccess(() -> Component.literal("[RailwaysUntold] auto-load OFF"), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        ctx.getSource().sendSuccess(() -> Component.literal("[RailwaysUntold] " + AutoLoadController.status(server)), false);
        return 1;
    }

    private static int enable(CommandContext<CommandSourceStack> ctx) {
        String arg = StringArgumentType.getString(ctx, "duration");
        long ticks = parseDurationTicks(arg);
        if (ticks <= 0) {
            ctx.getSource().sendFailure(Component.literal(
                    "Invalid duration '" + arg + "'. Use e.g. 30m, 1h, 1h30m (bare number = minutes)."));
            return 0;
        }
        MinecraftServer server = ctx.getSource().getServer();
        AutoLoadController.enable(server, ticks);
        ctx.getSource().sendSuccess(() -> Component.literal("[RailwaysUntold] " + AutoLoadController.status(server)), true);
        return 1;
    }

    /** Parses {@code 30m / 1h / 1h30m / 90} (bare number = minutes) into ticks (20/sec). Returns 0 if unparseable. */
    static long parseDurationTicks(String s) {
        if (s == null || s.isBlank()) return 0;
        if (s.matches("\\d+")) {
            return Long.parseLong(s) * 60L * 20L; // bare number = minutes
        }
        Matcher m = HMS.matcher(s);
        if (!m.matches() || (m.group(1) == null && m.group(2) == null && m.group(3) == null)) {
            return 0;
        }
        long h = m.group(1) != null ? Long.parseLong(m.group(1)) : 0;
        long min = m.group(2) != null ? Long.parseLong(m.group(2)) : 0;
        long sec = m.group(3) != null ? Long.parseLong(m.group(3)) : 0;
        return ((h * 3600) + (min * 60) + sec) * 20L;
    }
}
