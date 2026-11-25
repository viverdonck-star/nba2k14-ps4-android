package com.nba2k14.lanceur;

import android.util.SparseBooleanArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Turns Android gamepad events into the engine's UDP pad packet (see patch-pad-bridge.py). */
final class Manette {
    private static final int MAGIC = 0x504b324e;
    private static final int PORT = 27014;
    private static final int L3 = 0x2, R3 = 0x4, OPTIONS = 0x8, UP = 0x10, RIGHT = 0x20, DOWN = 0x40, LEFT = 0x80;
    private static final int L2 = 0x100, R2 = 0x200, L1 = 0x400, R1 = 0x800;
    private static final int TRIANGLE = 0x1000, CIRCLE = 0x2000, CROSS = 0x4000, SQUARE = 0x8000, TOUCHPAD = 0x100000;

    private static final int PACKET_SIZE = 16;

    // The buttons the player can reassign, in the order of the menu's Controls screen (lanceur.cpp, kOdinButtons
    // and kGameButtons): the Odin's buttons, and the PS4 buttons they give by default. L2 and R2 stay: the game
    // reads them as analog triggers too.
    private static final int[] TOUCHES_ODIN = {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
    };
    private static final String[] NOMS_ODIN = {"A", "B", "X", "Y", "L1", "R1", "L3", "R3", "START", "SELECT"};
    private static final int[] BOUTONS_JEU = {CROSS, CIRCLE, SQUARE, TRIANGLE, L1, R1, L3, R3, OPTIONS, TOUCHPAD};

    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final Runnable menu;
    // The PS4 button each of TOUCHES_ODIN gives.
    private final int[] attribution = BOUTONS_JEU.clone();
    private int buttons, physiques;
    private int leftX = 128, leftY = 128, rightX = 128, rightY = 128, leftTrigger, rightTrigger;
    // The on-screen pad (Tactile), mixed with the gamepad's state: buttons added, a moved stick wins.
    private int tactileBoutons, tactileLX = 128, tactileLY = 128, tactileRX = 128, tactileRY = 128;
    private DatagramSocket socket;
    // Gamepads whose right stick is on RX/RY and triggers on Z/RZ, by device id (see rxry).
    private final SparseBooleanArray rxry = new SparseBooleanArray();
    private InetAddress local;

    /**
     * @param touches the Odin's button for each PS4 button, in BOUTONS_JEU's order: "A,B,X,Y,L1,R1,L3,R3,START,
     *     SELECT" by default (the menu's "touches" setting); anything else keeps the default.
     */
    Manette(Runnable menu, String touches) {
        this.menu = menu;
        if (touches == null) {
            return;
        }
        final String[] noms = touches.split(",");
        if (noms.length != BOUTONS_JEU.length) {
            return;
        }
        final int[] lue = new int[TOUCHES_ODIN.length];
        int vues = 0;
        for (int jeu = 0; jeu < noms.length; jeu++) {
            final int odin = java.util.Arrays.asList(NOMS_ODIN).indexOf(noms[jeu]);
            if (odin < 0 || (vues & (1 << odin)) != 0) {
                return; // each Odin button once: a reassignment is a swap
            }
            vues |= 1 << odin;
            lue[odin] = BOUTONS_JEU[jeu];
        }
        System.arraycopy(lue, 0, attribution, 0, lue.length);
    }

    private int buttonOf(int key) {
        for (int i = 0; i < TOUCHES_ODIN.length; i++) {
            if (TOUCHES_ODIN[i] == key) {
                return attribution[i];
            }
        }
        switch (key) {
        case KeyEvent.KEYCODE_BUTTON_L2: return L2;
        case KeyEvent.KEYCODE_BUTTON_R2: return R2;
        case KeyEvent.KEYCODE_DPAD_UP: return UP;
        case KeyEvent.KEYCODE_DPAD_DOWN: return DOWN;
        case KeyEvent.KEYCODE_DPAD_LEFT: return LEFT;
        case KeyEvent.KEYCODE_DPAD_RIGHT: return RIGHT;
        default: return 0;
        }
    }

