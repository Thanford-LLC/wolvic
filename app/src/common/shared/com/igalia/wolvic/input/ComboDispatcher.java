package com.igalia.wolvic.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.PreferenceManager;

import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.browser.SettingsStore;
import com.igalia.wolvic.browser.engine.Session;
import com.igalia.wolvic.browser.engine.SessionStore;
import com.igalia.wolvic.ui.widgets.Windows;
import com.igalia.wolvic.ui.widgets.WindowWidget;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;
import com.igalia.wolvic.utils.UrlUtils;
import com.thanford.fingerdance.settings.Binding;
import com.thanford.fingerdance.settings.ComboBindingStore;
import com.thanford.fingerdance.settings.ComboHapticController;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Dispatches joystick-combo paths from native to browser actions.
 *
 * Two mapping modes, toggled by SharedPreferences key COMBO_MODE_4DIR_KEY:
 *   4-dir (default): cardinals only (2/4/6/8). More forgiving on Quest's
 *                    small thumbstick.
 *   8-dir          : full 1..9 grid with diagonals.
 *
 * See the combo table comments below for mappings.
 */
public class ComboDispatcher {

    private static final String LOGTAG = "ComboDispatcher";
    public  static final String COMBO_MODE_4DIR_KEY = "fingerdance_combo_4dir_mode";

    private static final float SCROLL_DELTA = 150.0f;
    private static final float MAX_SCROLL   = 1_000_000.0f;

    // Action identifiers — used as values in both combo tables.
    public static final int A_NONE           = 0;
    // Phase 4 sentinel: an override entry with action == A_REMOVED signals
    // "delete this path from the defaults table." A_NONE (0) is filtered as
    // "missing/unknown action" by ComboBindingStore.parseBindings, so the
    // sentinel must be non-zero; negative keeps it unambiguous vs. real actions.
    public static final int A_REMOVED        = -1;
    public static final int A_SCROLL_UP       = 1;
    public static final int A_SCROLL_DOWN     = 2;
    public static final int A_SCROLL_LEFT     = 3;
    public static final int A_SCROLL_RIGHT    = 4;
    public static final int A_SCROLL_TOP      = 5;
    public static final int A_SCROLL_BOTTOM   = 6;
    public static final int A_BACK            = 7;
    public static final int A_FORWARD         = 8;
    public static final int A_REFRESH         = 9;
    public static final int A_FIND_IN_PAGE    = 10;
    public static final int A_STOP            = 11;
    public static final int A_NEW_WINDOW      = 12;
    public static final int A_CLOSE_WINDOW    = 13;
    public static final int A_DUPLICATE       = 14;
    public static final int A_NEXT_WINDOW     = 15;
    public static final int A_PREV_WINDOW     = 16;
    public static final int A_URL_BAR         = 17;
    public static final int A_OPEN_BOOKMARKS  = 18;
    public static final int A_ADD_BOOKMARK    = 19;
    public static final int A_HISTORY         = 23;
    public static final int A_READER_MODE     = 25;
    public static final int A_PRIVATE_WINDOW  = 26;

    // Phase 8a — unbound by default; user assigns via Combos Settings.
    // Ints 27-31 were reserved per the plan; 29-31 (passthrough/resize) descoped.
    public static final int A_TOGGLE_HUD          = 27;
    public static final int A_TOGGLE_MODE         = 28;
    public static final int A_TOGGLE_CURVE_WINDOW = 32;
    public static final int A_GOTO_BOOKMARK       = 33;

    /** Listener for changes to the active binding table. */
    public interface BindingsListener {
        void onBindingsChanged();
    }

    /**
     * Phase 5 — capture-mode listener. When the user is drawing a combo in
     * {@code BindComboView} (FROM_SETTINGS flow), the dispatcher routes emitted
     * paths to the active listener INSTEAD of firing an action. Callbacks are
     * delivered on the main thread so the listener can touch UI directly.
     * Calling {@link #setCaptureMode(boolean, CaptureListener)} while a capture
     * is already active silently replaces the previous listener.
     */
    public interface CaptureListener {
        void onPathCaptured(@NonNull int[] path);
    }

