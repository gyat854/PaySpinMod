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
    private static final Pattern PAID = Pattern.compile(
            "^[^A-Za-z0-9_]*(?:\\[[^\\]]*\\]\\s*)?(\\w{3,16}) paid you [^0-9]{0,3}([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([kKmMbB])?(?![A-Za-z0-9]).*$",
            Pattern.CASE_INSENSITIVE);

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
        // weights sum to 1000, expected return = 0.96
        TILES.add(new Tile("Loss",      new ItemStack(Items.BARRIER),         0,   439, 0xFFC62828));
        TILES.add(new Tile("Gold",      new ItemStack(Items.GOLD_INGOT),      2,   80,  0xFF2E9E3E));
        TILES.add(new Tile("Coal",      new ItemStack(Items.COAL),            0.1, 50,  0xFF4A5565));
        TILES.add(new Tile("Copper",    new ItemStack(Items.COPPER_INGOT),    0.4, 80,  0xFF1E9BB0));
        TILES.add(new Tile("Iron",      new ItemStack(Items.IRON_INGOT),      1,   150, 0xFF2F5BD0));
        TILES.add(new Tile("Lapis",     new ItemStack(Items.LAPIS_LAZULI),    1.2, 70,  0xFF5B3FD0));
        TILES.add(new Tile("Redstone",  new ItemStack(Items.REDSTONE),        1.5, 58,  0xFFA03FC0));
        TILES.add(new Tile("Emerald",   new ItemStack(Items.EMERALD),         4,   50,  0xFFD03F9A));
        TILES.add(new Tile("Diamond",   new ItemStack(Items.DIAMOND),         7,   14,  0xFFD0782F));
        TILES.add(new Tile("Amethyst",  new ItemStack(Items.AMETHYST_SHARD),  12,  6,   0xFFD0B02F));
        TILES.add(new Tile("JACKPOT",   new ItemStack(Items.NETHERITE_INGOT), 24,  3,   0xFFE040FB));

        // 1) watch chat for payments
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString().replace('\u00A0', ' ').replaceAll("\\s+", " ").trim();
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
                mc.getConnection().sendCommand(PAY_COMMAND + " " + name + " " + amount);
            }
            return;
        }

        QUEUE.add(new Entry(name, amount, dry));
        say(name + " staked " + money(amount) + (dry ? " (TEST)" : "") + ". Position #" + QUEUE.size() + ".");
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        long now = System.currentTimeMillis();
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
        if (willPay) {
            if (mc.getConnection() == null) return; // not connected right now, try again next tick
            mc.getConnection().sendCommand(PAY_COMMAND + " " + current.name() + " " + payout);
        }

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
                + (current.dry() ? " (TEST, nothing sent)" : willPay ? " (paid)" : payout > 0 ? " (NOT paid: autopay off)" : ""));

        phase = Phase.RESULT;
        phaseStart = now;
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
