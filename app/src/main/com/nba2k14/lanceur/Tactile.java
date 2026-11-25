package com.nba2k14.lanceur;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

/**
 * An on-screen PS4 pad for phones without a gamepad, over the game. Left: a floating stick (it appears
 * where the thumb lands) and the D-pad; right: the four face buttons and a smaller right stick; top: L1/L2, R1/R2,
 * the touchpad, Options and the app's menu. Several fingers at once; each finger keeps the control it landed on.
 * The state goes to the game through Manette.tactile, mixed with a gamepad's.
 */
final class Tactile extends View {
    // The controls a finger can hold. Buttons first: their PS4 bit (Manette's values).
    private static final int AUCUN = 0, STICK_G = -1, STICK_D = -2, MENU = -3;
    private static final int L1 = 0x400, L2 = 0x100, R1 = 0x800, R2 = 0x200;
    private static final int TRIANGLE = 0x1000, ROND = 0x2000, CROIX = 0x4000, CARRE = 0x8000;
    private static final int HAUT = 0x10, DROITE = 0x20, BAS = 0x40, GAUCHE = 0x80;
    private static final int OPTIONS = 0x8, PAVE = 0x100000;

    private final Manette manette;
    private final Runnable menu;
    private final Paint trait = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plein = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint texte = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path forme = new Path();
    // Finger id -> what it holds (a button bit, or a stick).
    private final SparseIntArray doigts = new SparseIntArray();

    private float u; // a thousandth of the view's height, times the size setting
    private float taille = 1f, opacite = 1f;
    private int margeGauche, margeDroite; // a camera notch's side, in landscape
    private float rayonG, rayonD, rayonBouton;
    // Left stick: its centre (where the thumb landed) and its knob. Right stick: fixed centre.
    private float gx, gy, gxRepos, gyRepos, gKnobX, gKnobY;
    private float dx, dy, dKnobX, dKnobY;
    private float faceX, faceY, faceEcart, croixX, croixY, croixEcart;
    private final RectF rL1 = new RectF(), rL2 = new RectF(), rR1 = new RectF(), rR2 = new RectF();
    private final RectF rPave = new RectF(), rOptions = new RectF(), rMenu = new RectF();
    private int boutons;
    private int lx = 128, ly = 128, rx = 128, ry = 128;

    Tactile(Context context, Manette manette, Runnable menu) {
        super(context);
        this.manette = manette;
        this.menu = menu;
        trait.setStyle(Paint.Style.STROKE);
        texte.setTextAlign(Paint.Align.CENTER);
        texte.setTypeface(Typeface.DEFAULT_BOLD);
        setHapticFeedbackEnabled(true);
    }

    /** The menu's size and opacity settings: 0.85 to 1.2 times the size, 0.55 to 1.6 times the opacity. */
    void reglages(float taille, float opacite) {
        this.taille = taille;
        this.opacite = opacite;
        if (getWidth() > 0) {
            disposer(getWidth(), getHeight());
        }
        invalidate();
    }

    @Override
    public android.view.WindowInsets onApplyWindowInsets(android.view.WindowInsets insets) {
        if (android.os.Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
            margeGauche = insets.getDisplayCutout().getSafeInsetLeft();
            margeDroite = insets.getDisplayCutout().getSafeInsetRight();
            if (getWidth() > 0) {
                disposer(getWidth(), getHeight());
            }
        }
        return super.onApplyWindowInsets(insets);
    }

    private int a(int alpha) {
        return Math.max(0, Math.min(255, Math.round(alpha * opacite)));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        disposer(w, h);
    }

