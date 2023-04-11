package me.neznamy.tab.shared.features.layout;

import java.util.*;
import java.util.Map.Entry;

import lombok.Getter;
import me.neznamy.tab.api.feature.*;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.chat.EnumChatFormat;
import me.neznamy.tab.api.chat.IChatBaseComponent;
import me.neznamy.tab.shared.TAB;
import me.neznamy.tab.api.TabConstants;
import me.neznamy.tab.shared.features.PlayerList;
import me.neznamy.tab.shared.features.layout.skin.SkinManager;
import me.neznamy.tab.shared.features.sorting.Sorting;
import me.neznamy.tab.shared.placeholders.conditions.Condition;

public class LayoutManager extends TabFeature implements JoinListener, QuitListener, VanishListener, Loadable,
        UnLoadable, Refreshable {

    private final Direction direction = parseDirection(TAB.getInstance().getConfiguration().getLayout().getString("direction", "COLUMNS"));
    @Getter private final String defaultSkin = TAB.getInstance().getConfiguration().getLayout().getString("default-skin", "mineskin:1753261242");
    @Getter private final boolean remainingPlayersTextEnabled = TAB.getInstance().getConfiguration().getLayout().getBoolean("enable-remaining-players-text", true);
    @Getter private final String remainingPlayersText = EnumChatFormat.color(TAB.getInstance().getConfiguration().getLayout().getString("remaining-players-text", "... and %s more"));
    @Getter private final int emptySlotPing = TAB.getInstance().getConfiguration().getLayout().getInt("empty-slot-ping-value", 1000);
    @Getter private final boolean hideVanishedPlayers = TAB.getInstance().getConfiguration().getLayout().getBoolean("hide-vanished-players", true);
    @Getter private final SkinManager skinManager = new SkinManager(defaultSkin);
    @Getter private final Map<Integer, UUID> uuids = new HashMap<Integer, UUID>() {{
        for (int slot=1; slot<=80; slot++) {
            put(slot, new UUID(0, translateSlot(slot)));
        }
    }};
    private final Map<String, Layout> layouts = loadLayouts();
    @Getter private final WeakHashMap<TabPlayer, Layout> playerViews = new WeakHashMap<>();
    private final WeakHashMap<TabPlayer, String> teamNames = new WeakHashMap<>();
    @Getter private final Map<TabPlayer, String> sortedPlayers = Collections.synchronizedMap(new TreeMap<>(Comparator.comparing(teamNames::get)));
    private final Sorting sorting = (Sorting) TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.SORTING);
    @Getter private PlayerList playerList;
    @Getter private final String featureName = "Layout";
    @Getter private final String refreshDisplayName = "Switching layouts";

    @Override
    public void load() {
        playerList = (PlayerList) TAB.getInstance().getFeatureManager().getFeature(TabConstants.Feature.PLAYER_LIST);
        TAB.getInstance().getPlaceholderManager().addUsedPlaceholders(Collections.singletonList(TabConstants.Placeholder.VANISHED));
        if (!TAB.getInstance().getFeatureManager().isFeatureEnabled(TabConstants.Feature.PING_SPOOF))
            TAB.getInstance().getFeatureManager().registerFeature(TabConstants.Feature.LAYOUT_LATENCY, new LayoutLatencyRefresher(this));
        for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) {
            onJoin(p);
        }
    }

    private Direction parseDirection(String value) {
        try {
            return Direction.valueOf(value);
        } catch (IllegalArgumentException e) {
            TAB.getInstance().getErrorManager().startupWarn("\"&e" + value + "&c\" is not a valid type of layout direction. Valid options are: &e" + Arrays.deepToString(Direction.values()) + ". &bUsing COLUMNS");
            return Direction.COLUMNS;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Layout> loadLayouts() {
        Map<String, Layout> layoutMap = new LinkedHashMap<>();
        for (Entry<Object, Object> layout : TAB.getInstance().getConfiguration().getLayout().getConfigurationSection("layouts").entrySet()) {
            Map<String, Object> map = (Map<String, Object>) layout.getValue();
            Condition displayCondition = Condition.getCondition((String) map.get("condition"));
            if (displayCondition != null) addUsedPlaceholders(Collections.singletonList(TabConstants.Placeholder.condition(displayCondition.getName())));
            Map<Integer, FixedSlot> fixedSlots = new HashMap<>();
            List<Integer> emptySlots = new ArrayList<>();
            List<ParentGroup> parentGroups = new ArrayList<>();
            Layout l = new Layout(layout.getKey().toString(), this, displayCondition, fixedSlots, emptySlots, parentGroups);
            for (int slot=1; slot<=80; slot++) {
                emptySlots.add(slot);
            }
            for (String fixedSlot : (List<String>)map.getOrDefault("fixed-slots", Collections.emptyList())) {
                String[] array = fixedSlot.split("\\|");
                try {
                    int slot = Integer.parseInt(array[0]);
                    String text = array[1];
                    String skin = array.length > 2 ? array[2] : "";
                    int ping = array.length > 3 ? TAB.getInstance().getErrorManager().parseInteger(array[3], emptySlotPing) : emptySlotPing;
                    FixedSlot f = new FixedSlot(l, uuids.get(slot), text,
                            "Layout-" + l.getName() + "SLOT-" + slot,
                            skinManager.getSkin(skin.length() == 0 ? defaultSkin : skin), ping);
                    fixedSlots.put(slot, f);
                    emptySlots.remove((Integer)slot);
                    if (text.length() > 0) TAB.getInstance().getFeatureManager().registerFeature(TabConstants.Feature.layoutSlot(layout.getKey().toString(), slot), f);
                } catch (NumberFormatException e) {
                    TAB.getInstance().getErrorManager().startupWarn("Layout \"" + layout.getKey() + "\"'s fixed slot line \"" + fixedSlot + "&c\" has invalid number as slot.");
                }
            }
            Map<String, Map<String, Object>> groups = (Map<String, Map<String, Object>>) map.get("groups");
            if (groups != null) {
                for (Entry<String, Map<String, Object>> group : groups.entrySet()) {
                    Condition condition = Condition.getCondition((String) group.getValue().get("condition"));
                    List<Integer> positions = new ArrayList<>();
                    for (String line : (List<String>) group.getValue().get("slots")) {
                        String[] arr = line.split("-");
                        int from = Integer.parseInt(arr[0]);
                        int to = arr.length == 1 ? from : Integer.parseInt(arr[1]);
                        for (int i = from; i<= to; i++) {
                            positions.add(i);
                        }
                    }
                    positions.removeAll(fixedSlots.keySet());
                    parentGroups.add(new ParentGroup(l, condition, positions.stream().mapToInt(i->i).toArray(), (Boolean)group.getValue().getOrDefault("hide-vanished-players", true)));
                    emptySlots.removeAll(positions);
                    if (condition != null) {
                        l.addUsedPlaceholders(Collections.singletonList(TabConstants.Placeholder.condition(condition.getName())));
                    }
                }
            }
            layoutMap.put(layout.getKey().toString(), l);
            TAB.getInstance().getFeatureManager().registerFeature(TabConstants.Feature.layout(layout.getKey().toString()), l);
        }
        return layoutMap;
    }

    @Override
    public void onJoin(TabPlayer p) {
        teamNames.put(p, sorting.getFullTeamName(p));
        sortedPlayers.put(p, sorting.getFullTeamName(p));
        Layout highest = getHighestLayout(p);
        if (highest != null) highest.sendTo(p);
        playerViews.put(p, highest);
        layouts.values().forEach(Layout::tick);

        // Unformat original entries for players who can see a layout to avoid spaces due to unparsed placeholders and such
        if (highest == null) return;
        Map<UUID, IChatBaseComponent> data = new HashMap<>();
        for (TabPlayer all : TAB.getInstance().getOnlinePlayers()) {
            data.put(all.getTablistId(), null);
        }
        p.getTabList().updateDisplayNames(data);
    }

    @Override
    public void onQuit(TabPlayer p) {
        sortedPlayers.remove(p);
        teamNames.remove(p);
        layouts.values().forEach(Layout::tick);
    }

    private int translateSlot(int slot) {
        if (direction == Direction.ROWS) {
            return (slot-1)%4*20+(slot-((slot-1)%4))/4+1;
        } else {
            return slot;
        }
    }

    @Override
    public void refresh(TabPlayer p, boolean force) {
        Layout highest = getHighestLayout(p);
        Layout current = playerViews.get(p);
        if (current != highest) {
            if (current != null) current.removeFrom(p);
            if (highest != null) highest.sendTo(p);
            playerViews.put(p, highest);
        }
    }

    @Override
    public void unload() {
        for (TabPlayer p : TAB.getInstance().getOnlinePlayers()) {
            if (p.getVersion().getMinorVersion() < 8 || p.isBedrockPlayer()) continue;
            p.getTabList().removeEntries(uuids.values());
        }
    }

    @Override
    public void onVanishStatusChange(TabPlayer p) {
        layouts.values().forEach(Layout::tick);
    }

    private Layout getHighestLayout(TabPlayer p) {
        for (Layout layout : layouts.values()) {
            if (layout.isConditionMet(p)) return layout;
        }
        return null;
    }

    public UUID getUUID(int slot) {
        return uuids.get(slot);
    }

    public void updateTeamName(TabPlayer p, String teamName) {
        sortedPlayers.remove(p);
        teamNames.put(p, teamName);
        sortedPlayers.put(p, teamName);
        layouts.values().forEach(Layout::tick);
    }

    public enum Direction {

        COLUMNS, ROWS
    }
}