    private final Windows mWindows;
    private final WidgetManagerDelegate mWidgetManager;
    // Phase 4: persisted app context so removeBinding()/setBinding() can
    // instantiate ComboBindingStore. Null in the test-only ctor.
    private final Context mAppContext;
    /** Test-only override for {@link #is4DirMode()}. Null in production. */
    @VisibleForTesting
    Boolean mForcedMode4DirForTest = null;
    // volatile: UI-thread rebinds build a fresh HashMap locally and atomically
    // publish via reference reassignment; the input/render thread only reads
    // through these fields. Without volatile, dispatch() could observe a
    // half-resized HashMap during rebind (A1 race — fix per plan §D-E1).
    private volatile Map<String, Binding> mTable8Dir = new HashMap<>();
    private volatile Map<String, Binding> mTable4Dir = new HashMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<BindingsListener> mBindingsListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    // Phase 5 — capture mode. When mCaptureMode==true AND mCaptureListener!=null,
    // dispatch() posts the emitted path to the listener on the main thread and
    // returns WITHOUT consulting the dispatch table or firing a haptic. Both
    // fields are volatile so the input thread sees the UI-thread flip promptly.
    private volatile boolean mCaptureMode = false;
    private volatile CaptureListener mCaptureListener = null;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    // volatile: getLastChangedPathId() / getLastChangedAtMillis() are public
    // getters that may be polled from the render thread while stampBindingChange
    // writes from the input/Settings thread. The memory barrier is required so
    // a reader can't observe a stale pathId paired with a fresh timestamp.
    private volatile String mLastChangedPathId = null;
    private volatile long mLastChangedAtMillis = 0L;

    // Precomputed next-node index: prefix-as-Arrays.toString → legal next nodes.
    // Rebuilt in rebuildNextNodeIndex(), called from stampBindingChange() (tables
    // mutated) and lazily on first getLegalNextNodes() call.
    private final java.util.Map<String, java.util.Set<Integer>> mNextNodeIndex = new java.util.HashMap<>();
    private boolean mNextNodeIndexDirty = true;

    // Phase 7 — FROM_CAPTURE. Set by onAXButtonPressed(); cleared on first dispatch()
    // that follows the button press. When true, the next grip-release path is captured
    // and forwarded to Combos Settings (action picker) instead of dispatching normally.
    private volatile boolean mPendingAXCapture = false;
    // Stored by dispatch() when mPendingAXCapture is true + no binding found.
    // Read by CombosListBuilder/CombosSettingsView to show the pre-captured path banner.
    private volatile int[] mPendingCapturePath = null;

    // Phase 2 §D8 — haptic feedback on combo resolution. Null in the test-only
    // constructor; production path always has a non-null instance.
    private final ComboHapticController mHapticController;
    // Strong refs — SharedPreferences uses WeakHashMap for listener registration,
    // so we must hold both the prefs instance AND the listener lambda to keep
    // them alive for the life of the dispatcher.
    private final SharedPreferences mDefaultPrefs;
    private final SharedPreferences mBindingsPrefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener mDefaultPrefListener;
    private final SharedPreferences.OnSharedPreferenceChangeListener mBindingsPrefListener;

    public ComboDispatcher(@NonNull Windows windows, @NonNull WidgetManagerDelegate widgetManager) {
        mWindows = windows;
        mWidgetManager = widgetManager;
        // widgetManager is the VRBrowserActivity in production (is-a Context).
        // Cast defensively — if a non-Context delegate is ever passed in (tests,
        // headless tools), disable haptics gracefully rather than crash.
        Context appCtx = (widgetManager instanceof Context)
                ? ((Context) widgetManager).getApplicationContext()
                : null;
        mAppContext = appCtx;
        mHapticController = (appCtx != null)
                ? new ComboHapticController(appCtx, widgetManager)
                : null;

        // Register a pref listener on the default prefs file so CombosSettingsView
        // can flip 4-dir/8-dir mode without needing a direct handle to the dispatcher.
        // Correctness is already guaranteed by pushModeToNative() at the top of
        // dispatch(); this listener only provides immediate HUD-tip UI refresh.
        mDefaultPrefs = (appCtx != null)
                ? PreferenceManager.getDefaultSharedPreferences(appCtx)
                : null;
        mDefaultPrefListener = (prefs, key) -> {
            if (COMBO_MODE_4DIR_KEY.equals(key)) {
                pushModeToNative();
                stampBindingChange(null);
            }
        };
        if (mDefaultPrefs != null) {
            mDefaultPrefs.registerOnSharedPreferenceChangeListener(mDefaultPrefListener);
        }

        // Second listener on the ComboBindingStore prefs file — fires when the
        // Settings Reset footer clears the bindings blob; we then rebuild tables.
        mBindingsPrefs = (appCtx != null)
                ? appCtx.getSharedPreferences(ComboBindingStore.PREFS_FILE, Context.MODE_PRIVATE)
                : null;
        mBindingsPrefListener = (prefs, key) -> {
            if (ComboBindingStore.KEY_BLOB.equals(key)) {
                reloadBindings();
            }
        };
        if (mBindingsPrefs != null) {
            mBindingsPrefs.registerOnSharedPreferenceChangeListener(mBindingsPrefListener);
        }

        buildTables();
        pushModeToNative();
    }

