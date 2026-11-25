package com.nba2k14.lanceur;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The game folder the player picked with Android's folder picker. Its files are read through open descriptors
 * (/proc/self/fd/<n>), so the app needs no storage permission and copies nothing.
 */
final class Dossier {
    /** Depth searched for the game inside the picked folder: the player may pick its parent. */
    private static final int PROFONDEUR = 3;
    private static final String[] COLONNES = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
    };

    static final class Fichier {
        final String chemin; // relative to the game folder, with '/'
        final long taille;
        final Uri uri;

        Fichier(String chemin, long taille, Uri uri) {
            this.chemin = chemin;
            this.taille = taille;
            this.uri = uri;
        }
    }

    private Dossier() {}

    /** Every file of the folder, relative paths. Empty if the folder can't be read. */
    private static List<Fichier> lister(Context context, Uri arbre, String id, String prefixe, int profondeur) {
        final List<Fichier> fichiers = new ArrayList<>();
        final Uri enfants = DocumentsContract.buildChildDocumentsUriUsingTree(arbre, id);
        try (Cursor cursor = context.getContentResolver().query(enfants, COLONNES, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                final String enfant = cursor.getString(0), nom = cursor.getString(1);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                    if (profondeur > 0) {
                        fichiers.addAll(lister(context, arbre, enfant, prefixe + nom + "/", profondeur - 1));
                    }
                } else {
                    fichiers.add(new Fichier(prefixe + nom, cursor.getLong(3),
                            DocumentsContract.buildDocumentUriUsingTree(arbre, enfant)));
                }
            }
        } catch (RuntimeException unreadable) {
            fichiers.clear();
        }
        return fichiers;
    }

    /**
     * The game's files: those under the folder that holds eboot.bin and sce_sys/param.sfo, the picked folder
     * itself or one below it. All the picked folder's files if there is no such folder (the menu says why).
     */
    static List<Fichier> fichiersDuJeu(Context context, Uri arbre) {
        final List<Fichier> tous = lister(context, arbre, DocumentsContract.getTreeDocumentId(arbre), "",
                PROFONDEUR + 2);
        String racine = null;
        for (Fichier fichier : tous) {
            if (fichier.chemin.endsWith("sce_sys/param.sfo")) {
                final String base = fichier.chemin.substring(0, fichier.chemin.length() - "sce_sys/param.sfo".length());
                if (contient(tous, base + "eboot.bin") && (racine == null || base.length() < racine.length())) {
                    racine = base;
                }
            }
        }
        if (racine == null || racine.isEmpty()) {
            return tous;
        }
        final List<Fichier> jeu = new ArrayList<>();
        for (Fichier fichier : tous) {
            if (fichier.chemin.startsWith(racine)) {
                jeu.add(new Fichier(fichier.chemin.substring(racine.length()), fichier.taille, fichier.uri));
            }
        }
        return jeu;
    }

    private static boolean contient(List<Fichier> fichiers, String chemin) {
        for (Fichier fichier : fichiers) {
            if (fichier.chemin.equals(chemin)) {
                return true;
            }
        }
        return false;
    }

    /** The folder's name as the picker shows it. */
    static String nom(Context context, Uri arbre) {
        final Uri document = DocumentsContract.buildDocumentUriUsingTree(arbre, DocumentsContract.getTreeDocumentId(arbre));
        try (Cursor cursor = context.getContentResolver().query(document,
                new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (RuntimeException unreadable) {
            // the path's last part below
        }
        return arbre.getLastPathSegment();
    }

    static ParcelFileDescriptor ouvrir(Context context, Uri document) throws IOException {
        final ParcelFileDescriptor descripteur = context.getContentResolver().openFileDescriptor(document, "r");
        if (descripteur == null) {
            throw new IOException("illisible");
        }
        return descripteur;
    }
}
