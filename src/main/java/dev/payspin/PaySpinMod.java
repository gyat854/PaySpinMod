package dev.payspin;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.text.DecimalFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PaySpin (client mod).
 *
 * You are the host. When the server tells you "<name> paid you $<amount>", that player is added
 * to a queue. One spin runs at a time on your screen (tile strip, name, stake, who is next).
 * When it lands, the mod runs "/pay <name> <stake x multiplier>" for you.
 */
public class PaySpinMod implements ClientModInitializer {

    // ======================= SETTINGS (edit here, then rebuild) =======================
    static final long MIN_STAKE = 1_000_000L;
    static final long MAX_STAKE = 50_000_000L;
    static final long MAX_PAYOUT = 1_200_000_000L;   // 0 = no cap
    static final int MAX_QUEUE = 20;
    static final long SPIN_MS = 7000;                // length of the spin animation
    static final long RESULT_MS = 4000;              // how long the result stays before the next spin
    static final String PAY_COMMAND = "pay";         // sends "/pay <name> <amount>"
    // Out-of-range payments are refunded automatically if true.
    static final boolean REFUND_OUT_OF_RANGE = true;
    // ===================================================================================

    // "Name paid you $1,000,000" / "$1M" / "$2.5m"
    // The line must START with the payer (optionally after symbols or a [Rank] tag). A player typing
    // "Steve paid you $50m" in public chat can't fake it, because chat lines start with THEIR name.
    // Real server GAME messages: "ChrisT826841 paid you $1M" and ".ChrisT826841 paid you $1M".
    private static final Pattern PAID = Pattern.compile(
            "^(?:\\[[^\\]]+\\]\\s*)?(\\.?[A-Za-z0-9_]{3,16})\\s+paid\\s+you\\s+\\$?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?\\s*[.!]?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENT = Pattern.compile(
            "^You paid (\\.?[A-Za-z0-9_]{3,16})\\s+\\$?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?\\s*[.!]?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern REJECTED = Pattern.compile(
            "(?i).*(?:not enough money|insufficient funds|cannot pay|can't pay|could not pay|player not found|player is not online|unknown player|payment failed|invalid player|invalid amount).*" );
    private record Payout(String id, String name, long amount, String state, int attempts, long when) {}
    private static final Deque<Payout> PAYOUTS = new ArrayDeque<>();
    private static Payout awaiting;
    private static boolean ledgerLoaded = false;
    private static Path ledgerFile;
    private static long lastPayCommand = 0;
    private static final long PAY_GAP_MS = 3000;
    private static final long CONFIRM_MS = 12000;

    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final DecimalFormat MULT_FMT = new DecimalFormat("0.##");

    // ---- tiles: shown in EXACTLY this order, looping ----
    private record Tile(String name, ItemStack icon, double mult, int weight, int color) {}

    private static final List<Tile> TILES = new ArrayList<>();

    private record Entry(String name, long stake, boolean dry) {}

    private enum Phase { IDLE, SPINNING, RESULT }

    private static final Deque<Entry> QUEUE = new ArrayDeque<>();
    private static Phase phase = Phase.IDLE;
    private static Entry current;
    private static String code = "";
    private static Tile result;
    private static double target;
    private static long phaseStart;
    private static String resultLine = "";

    private static boolean enabled = true;
    private static boolean autoPay = true;
    private static boolean alwaysShow = true;
    private static boolean debug = false;   // keep the spinner on screen while idle

    // ---------------------------------------------------------------------------------