    /**
     * Test-only constructor: skips native push + UI wiring, forces a mode.
     * Used by unit tests that only exercise the table-building logic
     * (getAllBindings / getActionForExactPath / getLegalNextNodes).
     */
    @VisibleForTesting
    public ComboDispatcher(boolean is4DirMode) {
        mWindows = null;
        mWidgetManager = null;
        mAppContext = null;
        mForcedMode4DirForTest = is4DirMode;
        mHapticController = null;
        mDefaultPrefs = null;
        mBindingsPrefs = null;
        mDefaultPrefListener = null;
        mBindingsPrefListener = null;
        buildTables();
    }

    /**
     * Unregister SharedPreferences listeners + cancel any pending haptic
     * runnables. Call from VRBrowserActivity.onDestroy() when the dispatcher
     * is being torn down. Safe to call multiple times.
     */
    public void shutdown() {
        if (mDefaultPrefs != null && mDefaultPrefListener != null) {
            mDefaultPrefs.unregisterOnSharedPreferenceChangeListener(mDefaultPrefListener);
        }
        if (mBindingsPrefs != null && mBindingsPrefListener != null) {
            mBindingsPrefs.unregisterOnSharedPreferenceChangeListener(mBindingsPrefListener);
        }
        if (mHapticController != null) {
            mHapticController.shutdown();
        }
    }

    /**
     * Test-only: overwrite the action bound to {@code path} in both tables
     * and stamp a bindings-changed event. Mirrors the effect of a user
     * rebind without routing through the (not-yet-wired) Settings
     * persistence layer. Production code must not call this.
     */
    @VisibleForTesting
    public void setBindingForTest(@NonNull int[] path, int actionInt) {
        String k = key(path);
        Map<String, Binding> four = new HashMap<>(mTable4Dir);
        Map<String, Binding> eight = new HashMap<>(mTable8Dir);
        Binding b = Binding.of(actionInt);
        four.put(k, b);
        eight.put(k, b);
        mTable4Dir = four;
        mTable8Dir = eight;
        mNextNodeIndexDirty = true;
        stampBindingChange(k);
    }

    private boolean mLastPushed4DirMode = false;
    private boolean mHasPushedMode = false;
    private void pushModeToNative() {
        boolean now4Dir = is4DirMode();
        if (mWidgetManager instanceof VRBrowserActivity) {
            ((VRBrowserActivity) mWidgetManager).setComboFourDirMode(now4Dir);
        }
        if (!mHasPushedMode || mLastPushed4DirMode != now4Dir) {
            boolean first = !mHasPushedMode;
            mHasPushedMode = true;
            mLastPushed4DirMode = now4Dir;
            if (!first) stampBindingChange(null);
        }
    }

