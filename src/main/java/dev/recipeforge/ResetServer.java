package dev.recipeforge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Reset all: puts everything World Forge changed back to normal. A backup zip is made first,
 * so nothing is lost for good. The history list is kept.
 */
public final class ResetServer {
    private ResetServer() {}

    public static void resetAll(ServerPlayer player, MinecraftServer server) {
        try {
            Path backup = ExportServer.createZip("worldforge-backup-before-reset-");

            Path data = PackSetup.packDir().resolve("data").toAbsolutePath().normalize();
            if (!data.startsWith(PackSetup.packDir().toAbsolutePath().normalize())) {
                ServerHandler.fail(player, "Reset stopped for safety.");
                return;
            }
            if (Files.exists(data)) {
                List<Path> all;
                try (Stream<Path> walk = Files.walk(data)) {
                    all = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
                }
                for (Path p : all) {
                    Files.deleteIfExists(p);
                }
            }
            StationData.clearAll();
            BiomeServer.clearAll();
            PacketDistributor.sendToAllPlayers(new ServerMessagePayload("STATION_SYNC", "", StationData.toSyncJson()));
            History.add(player.getGameProfile().getName(), "RESET_ALL", "everything", "", "");
            ServerHandler.ok(player, "Everything is back to normal."
                    + (backup != null ? " A backup was saved to " + backup.toAbsolutePath() + "." : "")
                    + " Reloading... (re-enter the world for structures, enchantments and world generation)");
            ServerHandler.reload(server);
        } catch (IOException e) {
            WorldForge.LOGGER.error("World Forge reset failed", e);
            ServerHandler.fail(player, "Reset failed: " + e.getMessage());
        }
    }
}
