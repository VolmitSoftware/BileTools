package com.volmit.bile.config;

import art.arcane.volmlib.util.localization.LanguageAudience;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.volmlib.util.plugin.ComponentMessenger;
import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import com.volmit.bile.BileTools;
import com.volmit.bile.PlatformTasks;
import com.volmit.bile.localization.BileMessages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;

public final class BileConfigEditor implements Listener {
    private static final int SIZE = 54;
    private static final int BACK_SLOT = 45;
    private static final int CLOSE_SLOT = 53;
    private static final int[] CATEGORY_SLOTS = {19, 21, 23, 25, 28, 30, 32, 34};
    private static final long PROMPT_TICKS = 20L * 60L;
    private static final int MAXIMUM_INPUT_LENGTH = 512;

    private final BileTools plugin;
    private final List<Setting> settings;
    private final Map<UUID, PromptSession> prompts = new ConcurrentHashMap<>();
    private final ExecutorService writer;

    public BileConfigEditor(BileTools plugin) {
        this.plugin = plugin;
        settings = createSettings();
        writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BileTools-Config-Editor");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void open(Player player) {
        PlatformTasks.runForPlayer(plugin, player, () -> openRootOwned(player));
    }

    public void shutdown() {
        prompts.clear();
        writer.shutdownNow();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof EditorHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() < 0 || event.getRawSlot() >= SIZE) {
            return;
        }
        if (!player.hasPermission("biletools.config")) {
            player.closeInventory();
            send(player, BileMessages.PERMISSION_DENIED,
                    MessageArgs.builder().untrusted("permission", "biletools.config").build());
            return;
        }
        LanguageAudience.run(player.getUniqueId(), () -> handleClick(player, holder, event));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PromptSession prompt = prompts.remove(player.getUniqueId());
        if (prompt == null) {
            return;
        }
        event.setCancelled(true);
        FoliaScheduler.runEntity(plugin, player, () -> processPrompt(player, prompt, event.getMessage()), 0L,
                () -> prompts.remove(player.getUniqueId(), prompt));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId());
    }

    private void handleClick(Player player, EditorHolder holder, InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (holder.category == null) {
            Category category = categoryAt(slot);
            if (category == null) {
                return;
            }
            if (category == Category.LANGUAGES) {
                plugin.languageSwitcher().openEditor(player, this::openRootOwned);
            } else {
                openCategoryOwned(player, category);
            }
            return;
        }
        if (slot == BACK_SLOT) {
            openRootOwned(player);
            return;
        }
        List<Setting> entries = settings(holder.category);
        int settingIndex = settingSlots(entries.size()).indexOf(slot);
        if (settingIndex >= 0) {
            handleSetting(player, holder.category, entries.get(settingIndex), event);
        }
    }

    private void openRootOwned(Player player) {
        prompts.remove(player.getUniqueId());
        EditorHolder holder = new EditorHolder(null);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, legacy(player, BileMessages.GUI_ROOT_TITLE));
        holder.inventory = inventory;
        fill(inventory);
        Category[] categories = Category.values();
        for (int index = 0; index < categories.length; index++) {
            Category category = categories[index];
            inventory.setItem(CATEGORY_SLOTS[index], item(category.material,
                    legacy(player, category.name), List.of(legacy(player, BileMessages.GUI_CATEGORY_OPEN))));
        }
        navigation(player, inventory, false);
        player.openInventory(inventory);
    }

    private void openCategoryOwned(Player player, Category category) {
        EditorHolder holder = new EditorHolder(category);
        MessageArgs arguments = MessageArgs.builder().trusted("category", text(player, category.name)).build();
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                legacy(player, BileMessages.GUI_CATEGORY_TITLE, arguments));
        holder.inventory = inventory;
        fill(inventory);
        BileConfig config = BileTools.cfg;
        List<Setting> entries = settings(category);
        List<Integer> slots = settingSlots(entries.size());
        for (int index = 0; index < entries.size(); index++) {
            inventory.setItem(slots.get(index), settingItem(player, entries.get(index), config));
        }
        navigation(player, inventory, true);
        player.openInventory(inventory);
    }

    private void handleSetting(Player player, Category category, Setting setting, InventoryClickEvent event) {
        if (setting.kind == SettingKind.FILE_ONLY) {
            return;
        }
        if (setting.kind == SettingKind.LOCALE) {
            player.closeInventory();
            plugin.languageSwitcher().command(player, new String[]{"server"});
            return;
        }
        if (setting.kind == SettingKind.BOOLEAN) {
            saveMutation(player, category, setting, builder ->
                    setting.writer.write(builder, Boolean.toString(!Boolean.parseBoolean(setting.reader.apply(BileTools.cfg)))));
            return;
        }
        if (setting.kind.numeric() && !isPromptClick(event.getClick())) {
            double direction = event.isRightClick() ? -1D : 1D;
            double multiplier = event.isShiftClick() ? 10D : 1D;
            String adjusted = adjust(setting, setting.reader.apply(BileTools.cfg), direction * multiplier);
            saveMutation(player, category, setting, builder -> setting.writer.write(builder, adjusted));
            return;
        }
        beginPrompt(player, category, setting);
    }

    private void beginPrompt(Player player, Category category, Setting setting) {
        PromptSession prompt = new PromptSession(category, setting);
        prompts.put(player.getUniqueId(), prompt);
        player.closeInventory();
        send(player, BileMessages.GUI_PROMPT,
                MessageArgs.builder().untrusted("setting", name(player, setting)).build());
        if (setting.kind == SettingKind.LIST) {
            send(player, BileMessages.GUI_PROMPT_LIST, MessageArgs.empty());
        }
        send(player, BileMessages.GUI_PROMPT_CANCEL, MessageArgs.empty());
        boolean scheduled = FoliaScheduler.runEntity(plugin, player,
                () -> expirePrompt(player, prompt), PROMPT_TICKS,
                () -> prompts.remove(player.getUniqueId(), prompt));
        if (!scheduled) {
            prompts.remove(player.getUniqueId(), prompt);
            send(player, BileMessages.GUI_PROMPT_CANCELLED, MessageArgs.empty());
        }
    }

    private void processPrompt(Player player, PromptSession prompt, String input) {
        if (input.equalsIgnoreCase("cancel")) {
            send(player, BileMessages.GUI_PROMPT_CANCELLED, MessageArgs.empty());
            openCategoryOwned(player, prompt.category);
            return;
        }
        if (input.length() > MAXIMUM_INPUT_LENGTH) {
            failure(player, prompt.category, prompt.setting, "the value is longer than 512 characters");
            return;
        }
        saveMutation(player, prompt.category, prompt.setting,
                builder -> prompt.setting.writer.write(builder, input));
    }

    private void expirePrompt(Player player, PromptSession prompt) {
        if (!prompts.remove(player.getUniqueId(), prompt)) {
            return;
        }
        send(player, BileMessages.GUI_PROMPT_TIMEOUT, MessageArgs.empty());
        openCategoryOwned(player, prompt.category);
    }

    private void saveMutation(Player player, Category category, Setting setting, Consumer<BileConfig.Builder> mutation) {
        try {
            writer.execute(() -> saveOffThread(player, category, setting, mutation));
        } catch (RejectedExecutionException exception) {
            failure(player, category, setting, "the editor is shutting down");
        }
    }

    private void saveOffThread(Player player, Category category, Setting setting, Consumer<BileConfig.Builder> mutation) {
        try {
            BileConfig current = BileTools.cfg;
            String before = display(setting.reader.apply(current));
            BileConfig.Builder builder = current.toBuilder();
            mutation.accept(builder);
            BileConfig updated = builder.build();
            String after = display(setting.reader.apply(updated));
            plugin.applyConfiguration(updated);
            schedule(player, () -> {
                sendSingleLine(player, BileMessages.CHANGE_SAVED, MessageArgs.builder()
                        .untrusted("setting", name(player, setting))
                        .untrusted("new", compactChangeValue(after))
                        .untrusted("old", compactChangeValue(before))
                        .build());
                openCategoryOwned(player, category);
            });
        } catch (Exception exception) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save BileTools setting " + setting.path, exception);
            schedule(player, () -> failureOwned(player, category, setting, reason(exception)));
        }
    }

    private void failure(Player player, Category category, Setting setting, String reason) {
        schedule(player, () -> failureOwned(player, category, setting, reason));
    }

    private void failureOwned(Player player, Category category, Setting setting, String reason) {
        send(player, BileMessages.CONFIG_SAVE_FAILED, MessageArgs.builder()
                .untrusted("setting", name(player, setting)).untrusted("reason", reason).build());
        openCategoryOwned(player, category);
    }

    private void schedule(Player player, Runnable result) {
        PlatformTasks.runForPlayer(plugin, player, result);
    }

    private ItemStack settingItem(Player player, Setting setting, BileConfig config) {
        String value = setting.kind == SettingKind.FILE_ONLY ? "redacted" : display(setting.reader.apply(config));
        MessageArgs arguments = setting.kind == SettingKind.BOOLEAN
                ? MessageArgs.builder().trusted("value", Boolean.parseBoolean(value) ? "&aenabled" : "&cdisabled").build()
                : MessageArgs.builder().untrusted("value", value).build();
        TextKey instruction = switch (setting.kind) {
            case BOOLEAN -> BileMessages.GUI_TOGGLE;
            case INTEGER, LONG -> BileMessages.GUI_NUMBER;
            case LIST -> BileMessages.GUI_TEXT;
            case LOCALE -> BileMessages.GUI_LANGUAGE_SELECT;
            case FILE_ONLY -> BileMessages.GUI_FILE_ONLY;
        };
        return item(setting.material, legacy(player, setting.name), List.of(
                legacy(player, BileMessages.GUI_STATE, arguments), legacy(player, instruction)));
    }

    private void navigation(Player player, Inventory inventory, boolean back) {
        if (back) {
            inventory.setItem(BACK_SLOT, item(Material.ARROW, legacy(player, BileMessages.GUI_BACK), List.of()));
        }
        inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, legacy(player, BileMessages.GUI_CLOSE), List.of()));
    }

    private static void fill(Inventory inventory) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private Category categoryAt(int slot) {
        for (int index = 0; index < CATEGORY_SLOTS.length; index++) {
            if (CATEGORY_SLOTS[index] == slot) {
                return Category.values()[index];
            }
        }
        return null;
    }

    private List<Setting> settings(Category category) {
        ArrayList<Setting> entries = new ArrayList<>();
        for (Setting setting : settings) {
            if (setting.category == category) {
                entries.add(setting);
            }
        }
        return entries;
    }

    static List<Integer> settingSlots(int count) {
        if (count < 0 || count > 35) {
            throw new IllegalArgumentException("Category setting count must be between 0 and 35");
        }
        if (count == 0) {
            return List.of();
        }
        int rows = (count + 6) / 7;
        int firstRow = (5 - rows) / 2;
        ArrayList<Integer> slots = new ArrayList<>(count);
        int remaining = count;
        for (int row = 0; row < rows; row++) {
            int rowsLeft = rows - row;
            int rowSize = (remaining + rowsLeft - 1) / rowsLeft;
            int firstColumn = (9 - rowSize) / 2;
            for (int column = firstColumn; column < firstColumn + rowSize; column++) {
                slots.add((firstRow + row) * 9 + column);
            }
            remaining -= rowSize;
        }
        return List.copyOf(slots);
    }

    private String legacy(Player player, TextKey key) {
        return plugin.getLocalization().text(player, key).legacy();
    }

    private String legacy(Player player, TextKey key, MessageArgs arguments) {
        return plugin.getLocalization().text(player, key, arguments).legacy();
    }

    private String text(Player player, TextKey key) {
        return plugin.getLocalization().text(player, key).miniMessage();
    }

    private String name(Player player, Setting setting) {
        return plugin.getLocalization().text(player, setting.name).plain();
    }

    private void send(Player player, TextKey key, MessageArgs arguments) {
        ComponentMessenger.send(player, plugin.getLocalization().text(player, key, arguments));
    }

    private void sendSingleLine(Player player, TextKey key, MessageArgs arguments) {
        ComponentMessenger.send(player, plugin.getLocalization().singleLineText(player, key, arguments));
    }

    private static boolean isPromptClick(ClickType click) {
        return click == ClickType.DROP || click == ClickType.CONTROL_DROP || click == ClickType.MIDDLE;
    }

    private static String adjust(Setting setting, String current, double adjustment) {
        double value = Double.parseDouble(current.trim()) + (adjustment * setting.step);
        if (setting.kind == SettingKind.INTEGER) {
            return Integer.toString((int) Math.round(value));
        }
        return Long.toString(Math.round(value));
    }

    private static boolean bool(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals("true") && !normalized.equals("false")) {
            throw new IllegalArgumentException("expected true or false");
        }
        return Boolean.parseBoolean(normalized);
    }

    private static List<String> list(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.equalsIgnoreCase("none") || normalized.equals("[]")) {
            return List.of();
        }
        String[] split = normalized.split(",", -1);
        ArrayList<String> values = new ArrayList<>(split.length);
        for (String entry : split) {
            if (entry.trim().isEmpty()) {
                throw new IllegalArgumentException("list entries cannot be blank");
            }
            values.add(entry.trim());
        }
        return values;
    }

    private static String display(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }

    private static String compactChangeValue(String value) {
        String flattened = value.replace("\r\n", "\\n")
                .replace("\r", "\\n")
                .replace("\n", "\\n")
                .strip();
        return flattened.length() <= 120 ? flattened : flattened.substring(0, 117) + "…";
    }

    private static String reason(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static void rejectFileOnlyEdit(BileConfig.Builder builder, String value) {
        throw new IllegalStateException("sensitive settings are file-only");
    }

    private static List<Setting> createSettings() {
        return List.of(
                setting(Category.GENERAL, "language", BileMessages.SETTING_LANGUAGE, Material.WRITABLE_BOOK,
                        SettingKind.LOCALE, 1D, BileConfig::getLanguage, (builder, value) -> builder.language(value.trim())),
                setting(Category.GENERAL, "archive-plugins", BileMessages.SETTING_ARCHIVE, Material.CHEST,
                        SettingKind.BOOLEAN, 1D, config -> Boolean.toString(config.isArchivePlugins()),
                        (builder, value) -> builder.archivePlugins(bool(value))),
                setting(Category.METRICS, "metrics", BileMessages.SETTING_METRICS, Material.FILLED_MAP,
                        SettingKind.BOOLEAN, 1D, config -> Boolean.toString(config.isMetrics()),
                        (builder, value) -> builder.metrics(bool(value))),
                setting(Category.REMOTE_SLAVE, "remote-deploy.slave.slave-enabled", BileMessages.SETTING_SLAVE_ENABLED,
                        Material.RESPAWN_ANCHOR, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.isRemoteSlaveEnabled()),
                        (builder, value) -> builder.remoteSlaveEnabled(bool(value))),
                setting(Category.REMOTE_SLAVE, "remote-deploy.slave.slave-port", BileMessages.SETTING_SLAVE_PORT,
                        Material.COMPARATOR, SettingKind.INTEGER, 1D,
                        config -> Integer.toString(config.getRemoteSlavePort()),
                        (builder, value) -> builder.remoteSlavePort(Integer.parseInt(value.trim()))),
                setting(Category.REMOTE_SLAVE, "remote-deploy.slave.slave-payload", BileMessages.SETTING_SLAVE_PAYLOAD,
                        Material.TRIPWIRE_HOOK, SettingKind.FILE_ONLY, 1D, config -> "redacted",
                        BileConfigEditor::rejectFileOnlyEdit),
                setting(Category.REMOTE_MASTER, "remote-deploy.master.master-enabled", BileMessages.SETTING_MASTER_ENABLED,
                        Material.ENDER_EYE, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.isRemoteMasterEnabled()),
                        (builder, value) -> builder.remoteMasterEnabled(bool(value))),
                setting(Category.REMOTE_MASTER, "remote-deploy.master.master-deploy-to", BileMessages.SETTING_MASTER_TARGETS,
                        Material.TRIPWIRE_HOOK, SettingKind.FILE_ONLY, 1D, config -> "redacted",
                        BileConfigEditor::rejectFileOnlyEdit),
                setting(Category.REMOTE_MASTER, "remote-deploy.master.master-deploy-signatures", BileMessages.SETTING_MASTER_SIGNATURES,
                        Material.NAME_TAG, SettingKind.LIST, 1D,
                        config -> String.join(", ", config.getRemoteMasterDeploySignatures()),
                        (builder, value) -> builder.remoteMasterDeploySignatures(list(value))),
                setting(Category.NETWORK, "remote-deploy.socket-timeout-ms", BileMessages.SETTING_SOCKET_TIMEOUT,
                        Material.CLOCK, SettingKind.INTEGER, 100D,
                        config -> Integer.toString(config.getRemoteSocketTimeoutMs()),
                        (builder, value) -> builder.remoteSocketTimeoutMs(Integer.parseInt(value.trim()))),
                setting(Category.NETWORK, "remote-deploy.max-transfer-bytes", BileMessages.SETTING_MAX_TRANSFER,
                        Material.HEAVY_WEIGHTED_PRESSURE_PLATE, SettingKind.LONG, 1_048_576D,
                        config -> Long.toString(config.getRemoteMaxTransferBytes()),
                        (builder, value) -> builder.remoteMaxTransferBytes(Long.parseLong(value.trim()))),
                setting(Category.WATCHER, "watcher.idle-poll-ticks", BileMessages.SETTING_WATCHER_IDLE,
                        Material.CLOCK, SettingKind.LONG, 1D, config -> Long.toString(config.getWatcherIdlePollTicks()),
                        (builder, value) -> builder.watcherIdlePollTicks(Long.parseLong(value.trim()))),
                setting(Category.WATCHER, "watcher.active-poll-ticks", BileMessages.SETTING_WATCHER_ACTIVE,
                        Material.COMPARATOR, SettingKind.LONG, 1D, config -> Long.toString(config.getWatcherActivePollTicks()),
                        (builder, value) -> builder.watcherActivePollTicks(Long.parseLong(value.trim()))),
                setting(Category.WATCHER, "watcher.fingerprint-debounce-ticks", BileMessages.SETTING_WATCHER_DEBOUNCE,
                        Material.REPEATER, SettingKind.INTEGER, 1D,
                        config -> Integer.toString(config.getWatcherFingerprintDebounceTicks()),
                        (builder, value) -> builder.watcherFingerprintDebounceTicks(Integer.parseInt(value.trim()))),
                setting(Category.WATCHER, "watcher.ignore", BileMessages.SETTING_WATCHER_IGNORE,
                        Material.BARRIER, SettingKind.LIST, 1D, config -> String.join(", ", config.getWatcherIgnore()),
                        (builder, value) -> builder.watcherIgnore(list(value))),
                setting(Category.WATCHER, "watcher.only", BileMessages.SETTING_WATCHER_ONLY,
                        Material.LIME_DYE, SettingKind.LIST, 1D, config -> String.join(", ", config.getWatcherOnly()),
                        (builder, value) -> builder.watcherOnly(list(value))),
                setting(Category.LIFECYCLE, "observability.log-timings", BileMessages.SETTING_LOG_TIMINGS,
                        Material.SPYGLASS, SettingKind.BOOLEAN, 1D, config -> Boolean.toString(config.isLogTimings()),
                        (builder, value) -> builder.logTimings(bool(value))),
                setting(Category.LIFECYCLE, "lifecycle.health-check", BileMessages.SETTING_HEALTH_CHECK,
                        Material.TOTEM_OF_UNDYING, SettingKind.BOOLEAN, 1D,
                        config -> Boolean.toString(config.isHealthCheck()),
                        (builder, value) -> builder.healthCheck(bool(value)))
        );
    }

    private static Setting setting(Category category, String path, TextKey name, Material material,
                                   SettingKind kind, double step, Function<BileConfig, String> reader,
                                   SettingWriter writer) {
        return new Setting(category, path, name, material, kind, step, reader, writer);
    }

    private enum Category {
        GENERAL(BileMessages.GUI_CATEGORY_GENERAL, Material.COMMAND_BLOCK),
        METRICS(BileMessages.GUI_CATEGORY_METRICS, Material.FILLED_MAP),
        REMOTE_SLAVE(BileMessages.GUI_CATEGORY_REMOTE_SLAVE, Material.RESPAWN_ANCHOR),
        REMOTE_MASTER(BileMessages.GUI_CATEGORY_REMOTE_MASTER, Material.ENDER_EYE),
        NETWORK(BileMessages.GUI_CATEGORY_NETWORK, Material.COMPARATOR),
        WATCHER(BileMessages.GUI_CATEGORY_WATCHER, Material.OBSERVER),
        LIFECYCLE(BileMessages.GUI_CATEGORY_LIFECYCLE, Material.TOTEM_OF_UNDYING),
        LANGUAGES(BileMessages.GUI_CATEGORY_LANGUAGES, Material.BOOKSHELF);

        private final TextKey name;
        private final Material material;

        Category(TextKey name, Material material) {
            this.name = name;
            this.material = material;
        }
    }

    private enum SettingKind {
        BOOLEAN, INTEGER, LONG, LIST, LOCALE, FILE_ONLY;

        private boolean numeric() {
            return this == INTEGER || this == LONG;
        }
    }

    @FunctionalInterface
    private interface SettingWriter {
        void write(BileConfig.Builder builder, String value);
    }

    private record Setting(Category category, String path, TextKey name, Material material, SettingKind kind,
                           double step, Function<BileConfig, String> reader, SettingWriter writer) {
    }

    private record PromptSession(Category category, Setting setting) {
    }

    private static final class EditorHolder implements InventoryHolder {
        private final Category category;
        private Inventory inventory;

        private EditorHolder(Category category) {
            this.category = category;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
