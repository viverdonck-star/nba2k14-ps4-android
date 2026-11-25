package com.nba2k14.lanceur;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Saves and logs out of the app's folder and back, through Android's file picker: Android/data is hidden from
 * other apps and erased when the app is uninstalled. A zip of the saves (home/1000), another of the logs to
 * report a problem, and the import of a saves zip, the current saves being set aside first.
 */
final class Echanges {
    static final int EXPORTER_SAUVEGARDES = 2;
    static final int EXPORTER_JOURNAL = 3;
    static final int IMPORTER_SAUVEGARDES = 4;
    private static final String PROFIL = "1000";
    private static final String[] JOURNAUX = {"journal.txt", "lanceur.log", "son.log", "rythme.txt", "reglages.ini", "compatibilite.txt", "pilote.txt"};

    // For the menu: "" when idle, a message while it works or when done, "!" before an error.
    static volatile String etat = "";

    private Echanges() {}

    /** The saves of the engine's user, as it keeps them: home/.local/share/shadPS4/home/1000. */
    private static File sauvegardes(Context context) {
        return new File(Moteur.home(context), ".local/share/shadPS4/home/" + PROFIL);
    }

    private static String date() {
        return new SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.ROOT).format(new Date());
    }

    static void demander(Activity activity, int requete) {
        final Intent intent;
        if (requete == IMPORTER_SAUVEGARDES) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        } else {
            intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.putExtra(Intent.EXTRA_TITLE, (requete == EXPORTER_SAUVEGARDES ? "NBA2K14-sauvegardes-"
                    : "NBA2K14-journal-") + date() + ".zip");
        }
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        etat = "";
        activity.startActivityForResult(intent, requete);
    }

    /** The picker's answer, handled on a thread of its own. */
    static void repondre(final Context context, final int requete, final Uri uri) {
        etat = Textes.t("En cours…", "Working…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (requete == EXPORTER_SAUVEGARDES) {
                        exporterSauvegardes(context, uri);
                    } else if (requete == EXPORTER_JOURNAL) {
                        exporterJournal(context, uri);
                    } else {
                        importerSauvegardes(context, uri);
                    }
                } catch (IOException | RuntimeException error) {
                    etat = "!" + Textes.t("Échec : ", "Failed: ") + error.getMessage();
                }
            }
        }).start();
    }

    private static String nom(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (RuntimeException unknown) {
            // the name only decorates the message
        }
        return uri.getLastPathSegment();
    }

    private static void exporterSauvegardes(Context context, Uri uri) throws IOException {
        final File profil = sauvegardes(context);
        if (!profil.isDirectory()) {
            throw new IOException(Textes.t("aucune sauvegarde pour l'instant", "no saves yet"));
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt");
             ZipOutputStream zip = new ZipOutputStream(out)) {
            ajouter(zip, profil, PROFIL);
        }
        etat = Textes.t("Sauvegardes exportées : ", "Saves exported: ") + nom(context, uri);
    }

    private static void exporterJournal(Context context, Uri uri) throws IOException {
        final File donnees = Moteur.donnees(context);
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wt");
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String journal : JOURNAUX) {
                final File fichier = new File(donnees, journal);
                if (fichier.isFile()) {
                    ajouter(zip, fichier, journal);
                }
            }
            final File moteur = new File(Moteur.home(context), ".local/share/shadPS4/log");
            if (moteur.isDirectory()) {
                ajouter(zip, moteur, "shadPS4-log");
            }
        }
        etat = Textes.t("Journal exporté : ", "Log exported: ") + nom(context, uri);
    }

    private static void ajouter(ZipOutputStream zip, File fichier, String chemin) throws IOException {
        if (fichier.isDirectory()) {
            final File[] enfants = fichier.listFiles();
            if (enfants != null) {
                for (File enfant : enfants) {
                    ajouter(zip, enfant, chemin + "/" + enfant.getName());
                }
            }
            return;
        }
        zip.putNextEntry(new ZipEntry(chemin));
        try (InputStream in = new FileInputStream(fichier)) {
            copier(in, zip);
        }
        zip.closeEntry();
    }

    private static void copier(InputStream in, OutputStream out) throws IOException {
        final byte[] tampon = new byte[1 << 16];
        int lu;
        while ((lu = in.read(tampon)) > 0) {
            out.write(tampon, 0, lu);
        }
    }

    /**
     * Unpacked next to the saves first; only a zip made by the export (everything under 1000/savedata) is taken.
     * The current saves then go to sauvegardes-avant-import-<date>, in the app's folder, and the new ones take
     * their place.
     */
    private static void importerSauvegardes(Context context, Uri uri) throws IOException {
        if (Accueil.gameRunning()) {
            throw new IOException(Textes.t("quitte d'abord le jeu", "quit the game first"));
        }
        final File profil = sauvegardes(context);
        final File temporaire = new File(profil.getParentFile(), PROFIL + "-import");
        effacer(temporaire);
        int fichiers = 0;
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             ZipInputStream zip = new ZipInputStream(in)) {
            for (ZipEntry entree; (entree = zip.getNextEntry()) != null; ) {
                final String chemin = entree.getName();
                if (!chemin.startsWith(PROFIL + "/savedata/") || chemin.contains("..")) {
                    throw new IOException(Textes.t("ce n'est pas un export de sauvegardes de cette app",
                            "this isn't a saves export from this app"));
                }
                if (entree.isDirectory()) {
                    continue;
                }
                final File cible = new File(temporaire, chemin.substring(PROFIL.length() + 1));
                cible.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(cible)) {
                    copier(zip, out);
                }
                fichiers++;
            }
        } catch (IOException error) {
            effacer(temporaire);
            throw error;
        }
        if (fichiers == 0) {
            effacer(temporaire);
            throw new IOException(Textes.t("le fichier est vide", "the file is empty"));
        }
        final File cote = new File(Moteur.donnees(context), "sauvegardes-avant-import-" + date());
        if (profil.exists()) {
            cote.mkdirs();
            if (!profil.renameTo(new File(cote, PROFIL))) {
                effacer(temporaire);
                throw new IOException(Textes.t("les sauvegardes actuelles n'ont pas pu être mises de côté",
                        "the current saves couldn't be set aside"));
            }
        }
        if (!temporaire.renameTo(profil)) {
            throw new IOException(Textes.t("copie impossible, tes anciennes sauvegardes sont dans ",
                    "couldn't copy, your old saves are in ") + cote.getName());
        }
        etat = Textes.t("Sauvegardes importées. Les anciennes sont dans ", "Saves imported. The old ones are in ")
                + cote.getName();
    }

    /** Only the import's own temporary folder. */
    private static void effacer(File fichier) {
        final File[] enfants = fichier.listFiles();
        if (enfants != null) {
            for (File enfant : enfants) {
                effacer(enfant);
            }
        }
        fichier.delete();
    }
}