    boolean key(KeyEvent event) {
        final int button = buttonOf(event.getKeyCode());
        if (button == 0) {
            return false;
        }
        if (event.getRepeatCount() > 0) {
            return true;
        }
        // The stick clicks themselves open the app's menu, whatever they give in the game.
        final int physique = event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_THUMBL ? L3
                : event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_THUMBR ? R3 : 0;
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            buttons |= button;
            physiques |= physique;
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            buttons &= ~button;
            physiques &= ~physique;
        }
        if (physiques == (L3 | R3)) {
            buttons = 0; // released on the game's side while the menu is open
            physiques = 0;
            menu.run();
        }
        send();
        return true;
    }

    private static int stick(float value) {
        return Math.round((value + 1f) * 127.5f);
    }

    private static int trigger(float value) {
        return Math.round(value * 255f);
    }

    /**
     * Android maps known gamepads (Xbox, DualShock, DualSense, the Odin's) right stick to Z/RZ. A gamepad it
     * has no layout for keeps Linux's names: right stick on RX/RY, triggers on Z/RZ going from 0 to 1. That case is
     * told apart by the axes' ranges: RX and RY both ways, Z and RZ (when there) one way only.
     */
    private boolean rxry(InputDevice device) {
        if (device == null) {
            return false;
        }
        final int id = device.getId();
        if (rxry.indexOfKey(id) >= 0) {
            return rxry.get(id);
        }
        final InputDevice.MotionRange rx = device.getMotionRange(MotionEvent.AXIS_RX);
        final InputDevice.MotionRange ry = device.getMotionRange(MotionEvent.AXIS_RY);
        final InputDevice.MotionRange z = device.getMotionRange(MotionEvent.AXIS_Z);
        final InputDevice.MotionRange rz = device.getMotionRange(MotionEvent.AXIS_RZ);
        final boolean oui = rx != null && ry != null && rx.getMin() < -0.5f && ry.getMin() < -0.5f
                && (z == null || z.getMin() >= 0f) && (rz == null || rz.getMin() >= 0f);
        rxry.put(id, oui);
        return oui;
    }

    boolean motion(MotionEvent event) {
        leftX = stick(event.getAxisValue(MotionEvent.AXIS_X));
        leftY = stick(event.getAxisValue(MotionEvent.AXIS_Y));
        if (rxry(event.getDevice())) {
            rightX = stick(event.getAxisValue(MotionEvent.AXIS_RX));
            rightY = stick(event.getAxisValue(MotionEvent.AXIS_RY));
            leftTrigger = trigger(Math.max(event.getAxisValue(MotionEvent.AXIS_Z),
                    event.getAxisValue(MotionEvent.AXIS_LTRIGGER)));
            rightTrigger = trigger(Math.max(event.getAxisValue(MotionEvent.AXIS_RZ),
                    event.getAxisValue(MotionEvent.AXIS_RTRIGGER)));
        } else {
            rightX = stick(event.getAxisValue(MotionEvent.AXIS_Z));
            rightY = stick(event.getAxisValue(MotionEvent.AXIS_RZ));
            leftTrigger = trigger(Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_BRAKE)));
            rightTrigger = trigger(Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                    event.getAxisValue(MotionEvent.AXIS_GAS)));
        }
        final float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        final float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
        buttons &= ~(UP | DOWN | LEFT | RIGHT);
        buttons |= hatX < -0.5f ? LEFT : hatX > 0.5f ? RIGHT : 0;
        buttons |= hatY < -0.5f ? UP : hatY > 0.5f ? DOWN : 0;
        send();
        return true;
    }

    /** The on-screen pad's state (Tactile). L2 and R2 pressed there are full analog triggers too. */
    void tactile(int boutons, int lx, int ly, int rx, int ry) {
        tactileBoutons = boutons;
        tactileLX = lx;
        tactileLY = ly;
        tactileRX = rx;
        tactileRY = ry;
        send();
    }

    private static int melange(int manette, int tactile) {
        return tactile != 128 ? tactile : manette;
    }

    /** Android forbids network calls on the main thread, so each state goes to a sender thread. */
    private void send() {
        final byte[] state = new byte[PACKET_SIZE];
        final ByteBuffer out = ByteBuffer.wrap(state).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MAGIC).putInt(buttons | tactileBoutons);
        out.put((byte) melange(leftX, tactileLX)).put((byte) melange(leftY, tactileLY));
        out.put((byte) melange(rightX, tactileRX)).put((byte) melange(rightY, tactileRY));
        out.put((byte) ((tactileBoutons & L2) != 0 ? 255 : leftTrigger));
        out.put((byte) ((tactileBoutons & R2) != 0 ? 255 : rightTrigger));
        sender.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (socket == null) {
                        socket = new DatagramSocket();
                        local = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
                    }
                    socket.send(new DatagramPacket(state, state.length, local, PORT));
                } catch (Exception ignored) {
                    socket = null; // the next event tries again
                }
            }
        });
    }
}
