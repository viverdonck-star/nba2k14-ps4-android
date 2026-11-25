package com.nba2k14.lanceur;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Unpacks the engine from the APK's assets and starts it. The engine runs from the app's private files (Android
 * only runs programs from there); its home, with settings, saves and logs, is in the app's own folder on shared
 * storage.
 */
final class Moteur {
    private static final int VERSION = 8; // raised whenever the engine package changes
    private static final String SAUVEGARDES = "home/.local/share/shadPS4/home/1000/savedata/CUSA00104";

    private Moteur() {}

    static File racine(Context context) {
        return new File(context.getFilesDir(), "nba2k14");
    }

    /** Android/data/<package>/files. */
    static File donnees(Context context) {
        final File dossier = context.getExternalFilesDir(null);
        dossier.mkdirs();
        return dossier;
    }

    static File home(Context context) {
        return new File(donnees(context), "home");
    }

    static File socketImage(Context context) {
        return new File(racine(context), "tmp/image.sock");
    }

    static File journalRythme(Context context) {
        return new File(donnees(context), "rythme.txt");
    }

    /** Where the game's files appear for the engine: links to the open descriptors (JeuActivity). */
    static File dossierJeu(Context context) {
        return new File(racine(context), "jeu");
    }

    private static File marqueur(Context context) {
        return new File(racine(context), ".version");
    }

    interface Progression {
        void message(String texte);
    }

    /** Saves first: installing adds the default settings to the new home only where nothing is there. */
    static void preparer(Context context, Progression progression) throws IOException {
        deplacerHome(context, progression);
        if (!installe(context)) {
            installer(context, progression);
        }
        journalSobre(context);
    }

    /**
     * The engine logs everything by default (an empty filter, debug lines included), each line forced to disk:
     * a diagnostic setting. Warnings and errors are enough to understand a crash. Only an untouched filter
     * changes, once: a filter set by hand stays.
     */
    private static void journalSobre(Context context) {
        final File config = new File(home(context), ".local/share/shadPS4/config.json");
        try {
            final org.json.JSONObject racine = new org.json.JSONObject(
                    new String(Files.readAllBytes(config.toPath()), "UTF-8"));
            final org.json.JSONObject log = racine.optJSONObject("Log");
            if (log == null || !log.optString("filter", "?").isEmpty()) {
                return;
            }
            log.put("filter", "*:Warning");
            Files.write(config.toPath(), racine.toString(4).getBytes("UTF-8"));
        } catch (IOException | org.json.JSONException unreadable) {
            // the engine keeps its own setting
        }
    }

    /** The PS4 audio module the engine needs for sound: a console system file, so never shipped with the app. */
    static final String MODULE_SON = "libSceNgs2.sprx";

    static File moduleSon(Context context) {
        return new File(home(context), ".local/share/shadPS4/sys_modules/" + MODULE_SON);
    }

    /**
     * Copies the audio module from the game folder, where the player puts the one from their own console, into
     * the engine's system modules. Kept once there: the next games and updates find it. False when there is none:
     * the game then runs without sound.
     */
    static boolean installerModuleSon(Context context, android.net.Uri jeu) {
        final File cible = moduleSon(context);
        if (cible.isFile() && cible.length() > 0) {
            return true;
        }
        if (jeu == null) {
            return false;
        }
        for (Dossier.Fichier fichier : Dossier.fichiersDuJeu(context, jeu)) {
            final String nom = fichier.chemin.substring(fichier.chemin.lastIndexOf('/') + 1);
            if (!nom.equalsIgnoreCase(MODULE_SON)) {
                continue;
            }
            cible.getParentFile().mkdirs();
            final File partiel = new File(cible.getPath() + ".copie");
            try (android.os.ParcelFileDescriptor source = Dossier.ouvrir(context, fichier.uri);
                 InputStream in = new FileInputStream(source.getFileDescriptor());
                 OutputStream out = new FileOutputStream(partiel)) {
                final byte[] buffer = new byte[1 << 16];
                int count;
                while ((count = in.read(buffer)) > 0) {
                    out.write(buffer, 0, count);
                }
            } catch (IOException | RuntimeException unreadable) {
                partiel.delete();
                return false;
            }
            return partiel.renameTo(cible);
        }
        return false;
    }

    private static boolean installe(Context context) {
        try (InputStream in = new FileInputStream(marqueur(context))) {
            return in.read() == VERSION;
        } catch (IOException missing) {
            return false;
        }
    }

