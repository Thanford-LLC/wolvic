package com.igalia.wolvic.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.browser.SettingsStore;
import com.igalia.wolvic.browser.engine.Session;
import com.igalia.wolvic.browser.engine.SessionStore;
import com.igalia.wolvic.ui.widgets.Windows;
import com.igalia.wolvic.ui.widgets.WindowWidget;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;
import com.igalia.wolvic.utils.UrlUtils;

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

    /** Listener for changes to the active binding table. */
    public interface BindingsListener {
        void onBindingsChanged();
    }

    private final Windows mWindows;
    private final WidgetManagerDelegate mWidgetManager;
    private final Map<String, Integer> mTable8Dir = new HashMap<>();
    private final Map<String, Integer> mTable4Dir = new HashMap<>();
    private final java.util.concurrent.CopyOnWriteArrayList<BindingsListener> mBindingsListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();
    private String mLastChangedPathId = null;
    private long mLastChangedAtMillis = 0L;

    // Precomputed next-node index: prefix-as-Arrays.toString → legal next nodes.
    // Rebuilt in rebuildNextNodeIndex(), called from stampBindingChange() (tables
    // mutated) and lazily on first getLegalNextNodes() call.
    private final java.util.Map<String, java.util.Set<Integer>> mNextNodeIndex = new java.util.HashMap<>();
    private boolean mNextNodeIndexDirty = true;

    public ComboDispatcher(@NonNull Windows windows, @NonNull WidgetManagerDelegate widgetManager) {
        mWindows = windows;
        mWidgetManager = widgetManager;
        buildTables();
        pushModeToNative();
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
        // Shared cardinal combos — identical in both modes.
        int[][] shared = {
            {A_SCROLL_UP,     2},
            {A_SCROLL_DOWN,   8},
            {A_SCROLL_LEFT,   4},
            {A_SCROLL_RIGHT,  6},
        };
        for (int[] s : shared) {
            String k = key(s[1]);
            mTable8Dir.put(k, s[0]);
            mTable4Dir.put(k, s[0]);
        }
        putBoth(A_SCROLL_TOP,    2, 2);
        putBoth(A_SCROLL_BOTTOM, 8, 8);
        putBoth(A_BACK,          4, 4);
        putBoth(A_FORWARD,       6, 6);
        putBoth(A_REFRESH,       2, 8);
        putBoth(A_FIND_IN_PAGE,  8, 2);
        putBoth(A_STOP,          4, 6);
        putBoth(A_HISTORY,       2, 2, 2);

        // 8-dir specific
        mTable8Dir.put(key(3),           A_NEW_WINDOW);
        mTable8Dir.put(key(1),           A_CLOSE_WINDOW);
        mTable8Dir.put(key(3, 3),        A_DUPLICATE);
        mTable8Dir.put(key(2, 3, 6),     A_NEXT_WINDOW);
        mTable8Dir.put(key(2, 1, 4),     A_PREV_WINDOW);
        mTable8Dir.put(key(9),           A_URL_BAR);
        mTable8Dir.put(key(7),           A_OPEN_BOOKMARKS);
        mTable8Dir.put(key(8, 8, 8),     A_ADD_BOOKMARK);
        mTable8Dir.put(key(2, 1, 4, 7, 8), A_READER_MODE);
        mTable8Dir.put(key(2, 3, 6, 9, 8), A_PRIVATE_WINDOW);

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
            int action = entry[1][0];
            mTable4Dir.put(k, action);
            mTable8Dir.putIfAbsent(k, action);
        }
    }

    /**
     * Rebuilds the combo tables from persisted user overrides. v1 ships a stub
     * body — the Settings screen plan wires up SharedPreferences persistence.
     * Fires BindingsChanged on completion so subscribed listeners (HUD tip pool)
     * can refresh.
     */
    public void reloadBindings() {
        // Reset to defaults; Settings persistence plan will add SharedPreferences override reading.
        mTable8Dir.clear();
        mTable4Dir.clear();
        buildTables();
        stampBindingChange(null);
    }

    private void putBoth(int action, int... path) {
        String k = key(path);
        mTable8Dir.put(k, action);
        mTable4Dir.put(k, action);
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
        // Re-push mode every dispatch — covers preference flips without
        // needing a SharedPreferences listener wired up.
        pushModeToNative();
        int[] combo = Arrays.copyOf(path, length);
        Log.d(LOGTAG, "dispatch path=" + Arrays.toString(combo)
                + " mode=" + (is4DirMode() ? "4dir" : "8dir"));

        Map<String, Integer> table = currentTable();
        Integer action = table.get(key(combo));

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
            return;
        }
        runAction(action);
    }

    public boolean isCombo4DirMode() {
        return is4DirMode();
    }

    private Integer resolveCardinalInterpretation(int[] combo, Map<String, Integer> table, boolean collapseRuns) {
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
                           Map<String, Integer> table,
                           Integer[] found, boolean[] ambiguous,
                           boolean collapseRuns) {
        if (ambiguous[0]) return;
        if (idx == combo.length) {
            int[] lookup = collapseRuns ? collapseAdjacent(buf) : buf;
            Integer a = table.get(key(lookup));
            if (a == null) return;
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
     * key(int...); value is an A_* action constant.
     */
    public java.util.Map<String, Integer> getAllBindings() {
        return java.util.Collections.unmodifiableMap(currentTable());
    }

    /**
     * Returns the A_* action bound to the exact path, or A_NONE if the path is
     * unbound (or only a prefix of a longer combo). Does NOT apply the 4-dir
     * diagonal-drift fallback logic — this is for tip-builder / ghost-preview
     * lookups, which need to know "is this path the endpoint of a binding as-is".
     */
    public int getActionForExactPath(int[] path) {
        if (path == null) return A_NONE;
        Integer action = currentTable().get(key(path));
        return action == null ? A_NONE : action;
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

    private Map<String, Integer> currentTable() {
        return is4DirMode() ? mTable4Dir : mTable8Dir;
    }

    private boolean is4DirMode() {
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
            case A_PRIVATE_WINDOW: togglePrivateMode();        break;
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
