package com.example.secureredirect;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class SecureRedirectPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private final SecureRandom random = new SecureRandom();
    private NamespacedKey cookieKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Cookie key used to attach the hash to the player
        this.cookieKey = new NamespacedKey(this, "redirect_token");

        if (getCommand("redirect") != null) {
            getCommand("redirect").setExecutor(this);
            getCommand("redirect").setTabCompleter(this);
        }

        Bukkit.getPluginManager().registerEvents(this, this);
    }

    // -------- Config helpers --------

    private String getTargetHost() {
        return getConfig().getString("target-host", "");
    }

    private int getTargetPort() {
        // If target-port is not set or invalid, fall back to the default Minecraft port 25565.
        if (!getConfig().isSet("target-port")) {
            return 25565;
        }
        int port = getConfig().getInt("target-port", 25565);
        if (port <= 0 || port > 65535) {
            port = 25565;
        }
        return port;
    }

    private String getSendHash() {
        return getConfig().getString("send-hash", "");
    }

    private String getReceiveHash() {
        return getConfig().getString("receive-hash", "");
    }

    private boolean isRequireTransfer() {
        return getConfig().getBoolean("require-transfer", true);
    }

    private String getOriginId() {
        // Identifier this server uses when acting as a redirect source.
        // Defaults to the server name if not set in config.
        return getConfig().getString("origin-id", getServer().getName());
    }

    private List<String> getAllowedOrigins() {
        // If empty, allow any origin that presents the correct receive-hash.
        return getConfig().getStringList("allowed-origins");
    }

    private String getKickNoTransferMessage() {
        return ChatColor.translateAlternateColorCodes(
                '&',
                getConfig().getString("kick-no-transfer-message", "You must join this server via secure redirect.")
        );
    }

    private String getKickBadHashMessage() {
        return ChatColor.translateAlternateColorCodes(
                '&',
                getConfig().getString("kick-bad-hash-message", "Invalid or missing redirect token.")
        );
    }

    // -------- Command handling (/redirect ...) --------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (args.length >= 1 && args[0].equalsIgnoreCase("gen-hash")) {
            // /redirect gen-hash
            if (!sender.hasPermission("secureredirect.genhash")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }

            String newHash = generateHash();
            getConfig().set("send-hash", newHash);
            saveConfig();

            sender.sendMessage(ChatColor.GREEN + "Generated new send-hash:");
            sender.sendMessage(ChatColor.YELLOW + newHash);
            sender.sendMessage(ChatColor.GRAY + "Copy this into the other server's receive-hash in its config.yml.");
            return true;
        }

        // /redirect [player]
        Player target;

        if (args.length >= 1 && !"gen-hash".equalsIgnoreCase(args[0])) {
            // redirect another player
            if (!sender.hasPermission("secureredirect.redirect.others")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to redirect other players.");
                return true;
            }
            target = Bukkit.getPlayerExact(args[0]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[0]);
                return true;
            }
        } else {
            // redirect self
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Console must specify a player: /" + label + " <player>");
                return true;
            }
            if (!sender.hasPermission("secureredirect.redirect")) {
                sender.sendMessage(ChatColor.RED + "You don't have permission to do that.");
                return true;
            }
            target = (Player) sender;
        }

        redirectPlayer(sender, target);
        return true;
    }

    private void redirectPlayer(CommandSender initiator, Player target) {
        String host = getTargetHost();
        int port = getTargetPort();
        String sendHash = getSendHash();

        if (host == null || host.isEmpty()) {
            initiator.sendMessage(ChatColor.RED + "target-host is not set in config.yml.");
            return;
        }
        if (sendHash == null || sendHash.isEmpty()) {
            initiator.sendMessage(ChatColor.RED + "send-hash is empty. Use /redirect gen-hash first.");
            return;
        }

        String originId = getOriginId();
        String payload = originId + ":" + sendHash;
        byte[] tokenBytes = payload.getBytes(StandardCharsets.UTF_8);

        try {
            // Attach the origin + hash as a cookie on the client
            target.storeCookie(cookieKey, tokenBytes);
        } catch (IllegalStateException ex) {
            initiator.sendMessage(ChatColor.RED + "Could not store redirect token: " + ex.getMessage());
            return;
        }

        try {
            // Use Mojang's transfer packet (via Paper API) to move them
            target.transfer(host, port);
            initiator.sendMessage(ChatColor.GREEN + "Redirecting " + target.getName() + " to " + host + ":" + port);
        } catch (IllegalStateException ex) {
            initiator.sendMessage(ChatColor.RED + "Could not transfer player: " + ex.getMessage());
        }
    }

    private String generateHash() {
        // 192-bit random token hex-encoded
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // -------- Tab completion --------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("redirect")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase();
            List<String> suggestions = new ArrayList<>();

            if ("gen-hash".startsWith(input) && sender.hasPermission("secureredirect.genhash")) {
                suggestions.add("gen-hash");
            }

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(input)) {
                    suggestions.add(p.getName());
                }
            }
            return suggestions;
        }

        return Collections.emptyList();
    }

    // -------- Join security --------

    /**
     * Enforce require-transfer and then, for transferred connections, verify the cookie.
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        if (!player.isTransferred()) {
            if (isRequireTransfer()) {
                kickForNoTransfer(player);
            }
            return;
        }

        String expected = getReceiveHash();
        if (expected == null || expected.isEmpty()) {
            // No receive-hash configured => accept all transfers.
            return;
        }

        CompletableFuture<byte[]> future = player.retrieveCookie(cookieKey);
        future.thenAccept(value -> {
            if (value == null) {
                kickForBadHash(player);
                return;
            }

            String raw = new String(value, StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            if (sep <= 0 || sep == raw.length() - 1) {
                kickForBadHash(player);
                return;
            }

            String originId = raw.substring(0, sep);
            String receivedHash = raw.substring(sep + 1);

            if (!expected.equals(receivedHash)) {
                kickForBadHash(player);
                return;
            }

            List<String> allowed = getAllowedOrigins();
            if (!allowed.isEmpty() && !allowed.contains(originId)) {
                kickForBadHash(player);
                return;
            }

            // Optional: clear the cookie on successful validation to avoid re-use noise.
            try {
                player.storeCookie(cookieKey, new byte[0]);
            } catch (IllegalStateException ignored) {
                // If we can't clear it, it's not critical.
            }
        });
    }

    private void kickForBadHash(Player player) {
        String message = getKickBadHashMessage();
        // Must run kicks on the main server thread
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                player.kick(Component.text(message));
            }
        });
    }

    private void kickForNoTransfer(Player player) {
        String message = getKickNoTransferMessage();
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                player.kick(Component.text(message));
            }
        });
    }
}