    /** Where everything goes, from the size, the settings and a notch (the controls keep clear of it). */
    private void disposer(int largeurTotale, int h) {
        final int w = largeurTotale - margeDroite;
        final float x0 = margeGauche;
        u = h / 1000f * taille;
        rayonG = 140 * u;
        rayonD = 105 * u;
        rayonBouton = 56 * u;
        gxRepos = x0 + (w - x0) * 0.14f;
        gyRepos = h * 0.72f;
        gx = gKnobX = gxRepos;
        gy = gKnobY = gyRepos;
        faceX = w - 210 * u;
        faceY = h * 0.66f;
        faceEcart = 118 * u;
        dx = dKnobX = faceX - 330 * u;
        dy = dKnobY = h - 150 * u;
        croixX = x0 + 190 * u;
        croixY = h * 0.38f;
        croixEcart = 74 * u;
        final float lw = 170 * u, lh = 82 * u, m = 26 * u;
        rL2.set(x0 + m, m, x0 + m + lw, m + lh);
        rL1.set(x0 + m, m + lh + 18 * u, x0 + m + lw, m + 2 * lh + 18 * u);
        croixY = Math.max(croixY, rL1.bottom + croixEcart * 1.48f + 24 * u);
        rR2.set(w - m - lw, m, w - m, m + lh);
        rR1.set(w - m - lw, m + lh + 18 * u, w - m, m + 2 * lh + 18 * u);
        final float bw = 150 * u, bh = 66 * u, cx = largeurTotale / 2f;
        rPave.set(cx - bw * 1.5f - 24 * u, m, cx - bw * 0.5f - 24 * u, m + bh);
        rMenu.set(cx - bh, m, cx + bh, m + bh);
        rOptions.set(cx + bw * 0.5f + 24 * u, m, cx + bw * 1.5f + 24 * u, m + bh);
    }

    // ---------------------------------------------------------------- touch

    /** What a finger landing at (x, y) takes: a button, a stick, the menu, or nothing. */
    private int cible(float x, float y) {
        final RectF[] rectangles = {rL1, rL2, rR1, rR2, rPave, rOptions, rMenu};
        final int[] valeurs = {L1, L2, R1, R2, PAVE, OPTIONS, MENU};
        final float marge = 14 * u;
        for (int i = 0; i < rectangles.length; i++) {
            final RectF r = rectangles[i];
            if (x >= r.left - marge && x <= r.right + marge && y >= r.top - marge && y <= r.bottom + marge) {
                return valeurs[i];
            }
        }
        final int face = proche(x, y, faceX, faceY, faceEcart, TRIANGLE, ROND, CROIX, CARRE, rayonBouton * 1.35f);
        if (face != AUCUN) {
            return face;
        }
        final int croix = proche(x, y, croixX, croixY, croixEcart, HAUT, DROITE, BAS, GAUCHE, croixEcart * 0.75f);
        if (croix != AUCUN) {
            return croix;
        }
        if (distance(x, y, dx, dy) <= rayonD * 1.5f) {
            return STICK_D;
        }
        // The left stick: anywhere in the lower left part of the screen.
        if (x < margeGauche + (getWidth() - margeGauche - margeDroite) * 0.42f && y > getHeight() * 0.48f) {
            return STICK_G;
        }
        return AUCUN;
    }

