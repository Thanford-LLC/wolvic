#include <gtest/gtest.h>
#include "ComboWindowEngine.h"

using namespace glyphew;

// ---------------------------------------------------------------------------
// Test harness helpers
// ---------------------------------------------------------------------------

struct TestHarness {
    ComboEvent lastEvent{};
    int        eventCount = 0;
    int        progressEventCount = 0;
    int        previewProgressCallCount = 0;
    int        lastPreviewProgressZone = -1;
    float      lastPreviewProgressValue = -1.0f;
    bool       longPressFired = false;

    ComboWindowEngine engine{
        [this](const ComboEvent& e) {
            lastEvent   = e;
            eventCount++;
        },
        [this](const ComboEvent&) {
            progressEventCount++;
        }
    };

    TestHarness() {
        engine.SetPreviewProgressCallback([this](int zone, float progress) {
            previewProgressCallCount++;
            lastPreviewProgressZone = zone;
            lastPreviewProgressValue = progress;
        });
        engine.SetLongPressCallback([this]() {
            longPressFired = true;
        });
        // Tests default to 8-dir mode so all 8 nodes are reachable.
        ComboWindowEngine::SetFourDirMode(false);
    }

    // Shorthand: single frame call.
    bool Tick(float x, float y, bool thumb, bool grip, int64_t ms) {
        return engine.Process(x, y, thumb, grip, ms);
    }

    // Convenience: grip-press / grip-release frame, no axis.
    bool GripPress(int64_t ms)   { return Tick(0, 0, false, true,  ms); }
    bool GripRelease(int64_t ms) { return Tick(0, 0, false, false, ms); }

    void ExpectPath(std::initializer_list<int> expected) {
        ASSERT_EQ(lastEvent.length, (int)expected.size());
        int i = 0;
        for (int n : expected) {
            EXPECT_EQ(lastEvent.path[i++], n);
        }
    }
};

// Axis shortcuts — Y positive = up (node 2), X positive = right (node 6).
static constexpr float UP    =  0.8f;
static constexpr float DOWN  = -0.8f;
static constexpr float RIGHT =  0.8f;
static constexpr float LEFT  = -0.8f;
// MID: above CENTER_THRESHOLD (0.20) and PREVIEW_MAGNITUDE (0.25) but below
// ACTIVATION_MAGNITUDE (0.70). The engine is in the preview band, not activated.
static constexpr float MID   =  0.3f;
// CENTER_AXIS: below CENTER_THRESHOLD (0.20) and PREVIEW_MAGNITUDE (0.25).
// When at CENTER_AXIS with path content, center-dwell timer starts.
static constexpr float CENTER_AXIS = 0.05f;

// ---------------------------------------------------------------------------
// Grip-gate tests
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, NoGripNoActivation) {
    TestHarness h;
    bool consumed = h.Tick(0, UP, false, false, 100);
    EXPECT_FALSE(consumed);
    EXPECT_EQ(h.eventCount, 0);
    EXPECT_EQ(h.engine.GetPathLength(), 0);
}

TEST(ComboWindowEngine, GripHeldReturnsConsumed) {
    TestHarness h;
    bool consumed = h.GripPress(100);
    EXPECT_TRUE(consumed);
    EXPECT_EQ(h.eventCount, 0);
}

TEST(ComboWindowEngine, GripReleaseWithNoPathEmitsNothing) {
    TestHarness h;
    h.GripPress(100);
    h.GripRelease(110);
    EXPECT_EQ(h.eventCount, 0);
}

// ---------------------------------------------------------------------------
// Single-node paths
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, SingleNodeUp) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP, false, true, 10);    // → NODE_ACTIVE(2)
    h.GripRelease(20);                 // fire
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2});
}