    private static void installer(Context context, Progression progression) throws IOException {
        final File root = racine(context);
        final File data = donnees(context);
        final byte[] buffer = new byte[1 << 16];
        final InputStream asset = context.getAssets().open("runtime.zip");
        final long total = Math.max(asset.available(), 1);
        try (ZipInputStream zip = new ZipInputStream(asset)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                progression.message(Textes.t("Installation du moteur : ", "Installing the engine: ")
                        + (int) (100 - asset.available() * 100 / total) + " %");
                // The engine's home goes with the player's data; the rest stays private.
                final File base = entry.getName().startsWith("home/") ? data : root;
                final File target = new File(base, entry.getName());
                if (!target.getCanonicalPath().startsWith(base.getCanonicalPath())) {
                    throw new IOException("chemin refusé : " + entry.getName());
                }
                if (entry.isDirectory()) {
                    target.mkdirs();
                    continue;
                }
                // Saves and settings belong to the player: an update only adds what is missing.
                if (base == data && target.exists()) {
                    continue;
                }
                target.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(target)) {
                    int count;
                    while ((count = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, count);
                    }
                }
                if (base == root) {
                    target.setExecutable(true, true);
                }
            }
        }
        new File(root, "tmp").mkdirs();
        // Two trial drivers of development builds, never used: 30 MB back.
        new File(root, "turnip/libvulkan-gen8-v4.so").delete();
        new File(root, "turnip/libvulkan-banner-a8xx.so").delete();
        try (OutputStream out = new FileOutputStream(marqueur(context))) {
            out.write(VERSION);
        }
    }

    /**
     * Early development builds kept the engine's home, saves included, in the private files. It is copied once to the app's
     * folder; the old copy is kept, renamed home-avant-0.8, until the player has checked their saves.
     */
    private static void deplacerHome(Context context, Progression progression) throws IOException {
        final File ancien = new File(racine(context), "home");
        if (!ancien.isDirectory()) {
            return;
        }
        final File nouveau = home(context);
        // Copied beside it, then renamed: a stopped copy never looks finished.
        final File partiel = new File(donnees(context), "home-copie");
        if (!nouveau.isDirectory()) {
            supprimerCopie(partiel);
            final List<File> fichiers = new ArrayList<>();
            lister(ancien, fichiers);
            long total = 1, fait = 0;
            for (File fichier : fichiers) {
                total += fichier.length();
            }
            final String base = ancien.getPath();
            for (File fichier : fichiers) {
                final File cible = new File(partiel, fichier.getPath().substring(base.length()));
                cible.getParentFile().mkdirs();
                Files.copy(fichier.toPath(), cible.toPath());
                fait += fichier.length();
                progression.message(Textes.t("Déplacement de tes sauvegardes : ", "Moving your saves: ")
                        + (int) (fait * 100 / total) + " %");
            }
            if (!partiel.renameTo(nouveau)) {
                throw new IOException("déplacement des sauvegardes");
            }
        }
        if (!ancien.renameTo(new File(racine(context), "home-avant-0.8"))) {
            throw new IOException("ancien dossier des sauvegardes");
        }
    }

    private static void lister(File dossier, List<File> fichiers) {
        final File[] enfants = dossier.listFiles();
        if (enfants == null) {
            return;
        }
        for (File enfant : enfants) {
            if (Files.isSymbolicLink(enfant.toPath())) {
                continue;
            }
            if (enfant.isDirectory()) {
                lister(enfant, fichiers);
            } else {
                fichiers.add(enfant);
            }
        }
    }

    /** Removes a partial copy made by this class (never the player's files elsewhere). */
    private static void supprimerCopie(File dossier) {
        final List<File> fichiers = new ArrayList<>();
        lister(dossier, fichiers);
        for (File fichier : fichiers) {
            fichier.delete();
        }
        supprimerDossiersVides(dossier);
    }

    private static void supprimerDossiersVides(File dossier) {
        final File[] enfants = dossier.listFiles();
        if (enfants != null) {
            for (File enfant : enfants) {
                if (enfant.isDirectory() && !Files.isSymbolicLink(enfant.toPath())) {
                    supprimerDossiersVides(enfant);
                }
            }
        }
        dossier.delete();
    }

    /** Replaces the links of the last game with links to these descriptors (all in the game's process). */
    static void lierJeu(Context context, List<String> chemins, List<Integer> descripteurs) throws IOException {
        final File jeu = dossierJeu(context);
        effacerLiens(jeu);
        for (int i = 0; i < chemins.size(); i++) {
            final File lien = new File(jeu, chemins.get(i));
            if (!lien.getCanonicalPath().startsWith(jeu.getCanonicalPath())) {
                throw new IOException("chemin refusé : " + chemins.get(i));
            }
            lien.getParentFile().mkdirs();
            try {
                Os.symlink("/proc/self/fd/" + descripteurs.get(i), lien.getPath());
            } catch (ErrnoException error) {
                throw new IOException("lien " + chemins.get(i) + " : " + error.getMessage());
            }
        }
    }

    /** The folder holds only links and folders this class made: links are removed, never what they point to. */
    private static void effacerLiens(File dossier) {
        final File[] enfants = dossier.listFiles();
        if (enfants == null) {
            return;
        }
        for (File enfant : enfants) {
            if (Files.isSymbolicLink(enfant.toPath())) {
                enfant.delete();
            } else if (enfant.isDirectory()) {
                effacerLiens(enfant);
                enfant.delete();
            }
        }
    }

    /** users.json names new profiles; an existing save keeps its own copy of the name. */
    static void ecrireNomJoueur(Context context, String nom) throws IOException {
        final String propre = nom.replaceAll("[^A-Za-z0-9_-]", "");
        final File users = new File(donnees(context), "home/.local/share/shadPS4/users.json");
        if (propre.isEmpty() || !users.isFile()) {
            return;
        }
        final String text = new String(Files.readAllBytes(users.toPath()), "UTF-8");
        Files.write(users.toPath(), text.replaceFirst("\"user_name\": \"[^\"]*\"", "\"user_name\": \"" + propre + "\"")
                .getBytes("UTF-8"));
        final File[] dossiers = new File(donnees(context), SAUVEGARDES).listFiles();
        if (dossiers == null) {
            return;
        }
        final File copies = new File(donnees(context), "sauvegarde-avant-nom");
        final File temporaire = new File(racine(context), "tmp/sauvegarde.tmp");
        for (File dossier : dossiers) {
            for (String nomFichier : new String[] {"LATEST.dat", "Slot_0"}) {
                final File fichier = new File(dossier, nomFichier);
                if (fichier.isFile()) {
                    Sauvegarde.renommer(fichier, propre, copies, temporaire);
                }
            }
        }
    }

    static Process relaisSon(Context context) throws IOException {
        final File root = racine(context);
        return new ProcessBuilder(new File(root, "sonrelay").getPath(), new File(root, "tmp/son.sock").getPath())
                .redirectErrorStream(true)
                .redirectOutput(new File(donnees(context), "son.log"))
                .start();
    }

    /**
     * Starts the engine. Not with ProcessBuilder, which closes every descriptor in the new process: the game's
     * files are open descriptors, which the engine must keep (Ecran.lancer).
     */
    /** The Vulkan loader's description of the driver for this game (Pilotes). */
    private static void ecrireIcd(File root, String pilote) throws IOException {
        new File(root, "tmp").mkdirs();
        File bibliotheque = Pilotes.bibliotheque(root, pilote);
        if (!bibliotheque.isFile()) {
            bibliotheque = Pilotes.bibliotheque(root, Pilotes.HUIT);
        }
        try (OutputStream out = new FileOutputStream(new File(root, "tmp/icd.json"))) {
            out.write(("{\"file_format_version\":\"1.0.0\",\"ICD\":{\"library_path\":\""
                    + bibliotheque.getPath() + "\",\"api_version\":\"1.3.0\"}}").getBytes("UTF-8"));
        }
    }

    static int demarrer(Context context, String flipMs, String flipIntervalle, String msaa, String pilote)
            throws IOException {
        final File root = racine(context);
        ecrireIcd(root, pilote);
        try (OutputStream out = new FileOutputStream(new File(donnees(context), "pilote.txt"))) {
            out.write((pilote + " " + Pilotes.bibliotheque(root, pilote).getName() + "\n").getBytes("UTF-8"));
        }
        final File host = new File(root, "host");
        final String[] arguments = {
            new File(host, "ld-linux-aarch64.so.1").getPath(), "--library-path", host.getPath(),
            new File(host, "shadps4-arm64-fex-NBA").getPath(), "-g", new File(dossierJeu(context), "eboot.bin").getPath(),
            "-f", "false",
        };
        final Map<String, String> env = new java.util.HashMap<>(System.getenv());
        env.remove("LD_PRELOAD");
        env.remove("DISPLAY");
        env.put("HOME", home(context).getPath());
        env.put("TMPDIR", new File(root, "tmp").getPath());
        env.put("XDG_RUNTIME_DIR", new File(root, "tmp").getPath());
        env.put("VK_ICD_FILENAMES", new File(root, "tmp/icd.json").getPath());
        env.put("NBA2K14_HLE_MEMORY", "0");
        env.put("NBA2K14_AUTO_START", "0");
        env.put("NBA2K14_FLIP_MS", flipMs);
        env.put("NBA2K14_FLIP_SYNC", "1");
        env.put("NBA2K14_FLIP_INTERVAL", flipIntervalle);
        env.put("NBA2K14_MSAA_MAX", msaa);
        env.put("NBA2K14_FRAME_SOCKET", socketImage(context).getPath());
        env.put("BACHATA_ALSA_SOCKET", new File(root, "tmp/son.sock").getPath());
        if (Pilotes.PANVK.equals(pilote)) {
            // PanVK still calls itself unfinished on some Mali generations and refuses them without this.
            env.put("PAN_I_WANT_A_BROKEN_VULKAN_DRIVER", "1");
        } else {
            // Not a diagnostic: Turnip's author recommends drawing in system memory on the Adreno 830, its tile
            // memory (GMEM) path gives a corrupt picture there (out/RAPPORT-03.md).
            env.put("TU_DEBUG", "sysmem");
        }
        env.put("NBA2K14_PILOTE", pilote); // in the log's environment, to know which one ran
        final String[] environnement = new String[env.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : env.entrySet()) {
            environnement[i++] = entry.getKey() + "=" + entry.getValue();
        }
        final int pid = Ecran.lancer(arguments, environnement, root.getPath(),
                new File(donnees(context), "journal.txt").getPath());
        if (pid <= 0) {
            throw new IOException("démarrage du moteur (" + -pid + ")");
        }
        return pid;
    }
}