    /** One of four buttons in a diamond around (cx, cy): top, right, bottom, left. */
    private static int proche(float x, float y, float cx, float cy, float ecart, int haut, int droite, int bas,
                              int gauche, float rayon) {
        final float[][] centres = {{cx, cy - ecart}, {cx + ecart, cy}, {cx, cy + ecart}, {cx - ecart, cy}};
        final int[] valeurs = {haut, droite, bas, gauche};
        for (int i = 0; i < 4; i++) {
            if (distance(x, y, centres[i][0], centres[i][1]) <= rayon) {
                return valeurs[i];
            }
        }
        return AUCUN;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.hypot(x1 - x2, y1 - y2);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return true; // the app's menu is open: a tap beside it does nothing in the game
        }
        final int action = event.getActionMasked();
        final int index = event.getActionIndex();
        switch (action) {
        case MotionEvent.ACTION_DOWN:
        case MotionEvent.ACTION_POINTER_DOWN: {
            final float x = event.getX(index), y = event.getY(index);
            final int quoi = cible(x, y);
            if (quoi == AUCUN) {
                break;
            }
            doigts.put(event.getPointerId(index), quoi);
            if (quoi == MENU) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                menu.run();
            } else if (quoi == STICK_G) {
                gx = gKnobX = x;
                gy = gKnobY = y;
            } else if (quoi == STICK_D) {
                bougerDroit(x, y);
            } else {
                boutons |= quoi;
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            }
            break;
        }
        case MotionEvent.ACTION_MOVE:
            for (int i = 0; i < event.getPointerCount(); i++) {
                final int quoi = doigts.get(event.getPointerId(i), AUCUN);
                if (quoi == STICK_G) {
                    bougerGauche(event.getX(i), event.getY(i));
                } else if (quoi == STICK_D) {
                    bougerDroit(event.getX(i), event.getY(i));
                }
            }
            break;
        case MotionEvent.ACTION_UP:
        case MotionEvent.ACTION_POINTER_UP:
        case MotionEvent.ACTION_CANCEL:
            if (action == MotionEvent.ACTION_CANCEL) {
                for (int i = 0; i < doigts.size(); i++) {
                    lacher(doigts.valueAt(i));
                }
                doigts.clear();
            } else {
                final int id = event.getPointerId(index);
                lacher(doigts.get(id, AUCUN));
                doigts.delete(id);
            }
            break;
        default:
            return true;
        }
        envoyer();
        invalidate();
        return true;
    }

    private void lacher(int quoi) {
        if (quoi == STICK_G) {
            gx = gKnobX = gxRepos;
            gy = gKnobY = gyRepos;
            lx = ly = 128;
        } else if (quoi == STICK_D) {
            dKnobX = dx;
            dKnobY = dy;
            rx = ry = 128;
        } else if (quoi > 0) {
            boutons &= ~quoi;
        }
    }

    private void bougerGauche(float x, float y) {
        float ox = x - gx, oy = y - gy;
        final float d = (float) Math.hypot(ox, oy);
        if (d > rayonG) {
            // Past the edge, the stick follows the thumb: no dead push when the thumb drifts.
            gx += ox * (1 - rayonG / d);
            gy += oy * (1 - rayonG / d);
            ox = x - gx;
            oy = y - gy;
        }
        gKnobX = x;
        gKnobY = y;
        lx = axe(ox / rayonG);
        ly = axe(oy / rayonG);
    }

    private void bougerDroit(float x, float y) {
        float ox = x - dx, oy = y - dy;
        final float d = (float) Math.hypot(ox, oy);
        if (d > rayonD) {
            ox *= rayonD / d;
            oy *= rayonD / d;
        }
        dKnobX = dx + ox;
        dKnobY = dy + oy;
        rx = axe(ox / rayonD);
        ry = axe(oy / rayonD);
    }

    private static int axe(float value) {
        return Math.max(0, Math.min(255, Math.round(128 + value * 127)));
    }

    private void envoyer() {
        manette.tactile(boutons, lx, ly, rx, ry);
    }

    /** Everything let go: when the overlay hides or the game's menu opens. */
    void relacher() {
        doigts.clear();
        boutons = 0;
        lacher(STICK_G);
        lacher(STICK_D);
        envoyer();
        invalidate();
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        trait.setStrokeWidth(4 * u);
        // Sticks.
        cercle(canvas, gx, gy, rayonG, false);
        cercle(canvas, gKnobX, gKnobY, 62 * u, lx != 128 || ly != 128);
        cercle(canvas, dx, dy, rayonD, false);
        cercle(canvas, dKnobX, dKnobY, 48 * u, rx != 128 || ry != 128);
        texte.setTextSize(30 * u);
        texte.setColor(Color.argb(a(110), 255, 255, 255));
        canvas.drawText("R", dKnobX, dKnobY - (texte.descent() + texte.ascent()) / 2, texte);
        // Face buttons with their PS4 marks.
        face(canvas, faceX, faceY - faceEcart, TRIANGLE, Color.rgb(90, 220, 170));
        face(canvas, faceX + faceEcart, faceY, ROND, Color.rgb(255, 105, 110));
        face(canvas, faceX, faceY + faceEcart, CROIX, Color.rgb(120, 170, 255));
        face(canvas, faceX - faceEcart, faceY, CARRE, Color.rgb(240, 140, 220));
        // D-pad.
        fleche(canvas, croixX, croixY - croixEcart, HAUT, 0);
        fleche(canvas, croixX + croixEcart, croixY, DROITE, 90);
        fleche(canvas, croixX, croixY + croixEcart, BAS, 180);
        fleche(canvas, croixX - croixEcart, croixY, GAUCHE, 270);
        // Shoulders and the middle row.
        bouton(canvas, rL2, "L2", L2);
        bouton(canvas, rL1, "L1", L1);
        bouton(canvas, rR2, "R2", R2);
        bouton(canvas, rR1, "R1", R1);
        bouton(canvas, rPave, Textes.t("PAVÉ", "TOUCH"), PAVE);
        bouton(canvas, rOptions, "OPTIONS", OPTIONS);
        bouton(canvas, rMenu, "≡", 0);
    }

    private void cercle(Canvas canvas, float x, float y, float r, boolean actif) {
        plein.setColor(Color.argb(a(actif ? 120 : 45), 255, 255, 255));
        canvas.drawCircle(x, y, r, plein);
        trait.setColor(Color.argb(a(140), 255, 255, 255));
        canvas.drawCircle(x, y, r, trait);
    }

    private void face(Canvas canvas, float x, float y, int bit, int couleur) {
        cercle(canvas, x, y, rayonBouton, (boutons & bit) != 0);
        trait.setColor((couleur & 0xffffff) | a(230) << 24);
        final float s = 22 * u;
        if (bit == TRIANGLE) {
            forme.reset();
            forme.moveTo(x, y - s);
            forme.lineTo(x + s, y + s * 0.75f);
            forme.lineTo(x - s, y + s * 0.75f);
            forme.close();
            canvas.drawPath(forme, trait);
        } else if (bit == ROND) {
            canvas.drawCircle(x, y, s, trait);
        } else if (bit == CROIX) {
            canvas.drawLine(x - s, y - s, x + s, y + s, trait);
            canvas.drawLine(x - s, y + s, x + s, y - s, trait);
        } else {
            canvas.drawRect(x - s * 0.85f, y - s * 0.85f, x + s * 0.85f, y + s * 0.85f, trait);
        }
    }

    private void fleche(Canvas canvas, float x, float y, int bit, float angle) {
        final float r = croixEcart * 0.48f;
        plein.setColor(Color.argb(a((boutons & bit) != 0 ? 120 : 45), 255, 255, 255));
        canvas.drawRoundRect(x - r, y - r, x + r, y + r, 10 * u, 10 * u, plein);
        trait.setColor(Color.argb(a(140), 255, 255, 255));
        canvas.drawRoundRect(x - r, y - r, x + r, y + r, 10 * u, 10 * u, trait);
        canvas.save();
        canvas.rotate(angle, x, y);
        forme.reset();
        forme.moveTo(x, y - r * 0.5f);
        forme.lineTo(x + r * 0.45f, y + r * 0.25f);
        forme.lineTo(x - r * 0.45f, y + r * 0.25f);
        forme.close();
        plein.setColor(Color.argb(a(170), 255, 255, 255));
        canvas.drawPath(forme, plein);
        canvas.restore();
    }

    private void bouton(Canvas canvas, RectF r, String nom, int bit) {
        final float coin = r.height() / 2;
        plein.setColor(Color.argb(a(bit != 0 && (boutons & bit) != 0 ? 120 : 45), 255, 255, 255));
        canvas.drawRoundRect(r, coin, coin, plein);
        trait.setColor(Color.argb(a(140), 255, 255, 255));
        canvas.drawRoundRect(r, coin, coin, trait);
        texte.setTextSize((nom.length() > 3 ? 26 : 36) * u);
        texte.setColor(Color.argb(a(200), 255, 255, 255));
        canvas.drawText(nom, r.centerX(), r.centerY() - (texte.descent() + texte.ascent()) / 2, texte);
    }
}
