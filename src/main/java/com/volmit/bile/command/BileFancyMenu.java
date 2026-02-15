package com.volmit.bile.command;

import art.arcane.volmlib.util.director.visual.DirectorVisualCommand;
import art.arcane.volmlib.util.director.visual.DirectorVisualCommand.DirectorVisualParameter;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public final class BileFancyMenu {
    private static final int HELP_PAGE_SIZE = 17;
    private static final Map<String, String> HELP_CACHE = new ConcurrentHashMap<>();
    private static final Pattern MINI_TAG_PATTERN = Pattern.compile("<[^>]+>");

    private BileFancyMenu() {
    }

    public static boolean sendIfHelpRequested(CommandSender sender, DirectorVisualCommand root, String[] args) {
        Optional<DirectorVisualCommand.HelpRequest> request = DirectorVisualCommand.resolveHelp(root, Arrays.asList(args));
        if (request.isEmpty()) {
            return false;
        }

        sendDecreeHelp(sender, request.get().command(), request.get().page());
        return true;
    }

    public static void playTabSound(CommandSender sender) {
        if (sender instanceof Player player) {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.25f, randomPitch(0.125f, 1.95f));
        }
    }

    public static void playSuccessSound(CommandSender sender) {
        if (sender instanceof Player player) {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.77f, 1.65f);
            player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.125f, 2.99f);
        }
    }

    public static void playFailureSound(CommandSender sender) {
        if (sender instanceof Player player) {
            player.playSound(player.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.77f, 0.25f);
            player.playSound(player.getLocation(), Sound.BLOCK_BEACON_DEACTIVATE, 0.2f, 0.45f);
        }
    }

    private static float randomPitch(float min, float max) {
        return (float) ThreadLocalRandom.current().nextDouble(min, max);
    }

    private static void sendDecreeHelp(CommandSender sender, DirectorVisualCommand v, int page) {
        if (!(sender instanceof Player)) {
            for (DirectorVisualCommand i : v.getNodes()) {
                sendDecreeHelpNode(sender, i);
            }

            return;
        }

        sendRaw(sender, "\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n");

        if (v.getNodes().isNotEmpty()) {
            sendHeader(sender, v.getPath() + (page > 0 ? (" {" + (page + 1) + "}") : ""));
            if (v.getParent() != null) {
                sendRaw(sender, "<hover:show_text:'" + "<#2b7a3f>Click to go back to <#32bfad>" + capitalize(v.getParent().getName()) + " Help" + "'><click:run_command:" + v.getParent().getPath() + "><font:minecraft:uniform><#6fe98f>〈 Back</click></hover>");
            }

            AtomicBoolean next = new AtomicBoolean(false);
            for (DirectorVisualCommand i : paginate(v.getNodes(), HELP_PAGE_SIZE, page, next)) {
                sendDecreeHelpNode(sender, i);
            }

            String footer = "";
            int lineLength = 75 - (page > 0 ? 10 : 0) - (next.get() ? 10 : 0);
            if (page > 0) {
                footer += "<hover:show_text:'<green>Click to go back to page " + page + "'><click:run_command:" + v.getPath() + " help=" + page + "><gradient:#34eb6b:#1f8f4d>〈 Page " + page + "</click></hover><reset> ";
            }

            footer += "<reset><font:minecraft:uniform><strikethrough><gradient:#32bfad:#34eb6b>" + " ".repeat(Math.max(0, lineLength)) + "<reset>";
            if (next.get()) {
                footer += " <hover:show_text:'<green>Click to go to back to page " + (page + 2) + "'><click:run_command:" + v.getPath() + " help=" + (page + 2) + "><gradient:#1f8f4d:#34eb6b>Page " + (page + 2) + " ❭</click></hover>";
            }

            sendRaw(sender, footer);
        } else {
            sendRaw(sender, "<red>There are no subcommands in this group! Contact support, this is a command design issue!");
        }
    }

    private static void sendDecreeHelpNode(CommandSender sender, DirectorVisualCommand i) {
        if (sender instanceof Player) {
            sendRaw(sender, HELP_CACHE.computeIfAbsent(i.getPath(), (k) -> {
                String newline = "<reset>\n";

                String realText = i.getPath() + " >" + "<#46826a>⇀<gradient:#5ef288:#32bfad> " + i.getName();
                String hoverTitle = i.getNames().copy().reverse().convert((f) -> "<#5ef288>" + f).toString(", ");
                String description = "<#3fe05a>✎ <#6ad97d><font:minecraft:uniform>" + i.getDescription();
                String usage = "<#bbe03f>✒ <#a8e0a2><font:minecraft:uniform>";
                String onClick;
                if (i.isNode()) {
                    if (i.getNode().getParameters().isEmpty()) {
                        usage += "There are no parameters. Click to type command.";
                        onClick = "suggest_command";
                    } else {
                        usage += "Hover over all of the parameters to learn more.";
                        onClick = "suggest_command";
                    }
                } else {
                    usage += "This is a command category. Click to run.";
                    onClick = "run_command";
                }

                String suggestion = "";
                String suggestions = "";
                if (i.isNode() && i.getNode().getParameters().isNotEmpty()) {
                    suggestion += newline + "<#c2f7d2>✦ <#5ef288><font:minecraft:uniform>" + i.getParentPath() + " <#5ef288>" + i.getName() + " "
                            + i.getNode().getParameters().convert((f) -> "<#5ef288>" + f.example()).toString(" ");
                    suggestions += newline + "<font:minecraft:uniform>" + pickRandoms(Math.min(i.getNode().getParameters().size() + 1, 5), i);
                }

                StringBuilder nodes = new StringBuilder();
                if (i.isNode()) {
                    for (DirectorVisualParameter p : i.getNode().getParameters()) {
                        String nTitle = "<gradient:#5ef288:#32bfad>" + p.getName();
                        String nHoverTitle = p.getNames().convert((ff) -> "<#5ef288>" + ff).toString(", ");
                        String nDescription = "<#3fe05a>✎ <#6ad97d><font:minecraft:uniform>" + p.getDescription();
                        String nUsage;
                        String fullTitle;
                        if (p.isContextual()) {
                            fullTitle = "<#ffcc00>[" + nTitle + "<#ffcc00>] ";
                            nUsage = "<#ff9900>➱ <#ffcc00><font:minecraft:uniform>The value may be derived from environment context.";
                        } else if (p.isRequired()) {
                            fullTitle = "<red>[" + nTitle + "<red>] ";
                            nUsage = "<#db4321>⚠ <#faa796><font:minecraft:uniform>This parameter is required.";
                        } else if (p.hasDefault()) {
                            fullTitle = "<#4f4f4f>⊰" + nTitle + "<#4f4f4f>⊱";
                            nUsage = "<#3fbe6f>✔ <#9de5b6><font:minecraft:uniform>Defaults to \"" + p.getParam().defaultValue() + "\" if undefined.";
                        } else {
                            fullTitle = "<#4f4f4f>⊰" + nTitle + "<#4f4f4f>⊱";
                            nUsage = "<#3fbe6f>✔ <#9de5b6><font:minecraft:uniform>This parameter is optional.";
                        }
                        String type = "<#4fbf7f>✢ <#8ad9af><font:minecraft:uniform>This parameter is of type " + p.getType().getSimpleName() + ".";

                        nodes
                                .append("<hover:show_text:'")
                                .append(nHoverTitle).append(newline)
                                .append(nDescription).append(newline)
                                .append(nUsage).append(newline)
                                .append(type)
                                .append("'>")
                                .append(fullTitle)
                                .append("</hover>");
                    }
                } else {
                    nodes = new StringBuilder("<gradient:#b7eecb:#9de5b6> - Category of Commands");
                }

                return "<hover:show_text:'" +
                        hoverTitle + newline +
                        description + newline +
                        usage +
                        suggestion +
                        suggestions +
                        "'>" +
                        "<click:" +
                        onClick +
                        ":" +
                        realText +
                        "</click>" +
                        "</hover>" +
                        " " +
                        nodes;
            }));
        } else {
            sender.sendMessage(i.getPath());
        }
    }

    private static String pickRandoms(int max, DirectorVisualCommand i) {
        List<String> out = new ArrayList<>();
        for (int ix = 0; ix < max; ix++) {
            if (!i.isNode() || i.getNode().getParameters().isEmpty()) {
                continue;
            }

            List<DirectorVisualParameter> shuffled = new ArrayList<>(i.getNode().getParameters());
            java.util.Collections.shuffle(shuffled);
            List<String> parts = new ArrayList<>();
            for (DirectorVisualParameter parameter : shuffled) {
                if (parameter.isRequired() || ThreadLocalRandom.current().nextBoolean()) {
                    String key = randomElement(parameter.getNames());
                    parts.add("<#f2e15e>" + key + "=" + "<#5ef288>" + parameter.example());
                }
            }

            if (!parts.isEmpty()) {
                out.add("<#c2f7d2>✦ <#5ef288>" + i.getParentPath() + " <#5ef288>" + i.getName() + " " + String.join(" ", parts));
            }
        }

        List<String> deduped = out.stream().map(s -> s.replace("  ", " ")).distinct().toList();
        return String.join("\n", deduped);
    }

    private static <T> T randomElement(List<T> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }

        return values.get(ThreadLocalRandom.current().nextInt(values.size()));
    }

    private static <T> List<T> paginate(List<T> all, int linesPerPage, int page, AtomicBoolean hasNext) {
        int totalPages = (int) Math.ceil((double) all.size() / linesPerPage);
        page = page < 0 ? 0 : page >= totalPages ? Math.max(0, totalPages - 1) : page;
        hasNext.set(page < totalPages - 1);

        List<T> out = new ArrayList<>();
        for (int i = linesPerPage * page; i < Math.min(all.size(), linesPerPage * (page + 1)); i++) {
            out.add(all.get(i));
        }

        return out;
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static void sendHeader(CommandSender sender, String name) {
        sendHeader(sender, name, 44);
    }

    private static void sendHeader(CommandSender sender, String name, int overrideLength) {
        int len = overrideLength;
        int h = name.length() + 2;
        String s = " ".repeat(Math.max(0, len - h - 4));
        String si = "(".repeat(3);
        String so = ")".repeat(3);
        String sf = "[";
        String se = "]";

        if (name.trim().isEmpty()) {
            sendRaw(sender, "<font:minecraft:uniform><strikethrough><gradient:#34eb6b:#32bfad>" + sf + s + "<reset><font:minecraft:uniform><strikethrough><gradient:#32bfad:#34eb6b>" + s + se);
        } else {
            sendRaw(sender, "<font:minecraft:uniform><strikethrough><gradient:#34eb6b:#32bfad>" + sf + s + si + "<reset> <gradient:#32bfad:#1f8f4d>" + name + "<reset> <font:minecraft:uniform><strikethrough><gradient:#32bfad:#34eb6b>" + so + s + se);
        }
    }

    private static void sendRaw(CommandSender sender, String miniMessage) {
        if (miniMessage == null || miniMessage.trim().isEmpty()) {
            return;
        }

        try {
            sender.getClass().getMethod("sendRichMessage", String.class).invoke(sender, miniMessage);
        } catch (Throwable ignored) {
            sender.sendMessage(stripMini(miniMessage));
        }
    }

    private static String stripMini(String miniMessage) {
        return MINI_TAG_PATTERN.matcher(miniMessage).replaceAll("");
    }
}
