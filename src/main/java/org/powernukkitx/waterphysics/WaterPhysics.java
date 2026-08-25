package org.powernukkitx.waterphysics;

import org.powernukkitx.plugin.PluginBase;
import org.powernukkitx.plugin.annotation.PluginMeta;

@PluginMeta(
        name = "WaterPhysics",
        version = "1.0.0",
        authors = {
                "Buddelbubi"
        },
        api = {
                "3.0.0"
        },
        website = "https://github.com/Buddelbubi/WaterPhysics"
)
public class WaterPhysics extends PluginBase {

    private static WaterPhysics INSTANCE;

    @Override
    public void onEnable() {
        INSTANCE = this;
    }

    public static WaterPhysics get() {
        return INSTANCE;
    }
}