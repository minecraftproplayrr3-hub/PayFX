package com.rxglory.payfx;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.Locale;

public class PayFX extends JavaPlugin implements Listener {

    private Economy economy;

    private static final String[] SUFFIX = {"", "K", "M", "B", "T", "Qa", "Qi"};

    @Override
    public void onEnable() {
        RegisteredServiceProvider<Economy> rsp =
                getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().severe("No Vault economy found. Disabling PayFX.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        economy = rsp.getProvider();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("PayFX enabled!");
    }

    // Watches /pay, then checks if the receiver's money really went up
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        String[] parts = e.getMessage().trim().split("\\s+");
        if (parts.length < 3) return;

        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.contains(":")) cmd = cmd.substring(cmd.indexOf(':') + 1);
        if (!cmd.equals("pay") && !cmd.equals("epay")) return;

        final Player sender = e.getPlayer();
        final Player target = Bukkit.getPlayer(parts[1]);
        if (target == null || target.equals(sender)) return;

        final double before = economy.getBalance(target);

        Bukkit.getScheduler().runTaskLater(this, () -> {
            double gained = economy.getBalance(target) - before;
            if (gained > 0 && target.isOnline()) {
                play(sender, target, gained);
            }
        }, 5L);
    }

    private void play(Player sender, Player target, double amount) {
        String text = format(amount);

        // Tiers: 1 = small, 2 = $1K+, 3 = $1M+, 4 = $1B+, 5 = $1T+
        int tier = amount >= 1e12 ? 5 : amount >= 1e9 ? 4 : amount >= 1e6 ? 3 : amount >= 1e3 ? 2 : 1;

        NamedTextColor color = switch (tier) {
            case 1 -> NamedTextColor.GREEN;
            case 2 -> NamedTextColor.AQUA;
            case 3 -> NamedTextColor.LIGHT_PURPLE;
            case 4 -> NamedTextColor.GOLD;
            default -> NamedTextColor.RED;
        };

        // Title on the receiver's screen
        target.showTitle(Title.title(
                Component.text("+" + text, color).decorate(TextDecoration.BOLD),
                Component.text("from " + sender.getName(), NamedTextColor.GRAY),
                Title.Times.times(Duration.ofMillis(200),
                        Duration.ofMillis(1200 + tier * 300L),
                        Duration.ofMillis(500))));

        sender.sendActionBar(Component.text("You sent " + text + " to " + target.getName(),
                NamedTextColor.YELLOW));

        Location loc = target.getLocation();

        switch (tier) {
            case 1 -> target.playSound(loc, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);

            case 2 -> {
                target.playSound(loc, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f);
                loc.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING,
                        loc.clone().add(0, 1, 0), 40, 0.5, 0.8, 0.5, 0.2);
            }

            case 3 -> {
                target.playSound(loc, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                ring(loc, 2, Particle.END_ROD);
                spiral(target, 1.5, 3, Particle.TOTEM_OF_UNDYING, 40);
                firework(loc);
            }

            case 4 -> {
                target.playSound(loc, Sound.ITEM_TOTEM_USE, 1f, 1f);
                target.playSound(loc, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.8f, 1f);
                ring(loc, 3, Particle.FLAME);
                spiral(target, 2, 4, Particle.END_ROD, 60);
                loc.getWorld().strikeLightningEffect(loc);
                for (int i = 0; i < 3; i++) {
                    Bukkit.getScheduler().runTaskLater(this, () -> firework(target.getLocation()), i * 8L);
                }
                Bukkit.broadcast(Component.text(sender.getName() + " sent " + text + " to " + target.getName() + "!",
                        NamedTextColor.GOLD));
            }

            default -> {
                target.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 1f, 1f);
                target.playSound(loc, Sound.ITEM_TOTEM_USE, 1f, 0.8f);
                ring(loc, 4, Particle.FLAME);
                spiral(target, 3, 6, Particle.END_ROD, 100);
                spiral(target, 2, 6, Particle.TOTEM_OF_UNDYING, 100);
                for (int i = 0; i < 4; i++) {
                    Bukkit.getScheduler().runTaskLater(this,
                            () -> target.getWorld().strikeLightningEffect(target.getLocation()), i * 10L);
                }
                for (int i = 0; i < 8; i++) {
                    Bukkit.getScheduler().runTaskLater(this, () -> firework(target.getLocation()), i * 6L);
                }
                Bukkit.broadcast(Component.text("INSANE PAYMENT! " + sender.getName() + " sent "
                        + text + " to " + target.getName() + "!", NamedTextColor.RED)
                        .decorate(TextDecoration.BOLD));
            }
        }
    }

    // Shows 1500000 as $1.50M, 1000000000 as $1B, and so on
    private static String format(double v) {
        int i = 0;
        while (v >= 1000 && i < SUFFIX.length - 1) {
            v /= 1000;
            i++;
        }
        String s = (v == Math.floor(v)) ? String.valueOf((long) v) : String.format(Locale.US, "%.2f", v);
        return "$" + s + SUFFIX[i];
    }

    private void ring(Location c, double radius, Particle particle) {
        for (int i = 0; i < 40; i++) {
            double a = 2 * Math.PI * i / 40;
            c.getWorld().spawnParticle(particle,
                    c.getX() + Math.cos(a) * radius, c.getY() + 1, c.getZ() + Math.sin(a) * radius,
                    1, 0, 0, 0, 0);
        }
    }

    private void spiral(Player p, double radius, double height, Particle particle, int ticks) {
        new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!p.isOnline() || t >= ticks) {
                    cancel();
                    return;
                }
                Location base = p.getLocation();
                double y = (height * t) / ticks;
                for (int k = 0; k < 2; k++) {
                    double angle = t * 0.5 + k * Math.PI;
                    Location l = base.clone().add(Math.cos(angle) * radius, y, Math.sin(angle) * radius);
                    p.getWorld().spawnParticle(particle, l, 2, 0, 0, 0, 0);
                }
                t++;
            }
        }.runTaskTimer(this, 0L, 1L);
    }

    private void firework(Location loc) {
        Firework fw = loc.getWorld().spawn(loc, Firework.class);
        FireworkMeta meta = fw.getFireworkMeta();
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.AQUA, Color.YELLOW, Color.FUCHSIA)
                .withFade(Color.WHITE)
                .flicker(true)
                .trail(true)
                .build());
        fw.setFireworkMeta(meta);
        fw.detonate();
    }
}