    private void buildTables() {
        // Build fresh local maps, populate, then publish atomically by reassigning
        // the volatile fields. Readers on the input thread see either the old
        // reference or the new reference consistently — never a half-resized map.
        Map<String, Binding> four = new HashMap<>();
        Map<String, Binding> eight = new HashMap<>();

        // Shared cardinal combos — identical in both modes.
        int[][] shared = {
            {A_SCROLL_UP,     2},
            {A_SCROLL_DOWN,   8},
            {A_SCROLL_LEFT,   4},
            {A_SCROLL_RIGHT,  6},
        };
        for (int[] s : shared) {
            String k = key(s[1]);
            Binding b = Binding.of(s[0]);
            eight.put(k, b);
            four.put(k, b);
        }
        putBoth(four, eight, A_SCROLL_TOP,    2, 2);
        putBoth(four, eight, A_SCROLL_BOTTOM, 8, 8);
        putBoth(four, eight, A_BACK,          4, 4);
        putBoth(four, eight, A_FORWARD,       6, 6);
        putBoth(four, eight, A_REFRESH,       2, 8);
        putBoth(four, eight, A_FIND_IN_PAGE,  8, 2);
        putBoth(four, eight, A_STOP,          4, 6);
        putBoth(four, eight, A_HISTORY,       2, 2, 2);

        // 8-dir specific
        eight.put(key(3),              Binding.of(A_NEW_WINDOW));
        eight.put(key(1),              Binding.of(A_CLOSE_WINDOW));
        eight.put(key(3, 3),           Binding.of(A_DUPLICATE));
        eight.put(key(2, 3, 6),        Binding.of(A_NEXT_WINDOW));
        eight.put(key(2, 1, 4),        Binding.of(A_PREV_WINDOW));
        eight.put(key(9),              Binding.of(A_URL_BAR));
        eight.put(key(7),              Binding.of(A_OPEN_BOOKMARKS));
        eight.put(key(8, 8, 8),        Binding.of(A_ADD_BOOKMARK));
        eight.put(key(2, 1, 4, 7, 8),  Binding.of(A_READER_MODE));
        eight.put(key(2, 3, 6, 9, 8),  Binding.of(A_PRIVATE_WINDOW));

        // 4-dir-only entries. All cardinal-based, so they don't collide with
        // 8-dir's diagonal bindings — mirror them into the 8-dir table too so
        // a user who prefers cardinal paths still gets the same actions in
        // 8-dir mode. (8-dir's diagonal bindings remain available.)
        int[][][] fourDirCombos = {
            { {6, 6, 6},       {A_NEW_WINDOW}     },
            { {4, 4, 4},       {A_CLOSE_WINDOW}   },
            { {6, 6, 6, 6},    {A_DUPLICATE}      },
            { {2, 6},          {A_NEXT_WINDOW}    },
            { {2, 4},          {A_PREV_WINDOW}    },
            { {6, 8},          {A_URL_BAR}        },
            { {4, 8},          {A_OPEN_BOOKMARKS} },
            { {8, 8, 8},       {A_ADD_BOOKMARK}   },
            { {2, 4, 8},       {A_READER_MODE}    },
            { {2, 6, 8},       {A_PRIVATE_WINDOW} },
        };
        for (int[][] entry : fourDirCombos) {
            String k = key(entry[0]);
            Binding b = Binding.of(entry[1][0]);
            four.put(k, b);
            eight.putIfAbsent(k, b);
        }

        // Phase 4: layer user overrides on top of defaults.
        //   override.action == A_REMOVED  → delete the default binding at that path
        //   override.action >= 1          → replace the default binding at that path
        // ComboBindingStore already filters action == 0, so the loop body only
        // sees real action ints (positive) or the A_REMOVED sentinel (negative).
        if (mAppContext != null) {
            Map<String, Binding> overrides = new ComboBindingStore(mAppContext).load();
            for (Map.Entry<String, Binding> e : overrides.entrySet()) {
                int[] p = ComboBindingStore.keyToPath(e.getKey());
                if (p.length == 0) continue;
                String k = key(p);
                Binding b = e.getValue();
                if (b.action == A_REMOVED) {
                    four.remove(k);
                    eight.remove(k);
                } else {
                    four.put(k, b);
                    eight.put(k, b);
                }
            }
        }

        // Atomic publish — readers see a fully-built map after these writes.
        mTable4Dir = four;
        mTable8Dir = eight;
    }

    /**
     * Rebuilds the combo tables from defaults, then layers any user overrides
     * read from ComboBindingStore. Fires BindingsChanged so subscribed listeners
     * (HUD tip pool, Settings row list) refresh. Invoked automatically when the
     * KEY_BLOB pref changes (Reset footer, removeBinding, future setBinding);
     * safe to call directly from the UI thread.
     */
    public void reloadBindings() {
        buildTables();
        stampBindingChange(null);
    }

    /**
     * Phase 4 — mark {@code path} as removed in the user-override blob. The
     * KEY_BLOB pref listener fires reloadBindings() immediately after save(),
     * which rebuilds defaults and applies the A_REMOVED sentinel to erase this
     * binding from both tables. No-op when {@code mAppContext} is null (test
     * ctor) or the path is empty.
     */
    public void removeBinding(@NonNull int[] path) {
        if (mAppContext == null || path.length == 0) {
            return;
        }
        ComboBindingStore store = new ComboBindingStore(mAppContext);
        Map<String, Binding> overrides = new HashMap<>(store.load());
        overrides.put(ComboBindingStore.pathToKey(path), Binding.of(A_REMOVED));
        store.save(overrides);
        // KEY_BLOB listener auto-fires reloadBindings() → BindingsListener → UI refresh.
    }

    /**
     * Phase 5 — write a user-override binding for {@code path} → {@code binding}.
     * Takes a full {@link Binding} (not just an action int) so future parametric
     * actions (e.g. A_GOTO_BOOKMARK with a bookmark_id) just pass a param-bearing
     * Binding without widening the API. No-op when mAppContext is null (test ctor)
     * or path is empty. The KEY_BLOB pref listener fires reloadBindings() →
     * BindingsListener → UI refresh automatically after save().
     */
    public void setBinding(@NonNull int[] path, @NonNull Binding binding) {
        if (mAppContext == null || path.length == 0) {
            return;
        }
        ComboBindingStore store = new ComboBindingStore(mAppContext);
        Map<String, Binding> overrides = new HashMap<>(store.load());
        overrides.put(ComboBindingStore.pathToKey(path), binding);
        store.save(overrides);
    }

