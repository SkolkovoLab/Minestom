package net.minestom.server.extras.viaversion;

import com.viaversion.viabackwards.api.ViaBackwardsPlatform;

import java.io.File;
import java.util.logging.Logger;

final class MinestomViaBackwardsPlatform implements ViaBackwardsPlatform {
    private final Logger logger;
    private final File dataFolder;

    MinestomViaBackwardsPlatform(Logger logger, File dataFolder) {
        this.logger = logger;
        this.dataFolder = dataFolder;
    }

    @Override
    public Logger getLogger() {
        return this.logger;
    }

    @Override
    public File getDataFolder() {
        return this.dataFolder;
    }

    @Override
    public void disable() {

    }
}