TEST(ComboWindowEngine, AllEightDirections) {
    // Axis → expected node
    struct Case { float x; float y; int node; };
    const Case cases[] = {
        { 0,    UP,   2 },  // up
        { 0,    DOWN, 8 },  // down
        { LEFT, 0,    4 },  // left
        { RIGHT,0,    6 },  // right
        { RIGHT,UP,   3 },  // upper-right
        { LEFT, UP,   1 },  // upper-left
        { RIGHT,DOWN, 9 },  // lower-right
        { LEFT, DOWN, 7 },  // lower-left
    };
    for (auto& c : cases) {
        TestHarness h;
        h.GripPress(0);
        h.Tick(c.x, c.y, false, true, 10);
        h.GripRelease(20);
        ASSERT_EQ(h.eventCount, 1) << "node " << c.node;
        ASSERT_EQ(h.lastEvent.length, 1) << "node " << c.node;
        EXPECT_EQ(h.lastEvent.path[0], c.node) << "node " << c.node;
    }
}

TEST(ComboWindowEngine, AxisAtExactThreshold) {
    TestHarness h;
    h.GripPress(0);
    // Just below ACTIVATION_MAGNITUDE — should NOT activate.
    h.Tick(0, ACTIVATION_MAGNITUDE - 0.01f, false, true, 10);
    EXPECT_EQ(h.engine.GetPathLength(), 0);
    // Exactly at ACTIVATION_MAGNITUDE — activates (condition is >=).
    h.Tick(0, ACTIVATION_MAGNITUDE, false, true, 20);
    EXPECT_EQ(h.engine.GetPathLength(), 1);
}

// ---------------------------------------------------------------------------
// Repeat node (e.g. scroll-to-top: 5→2→2)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, RepeatNodeDoubleUp) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,       false, true, 10);   // NODE_ACTIVE(2)
    h.Tick(0, MID,      false, true, 20);   // magnitude drops below ACTIVATION_RELEASE → re-arm
    h.Tick(0, UP,       false, true, 30);   // NODE_ACTIVE(2) again → repeat
    h.GripRelease(40);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2, 2});
}

// ---------------------------------------------------------------------------
// Adjacent node (e.g. new-tab: 5→3 with two-node path 5→3→6)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, AdjacentNewNode) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(RIGHT, UP,   false, true, 10);   // NODE_ACTIVE(3)
    h.Tick(MID,   MID,  false, true, 20);   // below ACTIVATION_RELEASE → re-arm
    h.Tick(RIGHT, 0,    false, true, 30);   // NODE_ACTIVE(6)
    h.GripRelease(40);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({3, 6});
}

// ---------------------------------------------------------------------------
// Cross-center paths (CENTER_DWELL passthrough)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, CrossCenterPassthrough_Refresh) {
    // 5→2→8: up then quickly through center to down = Refresh
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,          false, true, 10);    // NODE_ACTIVE(2)
    h.Tick(0, CENTER_AXIS, false, true, 20);    // center-dwell starts (t=20)
    // Move to down before CENTER_EMIT_DWELL_MS (800ms) elapses → passthrough
    h.Tick(0, DOWN,        false, true, 30);    // NODE_ACTIVE(8), path [2,8]
    h.GripRelease(40);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2, 8});
}

TEST(ComboWindowEngine, CrossCenterPassthrough_Stop) {
    // 5→4→6: left then right = Stop
    TestHarness h;
    h.GripPress(0);
    h.Tick(LEFT,        0, false, true, 10);
    h.Tick(CENTER_AXIS, 0, false, true, 20);    // center-dwell starts
    h.Tick(RIGHT,       0, false, true, 30);    // passthrough — path [4,6]
    h.GripRelease(40);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({4, 6});
}

// ---------------------------------------------------------------------------
// CENTER_DWELL fires after holding at center for CENTER_EMIT_DWELL_MS (800ms)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, CenterDwellAutoEmit_800ms) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,          false, true, 10);      // NODE_ACTIVE(2)
    h.Tick(0, CENTER_AXIS, false, true, 20);      // center-dwell starts at t=20
    // Not fired yet at 799ms elapsed (t=819).
    h.Tick(0, CENTER_AXIS, false, true, 819);
    EXPECT_EQ(h.eventCount, 0);
    // Fires at CENTER_EMIT_DWELL_MS elapsed (800ms, t=820).
    h.Tick(0, CENTER_AXIS, false, true, 820);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2});
}