    /**
     * Phase 5 — enter/leave capture mode. While in capture mode the dispatcher
     * routes emitted paths to the listener on the main thread and does NOT
     * consult the dispatch table. Passing {@code enabled=true, listener=null}
     * is treated as disabled (prevents orphan state). Safe to call from any
     * thread; the UI thread is expected.
     */
    public void setCaptureMode(boolean enabled, @Nullable CaptureListener listener) {
        mCaptureListener = enabled ? listener : null;
        mCaptureMode = enabled && listener != null;
    }

    /**
     * Phase 7 — called from VRBrowserActivity.handleComboAXPressed() (JNI).
     * Marks the next grip-release as a FROM_CAPTURE request. Ignored when
     * combos are suspended (capture mode active, settings open via mCaptureMode).
     */
    public void onAXButtonPressed(int hand) {
        if (mCaptureMode) return; // already in capture mode, ignore
        Log.d(LOGTAG, "onAXButtonPressed hand=" + hand + " → pending capture set");
        mPendingAXCapture = true;
    }

    /** Returns the path captured via the FROM_CAPTURE A/X flow, or null if none pending. */
    @Nullable
    public int[] getPendingCapturePath() {
        return mPendingCapturePath;
    }

    /** Clears the pending capture path after the UI has consumed it. */
    public void clearPendingCapturePath() {
        mPendingCapturePath = null;
    }

    private static void putBoth(Map<String, Binding> four, Map<String, Binding> eight,
                                int action, int... path) {
        String k = key(path);
        Binding b = Binding.of(action);
        eight.put(k, b);
        four.put(k, b);
    }

    private static String key(int... path) {
        return Arrays.toString(path);
    }

    /** Inverse of key(int...) — parses "[2, 4, 6]" back to int[]. */
    private static int[] parseKey(String rawKey) {
        if (rawKey == null || rawKey.length() < 2) return new int[0];
        // rawKey produced by Arrays.toString: "[]", "[2]", "[2, 4, 6]"
        String trimmed = rawKey.substring(1, rawKey.length() - 1).trim();
        if (trimmed.isEmpty()) return new int[0];
        String[] parts = trimmed.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }

    // Node → candidate cardinal set for 4-dir disambiguation.
    // Cardinals map to themselves; diagonals expand to their two adjacent cardinals.
    private static final int[][] CARDINAL_CANDIDATES = {
            /*0*/ {},
            /*1 UL*/ {2, 4},
            /*2  U*/ {2},
            /*3 UR*/ {2, 6},
            /*4  L*/ {4},
            /*5  C*/ {},
            /*6  R*/ {6},
            /*7 DL*/ {4, 8},
            /*8  D*/ {8},
            /*9 DR*/ {6, 8},
    };

    public void dispatch(int[] path, int length) {
        if (length <= 0 || length > 8) return;
        // Phase 5 — capture-mode routing. When BindComboView is recording a
        // draw, hand the path off to the listener on the UI thread and skip
        // the dispatch table AND haptic entirely. Snapshot the listener before
        // posting so a concurrent setCaptureMode(false, null) can't null it
        // out between the check and the post.
        if (mCaptureMode) {
            CaptureListener listener = mCaptureListener;
            if (listener != null) {
                int[] captured = Arrays.copyOf(path, length);
                mMainHandler.post(() -> listener.onPathCaptured(captured));
                return;
            }
        }
        // Re-push mode every dispatch — covers preference flips without
        // needing a SharedPreferences listener wired up.
        pushModeToNative();
        int[] combo = Arrays.copyOf(path, length);
        Log.d(LOGTAG, "dispatch path=" + Arrays.toString(combo)
                + " mode=" + (is4DirMode() ? "4dir" : "8dir"));

        Map<String, Binding> table = currentTable();
        Binding binding = table.get(key(combo));
        Integer action = binding != null ? binding.action : null;

        // 4-dir fallback: if the native classifier emitted diagonals due to
        // thumbstick drift, enumerate every cardinal interpretation and take
        // the unique one that matches a registered combo. Ambiguity → skip.
        if (action == null && is4DirMode()) {
            action = resolveCardinalInterpretation(combo, table, /*collapseRuns=*/false);
            // Second pass: collapse runs of the same cardinal after expansion.
            // Handles spurious mid-arc activations — e.g. native emits [2,3,6]
            // for an intended 2→6 arc; diagonal 3 expands to 2 or 6, making
            // a duplicate with its neighbor. Collapsing gives [2,6]. Intended
            // repeats (6-6-6, 8-8-8) are already matched by the direct/non-
            // collapsing pass, so this can't hijack them.
            if (action == null) {
                action = resolveCardinalInterpretation(combo, table, /*collapseRuns=*/true);
            }
        }

        if (action == null) {
            Log.d(LOGTAG, "Unrecognised combo: " + Arrays.toString(combo));
            // Phase 7: A/X was pressed before grip release → FROM_CAPTURE flow.
            if (mPendingAXCapture) {
                mPendingAXCapture = false;
                mPendingCapturePath = combo;
                Log.d(LOGTAG, "FROM_CAPTURE triggered: path=" + Arrays.toString(combo));
                mMainHandler.post(() -> {
                    if (mWidgetManager instanceof VRBrowserActivity) {
                        ((VRBrowserActivity) mWidgetManager).openCombosSettings();
                    }
                });
                return;
            }
            if (mHapticController != null) mHapticController.fireIllegalCombo();
            return;
        }
        mPendingAXCapture = false; // Bound path found — discard any pending capture intent.
        if (mHapticController != null) mHapticController.fireLegalCombo();
        runAction(action);
    }

