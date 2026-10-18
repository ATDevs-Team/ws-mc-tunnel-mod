package org.atdevs.mctunnel;

import net.fabricmc.api.ClientModInitializer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class TunnelMod implements ClientModInitializer {
    private static TcpWebSocketBridge bridge;

    @Override
    public void onInitializeClient() {
        Path configPath = Path.of("config", "mc-ws-tunnel.properties");
        Properties config = new Properties();

        try {
            Files.createDirectories(configPath.getParent());

            if (Files.notExists(configPath)) {
                config.setProperty("listen.host", "127.0.0.1");
                config.setProperty("listen.port", "25565");
                config.setProperty(
                    "websocket.url",
                    "wss://play-atmc.atdevs.org/mc"
                );
                config.setProperty("websocket.token", "fd047bbe5ce1bcf85bb95981c8206c20cbf62a02e48840bd5861546794584310");

                try (OutputStream out = Files.newOutputStream(configPath)) {
                    config.store(out, "Minecraft WebSocket Tunnel");
                }

                System.out.println(
                    "[MC-WSTunnel] Created " + configPath
                    + ". Set websocket.token before connecting."
                );
            }

            try (InputStream in = Files.newInputStream(configPath)) {
                config.load(in);
            }

            String listenHost =
                config.getProperty("listen.host", "127.0.0.1").trim();

            int listenPort = Integer.parseInt(
                config.getProperty("listen.port", "25565").trim()
            );

            String websocketUrl =
                config.getProperty(
                    "websocket.url",
                    "ws://netboot-desktop:8765/mc"
                ).trim();

            String token =
                config.getProperty("websocket.token", "").trim();

            if (token.length() < 32) {
                System.err.println(
                    "[MC-WSTunnel] Configure websocket.token in "
                    + configPath + " with the shared secret."
                );
                return;
            }

            bridge = new TcpWebSocketBridge(
                listenHost,
                listenPort,
                websocketUrl,
                token
            );

            bridge.start();

        } catch (IOException | IllegalArgumentException e) {
            System.err.println(
                "[MC-WSTunnel] Initialization failed: " + e.getMessage()
            );
            e.printStackTrace();
        }
    }
}
