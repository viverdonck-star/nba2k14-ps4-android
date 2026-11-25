package com.nba2k14.lanceur;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Renames the player in a save: the game checks each block against a CRC32 kept in a compressed manifest. */
final class Sauvegarde {
    private static final int TABLE = 16, ENTREE = 304, NOM_ENTREE = 48, DEBUT_NOM = 20, TAILLE_NOM = 32, ALIGNEMENT = 8;
    private static final String BLOC = "DataStore_GDSG_SavedUserData_", MANIFESTE = "DataStoreManifest_bin.xml.z";
    private static final Pattern NOEUD = Pattern.compile("(<Node name=\"GDS_SavedUserData\"[^>]*binChecksum=\")(\\d+)\"");

    private Sauvegarde() {}

    private static String nomEntree(byte[] data, int position) {
        int end = position;
        while (data[end] != 0) {
            end++;
        }
        return new String(data, position, end - position, StandardCharsets.US_ASCII);
    }

    private static long crc(byte[] data, int position, int size) {
        final CRC32 crc = new CRC32();
        crc.update(data, position, size);
        return crc.getValue();
    }

    private static byte[] decompresser(byte[] data, int position, int size) throws DataFormatException {
        final Inflater inflater = new Inflater();
        inflater.setInput(data, position, size);
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[1 << 16];
        while (!inflater.finished()) {
            final int count = inflater.inflate(buffer);
            if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                throw new DataFormatException("manifeste incomplet");
            }
            out.write(buffer, 0, count);
        }
        inflater.end();
        return out.toByteArray();
    }

    private static byte[] compresser(byte[] data) {
        final Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[1 << 16];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    /** Returns the renamed file, or null when it already has this name or does not look as expected. */
    static byte[] renommer(byte[] data, String nom) {
        final byte[] nouveau = nom.getBytes(StandardCharsets.UTF_16LE);
        if (nouveau.length > TAILLE_NOM) {
            return null;
        }
        final ByteBuffer table = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        try {
            int bloc = -1, tailleBloc = 0, manifeste = -1, tailleManifeste = 0, entreeManifeste = 0;
            final int total = (int) table.getLong(ALIGNEMENT);
            for (int index = 0; index < table.getInt(4); index++) {
                final int entree = TABLE + index * ENTREE;
                final String name = nomEntree(data, entree + NOM_ENTREE);
                if (name.startsWith(BLOC)) {
                    bloc = (int) table.getLong(entree + 8);
                    tailleBloc = (int) table.getLong(entree + 24);
                } else if (name.equals(MANIFESTE)) {
                    manifeste = (int) table.getLong(entree + 8);
                    tailleManifeste = (int) table.getLong(entree + 24);
                    entreeManifeste = entree;
                }
            }
            if (bloc < 0 || manifeste < 0 || manifeste + tailleManifeste != total) {
                return null;
            }
            final String xml = new String(decompresser(data, manifeste, tailleManifeste), StandardCharsets.UTF_8);
            final Matcher noeud = NOEUD.matcher(xml);
            if (!noeud.find() || Long.parseLong(noeud.group(2)) != crc(data, bloc, tailleBloc)) {
                return null;
            }
            final byte[] copie = data.clone();
            final byte[] champ = new byte[TAILLE_NOM];
            System.arraycopy(nouveau, 0, champ, 0, nouveau.length);
            System.arraycopy(champ, 0, copie, bloc + DEBUT_NOM, TAILLE_NOM);
            if (crc(copie, bloc, tailleBloc) == crc(data, bloc, tailleBloc)) {
                return null;
            }
            final byte[] compresse = compresser(noeud.replaceFirst("$1" + crc(copie, bloc, tailleBloc) + "\"")
                    .getBytes(StandardCharsets.UTF_8));
            final int nouveauTotal = manifeste + compresse.length;
            final byte[] sortie = new byte[(nouveauTotal + ALIGNEMENT - 1) / ALIGNEMENT * ALIGNEMENT];
            System.arraycopy(copie, 0, sortie, 0, manifeste);
            System.arraycopy(compresse, 0, sortie, manifeste, compresse.length);
            final ByteBuffer fin = ByteBuffer.wrap(sortie).order(ByteOrder.LITTLE_ENDIAN);
            fin.putLong(ALIGNEMENT, nouveauTotal);
            fin.putLong(entreeManifeste + 24, compresse.length);
            return sortie;
        } catch (DataFormatException | IndexOutOfBoundsException | NumberFormatException unexpected) {
            return null;
        }
    }

    /** The first original of each file stays in the backup folder. */
    static void renommer(File fichier, String nom, File copies, File temporaire) throws IOException {
        final byte[] data = Files.readAllBytes(fichier.toPath());
        final byte[] sortie = renommer(data, nom);
        if (sortie == null) {
            return;
        }
        final File copie = new File(copies, fichier.getParentFile().getName() + "-" + fichier.getName());
        if (!copie.exists()) {
            copies.mkdirs();
            Files.write(copie.toPath(), data);
        }
        Files.write(temporaire.toPath(), sortie);
        Files.move(temporaire.toPath(), fichier.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