    public boolean isCombo4DirMode() {
        return is4DirMode();
    }

    private Integer resolveCardinalInterpretation(int[] combo, Map<String, Binding> table, boolean collapseRuns) {
        int[] buf = new int[combo.length];
        Integer[] found = new Integer[]{null};
        boolean[] ambiguous = new boolean[]{false};
        enumerate(combo, 0, buf, table, found, ambiguous, collapseRuns);
        if (ambiguous[0]) {
            Log.d(LOGTAG, "Ambiguous 4-dir interpretation for " + Arrays.toString(combo)
                    + " collapseRuns=" + collapseRuns);
            return null;
        }
        return found[0];
    }

    private void enumerate(int[] combo, int idx, int[] buf,
                           Map<String, Binding> table,
                           Integer[] found, boolean[] ambiguous,
                           boolean collapseRuns) {
        if (ambiguous[0]) return;
        if (idx == combo.length) {
            int[] lookup = collapseRuns ? collapseAdjacent(buf) : buf;
            Binding bb = table.get(key(lookup));
            if (bb == null) return;
            Integer a = bb.action;
            if (found[0] == null) {
                found[0] = a;
            } else if (!found[0].equals(a)) {
                ambiguous[0] = true;
            }
            return;
        }
        int node = combo[idx];
        int[] candidates = (node >= 1 && node <= 9) ? CARDINAL_CANDIDATES[node] : new int[0];
        for (int c : candidates) {
            buf[idx] = c;
            enumerate(combo, idx + 1, buf, table, found, ambiguous, collapseRuns);
            if (ambiguous[0]) return;
        }
    }

    private static int[] collapseAdjacent(int[] path) {
        if (path.length == 0) return path;
        int[] tmp = new int[path.length];
        int n = 0;
        for (int v : path) {
            if (n == 0 || tmp[n - 1] != v) tmp[n++] = v;
        }
        return Arrays.copyOf(tmp, n);
    }

    /**
     * Returns the active dispatch table (4-dir or 8-dir based on current mode) as
     * an unmodifiable view. Key is the grid-path identifier produced by
     * key(int...); value is a {@link Binding} wrapping the A_* action plus an
     * optional parametric payload (bookmark_id etc., reserved for Phase 2).
     */
    public java.util.Map<String, Binding> getAllBindings() {
        return java.util.Collections.unmodifiableMap(currentTable());
    }

    /**
     * Returns the 4-dir table regardless of current mode. Used by
     * {@link com.thanford.fingerdance.settings.CombosListBuilder} to filter
     * out 4-dir cardinal fallback paths when displaying the 8-dir list — if a
     * path key appears in BOTH tables for the same action it was mirrored from
     * the 4-dir defaults and should not clutter the 8-dir view.
     */
    public java.util.Map<String, Binding> get4DirBindings() {
        return java.util.Collections.unmodifiableMap(mTable4Dir);
    }

    /**
     * Returns the A_* action bound to the exact path, or A_NONE if the path is
     * unbound (or only a prefix of a longer combo). Does NOT apply the 4-dir
     * diagonal-drift fallback logic — this is for tip-builder / ghost-preview
     * lookups, which need to know "is this path the endpoint of a binding as-is".
     */
    public int getActionForExactPath(int[] path) {
        if (path == null) return A_NONE;
        Binding binding = currentTable().get(key(path));
        return binding == null ? A_NONE : binding.action;
    }