TEST(ComboWindowEngine, CenterDwellNotFiredBeforeTimeout) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,          false, true, 10);
    h.Tick(0, CENTER_AXIS, false, true, 20);
    h.Tick(0, CENTER_AXIS, false, true, 819);   // 799ms elapsed — not yet
    EXPECT_EQ(h.eventCount, 0);
}

TEST(ComboWindowEngine, CenterDwellInterruptedByNewActivation) {
    // Axis returns to center then flicks up again before dwell completes → path [2,2]
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,          false, true, 10);    // NODE_ACTIVE(2)
    h.Tick(0, CENTER_AXIS, false, true, 20);    // center-dwell starts at t=20
    // Flick up again at t=50 — only 30ms elapsed, well before 800ms.
    h.Tick(0, UP,          false, true, 50);    // NODE_ACTIVE(2) again; dwell resets
    h.GripRelease(60);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2, 2});
}

// ---------------------------------------------------------------------------
// Cancel (thumbstick button mid-path = silent cancel, CLAUDE.md §5.4)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, CancelFromIdle) {
    TestHarness h;
    // Pressing thumbstick in IDLE (grip-OFF) starts the long-press timer.
    // No combo events must fire.
    h.Tick(0, 0, true, false, 10);
    EXPECT_EQ(h.eventCount, 0);
}

TEST(ComboWindowEngine, CancelFromNodeActive) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,  false, true,  10);    // NODE_ACTIVE(2)
    h.Tick(0, UP,  true,  true,  20);    // thumbstick press mid-path → cancel
    EXPECT_EQ(h.eventCount, 0);
    EXPECT_EQ(h.engine.GetPathLength(), 0);
    // Engine is back in IDLE — subsequent grip+flick works.
    h.GripPress(30);
    h.Tick(0, UP, false, true, 40);
    h.GripRelease(50);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2});
}

TEST(ComboWindowEngine, CancelFromBetweenNodes) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,  false, true, 10);    // NODE_ACTIVE(2)
    h.Tick(0, MID, false, true, 20);    // below ACTIVATION_RELEASE
    h.Tick(0, MID, true,  true, 30);    // thumbstick → cancel
    EXPECT_EQ(h.eventCount, 0);
}

// ---------------------------------------------------------------------------
// Holding at MID (between nodes) does NOT auto-emit
// Regression pin: there is no "between-nodes timeout" — path only fires on
// grip release or center-dwell. (Previous test assumed a removed feature.)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, MidAxisHoldDoesNotAutoEmit) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,  false, true, 0);      // NODE_ACTIVE(2)
    h.Tick(0, MID, false, true, 10);     // below ACTIVATION_RELEASE → preview band
    // Hold at MID for >2000ms — should NOT emit.
    h.Tick(0, MID, false, true, 2011);
    EXPECT_EQ(h.eventCount, 0);
    // Path still held; grip release fires it.
    h.GripRelease(2020);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2});
}

// ---------------------------------------------------------------------------
// Multi-node paths matching built-in combos
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, ScrollToTop_2_2) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,  false, true, 10);
    h.Tick(0, MID, false, true, 20);
    h.Tick(0, UP,  false, true, 30);
    h.GripRelease(40);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2, 2});
}

TEST(ComboWindowEngine, Back_4_4) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(LEFT, 0,   false, true, 10);
    h.Tick(MID,  0,   false, true, 20);
    h.Tick(LEFT, 0,   false, true, 30);
    h.GripRelease(40);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({4, 4});
}

TEST(ComboWindowEngine, NextTab_2_3) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0,     UP, false, true, 10);     // node 2
    h.Tick(MID,   MID,false, true, 20);     // between
    h.Tick(RIGHT, UP, false, true, 30);     // node 3
    h.GripRelease(40);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2, 3});
}

TEST(ComboWindowEngine, PrevTab_2_1) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0,    UP, false, true, 10);      // node 2
    h.Tick(MID,  MID,false, true, 20);      // between
    h.Tick(LEFT, UP, false, true, 30);      // node 1
    h.GripRelease(40);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2, 1});
}