    @Override
    public void onInitializeClient() {
        // 93.8% RTP / 6.2% house edge. Weights sum to 2000.
        TILES.add(new Tile("Loss", new ItemStack(Items.BARRIER), 0, 500, 0xFFC62828));
        TILES.add(new Tile("Gold", new ItemStack(Items.GOLD_INGOT), 2, 262, 0xFF2E9E3E));
        TILES.add(new Tile("Coal", new ItemStack(Items.COAL), 0.1, 100, 0xFF4A5565));
        TILES.add(new Tile("Copper", new ItemStack(Items.COPPER_INGOT), 0.4, 100, 0xFF1E9BB0));
        TILES.add(new Tile("Iron", new ItemStack(Items.IRON_INGOT), 1, 988, 0xFF2F5BD0));
        TILES.add(new Tile("Emerald", new ItemStack(Items.EMERALD), 4, 30, 0xFFD03F9A));
        TILES.add(new Tile("Diamond", new ItemStack(Items.DIAMOND), 7, 14, 0xFFD0782F));
        TILES.add(new Tile("Amethyst", new ItemStack(Items.AMETHYST_SHARD), 12, 4, 0xFFD0B02F));
        TILES.add(new Tile("JACKPOT", new ItemStack(Items.NETHERITE_INGOT), 24, 2, 0xFFE040FB));

        // 1) watch chat for payments
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString().replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
            onPayReply(text);
            Matcher m = PAID.matcher(text);
            boolean ok = m.matches();
            if (text.toLowerCase().contains("paid")) {
                System.out.println("[PaySpin] saw: " + text + " -> " + (ok ? "MATCHED" : "no match"));
                if (debug) {
                    Minecraft.getInstance().execute(() ->
                            say("debug: saw [" + text + "] -> " + (ok ? "MATCHED" : "no match")));
                }
            }
            if (!ok) return;
            long amount = parseAmount(m.group(2), m.group(3));
            if (amount <= 0) return;
            String name = m.group(1);
            Minecraft.getInstance().execute(() -> onPayment(name, amount, false));
        });

        // 2) run the state machine
        ClientTickEvents.END_CLIENT_TICK.register(PaySpinMod::tick);

        // 3) draw the overlay
        HudElementRegistry.attachElementBefore(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("payspin", "overlay"),
                PaySpinMod::render);

        // 4) /payspin client command
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("payspin")
                        .then(ClientCommandManager.literal("on").executes(c -> {
                            enabled = true;
                            fb(c, "Enabled. Watching for payments.");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(c -> {
                            enabled = false;
                            fb(c, "Disabled. Payments will be ignored.");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("autopay").executes(c -> {
                            autoPay = !autoPay;
                            fb(c, "Auto-pay is now " + (autoPay ? "ON" : "OFF (results shown, no /pay sent)"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("debug").executes(c -> {
                            debug = !debug;
                            fb(c, "Debug is now " + (debug ? "ON: any chat line containing 'paid' is echoed here" : "OFF"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("hud").executes(c -> {
                            alwaysShow = !alwaysShow;
                            fb(c, "Always-on spinner is now " + (alwaysShow ? "ON" : "OFF (only shows during spins)"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("skip").executes(c -> {
                            if (phase == Phase.RESULT) {
                                phase = Phase.IDLE;
                                current = null;
                            }
                            fb(c, "Skipped result screen.");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("status").executes(c -> {
                            fb(c, "Enabled=" + enabled + ", autopay=" + autoPay + ", queue=" + QUEUE.size()
                                    + ", stake " + money(MIN_STAKE) + " - " + money(MAX_STAKE)
                                    + ", return " + rtp());
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("unpaid").executes(c -> {
                            int count = 0;
                            for (Payout x : PAYOUTS) {
                                if (!x.state().equals("CONFIRMED")) {
                                    fb(c, x.id() + " " + x.name() + " " + money(x.amount()) + " [" + x.state() + "]");
                                    count++;
                                }
                            }
                            if (count == 0) fb(c, "No unpaid payout records.");
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("test")
                                .then(ClientCommandManager.argument("name", StringArgumentType.word())
                                        .then(ClientCommandManager.argument("amount", StringArgumentType.word())
                                                .executes(c -> {
                                                    String name = StringArgumentType.getString(c, "name");
                                                    String raw = StringArgumentType.getString(c, "amount").toLowerCase();
                                                    String suffix = null;
                                                    if (raw.matches(".*[kmb]$")) {
                                                        suffix = raw.substring(raw.length() - 1);
                                                        raw = raw.substring(0, raw.length() - 1);
                                                    }
                                                    long amt = parseAmount(raw, suffix);
                                                    if (amt <= 0) {
                                                        fb(c, "Bad amount. Example: /payspin test Steve 1m");
                                                        return 0;
                                                    }
                                                    onPayment(name, amt, true); // dry run: never sends /pay
                                                    return 1;
                                                }))))));
    }

    // ---------------------------------------------------------------------------------
    // payments + queue
    // ---------------------------------------------------------------------------------

    private static void onPayment(String name, long amount, boolean dry) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();

        boolean outOfRange = amount < MIN_STAKE || amount > MAX_STAKE;
        boolean full = QUEUE.size() >= MAX_QUEUE;
        if (outOfRange || full) {
            say((outOfRange
                    ? name + " paid " + money(amount) + " (allowed " + money(MIN_STAKE) + " - " + money(MAX_STAKE) + ")"
                    : "Queue full, " + name + " paid " + money(amount))
                    + (REFUND_OUT_OF_RANGE && !dry && autoPay ? " - refunding." : " - ignored."));
            if (REFUND_OUT_OF_RANGE && !dry && autoPay && mc.getConnection() != null) {
                addPayout(name, amount);
            }
            return;
        }

        QUEUE.add(new Entry(name, amount, dry));
        saveLedger();
        say(name + " staked " + money(amount) + (dry ? " (TEST)" : "") + ". Position #" + QUEUE.size() + ".");
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        if (!ledgerLoaded) loadLedger(mc);
        long now = System.currentTimeMillis();
        processPayouts(mc, now);
        switch (phase) {
            case IDLE -> {
                if (!QUEUE.isEmpty() && enabled) start(now);
            }
            case SPINNING -> {
                if (now - phaseStart >= SPIN_MS) finish(mc, now);
            }
            case RESULT -> {
                if (now - phaseStart >= RESULT_MS) {
                    phase = Phase.IDLE;
                    current = null;
                }
            }
        }
    }

    private static void start(long now) {
        current = QUEUE.poll();
        if (current == null) return;
        saveLedger();
        result = roll();
        int idx = TILES.indexOf(result);
        target = TILES.size() * 4 + idx; // many laps, ends exactly on the rolled tile
        code = newCode();
        phase = Phase.SPINNING;
        phaseStart = now;
    }

    private static void finish(Minecraft mc, long now) {
        long payout = (long) Math.floor(current.stake() * result.mult());
        if (MAX_PAYOUT > 0 && payout > MAX_PAYOUT) payout = MAX_PAYOUT;

        boolean willPay = payout > 0 && !current.dry() && autoPay;
        if (payout > 0 && !current.dry()) addPayout(current.name(), payout);

        String mult = MULT_FMT.format(result.mult()) + "x";
        if (payout > current.stake()) {
            resultLine = mult + "  -  " + current.name() + " won " + money(payout);
        } else if (payout == current.stake()) {
            resultLine = mult + "  -  " + current.name() + " gets the stake back";
        } else if (payout > 0) {
            resultLine = mult + "  -  " + current.name() + " gets back " + money(payout);
        } else {
            resultLine = mult + "  -  " + current.name() + " lost " + money(current.stake());
        }
        say("[" + code + "] " + resultLine
                + (current.dry() ? " (TEST, nothing sent)" : willPay ? " (queued; not yet confirmed)" : payout > 0 ? " (queued; autopay off)" : ""));

        phase = Phase.RESULT;
        phaseStart = now;
        saveLedger();
    }

    private static Tile roll() {
        int total = 0;
        for (Tile t : TILES) total += t.weight();
        int r = ThreadLocalRandom.current().nextInt(total);
        for (Tile t : TILES) {
            r -= t.weight();
            if (r < 0) return t;
        }
        return TILES.get(TILES.size() - 1);
    }

    private static String rtp() {
        double ev = 0;
        int total = 0;
        for (Tile t : TILES) {
            ev += t.weight() * t.mult();
            total += t.weight();
        }
        return String.format("%.3f", ev / total);
    }

    // Journal payouts before sending any command. An unanswered /pay is UNCERTAIN,
    // never automatically retried: otherwise players could be paid twice.
    private static void addPayout(String name, long amount) {
        PAYOUTS.addLast(new Payout(newCode(), name, amount, "QUEUED", 0, 0));
        saveLedger();
    }

    private static void replacePayout(Payout old, Payout next) {
        Deque<Payout> copy = new ArrayDeque<>();
        for (Payout p : PAYOUTS) copy.addLast(p == old ? next : p);
        PAYOUTS.clear(); PAYOUTS.addAll(copy);
        saveLedger();
    }

    private static void processPayouts(Minecraft mc, long now) {
        if (awaiting != null) {
            if (now - awaiting.when() > CONFIRM_MS) {
                replacePayout(awaiting, new Payout(awaiting.id(), awaiting.name(), awaiting.amount(), "UNCERTAIN", awaiting.attempts(), awaiting.when()));
                say("WARNING: payout " + awaiting.id() + " unconfirmed. Check /payspin unpaid and server balance before manual action.");
                awaiting = null;
            }
            return;
        }
        if (!autoPay || mc.getConnection() == null || now - lastPayCommand < PAY_GAP_MS) return;
        for (Payout p : PAYOUTS) {
            if (!(p.state().equals("QUEUED") || p.state().equals("RETRY"))) continue;
            if (p.when() > now) continue;
            Payout sent = new Payout(p.id(), p.name(), p.amount(), "SENT", p.attempts() + 1, now);
            replacePayout(p, sent);
            awaiting = sent;
            lastPayCommand = now;
            mc.getConnection().sendCommand(PAY_COMMAND + " " + sent.name() + " " + sent.amount());
            say("Payout " + sent.id() + " sent; awaiting confirmation.");
            break;
        }
    }

    private static void onPayReply(String text) {
        if (awaiting == null) return;
        Matcher m = SENT.matcher(text);
        if (m.matches()) {
            long amount = parseAmount(m.group(2), m.group(3));
            if (awaiting.name().equalsIgnoreCase(m.group(1)) && amount == awaiting.amount()) {
                replacePayout(awaiting, new Payout(awaiting.id(), awaiting.name(), awaiting.amount(), "CONFIRMED", awaiting.attempts(), awaiting.when()));
                say("Confirmed payout " + awaiting.id() + " to " + awaiting.name());
                awaiting = null;
            }
        } else if (REJECTED.matcher(text).matches()) {
            String state = awaiting.attempts() < 3 ? "RETRY" : "FAILED";
            replacePayout(awaiting, new Payout(awaiting.id(), awaiting.name(), awaiting.amount(), state, awaiting.attempts(), System.currentTimeMillis() + PAY_GAP_MS));
            say("Payout " + awaiting.id() + " rejected; status " + state);
            awaiting = null;
        }
    }

    private static void loadLedger(Minecraft mc) {
        ledgerLoaded = true;
        ledgerFile = mc.gameDirectory.toPath().resolve("config").resolve("payspin-ledger.properties");
        if (!Files.exists(ledgerFile)) return;
        try (InputStream in = Files.newInputStream(ledgerFile)) {
            Properties props = new Properties(); props.load(in);
            int count = Integer.parseInt(props.getProperty("count", "0"));
            for (int i = 0; i < count; i++) {
                String k = "p." + i + ".";
                String state = props.getProperty(k + "state", "UNCERTAIN");
                if (state.equals("SENT")) state = "UNCERTAIN";
                PAYOUTS.addLast(new Payout(props.getProperty(k + "id"), props.getProperty(k + "name"),
                        Long.parseLong(props.getProperty(k + "amount")), state,
                        Integer.parseInt(props.getProperty(k + "attempts", "0")), 0));
            }
            int countQ = Integer.parseInt(props.getProperty("queue", "0"));
            for (int i = 0; i < countQ; i++) {
                String k = "q." + i + ".";
                QUEUE.addLast(new Entry(props.getProperty(k + "name"), Long.parseLong(props.getProperty(k + "stake")), false));
            }
            if (props.containsKey("current.name")) {
                QUEUE.addFirst(new Entry(props.getProperty("current.name"), Long.parseLong(props.getProperty("current.stake")), false));
            }
            say("Restored " + PAYOUTS.size() + " payout records and " + QUEUE.size() + " pending spins.");
            saveLedger();
        } catch (Exception e) { say("ERROR loading ledger: " + e.getMessage()); }
    }

    private static void saveLedger() {
        if (!ledgerLoaded || ledgerFile == null) return;
        try {
            Files.createDirectories(ledgerFile.getParent());
            Properties props = new Properties();
            int i = 0;
            for (Payout p : PAYOUTS) {
                String k = "p." + i++ + ".";
                props.setProperty(k + "id", p.id()); props.setProperty(k + "name", p.name());
                props.setProperty(k + "amount", Long.toString(p.amount()));
                props.setProperty(k + "state", p.state());
                props.setProperty(k + "attempts", Integer.toString(p.attempts()));
            }
            props.setProperty("count", Integer.toString(i));
            i = 0;
            for (Entry e : QUEUE) {
                if (e.dry()) continue;
                String k = "q." + i++ + ".";
                props.setProperty(k + "name", e.name()); props.setProperty(k + "stake", Long.toString(e.stake()));
            }
            props.setProperty("queue", Integer.toString(i));
            if (current != null && phase == Phase.SPINNING && !current.dry()) {
                props.setProperty("current.name", current.name());
                props.setProperty("current.stake", Long.toString(current.stake()));
            }
            Path tmp = ledgerFile.resolveSibling("payspin-ledger.tmp");
            try (OutputStream out = Files.newOutputStream(tmp)) { props.store(out, "PaySpin payment journal"); }
            Files.move(tmp, ledgerFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { System.err.println("[PaySpin] Ledger write failed: " + e); }
    }

    // ---------------------------------------------------------------------------------
    // overlay
    // ---------------------------------------------------------------------------------

    private static final int TW = 44;          // tile slot width
    private static final int VISIBLE = 7;      // tiles visible at once
    private static final int WHITE = 0xFFFFFFFF;
    private static final int GOLD = 0xFFFFD34D;
    private static final int GREY = 0xFFAAAAAA;

    private static double position(long now) {
        if (phase == Phase.RESULT) return target;
        double x = Math.min(1.0, (now - phaseStart) / (double) SPIN_MS);
        double e = 1.0 - Math.pow(1.0 - x, 3.0); // fast start, slow landing
        return target * e;
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        boolean idle = phase == Phase.IDLE || current == null;
        if (idle && !alwaysShow) return;

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        long now = System.currentTimeMillis();
        int sw = mc.getWindow().getGuiScaledWidth();
        int cx = sw / 2;

        int panelW = VISIBLE * TW + 24;
        int px1 = cx - panelW / 2;
        int px2 = cx + panelW / 2;
        int py1 = 12;
        int py2 = py1 + 122;

        // panel
        g.fill(px1, py1, px2, py2, 0xD0101018);
        g.fill(px1, py1, px2, py1 + 2, GOLD);

        // title + subtitle
        center(g, font, Component.literal("PAY TO SPIN").withStyle(ChatFormatting.BOLD), cx, py1 + 7, WHITE);
        if (idle) {
            center(g, font, Component.literal("Pay me " + money(MIN_STAKE) + " - " + money(MAX_STAKE) + " to spin"),
                    cx, py1 + 22, GREY);
        } else {
            center(g, font, Component.literal(code + "  \u2022  " + money(current.stake()) + " stake  \u2022  " + current.name()),
                    cx, py1 + 22, GREY);
        }

        // strip
        int stripY = py1 + 46;
        int stripH = 48;
        int sx1 = cx - (VISIBLE * TW) / 2;
        int sx2 = cx + (VISIBLE * TW) / 2;

        int n = TILES.size();
        // idle: slow endless scroll (0.5 tile/s). The strip loops every n tiles, so it wraps seamlessly.
        double pos = idle ? (now % (n * 2000L)) / 2000.0 : position(now);

        g.enableScissor(sx1, stripY, sx2, stripY + stripH);
        int kStart = (int) Math.floor(pos) - VISIBLE / 2 - 1;
        int kEnd = (int) Math.floor(pos) + VISIBLE / 2 + 2;
        for (int k = kStart; k <= kEnd; k++) {
            Tile t = TILES.get(Math.floorMod(k, n));
            int centerX = cx + (int) Math.round((k - pos) * TW);
            int x1 = centerX - TW / 2 + 2;
            int x2 = centerX + TW / 2 - 2;

            g.fill(x1, stripY, x2, stripY + stripH, t.color());
            g.fill(x1 + 2, stripY + 2, x2 - 2, stripY + stripH - 2, 0xFF1A1D26);

            g.renderItem(t.icon(), centerX - 8, stripY + 8);
            Component label = Component.literal(MULT_FMT.format(t.mult()) + "x");
            g.drawString(font, label, centerX - font.width(label) / 2, stripY + 32, WHITE, true);
        }
        g.disableScissor();

        // pointer
        center(g, font, Component.literal("\u25BC"), cx, stripY - 11, GOLD);
        center(g, font, Component.literal("\u25B2"), cx, stripY + stripH + 3, GOLD);

        // status
        int fy = py1 + 100;
        if (idle) {
            center(g, font, Component.literal("Waiting for a payment..."), cx, fy, GOLD);
        } else if (phase == Phase.SPINNING) {
            center(g, font, Component.literal("Spinning..."), cx, fy, GOLD);
        } else {
            center(g, font, Component.literal(resultLine), cx, fy, WHITE);
        }

        // next up
        Entry next = QUEUE.peek();
        Component nextLine = next == null
                ? Component.literal("Queue empty")
                : Component.literal("Next: " + next.name() + " (" + money(next.stake()) + ")"
                + (QUEUE.size() > 1 ? "  +" + (QUEUE.size() - 1) + " more" : ""));
        center(g, font, nextLine, cx, fy + 12, next == null ? GREY : 0xFF7CFC9A);
    }

    private static void center(GuiGraphics g, Font font, Component c, int cx, int y, int color) {
        g.drawString(font, c, cx - font.width(c) / 2, y, color, true);
    }

    // ---------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------

    private static long parseAmount(String number, String suffix) {
        try {
            double v = Double.parseDouble(number.replace(",", ""));
            if (suffix != null && !suffix.isEmpty()) {
                switch (Character.toLowerCase(suffix.charAt(0))) {
                    case 'k' -> v *= 1_000d;
                    case 'm' -> v *= 1_000_000d;
                    case 'b' -> v *= 1_000_000_000d;
                    default -> { }
                }
            }
            if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0) return -1;
            return (long) Math.floor(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String money(long v) {
        return String.format("$%,d", v);
    }

    private static String newCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(CODE_CHARS.charAt(ThreadLocalRandom.current().nextInt(CODE_CHARS.length())));
        }
        return sb.toString();
    }

    private static void say(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null) return;
        mc.gui.getChat().addMessage(Component.literal("[PaySpin] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(s).withStyle(ChatFormatting.GRAY)));
    }

    private static void fb(CommandContext<FabricClientCommandSource> c, String s) {
        c.getSource().sendFeedback(Component.literal("[PaySpin] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(s).withStyle(ChatFormatting.GRAY)));
    }
}