    /**
     * Returns the set of grid nodes (1..9, excluding 5) that are legal next
     * steps from the given path — i.e. there exists some binding whose path
     * begins with {@code currentPath + [node]}. Used by the HUD ghost-trace
     * preview to paint only reachable next moves.
     *
     * Never returns 5 (center is implicit origin, never mid-path).
     *
     * Reads a precomputed index (rebuilt lazily via mNextNodeIndexDirty) so
     * repeated calls from the HUD per path-state change do not allocate.
     */
    public java.util.Set<Integer> getLegalNextNodes(int[] currentPath) {
        if (mNextNodeIndexDirty) rebuildNextNodeIndex();
        String prefixKey = java.util.Arrays.toString(currentPath == null ? new int[0] : currentPath);
        java.util.Set<Integer> hit = mNextNodeIndex.get(prefixKey);
        return hit == null ? java.util.Collections.emptySet() : java.util.Collections.unmodifiableSet(hit);
    }

    private void rebuildNextNodeIndex() {
        mNextNodeIndex.clear();
        for (String rawKey : currentTable().keySet()) {
            int[] path = parseKey(rawKey);
            // For every proper prefix of the binding path (including empty), the
            // node at prefix.length is a legal next node. Skip node 5.
            for (int prefixLen = 0; prefixLen < path.length; prefixLen++) {
                int next = path[prefixLen];
                if (next == 5) continue;
                int[] prefix = java.util.Arrays.copyOf(path, prefixLen);
                String prefixKey = java.util.Arrays.toString(prefix);
                java.util.Set<Integer> set = mNextNodeIndex.get(prefixKey);
                if (set == null) {
                    set = new java.util.HashSet<>();
                    mNextNodeIndex.put(prefixKey, set);
                }
                set.add(next);
            }
        }
        mNextNodeIndexDirty = false;
    }

    public void addBindingsListener(@NonNull BindingsListener listener) {
        mBindingsListeners.addIfAbsent(listener);
    }

    public void removeBindingsListener(@NonNull BindingsListener listener) {
        mBindingsListeners.remove(listener);
    }

    public String getLastChangedPathId() { return mLastChangedPathId; }
    public long getLastChangedAtMillis() { return mLastChangedAtMillis; }

    /**
     * Stamps recency state (pathId + timestamp) and fires bindingsChanged to
     * subscribers. Use this whenever the dispatch tables have just changed.
     * A null pathId means "bulk change, no specific path" (e.g., mode flip,
     * reloadBindings with no override); the HUD selector's 30s recency bias
     * treats null as "no specific tip to boost".
     */
    private void stampBindingChange(String pathId) {
        mLastChangedPathId = pathId;
        mLastChangedAtMillis = System.currentTimeMillis();
        mNextNodeIndexDirty = true;
        fireBindingsChanged();
    }

    private void fireBindingsChanged() {
        for (BindingsListener l : mBindingsListeners) {
            l.onBindingsChanged();
        }
    }

    private Map<String, Binding> currentTable() {
        return is4DirMode() ? mTable4Dir : mTable8Dir;
    }

    private boolean is4DirMode() {
        if (mForcedMode4DirForTest != null) return mForcedMode4DirForTest;
        Context ctx = contextForPrefs();
        if (ctx == null) return true;  // default to 4-dir (ergonomic default)
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean(COMBO_MODE_4DIR_KEY, true);
    }

    private Context contextForPrefs() {
        WindowWidget win = focusedWindow();
        return win != null ? win.getContext() : null;
    }

    private void runAction(int action) {
        switch (action) {
            case A_SCROLL_UP:      scroll(0,  SCROLL_DELTA);  break;
            case A_SCROLL_DOWN:    scroll(0, -SCROLL_DELTA);  break;
            case A_SCROLL_LEFT:    scroll(-SCROLL_DELTA, 0);  break;
            case A_SCROLL_RIGHT:   scroll( SCROLL_DELTA, 0);  break;
            case A_SCROLL_TOP:     scroll(0,  MAX_SCROLL);    break;
            case A_SCROLL_BOTTOM:  scroll(0, -MAX_SCROLL);    break;
            case A_BACK:           back();                     break;
            case A_FORWARD:        forward();                  break;
            case A_REFRESH:        refresh();                  break;
            case A_FIND_IN_PAGE:   findInPage();               break;
            case A_STOP:           stop();                     break;
            case A_NEW_WINDOW:     newWindow();                break;
            case A_CLOSE_WINDOW:   closeCurrentWindow();       break;
            case A_DUPLICATE:      duplicateWindow();          break;
            case A_NEXT_WINDOW:    rotateWindows(+1);          break;
            case A_PREV_WINDOW:    rotateWindows(-1);          break;
            case A_URL_BAR:        focusUrlBar();              break;
            case A_OPEN_BOOKMARKS: openBookmarks();            break;
            case A_ADD_BOOKMARK:   addToBookmarks();           break;
            case A_HISTORY:        openHistory();              break;
            case A_READER_MODE:    toggleReaderMode();         break;
            case A_PRIVATE_WINDOW:      togglePrivateMode();     break;
            case A_TOGGLE_CURVE_WINDOW: toggleCurvedWindow();   break;
            default:               Log.d(LOGTAG, "No handler for action " + action); break;
        }
    }

