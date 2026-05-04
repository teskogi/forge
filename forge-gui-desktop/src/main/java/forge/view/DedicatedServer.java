package forge.view;

import forge.game.GameType;
import forge.gamemodes.match.GameLobby.GameLobbyData;
import forge.gamemodes.match.HostedMatch;
import forge.gamemodes.match.LobbySlot;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.client.ClientGameLobby;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.interfaces.ILobbyListener;
import forge.interfaces.IUpdateable;
import forge.localinstance.properties.ForgeNetPreferences;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.net.HeadlessGuiDesktop;
import forge.util.BuildInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * Headless dedicated server for hosting online Forge games from a terminal.
 * The server itself does not occupy a player slot â all slots are open for
 * remote players. The first player to connect becomes the lobby admin.
 *
 * Usage: java -jar forge.jar server [--port PORT] [--players N] [--mode MODE]
 *
 * Options:
 *   --port PORT      Server port (default: 36743)
 *   --players N      Number of player slots, 2-8 (default: 4)
 *   --mode MODE      Game mode: commander, constructed, oathbreaker, brawl,
 *                    tinyLeaders (default: commander)
 */
public class DedicatedServer {

    private static final int DEFAULT_PORT = 36743;
    private static final int DEFAULT_PLAYERS = 4;