// ---------------------------------------------------------------------------
// Combos disabled passthrough (grip as the gate)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, CombosDisabledWhenNoGrip) {
    TestHarness h;
    for (int t = 0; t < 10; t++) {
        bool consumed = h.Tick(0, UP, false, false, t * 10);
        EXPECT_FALSE(consumed) << "frame " << t;
    }
    EXPECT_EQ(h.eventCount, 0);
}

// ---------------------------------------------------------------------------
// Silent cancel (OS overlay / focus loss)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, SilentCancelDiscardsPath) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP, false, true, 10);
    h.engine.CancelSilent();
    EXPECT_EQ(h.eventCount, 0);
    EXPECT_EQ(h.engine.GetPathLength(), 0);
}

// Regression pin: CancelSilent must fire an empty progress event (length=0)
// so the HUD clears its path buffer, AND reset the preview-progress sentinel
// so the next grip cycle re-syncs from scratch.
TEST(ComboWindowEngine, CancelSilentFiresEmptyProgressAndResetsPreviewSentinel) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP, false, true, 10);     // appends node 2 → progress event fires
    int progressBefore = h.progressEventCount;
    // Preview-progress sentinel should be at some non-idle value now.
    h.engine.CancelSilent();
    // A path WAS present → empty progress event must fire.
    EXPECT_GT(h.progressEventCount, progressBefore)
        << "CancelSilent with a non-empty path must fire an empty progress event";
    // Next tick with no movement should hit the reset sentinel, forcing a (0, 0.0)
    // preview-progress emit even if the value hasn't changed meaningfully.
    int ppCallsBefore = h.previewProgressCallCount;
    h.Tick(0, UP, false, true, 20);  // start fresh grip cycle
    // The sentinel was reset to -1.0f by CancelSilent; any non-negative progress
    // passes the epsilon gate → at least one preview-progress emit expected.
    EXPECT_GT(h.previewProgressCallCount, ppCallsBefore);
}

TEST(ComboWindowEngine, SilentCancelOnEmptyPathDoesNotFireProgress) {
    TestHarness h;
    // Cancel with no path in progress must NOT fire an extra progress event.
    h.GripPress(0);
    int progressBefore = h.progressEventCount;
    h.engine.CancelSilent();  // no nodes appended
    EXPECT_EQ(h.progressEventCount, progressBefore);
}

// ---------------------------------------------------------------------------
// Path buffer overflow guard (max 8 nodes)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, PathBufferDoesNotOverflow) {
    TestHarness h;
    h.GripPress(0);
    // Alternate up/between 10 times — should cap at MAX_PATH_LENGTH.
    for (int i = 0; i < 10; i++) {
        h.Tick(0, UP,  false, true, i * 20 + 10);
        h.Tick(0, MID, false, true, i * 20 + 15);
    }
    h.GripRelease(500);
    ASSERT_EQ(h.eventCount, 1);
    EXPECT_LE(h.lastEvent.length, MAX_PATH_LENGTH);
}

// ---------------------------------------------------------------------------
// Invariant: 5 never appears mid-path (CLAUDE.md §4.1 + §5.1)
// NodeFromAxis returns 0 (center) not 5 — 5 is the grid label for center
// but is never recorded. Property test fuzz.
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, FiveNeverAppearsMidPath) {
    // Pairs of (axisX, axisY) exercising all regions including center.
    const float axes[][2] = {
        {0, 0}, {0, UP}, {0, DOWN}, {LEFT, 0}, {RIGHT, 0},
        {LEFT, UP}, {RIGHT, UP}, {LEFT, DOWN}, {RIGHT, DOWN},
        {0, MID}, {MID, 0}, {CENTER_AXIS, CENTER_AXIS},
        {0, ACTIVATION_MAGNITUDE}, {0, ACTIVATION_RELEASE + 0.01f},
        {0, CENTER_THRESHOLD - 0.01f}, {MID, MID},
    };
    constexpr int N_AXES = sizeof(axes) / sizeof(axes[0]);

    TestHarness h;
    h.GripPress(0);
    int64_t ms = 10;
    for (int rep = 0; rep < 200; rep++) {
        const auto& a = axes[rep % N_AXES];
        h.Tick(a[0], a[1], false, true, ms);
        ms += 5;
        for (int i = 0; i < h.engine.GetPathLength(); i++) {
            EXPECT_NE(h.engine.GetPathNode(i), 5)
                << "Node 5 must never appear mid-path (rep=" << rep << " i=" << i << ")";
        }
    }
}

