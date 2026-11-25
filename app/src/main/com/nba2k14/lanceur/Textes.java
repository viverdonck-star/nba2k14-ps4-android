package com.nba2k14.lanceur;

/**
 * The app's language, chosen in the menu (lanceur.cpp, Accueil.setLanguage) and handed to the game's process
 * (JeuActivity's "langue" extra).
 */
final class Textes {
    static volatile boolean anglais;

    private Textes() {}

    static String t(String francais, String english) {
        return anglais ? english : francais;
    }
}
