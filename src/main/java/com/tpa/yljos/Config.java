package com.tpa.yljos;

import net.neoforged.neoforge.common.ModConfigSpec;

public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Feature switch for weekday restriction
    public static final ModConfigSpec.BooleanValue RESTRICT_DAYS_ENABLED = BUILDER
            .comment("Whether to restrict non-op players from joining Monday through Thursday")
            .define("restrictDaysEnabled", true);

    public static final ModConfigSpec SPEC = BUILDER.build();
}