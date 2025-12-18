package com.kieranbeals;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AntiGunpowderFarm implements ModInitializer {

    public static final String MOD_ID = "anti-gunpowder-farm";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Anti Gunpowder Farm Initialized!");
        // Register your event handler class
        GunpowderGuard.register();
    }
}
