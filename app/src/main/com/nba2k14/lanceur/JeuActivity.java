package com.nba2k14.lanceur;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.hardware.input.InputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The game, full screen, in a process of its own (":game"), with a side menu opened by the Odin's menu key or
 * L3 + R3, and Select + Start held for two seconds to quit, as in the World Cup 14 port. The process ends with
 * the game, and the menu (Accueil) comes back.
 */
public class JeuActivity extends Activity implements SurfaceHolder.Callback {
    private static final int SECOND = 1000;
    private static final int LARGEUR_MENU = 300;
    private static final int MAINTIEN_MS = 2000;
    private static final int AIDE_MS = 8000;
    private static final int PAS_MS = 30;
    private static final int[] TOUCHES_MENU = {
        KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_F12,
    };

    private Manette manette;
    // Heat. Above "severe" (or a forecast headroom of 1), 30 FPS until the device has been cool a minute.
    private static final int CADENCE_FROIDE_MS = 33;
    private static final long REFROIDI_MS = 60 * SECOND;
    private PowerManager alimentation;
    private int cadenceChoisie;
    private boolean ralenti;
    private long fraisDepuis, prochaineMarge;
    private float marge = Float.NaN;
    private TextView alerte;
    private ScrollView cadreMenu;
    // The on-screen pad, and when it shows: 0 = without a gamepad (default), 1 = always, 2 = never.
    private Tactile tactile;
    private Button boutonTactile;
    private InputManager entrees;
    private final InputManager.InputDeviceListener branchements = new InputManager.InputDeviceListener() {
        @Override
        public void onInputDeviceAdded(int id) {
            montrerTactile();
        }

        @Override
        public void onInputDeviceRemoved(int id) {
            montrerTactile();
        }

        @Override
        public void onInputDeviceChanged(int id) {
            montrerTactile();
        }
    };
    private final Handler handler = new Handler();
    private SurfaceView view;
    private LinearLayout menu;
    private TextView infos;
    private Button reprendre;
    private boolean infosVisibles;
    private int imagesAvant;
    // Select + Start: the hint at the start, the bar while they're held.
    private TextView aide;
    private LinearLayout quitter;
    private ProgressBar barre;
    private boolean select, start, aideMontree;
    private long debutMaintien;
    private final Runnable maintien = new Runnable() {
        @Override
        public void run() {
            final long ecoule = SystemClock.uptimeMillis() - debutMaintien;
            barre.setProgress((int) Math.min(MAINTIEN_MS, ecoule));
            if (ecoule >= MAINTIEN_MS) {
                Partie.arreter();
                finish();
                return;
            }
            handler.postDelayed(this, PAS_MS);
        }
    };

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Textes.anglais = "en".equals(getIntent().getStringExtra("langue"));
        manette = new Manette(new Runnable() {
            @Override
            public void run() {
                basculerMenu();
            }
        }, getIntent().getStringExtra("touches"));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            // The whole screen beside a camera notch too; the touch pad keeps clear of it (Tactile).
            final WindowManager.LayoutParams fenetre = getWindow().getAttributes();
            fenetre.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(fenetre);
        }
        alimentation = (PowerManager) getSystemService(Context.POWER_SERVICE);
        final boolean etire = "stretch".equals(getIntent().getStringExtra("format"));
        view = new SurfaceView(this);
        view.getHolder().addCallback(this);

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        // The game draws 16:9: on another screen shape, black bars rather than a stretched picture.
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View unused, int left, int top, int right, int bottom,
                                       int oldLeft, int oldTop, int oldRight, int oldBottom) {
                final int largeur = right - left, hauteur = bottom - top;
                if (largeur <= 0 || hauteur <= 0) {
                    return;
                }
                // Or stretched over the whole screen, when the player chose it.
                final int w = etire ? largeur : Math.min(largeur, hauteur * 16 / 9);
                final int h = etire ? hauteur : Math.min(hauteur, largeur * 9 / 16);
                final FrameLayout.LayoutParams taille = (FrameLayout.LayoutParams) view.getLayoutParams();
                if (taille.width != w || taille.height != h) {
                    taille.width = w;
                    taille.height = h;
                    view.post(new Runnable() {
                        @Override
                        public void run() {
                            view.setLayoutParams(taille);
                        }
                    });
                }
            }
        });
        infos = new TextView(this);
        infos.setTextColor(Color.WHITE);
        infos.setBackgroundColor(Color.argb(150, 0, 0, 0));
        infos.setPadding(dp(8), dp(4), dp(8), dp(4));
        infos.setVisibility(View.GONE);
        root.addView(infos, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.START));
        tactile = new Tactile(this, manette, new Runnable() {
            @Override
            public void run() {
                basculerMenu();
            }
        });
        root.addView(tactile, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        aide = new TextView(this);
        aide.setTextColor(Color.WHITE);
        aide.setTextSize(15);
        aide.setBackgroundColor(Color.argb(170, 0, 0, 0));
        aide.setPadding(dp(14), dp(8), dp(14), dp(8));
        aide.setVisibility(View.GONE);
        final FrameLayout.LayoutParams basGauche = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        basGauche.setMargins(dp(16), 0, 0, dp(16));
        root.addView(aide, basGauche);
        alerte = new TextView(this);
        alerte.setTextColor(Color.WHITE);
        alerte.setTextSize(15);
        alerte.setBackgroundColor(Color.argb(190, 150, 20, 20));
        alerte.setPadding(dp(14), dp(8), dp(14), dp(8));
        alerte.setVisibility(View.GONE);
        final FrameLayout.LayoutParams haut = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        haut.setMargins(0, dp(64), 0, 0);
        root.addView(alerte, haut);
        root.addView(creerQuitter(), new FrameLayout.LayoutParams(dp(320), FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        root.addView(creerMenu(), new FrameLayout.LayoutParams(dp(LARGEUR_MENU),
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END));
        setContentView(root);
        Accueil.pleinEcran(this);
        entrees = (InputManager) getSystemService(Context.INPUT_SERVICE);
        entrees.registerInputDeviceListener(branchements, handler);
        appliquerReglagesTactiles();
        montrerTactile();

        try {
            cadenceChoisie = Integer.parseInt(getIntent().getStringExtra("flip_ms"));
            if (Partie.sansMoteur(this)) {
                surveiller(); // the heat watch too, to try it
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        montrerAide();
                    }
                });
                return;
            }
            if (!Partie.enCours()) {
                ouvrirJeu(Uri.parse(getIntent().getStringExtra("uri")));
            }
            Partie.demarrer(this, getIntent().getStringExtra("flip_ms"),
                    getIntent().getStringExtra("flip_intervalle"), getIntent().getStringExtra("msaa"),
                    getIntent().getStringExtra("pilote"));
        } catch (IOException | RuntimeException error) {
            Partie.message(this, Textes.t("Le jeu n'a pas pu démarrer : ", "The game couldn't start: ")
                    + error.getMessage() + Textes.t(". Essaie un autre pilote graphique dans Réglages ; le détail est "
                    + "dans le journal (SAUVEGARDES).", ". Try another graphics driver in Settings; the details are "
                    + "in the log (SAVES)."));
            finish();
            return;
        }
        startForegroundService(new Intent(this, PartieService.class));
        surveiller();
    }

    /**
     * Opens the game's files and gives the engine a folder of links to them: /proc/self/fd/<n>, kept by the
     * engine's process (Ecran.garder, Moteur.lierJeu).
     */
    private void ouvrirJeu(Uri arbre) throws IOException {
        final List<String> chemins = new ArrayList<>();
        final List<Integer> descripteurs = new ArrayList<>();
        for (Dossier.Fichier fichier : Dossier.fichiersDuJeu(this, arbre)) {
            try (ParcelFileDescriptor ouvert = Dossier.ouvrir(this, fichier.uri)) {
                final int garde = Ecran.garder(ouvert.getFd());
                if (garde < 0) {
                    throw new IOException("descripteur " + fichier.chemin);
                }
                chemins.add(fichier.chemin);
                descripteurs.add(garde);
            }
        }
        if (!chemins.contains("eboot.bin")) {
            throw new IOException("dossier du jeu illisible");
        }
        Moteur.lierJeu(this, chemins, descripteurs);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            Accueil.pleinEcran(this);
        }
    }

    private Button bouton(String text, View.OnClickListener action) {
        final Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(action);
        return button;
    }

    private View creerMenu() {
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(16), dp(20), dp(16), dp(20));
        cadreMenu = new ScrollView(this);
        cadreMenu.setBackgroundColor(Color.argb(235, 12, 12, 14));
        cadreMenu.setVisibility(View.GONE);
        cadreMenu.addView(menu);
        final TextView title = new TextView(this);
        title.setText("NBA 2K14");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setPadding(0, 0, 0, dp(16));
        menu.addView(title);
        reprendre = bouton(Textes.t("Reprendre", "Resume"), new View.OnClickListener() {
            @Override
            public void onClick(View unused) {
                fermerMenu();
            }
        });
        menu.addView(reprendre);
        menu.addView(bouton(Textes.t("Afficher les infos", "Show info"), new View.OnClickListener() {
            @Override
            public void onClick(View unused) {
                infosVisibles = !infosVisibles;
                infos.setVisibility(infosVisibles ? View.VISIBLE : View.GONE);
            }
        }));
        boutonTactile = bouton(texteTactile(), new View.OnClickListener() {
            @Override
            public void onClick(View unused) {
                getSharedPreferences("jeu", MODE_PRIVATE).edit().putInt("tactile", (modeTactile() + 1) % 3).apply();
                boutonTactile.setText(texteTactile());
                montrerTactile();
            }
        });
        menu.addView(boutonTactile);
        final Button[] reglagesTactiles = new Button[2];
        reglagesTactiles[0] = bouton(texteTailleTactile(), new View.OnClickListener() {
            @Override
            public void onClick(View unused) {
                getSharedPreferences("jeu", MODE_PRIVATE).edit()
                        .putInt("tactile_taille", (preference("tactile_taille") + 1) % 3).apply();
                reglagesTactiles[0].setText(texteTailleTactile());
                appliquerReglagesTactiles();
            }
        });
        reglagesTactiles[1] = bouton(texteOpaciteTactile(), new View.OnClickListener() {
            @Override
            public void onClick(View unused) {
                getSharedPreferences("jeu", MODE_PRIVATE).edit()
                        .putInt("tactile_opacite", (preference("tactile_opacite") + 1) % 3).apply();
                reglagesTactiles[1].setText(texteOpaciteTactile());
                appliquerReglagesTactiles();
            }
        });
        menu.addView(reglagesTactiles[0]);
        menu.addView(reglagesTactiles[1]);
        menu.addView(bouton(Textes.t("Retour au menu (le match continue)", "Back to the menu (the game goes on)"),
                new View.OnClickListener() {
            @Override
            public void onClick(View unused) {
                fermerMenu();
                startActivity(new Intent(JeuActivity.this, Accueil.class));
            }
        }));
        menu.addView(bouton(Textes.t("Quitter le jeu", "Quit the game"), new View.OnClickListener() {
            @Override
            public void onClick(View unused) {
                Partie.arreter();
                finish();
            }
        }));
        return cadreMenu;
    }

    private int preference(String cle) {
        return getSharedPreferences("jeu", MODE_PRIVATE).getInt(cle, 0);
    }

    private String texteTailleTactile() {
        final int taille = preference("tactile_taille");
        return Textes.t("Taille des boutons : ", "Button size: ") + (taille == 0 ? Textes.t("normale", "normal")
                : taille == 1 ? Textes.t("grande", "large") : Textes.t("petite", "small"));
    }

    private String texteOpaciteTactile() {
        final int opacite = preference("tactile_opacite");
        return Textes.t("Boutons : ", "Buttons: ") + (opacite == 0 ? Textes.t("normaux", "normal")
                : opacite == 1 ? Textes.t("discrets", "faint") : Textes.t("bien visibles", "bold"));
    }

    private void appliquerReglagesTactiles() {
        final int taille = preference("tactile_taille"), opacite = preference("tactile_opacite");
        tactile.reglages(taille == 1 ? 1.2f : taille == 2 ? 0.85f : 1f,
                opacite == 1 ? 0.55f : opacite == 2 ? 1.6f : 1f);
    }

    /**
     * Android's thermal status (10) and headroom (11): the same signals the Android Dynamic Performance Framework
     * gives games. Too hot: the screen paces the engine at 30 FPS (Ecran.cadence, no restart); a minute cool
     * again: back to the player's choice. Above "critical", a plain warning: Android may stop the game.
     */
    private void surveillerChaleur() {
        if (Build.VERSION.SDK_INT < 29 || alimentation == null) {
            return;
        }
        final int etat = alimentation.getCurrentThermalStatus();
        final long maintenant = SystemClock.uptimeMillis();
        if (Build.VERSION.SDK_INT >= 30 && maintenant >= prochaineMarge) {
            prochaineMarge = maintenant + 5 * SECOND; // Android answers NaN when asked more often
            marge = alimentation.getThermalHeadroom(10);
        }
        final boolean tropChaud = etat >= PowerManager.THERMAL_STATUS_SEVERE || (!Float.isNaN(marge) && marge >= 1f);
        final boolean frais = etat <= PowerManager.THERMAL_STATUS_LIGHT && (Float.isNaN(marge) || marge < 0.85f);
        if (tropChaud && !ralenti && cadenceChoisie > 0 && cadenceChoisie < CADENCE_FROIDE_MS) {
            ralenti = true;
            fraisDepuis = 0;
            Ecran.cadence(CADENCE_FROIDE_MS);
        } else if (ralenti) {
            if (!frais) {
                fraisDepuis = 0;
            } else if (fraisDepuis == 0) {
                fraisDepuis = maintenant;
            } else if (maintenant - fraisDepuis >= REFROIDI_MS) {
                ralenti = false;
                Ecran.cadence(cadenceChoisie);
            }
        }
        final String texte = etat >= PowerManager.THERMAL_STATUS_CRITICAL
                ? Textes.t("Appareil très chaud : mets le jeu en pause et laisse-le refroidir.",
                           "Device very hot: pause the game and let it cool down.")
                : ralenti ? Textes.t("Appareil chaud : 30 images/s le temps qu'il refroidisse.",
                                     "Device hot: 30 FPS while it cools down.") : null;
        alerte.setText(texte != null ? texte : "");
        alerte.setVisibility(texte != null ? View.VISIBLE : View.GONE);
    }

    private int modeTactile() {
        return getSharedPreferences("jeu", MODE_PRIVATE).getInt("tactile", 0);
    }

    private String texteTactile() {
        final int mode = modeTactile();
        return Textes.t("Commandes tactiles : ", "Touch controls: ") + (mode == 0
                ? Textes.t("sans manette", "without a gamepad")
                : mode == 1 ? Textes.t("toujours", "always") : Textes.t("jamais", "never"));
    }

    /** A gamepad that Android sees: built in (the Odin's) or plugged in, not the screen or a keyboard. */
    private static boolean manetteBranchee() {
        for (int id : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual()) {
                continue;
            }
            final int sources = device.getSources();
            if ((sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                    || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
                return true;
            }
        }
        return false;
    }

    private void montrerTactile() {
        final int mode = modeTactile();
        final boolean visible = mode == 1 || (mode == 0 && !manetteBranchee());
        if (!visible) {
            tactile.relacher();
        }
        tactile.setVisibility(visible ? View.VISIBLE : View.GONE);
        aide.setText(visible ? Textes.t("≡ en haut, ou Retour : le menu de l'app (reprendre, quitter…)",
                "≡ at the top, or Back: the app's menu (resume, quit…)")
                : Textes.t("Maintiens Select + Start pour quitter", "Hold Select + Start to quit"));
    }

    /** "Quitting" and a bar filling up while Select + Start are held, in the middle of the screen. */
    private View creerQuitter() {
        quitter = new LinearLayout(this);
        quitter.setOrientation(LinearLayout.VERTICAL);
        quitter.setPadding(dp(24), dp(16), dp(24), dp(20));
        quitter.setBackgroundColor(Color.argb(215, 12, 12, 14));
        quitter.setVisibility(View.GONE);
        final TextView titre = new TextView(this);
        titre.setText(Textes.t("Fermeture du jeu…", "Quitting…"));
        titre.setTextColor(Color.WHITE);
        titre.setTextSize(22);
        titre.setGravity(Gravity.CENTER);
        quitter.addView(titre);
        final TextView detail = new TextView(this);
        detail.setText(Textes.t("Lâche pour continuer à jouer", "Let go to keep playing"));
        detail.setTextColor(Color.LTGRAY);
        detail.setTextSize(14);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(4), 0, dp(12));
        quitter.addView(detail);
        barre = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        barre.setMax(MAINTIEN_MS);
        barre.setProgressTintList(ColorStateList.valueOf(Color.rgb(227, 27, 35)));
        quitter.addView(barre, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10)));
        return quitter;
    }

    /** Select and Start, also passed on to the game: both held starts the countdown, letting go cancels it. */
    private void quitterSiMaintenus(KeyEvent event) {
        final int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_BUTTON_SELECT && code != KeyEvent.KEYCODE_BUTTON_START) {
            return;
        }
        final boolean bas = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT) {
            select = bas;
        } else {
            start = bas;
        }
        final boolean maintenus = select && start;
        if (maintenus && quitter.getVisibility() != View.VISIBLE) {
            debutMaintien = SystemClock.uptimeMillis();
            barre.setProgress(0);
            quitter.setVisibility(View.VISIBLE);
            handler.post(maintien);
        } else if (!maintenus && quitter.getVisibility() == View.VISIBLE) {
            handler.removeCallbacks(maintien);
            quitter.setVisibility(View.GONE);
        }
    }

    /** The hint, from the game's first picture on (not over the black start-up), then fading out. */
    private void montrerAide() {
        aideMontree = true;
        aide.setVisibility(View.VISIBLE);
        aide.animate().alpha(0f).setStartDelay(AIDE_MS - SECOND).setDuration(SECOND).withEndAction(new Runnable() {
            @Override
            public void run() {
                aide.setVisibility(View.GONE);
            }
        });
    }

    private boolean menuOuvert() {
        return cadreMenu.getVisibility() == View.VISIBLE;
    }

    private void basculerMenu() {
        if (menuOuvert()) {
            fermerMenu();
        } else {
            tactile.relacher(); // nothing stays held in the game behind the menu
            tactile.setEnabled(false);
            cadreMenu.setVisibility(View.VISIBLE);
            cadreMenu.scrollTo(0, 0);
            reprendre.requestFocus();
        }
    }

    private void fermerMenu() {
        cadreMenu.setVisibility(View.GONE);
        tactile.setEnabled(true);
        Accueil.pleinEcran(this);
    }

    /** Hottest readable thermal sensor, or a dash where Android hides them. */
    private static String temperature() {
        final File[] zones = new File("/sys/class/thermal").listFiles();
        int best = 0;
        if (zones != null) {
            for (File zone : zones) {
                try {
                    final String type = new String(Files.readAllBytes(new File(zone, "type").toPath()), "UTF-8");
                    if (type.startsWith("cpu") || type.startsWith("gpu")) {
                        final String value = new String(Files.readAllBytes(new File(zone, "temp").toPath()), "UTF-8");
                        best = Math.max(best, Integer.parseInt(value.trim()));
                    }
                } catch (IOException | NumberFormatException | SecurityException hidden) {
                    continue;
                }
            }
        }
        return best > 0 ? (best / 1000) + " °C" : "--";
    }

    /** Refreshes the overlay and closes the screen if the engine stops by itself. */
    private void surveiller() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!Partie.enCours() && !Partie.sansMoteur(JeuActivity.this)) {
                    if (!Partie.arretDemande()) {
                        // Not the player's doing: most often Android ending it for memory.
                        Partie.message(JeuActivity.this, Textes.t("Le jeu s'est arrêté tout seul (souvent : plus "
                                + "assez de mémoire). Pour savoir pourquoi : SAUVEGARDES, puis Exporter le journal.",
                                "The game stopped by itself (often: out of memory). To know why: SAVES, then "
                                + "Export the log."));
                    }
                    finish();
                    return;
                }
                surveillerChaleur();
                final int images = Ecran.images();
                if (!aideMontree && images > 0) {
                    montrerAide();
                }
                if (infosVisibles) {
                    infos.setText((images - imagesAvant) + Textes.t(" images/s   ", " fps   ") + Ecran.hertz() + " Hz   "
                            + temperature() + (Float.isNaN(marge) ? "" : String.format(java.util.Locale.ROOT,
                            Textes.t("   marge %.0f %%", "   headroom %.0f %%"), Math.max(0f, 100 * (1 - marge))))
                            + (ralenti ? Textes.t("   (ralenti : chaleur)", "   (slowed: heat)") : ""));
                }
                imagesAvant = images;
                handler.postDelayed(this, SECOND);
            }
        }, SECOND);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Ecran.surface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Ecran.surface(null);
    }

    private static boolean toucheMenu(int code) {
        for (int key : TOUCHES_MENU) {
            if (key == code) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (toucheMenu(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                basculerMenu();
            }
            return true;
        }
        if (menuOuvert()) {
            return super.dispatchKeyEvent(event); // the gamepad drives the menu, not the game
        }
        if (event.getRepeatCount() == 0) {
            quitterSiMaintenus(event);
        }
        if (manette.key(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!menuOuvert() && (event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            return manette.motion(event);
        }
        return super.dispatchGenericMotionEvent(event);
    }

    /** Back opens the menu: leaving the game is always a deliberate choice. */
    @Override
    public void onBackPressed() {
        basculerMenu();
    }

    /** The game's process ends with the game: its descriptors and links go with it. */
    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (entrees != null) {
            entrees.unregisterInputDeviceListener(branchements);
        }
        super.onDestroy();
        if (!Partie.enCours()) {
            Partie.arreter();
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }
}
