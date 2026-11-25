package com.nba2k14.lanceur;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/** The running game outlives its screen: it stops only on request or when the engine exits. */
final class Partie {
    private static int jeu = -1;
    private static Process son;
    private static boolean ecran;
    // Whether the last stop was asked for (by the player), or the engine ended by itself.
    private static boolean demande;

    private Partie() {}

    static boolean enCours() {
        return jeu > 0 && Ecran.vivant(jeu);
    }

    static void demarrer(Context context, String flipMs, String flipIntervalle, String msaa, String pilote)
            throws IOException {
        if (enCours()) {
            return;
        }
        if (!ecran) {
            if (!Ecran.demarrer(Moteur.socketImage(context).getPath(), Moteur.journalRythme(context).getPath())) {
                throw new IOException("écran");
            }
            ecran = true;
        }
        demande = false;
        Ecran.cadence(Integer.parseInt(flipMs));
        son = Moteur.relaisSon(context);
        jeu = Moteur.demarrer(context, flipMs, flipIntervalle, msaa, pilote);
    }

    /** For the menu, which shows it when it comes back (Accueil.lastGameMessage). */
    static void message(Context context, String texte) {
        try (OutputStream out = new FileOutputStream(new File(Moteur.donnees(context), "message-jeu.txt"))) {
            out.write(texte.getBytes("UTF-8"));
        } catch (IOException ignored) {
            // nothing more to do
        }
    }

    /** Test builds only: "sans-moteur" in the app's folder opens the game's screen without starting the engine. */
    static boolean sansMoteur(Context context) {
        return (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && new File(Moteur.donnees(context), "sans-moteur").exists();
    }

    static boolean arretDemande() {
        return demande;
    }

    static void arreter() {
        demande = true;
        if (jeu > 0) {
            Ecran.arreter(jeu);
            jeu = -1;
        }
        if (son != null) {
            son.destroy();
            son = null;
        }
    }
}
