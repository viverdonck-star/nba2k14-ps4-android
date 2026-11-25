package com.nba2k14.lanceur;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;

/**
 * Keeps the game's process alive while the app is in the background, and ends it with the game: the menu then
 * knows that no game is running.
 */
public class PartieService extends Service {
    private static final String CANAL = "partie";
    private static final int CHECK_MS = 5000;
    private static final int ICONE = 17301540; // android.R.drawable.ic_media_play

    private final Handler handler = new Handler();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int id) {
        final NotificationChannel channel =
                new NotificationChannel(CANAL, Textes.t("Partie en cours", "Game running"), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        final PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, JeuActivity.class),
                PendingIntent.FLAG_IMMUTABLE);
        final Notification notification = new Notification.Builder(this, CANAL)
                .setContentTitle("NBA 2K14")
                .setContentText(Textes.t("Partie en cours, touche pour reprendre", "Game running, tap to resume"))
                .setSmallIcon(ICONE)
                .setContentIntent(open)
                .build();
        startForeground(1, notification);
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (Partie.enCours()) {
                    handler.postDelayed(this, CHECK_MS);
                } else {
                    stopSelf();
                    Partie.arreter();
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }
        }, CHECK_MS);
        return START_NOT_STICKY;
    }
}