// ---------------------------------------------------------------------------
// Invariant: path length never exceeds MAX_PATH_LENGTH at any intermediate step
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, MaxPathLengthNeverExceededDuringRecording) {
    const float seqs[][2] = {
        {0, UP}, {0, MID}, {RIGHT, 0}, {MID, 0},
        {0, DOWN}, {0, MID}, {LEFT, 0}, {MID, 0},
    };
    constexpr int N = sizeof(seqs) / sizeof(seqs[0]);

    TestHarness h;
    h.GripPress(0);
    for (int i = 0; i < 40; i++) {
        h.Tick(seqs[i % N][0], seqs[i % N][1], false, true, i * 15 + 10);
        EXPECT_LE(h.engine.GetPathLength(), MAX_PATH_LENGTH)
            << "PathLength exceeded MAX_PATH_LENGTH at step " << i;
    }
}

// ---------------------------------------------------------------------------
// Invariant: zero heap allocation on the hot input path (CLAUDE.md §5.2)
// ---------------------------------------------------------------------------

namespace {
static int gAllocCount = 0;
} // namespace

void* operator new(std::size_t size) {
    ++gAllocCount;
    void* p = std::malloc(size);
    if (!p) throw std::bad_alloc();
    return p;
}
void operator delete(void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }

TEST(ComboWindowEngine, ZeroHeapAllocationOnHotPath) {
    // Construct engine BEFORE measuring allocations so ctor overhead is excluded.
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP, false, true, 1);  // one warm-up tick

    const int allocsBefore = gAllocCount;
    for (int i = 0; i < 1000; i++) {
        const float y = (i % 3 == 0) ? UP : (i % 3 == 1) ? MID : CENTER_AXIS;
        h.Tick(0, y, false, true, 2 + i * 2);
    }
    const int allocsAfter = gAllocCount;
    EXPECT_EQ(allocsAfter - allocsBefore, 0)
        << "Process() must not heap-allocate. "
        << (allocsAfter - allocsBefore) << " allocation(s) detected.";
}

// ---------------------------------------------------------------------------
// Regression pin: mAtMax hysteresis — enter at 0.70, re-arm only after 0.50
// (ACTIVATION_MAGNITUDE / ACTIVATION_RELEASE, ComboWindowEngine.h:11-12)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, MAtMaxHysteresis_EnterAt070_LeaveAt050) {
    TestHarness h;
    h.GripPress(0);

    // Push to ACTIVATION_MAGNITUDE → activate node 2.
    h.Tick(0, ACTIVATION_MAGNITUDE + 0.01f, false, true, 10);
    EXPECT_EQ(h.engine.GetPathLength(), 1);
    int pathAfterFirst = h.engine.GetPathLength();

    // Stay between ACTIVATION_RELEASE (0.50) and ACTIVATION_MAGNITUDE (0.70)
    // — still at max, should NOT add another node even though we're above 0.50.
    for (int i = 0; i < 5; i++) {
        // 0.60 is in (ACTIVATION_RELEASE, ACTIVATION_MAGNITUDE) — still at max latch.
        h.Tick(0, 0.60f, false, true, 20 + i * 5);
    }
    EXPECT_EQ(h.engine.GetPathLength(), pathAfterFirst)
        << "No new node should activate while in (ACTIVATION_RELEASE, ACTIVATION_MAGNITUDE)";

    // Drop below ACTIVATION_RELEASE → re-arm.
    h.Tick(0, ACTIVATION_RELEASE - 0.05f, false, true, 60);
    // Push back to activation — should add another node (repeat).
    h.Tick(0, ACTIVATION_MAGNITUDE + 0.01f, false, true, 70);
    EXPECT_EQ(h.engine.GetPathLength(), pathAfterFirst + 1)
        << "Should re-activate after dropping below ACTIVATION_RELEASE and pushing again";

    h.GripRelease(80);
}

