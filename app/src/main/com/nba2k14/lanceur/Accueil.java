package com.nba2k14.lanceur;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.libsdl.app.SDLActivity;

/**
 * The menu (liblanceur.so, jni/lanceur.cpp), in the app's main process. The game folder is picked once with
 * Android's folder picker; reading it stays allowed (a persisted permission) and the native code reads its files
 * through /proc/self/fd/<n>. Play starts JeuActivity, the game, in a process of its own (":game").
 */
public class Accueil extends SDLActivity {
    private static final String PREFS = "jeu";
    private static final int CHOISIR = 1;
    private static final String JEU = ":game";
    private static final Uri DOSSIER_GAMES =
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Games");

    private static Accueil self;
    // Kept open while the menu uses them: their descriptors are the native paths.
    private static final List<ParcelFileDescriptor> ouverts = new ArrayList<>();
    private static volatile String etat = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        self = this;
        Compatibilite.preparer(getApplicationContext());
        super.onCreate(savedInstanceState);
        pleinEcran(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            pleinEcran(this);
        }
    }

    /** The whole screen, without Android's status and navigation bars. */
    static void pleinEcran(android.app.Activity activity) {
        final android.view.Window window = activity.getWindow();
        if (android.os.Build.VERSION.SDK_INT < 30) {
            // Android 8 to 10: the older flags, deprecated since but the only ones there.
            window.getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            return;
        }
        window.setDecorFitsSystemWindows(false);
        final android.view.WindowInsetsController controller = window.getInsetsController();
        if (controller != null) {
            controller.hide(android.view.WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[] {"c++_shared", "lanceur"};
    }

    // ---------------------------------------------------------------- native

    /** Settings, logs and saves: the app's own folder on shared storage, Android/data/<package>/files. */
    public static String dataFolder() {
        return Moteur.donnees(self).getAbsolutePath();
    }

    /** The folder kept from last time, or null. */
    public static String gameFolder() {
        return self.getSharedPreferences(PREFS, MODE_PRIVATE).getString("uri", null);
    }

    public static String gameName() {
        return self.getSharedPreferences(PREFS, MODE_PRIVATE).getString("nom", "");
    }

    /** A folder's game files as "<relative path>\t<size>\t/proc/self/fd/<n>" lines, or null if it can't be read. */
    public static String openFolder(String uri) {
        fermer();
        final List<Dossier.Fichier> fichiers = Dossier.fichiersDuJeu(self, Uri.parse(uri));
        if (fichiers.isEmpty()) {
            return null;
        }
        final StringBuilder lignes = new StringBuilder();
        for (Dossier.Fichier fichier : fichiers) {
            String chemin = "";
            try {
                final ParcelFileDescriptor descripteur = Dossier.ouvrir(self, fichier.uri);
                ouverts.add(descripteur);
                chemin = "/proc/self/fd/" + descripteur.getFd();
            } catch (IOException | RuntimeException unreadable) {
                // listed without a path: the menu names it as unreadable
            }
            lignes.append(fichier.chemin).append('\t').append(fichier.taille).append('\t').append(chemin).append('\n');
        }
        return lignes.toString();
    }

    private static void fermer() {
        for (ParcelFileDescriptor descripteur : ouverts) {
            try {
                descripteur.close();
            } catch (IOException ignored) {
                // closing only
            }
        }
        ouverts.clear();
    }

    /** Android's folder picker, opened on Games; its answer goes to nativeFolderPicked. */
    public static void pickFolder() {
        self.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOSSIER_GAMES);
                self.startActivityForResult(intent, CHOISIR);
            }
        });
    }

    /** The checked folder is the game from now on. */
    public static void keepFolder(String uri) {
        self.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("uri", uri).putString("nom", Dossier.nom(self, Uri.parse(uri))).commit();
    }

    /** Whether the game's process is still there: Play then brings it back. */
    public static boolean gameRunning() {
        final ActivityManager manager = (ActivityManager) self.getSystemService(Context.ACTIVITY_SERVICE);
        final List<ActivityManager.RunningAppProcessInfo> processus = manager.getRunningAppProcesses();
        if (processus != null) {
            for (ActivityManager.RunningAppProcessInfo info : processus) {
                if (info.processName.endsWith(JEU)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** "fr" or "en", for the messages Java shows. */
    public static void setLanguage(String code) {
        Textes.anglais = code.equals("en");
    }

    /** Preparation progress for the menu: "" when idle, a message while it works, "!" before an error. */
    public static String state() {
        return etat;
    }

    /** Prepares the engine (first start, moving old saves, player name), then starts the game's activity. */
    public static void play(final String fps, final String msaa, final String nom, final String touches,
                            final String pilote, final String format) {
        final String uri = gameFolder();
        if (gameRunning()) {
            lancerJeu(uri, fps, msaa, touches, pilote, format);
            return;
        }
        etat = Textes.t("Préparation…", "Getting ready…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Moteur.preparer(self, new Moteur.Progression() {
                        @Override
                        public void message(String texte) {
                            etat = texte;
                        }
                    });
                    Moteur.ecrireNomJoueur(self, nom);
                    Moteur.installerModuleSon(self, uri == null ? null : Uri.parse(uri));
                    etat = "";
                    lancerJeu(uri, fps, msaa, touches, pilote, format);
                } catch (IOException error) {
                    etat = "!" + Textes.t("Préparation impossible : ", "Couldn't get ready: ") + error.getMessage();
                }
            }
        }).start();
    }

    private static void lancerJeu(final String uri, final String fps, final String msaa, final String touches,
                                  final String pilote, final String format) {
        self.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final Intent game = new Intent(self, JeuActivity.class);
                game.putExtra("uri", uri);
                game.putExtra("flip_ms", fps.equals("40") ? "25" : fps.equals("30") ? "33" : "0");
                game.putExtra("flip_intervalle", fps.equals("30") ? "2" : "1");
                game.putExtra("msaa", msaa);
                game.putExtra("langue", Textes.anglais ? "en" : "fr");
                game.putExtra("touches", touches);
                game.putExtra("pilote", Pilotes.choisi(pilote));
                game.putExtra("format", format);
                self.startActivity(game);
            }
        });
    }

    /** The app's version, as the About screen shows it. */
    public static String version() {
        try {
            return self.getPackageManager().getPackageInfo(self.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException impossible) {
            return "?";
        }
    }

    /** A message the game's screen left (Partie.message), once; "" when none. */
    public static String lastGameMessage() {
        final java.io.File fichier = new java.io.File(Moteur.donnees(self), "message-jeu.txt");
        try {
            final String texte = new String(java.nio.file.Files.readAllBytes(fichier.toPath()), "UTF-8");
            fichier.delete();
            return texte;
        } catch (IOException | RuntimeException none) {
            return "";
        }
    }

    /** Whether the PS4 audio module is installed, or sits in the game folder ready to be (Moteur.MODULE_SON). */
    public static boolean soundModule() {
        if (Moteur.moduleSon(self).isFile()) {
            return true;
        }
        final String uri = gameFolder();
        if (uri == null) {
            return false;
        }
        for (Dossier.Fichier fichier : Dossier.fichiersDuJeu(self, Uri.parse(uri))) {
            if (fichier.chemin.toLowerCase(java.util.Locale.ROOT).endsWith("libscengs2.sprx")) {
                return true;
            }
        }
        return false;
    }

    /** 30 or 40: the frame rate the Settings start with on this device (Compatibilite.cadenceConseillee). */
    public static String recommendedFps() {
        return Compatibilite.cadenceConseillee(self);
    }

    /** The screen's fastest refresh rate, for the frame rate's hint. */
    public static String screenHz() {
        return String.valueOf(Compatibilite.hertzMax(self));
    }

    /** "auto,turnip-gen8,…": the drivers the Settings offer, and the name of one. */
    public static String drivers() {
        return android.text.TextUtils.join(",", Pilotes.TOUS);
    }

    public static String driverName(String id) {
        return Pilotes.AUTO.equals(id) ? Textes.t("Auto : ", "Auto: ") + Pilotes.nom(Compatibilite.piloteAuto())
                : Pilotes.nom(id);
    }

    /** What this device has against what the engine needs (Compatibilite), for the DEVICE screen. */
    public static String compatibility() {
        return Compatibilite.rapport(self);
    }

    /** Saves and logs out of the app's folder, or saves back in: "exportSaves", "exportLog" or "importSaves". */
    public static void exchange(final String what) {
        self.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Echanges.demander(self, what.equals("exportSaves") ? Echanges.EXPORTER_SAUVEGARDES
                        : what.equals("exportLog") ? Echanges.EXPORTER_JOURNAL : Echanges.IMPORTER_SAUVEGARDES);
            }
        });
    }

    /** The last exchange: "" when none, a message, "!" before an error. */
    public static String exchangeState() {
        return Echanges.etat;
    }

    static native void nativeFolderPicked(String uri);

    // ---------------------------------------------------------------- picker

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (request != CHOISIR) {
            Echanges.repondre(this, request, data.getData());
            return;
        }
        final Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException notKept) {
            // readable now, though not after a restart
        }
        nativeFolderPicked(uri.toString());
    }

    @Override
    protected void onDestroy() {
        fermer();
        super.onDestroy();
    }
}
