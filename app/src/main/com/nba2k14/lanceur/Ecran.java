package com.nba2k14.lanceur;

/** Native side of the game screen: receives the engine's frames and draws them on a surface. Starts the engine. */
final class Ecran {
    static {
        System.loadLibrary("ecran");
    }

    private Ecran() {}

    static native boolean demarrer(String socket, String rythme);

    /** Time between frames in ms, 0 for no limit. The screen then sets the engine's pace. */
    static native void cadence(int ms);

    static native void surface(android.view.Surface surface);

    static native int images();

    static native int hertz();

    /** A copy of the descriptor that a started program keeps (Android's are closed when a program starts). */
    static native int garder(int fd);

    /** Starts a program with the descriptors kept, its output in a file. Its pid, or minus errno. */
    static native int lancer(String[] arguments, String[] environnement, String dossier, String sortie);

    static native boolean vivant(int pid);

    static native void arreter(int pid);
}
