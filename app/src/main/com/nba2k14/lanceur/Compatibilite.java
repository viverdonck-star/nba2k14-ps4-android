package com.nba2k14.lanceur;

import android.app.ActivityManager;
import android.content.Context;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What this device has against what the engine needs, for the menu's DEVICE screen and the log.
 *
 * The engine needs an arm64 Android, a Qualcomm Adreno graphics chip of the 6xx, 7xx or 8xx families (the app's
 * Turnip driver knows only those) and memory for the PS4's 8 GB. Speed is a guess from the chip's number: only the
 * Odin 3 (Adreno 830) was measured. The report, as the menu reads it: the verdict on the first line ("OK", "PEUT"
 * or "NON"), then a line per check, "<+|?|->\t<what>\t<detail>".
 */
final class Compatibilite {
    private static final long GIO = 1L << 30;
    private static volatile String gpu;

    private Compatibilite() {}

    /** The graphics chip's name, read once from a throwaway OpenGL context (on its own thread: none is current). */
    static void preparer(final Context context) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                gpu = essai(context, lireGpu());
                ecrire(context, construire(context));
            }
        }, "compatibilite").start();
    }

    /** The report in the menu's language, waiting a moment for the chip's name. */
    static String rapport(Context context) {
        gpu();
        return construire(context);
    }

    /**
     * Test builds only (android:debuggable): a chip name in gpu-essai.txt, in the app's folder, stands for the real
     * one, to try the report and the driver choice for chips there's no device for. The final build ignores it.
     */
    private static String essai(Context context, String vrai) {
        if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return vrai;
        }
        try {
            final File fichier = new File(Moteur.donnees(context), "gpu-essai.txt");
            if (fichier.isFile()) {
                final String nom = new String(java.nio.file.Files.readAllBytes(fichier.toPath()), "UTF-8").trim();
                return nom.isEmpty() ? vrai : nom;
            }
        } catch (IOException | RuntimeException ignored) {
            // the real chip
        }
        return vrai;
    }

    private static String lireGpu() {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext contexte = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            final int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                return "";
            }
            final EGLConfig[] configs = new EGLConfig[1];
            final int[] nombre = new int[1];
            final int[] attributs = {EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
            if (!EGL14.eglChooseConfig(display, attributs, 0, configs, 0, 1, nombre, 0) || nombre[0] < 1) {
                return "";
            }
            contexte = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[] {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreatePbufferSurface(display, configs[0],
                    new int[] {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, surface, surface, contexte)) {
                return "";
            }
            final String nom = GLES20.glGetString(GLES20.GL_RENDERER);
            return nom != null ? nom.trim() : "";
        } catch (RuntimeException failed) {
            return "";
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface);
                }
                if (contexte != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, contexte);
                }
                // No eglTerminate: the display is the process's, and another user of it may be starting.
            }
        }
    }

    private static String construire(Context context) {
        final StringBuilder lignes = new StringBuilder();
        int pire = 2; // 2 = OK, 1 = maybe, 0 = no

        // The processor's instruction set: the engine and FEX are arm64 only.
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_64_BIT_ABIS) {
            arm64 |= abi.equals("arm64-v8a");
        }
        pire = Math.min(pire, ligne(lignes, arm64 ? 2 : 0, Textes.t("Processeur", "Processor"),
                (Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL + ", " : "")
                        + (arm64 ? Textes.t("64 bits ARM", "64-bit ARM")
                                 : Textes.t("pas 64 bits ARM : le moteur ne peut pas tourner",
                                            "not 64-bit ARM: the engine can't run"))));

        pire = Math.min(pire, ligneGpu(lignes));

        // Memory: the PS4 had 8 GB; a 12 GB device reports a little under 12.
        final ActivityManager.MemoryInfo memoire = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(memoire);
        final double gio = memoire.totalMem / (double) GIO;
        final int niveauMemoire = gio >= 10.5 ? 2 : gio >= 7 ? 1 : 0;
        pire = Math.min(pire, ligne(lignes, niveauMemoire, Textes.t("Mémoire", "Memory"),
                String.format(Textes.anglais ? Locale.ROOT : Locale.FRANCE, "%.1f ", gio) + Textes.t("Go", "GB") + (niveauMemoire == 2 ? ""
                        : niveauMemoire == 1 ? Textes.t(" : juste, le jeu peut se fermer en plein match",
                                                        ": tight, the game may close mid-match")
                        : Textes.t(" : trop peu pour un jeu PS4", ": too little for a PS4 game"))));

        // Memory pages: the engine was only ever run with 4 KB pages; a few recent phones use 16 KB.
        final long page = Os.sysconf(OsConstants._SC_PAGESIZE);
        pire = Math.min(pire, ligne(lignes, page == 4096 ? 2 : 1, Textes.t("Pages mémoire", "Memory pages"),
                (page / 1024) + Textes.t(" Ko", " KB") + (page == 4096 ? "" : Textes.t(" : jamais essayé avec le moteur",
                        ": never tried with the engine"))));

        final int hz = hertzMax(context);
        ligne(lignes, 2, Textes.t("Écran", "Screen"), hz + " Hz" + (hz >= 119
                ? Textes.t(" : 40 images/s régulières possibles", ": steady 40 FPS possible")
                : Textes.t(" : 30 images/s, les seules régulières sur cet écran", ": 30 FPS, the only steady rate on it")));
        final boolean connue = numeroAdreno(gpu()) > 0 || numeroMali(gpu()) > 0;
        ligne(lignes, connue ? 2 : 0, Textes.t("Pilote prévu", "Planned driver"), connue
                ? Pilotes.nom(piloteAuto()) + Textes.t(" (Réglages pour en essayer un autre)", " (Settings to try another)")
                : Textes.t("aucun ne convient à cette puce", "none suits this chip"));

        ligne(lignes, 2, "Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + "), "
                + Build.MANUFACTURER + " " + Build.MODEL);

        final String verdict = pire == 2 ? "OK" : pire == 1 ? "PEUT" : "NON";
        return verdict + "\n" + lignes;
    }

    /** The graphics chip and the speed to expect from it, for the Adreno families the driver knows. */
    private static int ligneGpu(StringBuilder lignes) {
        final String titre = Textes.t("Puce graphique", "Graphics chip");
        final String gpu = gpu();
        if (gpu.isEmpty()) {
            return ligne(lignes, 1, titre, Textes.t("inconnue (Android ne l'a pas dite)", "unknown (Android didn't say)"));
        }
        final int mali = numeroMali(gpu);
        if (mali != 0) {
            if (mali < 0) {
                return ligne(lignes, 0, titre, gpu + Textes.t(" : puce Mali trop ancienne pour le pilote PanVK",
                        ": Mali chip too old for the PanVK driver"));
            }
            return ligne(lignes, 1, titre, gpu + Textes.t(" : pilote libre PanVK, expérimental (essayé par d'autres sur "
                    + "Mali-G615, jamais avec ce jeu)", ": PanVK open driver, experimental (tried by others on Mali-G615, "
                    + "never with this game)"));
        }
        final int numero = numeroAdreno(gpu);
        if (numero == 0) {
            return ligne(lignes, 0, titre, gpu + Textes.t(" : ni Adreno (Snapdragon) ni Mali, aucun pilote du jeu ne la "
                    + "connaît", ": neither Adreno (Snapdragon) nor Mali, none of the game's drivers knows it"));
        }
        final int niveau;
        final String vitesse;
        if (numero >= 830) {
            niveau = 2;
            vitesse = Textes.t("comme l'Odin 3, où 40 images/s ont été mesurées", "like the Odin 3, measured at 40 FPS");
        } else if (numero >= 735 || (numero >= 800 && numero < 830)) {
            niveau = 1;
            vitesse = Textes.t("devrait tourner, moins fluide que l'Odin 3 (30 images/s conseillées)",
                    "should run, less fluid than the Odin 3 (30 FPS advised)");
        } else if (numero >= 640) {
            niveau = 1;
            vitesse = Textes.t("sans doute trop lente pour un match fluide", "likely too slow for a fluid match");
        } else {
            niveau = 0;
            vitesse = Textes.t("trop ancienne pour un jeu PS4", "too old for a PS4 game");
        }
        return ligne(lignes, niveau, titre, gpu + " : " + vitesse);
    }

    /** The Adreno number (830 for "Adreno (TM) 830"), or 0. */
    static int numeroAdreno(String gpu) {
        final Matcher adreno = Pattern.compile("Adreno[^0-9]*([0-9]{3})").matcher(gpu);
        return adreno.find() ? Integer.parseInt(adreno.group(1)) : 0;
    }

    /**
     * Mali and Immortalis: the number when PanVK may drive it (Valhall and later: G57, G68, G77, G78, G310 and up,
     * the G1 generation), -1 for older ones (Bifrost, Midgard), 0 when it isn't a Mali.
     */
    static int numeroMali(String gpu) {
        final Matcher mali = Pattern.compile("(Mali|Immortalis)-G([0-9]+)").matcher(gpu);
        if (!mali.find()) {
            return 0;
        }
        final int n = Integer.parseInt(mali.group(2));
        return n == 1 || n == 57 || n == 68 || n == 77 || n == 78 || n >= 310 ? n : -1;
    }

    static String gpu() {
        for (int i = 0; i < 40 && gpu == null; i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException stop) {
                break;
            }
        }
        return gpu != null ? gpu : "";
    }

    /** The driver this chip gets by default, as Bachata S4 matches them (Pilotes). */
    static String piloteAuto() {
        final String nom = gpu();
        if (numeroMali(nom) > 0) {
            return Pilotes.PANVK;
        }
        final int adreno = numeroAdreno(nom);
        if (adreno >= 700 && adreno < 800) {
            return Pilotes.SEPT;
        }
        if (adreno >= 600 && adreno < 700) {
            return Pilotes.SIX;
        }
        return Pilotes.HUIT;
    }

    /** The fastest refresh rate the screen offers. */
    static int hertzMax(Context context) {
        float best = 60;
        try {
            final Display ecran = ((DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE))
                    .getDisplay(Display.DEFAULT_DISPLAY);
            for (Display.Mode mode : ecran.getSupportedModes()) {
                best = Math.max(best, mode.getRefreshRate());
            }
        } catch (RuntimeException unknown) {
            // 60 Hz, the safe guess
        }
        return Math.round(best);
    }

    /**
     * The frame rate the menu starts with: 40 only where it was measured smooth (an Adreno 830 or newer) and where
     * the screen shows it evenly (120 Hz: 3 refreshes per frame); 30 everywhere else.
     */
    static String cadenceConseillee(Context context) {
        return numeroAdreno(gpu()) >= 830 && hertzMax(context) >= 119 ? "40" : "30";
    }

    private static int ligne(StringBuilder lignes, int niveau, String quoi, String detail) {
        lignes.append(niveau == 2 ? '+' : niveau == 1 ? '?' : '-').append('\t').append(quoi).append('\t')
                .append(detail.replace('\t', ' ').replace('\n', ' ')).append('\n');
        return niveau;
    }

    /** Beside the other logs, for "Export the log". */
    private static void ecrire(Context context, String texte) {
        try (OutputStream out = new FileOutputStream(new File(Moteur.donnees(context), "compatibilite.txt"))) {
            out.write(texte.getBytes("UTF-8"));
        } catch (IOException | RuntimeException ignored) {
            // the menu still shows it
        }
    }
}