    public static void start(final String[] args) {
        int port = DEFAULT_PORT;
        int players = DEFAULT_PLAYERS;
        String mode = "commander";

        // Parse arguments
        for (int i = 1; i < args.length; i++) {
            if ("--port".equals(args[i]) && i + 1 < args.length) {
                try {
                    port = Integer.parseInt(args[++i]);
                } catch (NumberFormatException e) {
                    System.err.println("Invalid port number: " + args[i]);
                    System.exit(1);
                }
            } else if ("--players".equals(args[i]) && i + 1 < args.length) {
                try {
                    players = Integer.parseInt(args[++i]);
                    if (players < 2 || players > 8) {
                        System.err.println("Players must be between 2 and 8.");
                        System.exit(1);
                    }
                } catch (NumberFormatException e) {
                    System.err.println("Invalid player count: " + args[i]);
                    System.exit(1);
                }
            } else if ("--mode".equals(args[i]) && i + 1 < args.length) {
                mode = args[++i].toLowerCase(Locale.ROOT);
            }
        }

        final GameType gameType = parseGameType(mode);
        if (gameType == null) {
            System.err.println("Unknown game mode: " + mode);
            System.err.println("Valid modes: commander, constructed, oathbreaker, brawl, tinyLeaders");
            System.exit(1);
        }

        System.out.println("=== Forge Dedicated Server ===");
        System.out.println("Version: " + BuildInfo.getVersionString());
        System.out.println("Game mode: " + gameType);
        System.out.println("Player slots: " + players);
        System.out.println();

        // Initialize headless GUI
        System.out.println("[Server] Initializing headless environment...");
        GuiBase.setInterface(new HeadlessGuiDesktop());

        // Initialize game data (card database, preferences, etc.)
        System.out.println("[Server] Loading card database and game data...");
        FModel.initialize(null, preferences -> {
            preferences.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
            preferences.setPref(FPref.UI_LANGUAGE, "en-US");
            preferences.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
            FModel.getNetPreferences().setPref(ForgeNetPreferences.FNetPref.UPnP, "NEVER");
            return null;
        });
        System.out.println("[Server] Game data loaded successfully.");

        // Start network server
        final FServerManager serverManager = FServerManager.getInstance();
        serverManager.startServer(port);

        if (!serverManager.isHosting()) {
            System.err.println("[Server] Failed to start server on port " + port);
            System.exit(1);
        }

        // Create lobby with all OPEN slots
        final ServerGameLobby lobby = new ServerGameLobby();
        serverManager.setLobby(lobby);

        // Reconfigure slot 0 from LOCAL to OPEN (server has no player)
        final LobbySlot slot0 = lobby.getSlot(0);
        slot0.setType(LobbySlotType.OPEN);
        slot0.setName(null);
        slot0.setIsReady(false);

        // Add extra player slots (lobby starts with 2, add up to desired count)
        for (int i = 2; i < players; i++) {
            lobby.addSlot();
        }

        // Set game mode
        if (gameType != GameType.Constructed) {
            lobby.applyVariant(gameType);
        }

        // Set lobby listener for console output
        serverManager.setLobbyListener(new ILobbyListener() {
            @Override
            public void message(final String source, final String message) {
                if (source != null) {
                    System.out.println("[Chat] " + source + ": " + message);
                } else {
                    System.out.println("[Server] " + message);
                }
            }

            @Override
            public void update(final GameLobbyData state, final int slot) {
            }

            @Override
            public void close() {
            }

            @Override
            public ClientGameLobby getLobby() {
                return null;
            }
        });

        // Set lobby update listener
        lobby.setListener(new IUpdateable() {
            @Override
            public void update(final boolean fullUpdate) {
            }

            @Override
            public void update(final int slot, final LobbySlotType type) {
            }
        });

        // Print server info
        final String localAddr = FServerManager.getLocalAddress();
        System.out.println();
        System.out.println("[Server] Listening on port " + port);
        System.out.println("[Server] Local address: " + localAddr + ":" + port);

        final String externalAddr = FServerManager.getExternalAddress();
        if (externalAddr != null) {
            System.out.println("[Server] External address: " + externalAddr + ":" + port);
        }

        System.out.println();
        System.out.println("[Server] First player to connect will be the lobby admin.");
        System.out.println("[Server] Waiting for players to connect...");
        System.out.println();
        printHelp();

        // Command loop
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                if (!handleCommand(line, serverManager, lobby)) {
                    break;
                }
            }
        } catch (IOException e) {
            System.err.println("[Server] Error reading input: " + e.getMessage());
        }

        // Shutdown
        System.out.println("[Server] Shutting down...");
        serverManager.stopServer();
        System.out.println("[Server] Server stopped.");
    }

    private static GameType parseGameType(final String mode) {
        switch (mode) {
            case "commander":
                return GameType.Commander;
            case "constructed":
                return GameType.Constructed;
            case "oathbreaker":
                return GameType.Oathbreaker;
            case "brawl":
                return GameType.Brawl;
            case "tinyleaders":
                return GameType.TinyLeaders;
            default:
                return null;
        }
    }

    private static boolean handleCommand(final String input, final FServerManager server, final ServerGameLobby lobby) {
        final String cmd = input.toLowerCase(Locale.ROOT);

        // Try server slash commands first (e.g. /skipreconnect, /skiptimeout)
        if (cmd.startsWith("/") && server.handleCommand(input)) {
            return true;
        }

        switch (cmd) {
            case "help":
                printHelp();
                break;

            case "status":
                printStatus(server, lobby);
                break;

            case "start":
                startGame(lobby);
                break;

            case "kick":
                System.out.println("[Server] Usage: kick <slot_number>");
                break;

            case "stop":
            case "quit":
            case "exit":
                return false;

            default:
                if (cmd.startsWith("kick ")) {
                    handleKick(cmd.substring(5).trim(), lobby, server);
                } else {
                    System.out.println("[Server] Unknown command: " + input);
                    System.out.println("[Server] Type 'help' for available commands.");
                }
                break;
        }
        return true;
    }

    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  help              - Show this help message");
        System.out.println("  status            - Show server and lobby status");
        System.out.println("  start             - Start the game (all players must be ready)");
        System.out.println("  kick <slot>       - Kick player from a slot");
        System.out.println("  /skipreconnect    - Replace disconnected player with AI");
        System.out.println("  /skiptimeout      - Wait indefinitely for player reconnect");
        System.out.println("  stop              - Stop the server and exit");
        System.out.println();
    }

    private static void printStatus(final FServerManager server, final ServerGameLobby lobby) {
        System.out.println("--- Server Status ---");
        System.out.println("Hosting: " + server.isHosting());
        System.out.println("Match active: " + server.isMatchActive());
        System.out.println("Game mode: " + lobby.getGameType());

        final int slots = lobby.getNumberOfSlots();
        System.out.println("Lobby slots: " + slots);
        for (int i = 0; i < slots; i++) {
            final LobbySlot slot = lobby.getSlot(i);
            final String name = slot.getName() != null && !slot.getName().isEmpty()
                    ? slot.getName() : "(open)";
            System.out.printf("  Slot %d: [%s] %s (ready: %s)%n",
                    i, slot.getType(), name, slot.isReady());
        }

        if (server.isMatchActive()) {
            final HostedMatch match = lobby.getHostedMatch();
            if (match != null && match.getGame() != null) {
                System.out.println("Turn: " + match.getGame().getPhaseHandler().getTurn());
            }
        }
        System.out.println("---------------------");
    }

    private static void startGame(final ServerGameLobby lobby) {
        boolean allReady = true;
        int playerCount = 0;
        for (int i = 0; i < lobby.getNumberOfSlots(); i++) {
            final LobbySlot slot = lobby.getSlot(i);
            if (slot.getType() == LobbySlotType.OPEN) {
                continue;
            }
            playerCount++;
            if (!slot.isReady()) {
                allReady = false;
                System.out.println("[Server] Slot " + i + " (" + slot.getName() + ") is not ready.");
            }
        }

        if (playerCount < 2) {
            System.out.println("[Server] Need at least 2 players to start a game.");
            return;
        }

        if (!allReady) {
            System.out.println("[Server] Not all players are ready.");
            return;
        }

        System.out.println("[Server] Starting game...");
        final Runnable startGame = lobby.startGame();
        if (startGame != null) {
            new Thread(startGame, "GameThread").start();
            System.out.println("[Server] Game started!");
        } else {
            System.out.println("[Server] Failed to start game.");
        }
    }

    private static void handleKick(final String slotArg, final ServerGameLobby lobby, final FServerManager server) {
        try {
            final int slotIndex = Integer.parseInt(slotArg);
            final LobbySlot slot = lobby.getSlot(slotIndex);
            if (slot == null) {
                System.out.println("[Server] Invalid slot: " + slotIndex);
                return;
            }
            if (slot.getType() != LobbySlotType.REMOTE) {
                System.out.println("[Server] Slot " + slotIndex + " has no remote player to kick.");
                return;
            }
            final String name = slot.getName();
            lobby.disconnectPlayer(slotIndex);
            server.updateLobbyState();
            System.out.println("[Server] Kicked " + name + " from slot " + slotIndex + ".");
        } catch (NumberFormatException e) {
            System.out.println("[Server] Usage: kick <slot_number>");
        }
    }
}
