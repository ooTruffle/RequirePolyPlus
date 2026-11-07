package dev.requirepolyplus;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import org.polyfrost.oneclient.api.OneClientPlayer;

import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiConsumer;

/**
 * /requirepolyplus (short: /rpp): in-game settings for Require Poly+ and the OneClient Server-API. Ops only.
 * Every change is saved to config/require-polyplus.properties right away.
 * Not called /polyplus: Poly+ has a client-side /polyplus command, and client commands win over server ones.
 */
final class PolyPlusCommand {
    private PolyPlusCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, RequirePolyPlus mod) {
        for (String name : new String[]{"requirepolyplus", "rpp"}) {
            dispatcher.register(build(name, mod));
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name, RequirePolyPlus mod) {
        return Commands.literal(name)
            .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
            .executes(ctx -> help(ctx.getSource()))
            .then(Commands.literal("list").executes(ctx -> list(ctx.getSource(), mod)))
            .then(Commands.literal("info")
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> info(ctx.getSource(), mod, EntityArgument.getPlayer(ctx, "player")))))
            .then(Commands.literal("reload").executes(ctx -> {
                mod.reload(ctx.getSource().getServer());
                return ok(ctx.getSource(), "Reloaded config/require-polyplus.properties");
            }))
            .then(Commands.literal("settings").executes(ctx -> settings(ctx.getSource(), mod)))
            .then(setCommands(mod))
            .then(exemptCommands(mod));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> setCommands(RequirePolyPlus mod) {
        return Commands.literal("set")
            .then(Commands.literal("required")
                .then(Commands.argument("value", BoolArgumentType.bool()).executes(ctx -> {
                    mod.config().kickEnabled = BoolArgumentType.getBool(ctx, "value");
                    mod.settingsChanged(ctx.getSource().getServer());
                    return ok(ctx.getSource(), mod.config().kickEnabled
                        ? "Poly+ is now required. Players without it get kicked after " + mod.config().timeoutSeconds + "s"
                        : "Poly+ is no longer required");
                })))
            .then(Commands.literal("timeout")
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600)).executes(ctx -> {
                    mod.config().timeoutSeconds = IntegerArgumentType.getInteger(ctx, "seconds");
                    mod.settingsChanged(ctx.getSource().getServer());
                    return ok(ctx.getSource(), "Players now have " + mod.config().timeoutSeconds + "s to identify as Poly+ (applies to new joins)");
                })))
            .then(textSetting("kickmessage", false, "Kick message", mod, (config, text) -> config.kickMessage = text))
            .then(textSetting("kickbroadcast", true, "Kick broadcast", mod, (config, text) -> config.kickBroadcast = text))
            .then(textSetting("outdatedmessage", false, "Outdated message", mod, (config, text) -> config.outdatedMessage = text))
            .then(textSetting("welcome", true, "Welcome message", mod, (config, text) -> config.welcomeMessage = text))
            .then(Commands.literal("minversion")
                .executes(ctx -> {
                    mod.config().minVersion = "";
                    mod.settingsChanged(ctx.getSource().getServer());
                    return ok(ctx.getSource(), "Any Poly+ version is allowed now");
                })
                .then(Commands.argument("version", StringArgumentType.word()).executes(ctx -> {
                    mod.config().minVersion = StringArgumentType.getString(ctx, "version");
                    mod.settingsChanged(ctx.getSource().getServer());
                    return ok(ctx.getSource(), "Poly+ " + mod.config().minVersion + " or newer is required now (applies to new joins)");
                })))
            .then(Commands.literal("tag")
                .executes(ctx -> setTag(ctx, mod, ""))
                .then(Commands.argument("name", StringArgumentType.word())
                    .executes(ctx -> setTag(ctx, mod, StringArgumentType.getString(ctx, "name")))));
    }

    /** /rpp set <name> <text...>; with {@code clearable}, leaving the text out turns it off. */
    private static LiteralArgumentBuilder<CommandSourceStack> textSetting(String name, boolean clearable, String label,
                                                                          RequirePolyPlus mod, BiConsumer<Config, String> setter) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(name)
            .then(Commands.argument("text", StringArgumentType.greedyString()).executes(ctx -> {
                String text = StringArgumentType.getString(ctx, "text");
                setter.accept(mod.config(), text);
                mod.settingsChanged(ctx.getSource().getServer());
                ctx.getSource().sendSuccess(() -> Component.literal(label + " set to:\n").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(Config.format(text)).withStyle(ChatFormatting.RESET)), true);
                return 1;
            }));
        if (clearable) {
            node.executes(ctx -> {
                setter.accept(mod.config(), "");
                mod.settingsChanged(ctx.getSource().getServer());
                return ok(ctx.getSource(), label + " turned off");
            });
        }
        return node;
    }

    private static int setTag(CommandContext<CommandSourceStack> ctx, RequirePolyPlus mod, String tag) {
        String oldTag = mod.config().tag;
        mod.config().tag = tag;
        mod.settingsChanged(ctx.getSource().getServer());
        mod.tagChanged(ctx.getSource().getServer(), oldTag);
        return ok(ctx.getSource(), tag.isEmpty()
            ? "Poly+ players no longer get a tag"
            : "Poly+ players now get the tag \"" + tag + "\" (use @a[tag=" + tag + "] in commands)");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> exemptCommands(RequirePolyPlus mod) {
        return Commands.literal("exempt")
            .executes(ctx -> exemptList(ctx.getSource(), mod))
            .then(Commands.literal("list").executes(ctx -> exemptList(ctx.getSource(), mod)))
            .then(Commands.literal("add")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ctx.getSource().getOnlinePlayerNames(), builder))
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                        if (!mod.config().exempt.add(name)) {
                            return fail(ctx.getSource(), name + " is already exempt");
                        }
                        mod.settingsChanged(ctx.getSource().getServer());
                        return ok(ctx.getSource(), name + " can now join without Poly+");
                    })))
            .then(Commands.literal("remove")
                .then(Commands.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(mod.config().exempt, builder))
                    .executes(ctx -> {
                        String name = StringArgumentType.getString(ctx, "name").toLowerCase(Locale.ROOT);
                        if (!mod.config().exempt.remove(name)) {
                            return fail(ctx.getSource(), name + " isn't exempt");
                        }
                        mod.settingsChanged(ctx.getSource().getServer());
                        return ok(ctx.getSource(), name + " needs Poly+ again");
                    })));
    }

    private static int help(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Require Poly+ commands (/requirepolyplus works too):").withStyle(ChatFormatting.AQUA)
            .append(line("/rpp list", "everyone online and their client"))
            .append(line("/rpp info <player>", "a player's client, brand and mod channels"))
            .append(line("/rpp settings", "show all settings"))
            .append(line("/rpp set required <true|false>", "kick players without Poly+"))
            .append(line("/rpp set timeout <seconds>", "time to identify before the kick"))
            .append(line("/rpp set kickmessage <text>", "message for players without Poly+"))
            .append(line("/rpp set kickbroadcast [text]", "chat message when someone is kicked (blank = off)"))
            .append(line("/rpp set minversion [version]", "oldest Poly+ allowed (blank = any)"))
            .append(line("/rpp set outdatedmessage <text>", "message for outdated Poly+"))
            .append(line("/rpp set tag [name]", "tag for Poly+ players (blank = off)"))
            .append(line("/rpp set welcome [text]", "message to Poly+ players (blank = off)"))
            .append(line("/rpp exempt add|remove|list", "players allowed without Poly+"))
            .append(line("/rpp reload", "re-read the config file"))
            .append(Component.literal("\nIn messages, \\n makes a new line and & makes colors (&a, &l...). "
                + "%player%, %version%, %min% and %client% are filled in.").withStyle(ChatFormatting.GRAY)), false);
        return 1;
    }

    private static MutableComponent line(String command, String description) {
        return Component.literal("\n" + command).withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(" - " + description).withStyle(ChatFormatting.GRAY));
    }

    private static int list(CommandSourceStack source, RequirePolyPlus mod) {
        Collection<ServerPlayer> online = source.getServer().getPlayerList().getPlayers();
        int polyCount = mod.api().getPlayers().size();
        MutableComponent msg = Component.literal("Players online: " + online.size() + " (" + polyCount + " on Poly+)")
            .withStyle(ChatFormatting.AQUA);
        for (ServerPlayer player : online) {
            boolean poly = mod.api().isOneClient(player.getUUID());
            msg.append(Component.literal("\n - " + player.getName().getString()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" " + ClientDetector.detect(player, mod.api()))
                    .withStyle(poly ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        source.sendSuccess(() -> msg, false);
        return polyCount;
    }

    private static int info(CommandSourceStack source, RequirePolyPlus mod, ServerPlayer player) throws CommandSyntaxException {
        String name = player.getName().getString();
        Optional<OneClientPlayer> poly = mod.api().getPlayer(player.getUUID());
        MutableComponent msg = Component.literal(name + ": ").withStyle(ChatFormatting.AQUA);
        if (poly.isPresent()) {
            OneClientPlayer p = poly.get();
            long seconds = (System.currentTimeMillis() - p.getJoinedAtMillis()) / 1000;
            msg.append(Component.literal("using Poly+ " + p.getClientVersion()).withStyle(ChatFormatting.GREEN))
                .append(Component.literal("\n Minecraft " + p.getMinecraftVersion()
                    + "\n API protocol " + p.getProtocolVersion()
                    + "\n Identified " + seconds / 60 + "m " + seconds % 60 + "s ago"
                    + (mod.config().meetsMinVersion(p.getClientVersion()) ? "" : "\n Older than the minimum " + mod.config().minVersion))
                    .withStyle(ChatFormatting.GRAY));
        } else {
            msg.append(Component.literal("not using Poly+").withStyle(ChatFormatting.RED))
                .append(Component.literal("\n Looks like: " + ClientDetector.detect(player, mod.api())).withStyle(ChatFormatting.WHITE));
        }
        String brand = ClientDetector.brand(player.getUUID());
        java.util.Set<String> channels = ClientDetector.interestingChannels(player);
        msg.append(Component.literal("\n Brand: " + (brand == null ? "none sent" : brand)
                + "\n Mod channels: " + (channels.isEmpty() ? "none" : String.join(", ", channels)))
            .withStyle(ChatFormatting.GRAY));
        if (mod.isExempt(player)) {
            msg.append(Component.literal("\n Exempt: can play without Poly+").withStyle(ChatFormatting.YELLOW));
        }
        source.sendSuccess(() -> msg, false);
        return poly.isPresent() ? 1 : 0;
    }

    private static int settings(CommandSourceStack source, RequirePolyPlus mod) {
        Config c = mod.config();
        source.sendSuccess(() -> Component.literal("Require Poly+ settings:").withStyle(ChatFormatting.AQUA)
            .append(setting("required", String.valueOf(c.kickEnabled)))
            .append(setting("timeout", c.timeoutSeconds + "s"))
            .append(setting("kickmessage", c.kickMessage))
            .append(setting("kickbroadcast", c.kickBroadcast.isEmpty() ? "off" : c.kickBroadcast))
            .append(setting("minversion", c.minVersion.isEmpty() ? "any" : c.minVersion))
            .append(setting("outdatedmessage", c.outdatedMessage))
            .append(setting("tag", c.tag.isEmpty() ? "off" : c.tag))
            .append(setting("welcome", c.welcomeMessage.isEmpty() ? "off" : c.welcomeMessage))
            .append(setting("exempt", c.exempt.isEmpty() ? "nobody" : String.join(", ", c.exempt))), false);
        return 1;
    }

    private static MutableComponent setting(String name, String value) {
        return Component.literal("\n " + name + ": ").withStyle(ChatFormatting.YELLOW)
            .append(Component.literal(value).withStyle(ChatFormatting.WHITE));
    }

    private static int exemptList(CommandSourceStack source, RequirePolyPlus mod) {
        Collection<String> exempt = mod.config().exempt;
        return ok(source, exempt.isEmpty() ? "Nobody is exempt" : "Exempt: " + String.join(", ", exempt));
    }

    private static int ok(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message).withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