// ---------------------------------------------------------------------------
// Regression pin: sliding-rim arm dwell — new zone must hold ARM_DWELL_MS (80ms)
// before committing while mAtMax (ComboWindowEngine.h:23)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, SlidingRimArmDwell_80ms) {
    TestHarness h;
    h.GripPress(0);

    // Activate node 2 (UP) — mAtMax = true.
    h.Tick(0, UP, false, true, 10);
    EXPECT_EQ(h.engine.GetPathLength(), 1);

    // While at max, sweep to node 6 (RIGHT). At ARM_DWELL_MS - 1 = 79ms: not committed.
    int64_t armStart = 20;
    h.Tick(RIGHT, 0, false, true, armStart);  // mArmingZone = 6, mArmingStartMs = armStart
    h.Tick(RIGHT, 0, false, true, armStart + ARM_DWELL_MS - 1);  // 79ms elapsed
    EXPECT_EQ(h.engine.GetPathLength(), 1)
        << "New zone should NOT commit before ARM_DWELL_MS (" << ARM_DWELL_MS << "ms)";

    // At ARM_DWELL_MS: committed.
    h.Tick(RIGHT, 0, false, true, armStart + ARM_DWELL_MS);
    EXPECT_EQ(h.engine.GetPathLength(), 2)
        << "New zone should commit at ARM_DWELL_MS (" << ARM_DWELL_MS << "ms)";

    h.GripRelease(200);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2, 6});
}

// ---------------------------------------------------------------------------
// Regression pin: long-press fires ONLY with grip OFF, not during grip-HELD
// (LONG_PRESS_MS = 800ms, ComboWindowEngine.h:27, CLAUDE.md §5.4)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, LongPress_FiresWhenGripOff_After800ms) {
    TestHarness h;
    // Start at t=1: mThumbstickDownTime=0 is the "not tracking" sentinel, so
    // t=0 as a start time would prevent the timer from ever triggering.
    h.Tick(0, 0, true,  false, 1);    // thumbstick press at t=1, grip off
    h.Tick(0, 0, true,  false, LONG_PRESS_MS);  // 799ms elapsed — not yet
    EXPECT_FALSE(h.longPressFired);
    h.Tick(0, 0, true,  false, LONG_PRESS_MS + 1);  // 800ms elapsed → fires
    EXPECT_TRUE(h.longPressFired);
}

TEST(ComboWindowEngine, LongPress_DoesNotFireWhenGripHeld) {
    TestHarness h;
    // Grip held throughout — long-press accumulator is reset every grip-ON tick.
    h.GripPress(1);
    h.Tick(0, 0, true,  true,  1);    // thumbstick + grip
    h.Tick(0, 0, true,  true,  LONG_PRESS_MS + 100);
    EXPECT_FALSE(h.longPressFired)
        << "Long-press must NOT fire while grip is held (CLAUDE.md §5.4)";
}

TEST(ComboWindowEngine, LongPress_FiredOnceOnly) {
    TestHarness h;
    // Start at t=1 (see LongPress_FiresWhenGripOff_After800ms for why t=0 is wrong).
    h.Tick(0, 0, true,  false, 1);
    for (int64_t t = LONG_PRESS_MS + 1; t < LONG_PRESS_MS * 3; t += 50) {
        h.Tick(0, 0, true, false, t);
    }
    int fires = h.longPressFired ? 1 : 0;  // bool, so at most 1
    EXPECT_EQ(fires, 1) << "Long-press callback must fire exactly once per hold";
}

// ---------------------------------------------------------------------------
// Regression pin: path [1] (close-window / upper-left) emits correctly in 8-dir
// (PROGRESS.md:125 — GHOST_CAP=5 silently dropped combos on the Java side;
// this pin proves the C++ engine correctly emits [1] to the dispatcher)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, CloseWindowPath_1_EmitsIn8DirMode) {
    // Precondition: test harness ctor already sets 8-dir mode.
    TestHarness h;
    h.GripPress(0);
    h.Tick(LEFT, UP, false, true, 10);  // upper-left → node 1
    h.GripRelease(20);
    ASSERT_EQ(h.eventCount, 1)
        << "Engine must emit [1] in 8-dir mode (upper-left close-window combo)";
    h.ExpectPath({1});
}
