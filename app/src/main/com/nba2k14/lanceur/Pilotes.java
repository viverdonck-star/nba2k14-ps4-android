package com.nba2k14.lanceur;

import java.io.File;

/**
 * The Vulkan drivers in the engine's files, all Mesa builds for glibc (the engine is a glibc program, so
 * Android's own drivers can't be loaded), taken from Bachata S4 0.2.5's assets/drivers like the first one. Each chip
 * family gets the one Bachata S4 ships for it; the player can pick another in the menu's Settings.
 */
final class Pilotes {
    static final String AUTO = "auto";
    /** Turnip gen8 v5: the one measured on the Odin 3 (Adreno 830). */
    static final String HUIT = "turnip-gen8";
    /** Turnip vauzi-7xx: Adreno 7xx (Snapdragon 8 Gen 1 to 8 Gen 3, 7 series). */
    static final String SEPT = "turnip-7xx";
    /** Turnip mojo 26.1: a general build, the one for Adreno 6xx. */
    static final String SIX = "turnip-26.1";
    /** Turnip mojo 25.0: an older general build, a last try. */
    static final String ANCIEN = "turnip-25.0";
    /** PanVK V3 (kbase): Mali Valhall and later (MediaTek Dimensity, Google Tensor G1 to G4, Exynos up to 2100). */
    static final String PANVK = "panvk";

    static final String[] TOUS = {AUTO, HUIT, SEPT, SIX, ANCIEN, PANVK};

    private Pilotes() {}

    static String nom(String id) {
        switch (id) {
        case HUIT: return "Turnip gen8 (Adreno 8xx)";
        case SEPT: return "Turnip 7xx (Adreno 7xx)";
        case SIX: return "Turnip 26.1 (Adreno 6xx)";
        case ANCIEN: return "Turnip 25.0";
        case PANVK: return "PanVK (Mali)";
        default: return Textes.t("Automatique", "Automatic");
        }
    }

    /** The driver library in the engine's files; the default one keeps its original place. */
    static File bibliotheque(File racine, String id) {
        switch (id) {
        case SEPT: return new File(racine, "pilotes/turnip-7xx/libvulkan_freedreno.so");
        case SIX: return new File(racine, "pilotes/turnip-26.1/libvulkan_freedreno.so");
        case ANCIEN: return new File(racine, "pilotes/turnip-25.0/libvulkan_freedreno.so");
        case PANVK: return new File(racine, "pilotes/panvk/libvulkan_panfrost.so");
        default: return new File(racine, "turnip/libvulkan_freedreno.so");
        }
    }

    /** "auto" or a missing choice: the chip's own. */
    static String choisi(String id) {
        if (id == null || AUTO.equals(id)) {
            return Compatibilite.piloteAuto();
        }
        for (String connu : TOUS) {
            if (connu.equals(id)) {
                return id;
            }
        }
        return Compatibilite.piloteAuto();
    }
}