    // ---------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------

    private void scroll(float deltaX, float deltaY) {
        WindowWidget win = focusedWindow();
        if (win == null) return;
        MotionEventGenerator.dispatchScroll(win, 0, true, deltaX, deltaY);
    }

    private void back() {
        mWindows.handleBack();
    }

    private void forward() {
        Session session = focusedSession();
        if (session != null) session.goForward();
    }

    private void newWindow() {
        WindowWidget win = mWindows.addWindow();
        if (win != null) {
            win.loadHome();
        }
    }

    private void closeCurrentWindow() {
        WindowWidget win = focusedWindow();
        if (win != null) mWindows.closeWindow(win);
    }

    private void duplicateWindow() {
        WindowWidget src = focusedWindow();
        if (src == null) return;
        String uri = src.getSession().getCurrentUri();
        WindowWidget newWin = mWindows.addWindow();
        if (newWin != null && uri != null && !uri.isEmpty()) {
            newWin.getSession().loadUri(uri);
        }
    }

    // direction > 0 → counterclockwise bring-in (right neighbour slides to
    // front, front slides right). direction < 0 → clockwise (left neighbour
    // to front). No-op with fewer than 2 windows. Focus follows the new front
    // so keyboard/URL-bar interactions target the visible window.
    private void rotateWindows(int direction) {
        WindowWidget front = mWindows.getFrontWindow();
        int count = mWindows.getCurrentWindows().size();
        Log.d(LOGTAG, "rotateWindows ENTER dir=" + direction + " front=" + front + " count=" + count);
        if (front == null) {
            Log.d(LOGTAG, "rotateWindows: no front window — abort");
            return;
        }
        if (count < 2) {
            Log.d(LOGTAG, "rotateWindows: only " + count + " window(s), need 2+ to rotate");
            return;
        }
        if (direction > 0) {
            mWindows.moveWindowRight(front);
        } else {
            mWindows.moveWindowLeft(front);
        }
        WindowWidget newFront = mWindows.getFrontWindow();
        if (newFront != null) mWindows.focusWindow(newFront);
        Log.d(LOGTAG, "rotateWindows EXIT dir=" + direction + " newFront=" + newFront);
    }

    private void focusUrlBar() {
        mWidgetManager.getNavigationBar().focusUrlBar();
    }

    private void openBookmarks() {
        WindowWidget win = focusedWindow();
        if (win != null) win.showLibrary(Windows.ContentType.BOOKMARKS);
    }

    private void openHistory() {
        WindowWidget win = focusedWindow();
        if (win != null) win.showLibrary(Windows.ContentType.HISTORY);
    }

    private void addToBookmarks() {
        WindowWidget win = focusedWindow();
        if (win == null) return;
        Session session = win.getSession();
        String url   = session.getCurrentUri();
        String title = session.getCurrentTitle();
        if (url == null || url.isEmpty()) return;
        SessionStore.get().getBookmarkStore().addBookmark(url, title);
    }

    private void findInPage() {
        WindowWidget win = focusedWindow();
        if (win != null) win.showFindInPage();
    }

    private void refresh() {
        Session session = focusedSession();
        if (session != null) session.reload();
    }

    private void stop() {
        Session session = focusedSession();
        if (session != null) session.stop();
    }

    private void togglePrivateMode() {
        if (mWindows.isInPrivateMode()) {
            mWindows.exitPrivateMode();
        } else {
            mWindows.enterPrivateMode();
        }
    }

    private void toggleCurvedWindow() {
        if (mAppContext == null || mWindows == null) return;
        com.igalia.wolvic.browser.SettingsStore store =
                com.igalia.wolvic.browser.SettingsStore.getInstance(mAppContext);
        boolean nowCurved = store.isCurvedModeEnabled();
        store.setCylinderDensity(nowCurved ? 0f
                : com.igalia.wolvic.browser.SettingsStore.CYLINDER_DENSITY_ENABLED_DEFAULT);
        mWindows.updateCurvedMode(true);
    }

    private void toggleReaderMode() {
        // Reader mode wiring TBD — Wolvic's reader view lives in the UI layer
        // and isn't exposed cleanly for programmatic toggle. Log for now so
        // combo recognition can be verified; revisit when wiring up.
        Log.d(LOGTAG, "Reader mode combo recognized — UI hook not yet implemented");
    }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private WindowWidget focusedWindow() {
        return mWindows.getFocusedWindow();
    }

    private Session focusedSession() {
        WindowWidget win = focusedWindow();
        return win != null ? win.getSession() : null;
    }
}
