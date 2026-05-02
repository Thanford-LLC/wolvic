#include <gtest/gtest.h>
#include "ComboWindowEngine.h"

using namespace glyphew;

// ---------------------------------------------------------------------------
// Test harness helpers
// ---------------------------------------------------------------------------

struct TestHarness {
    ComboEvent lastEvent{};
    int        eventCount = 0;

    ComboWindowEngine engine{[this](const ComboEvent& e) {
        lastEvent   = e;
        eventCount++;
    }};

    // Shorthand: single frame call.
    bool Tick(float x, float y, bool thumb, bool grip, int64_t ms) {
        return engine.Process(x, y, thumb, grip, ms);
    }

    // Convenience: grip-press frame, no axis.
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

// Axis shortcuts — Y positive = up (node 2).
static constexpr float UP    =  0.8f;
static constexpr float DOWN  = -0.8f;
static constexpr float RIGHT =  0.8f;
static constexpr float LEFT  = -0.8f;
static constexpr float MID   =  0.3f;  // sub-threshold between-nodes zone
static constexpr float CENTER_AXIS = 0.05f; // inside release zone

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
    // Exactly at FLICK_THRESHOLD — should NOT activate (must be strictly greater).
    h.Tick(0, FLICK_THRESHOLD, false, true, 10);
    EXPECT_EQ(h.engine.GetPathLength(), 0);
    // Just above threshold — should activate.
    h.Tick(0, FLICK_THRESHOLD + 0.01f, false, true, 20);
    EXPECT_EQ(h.engine.GetPathLength(), 1);
}

// ---------------------------------------------------------------------------
// Repeat node (e.g. scroll-to-top: 5→2→2)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, RepeatNodeDoubleUp) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,       false, true, 10);   // NODE_ACTIVE(2)
    h.Tick(0, MID,      false, true, 20);   // BETWEEN_NODES
    h.Tick(0, UP,       false, true, 30);   // NODE_ACTIVE(2) again
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
    h.Tick(MID,   MID,  false, true, 20);   // BETWEEN_NODES
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
    h.Tick(0, CENTER_AXIS, false, true, 20);    // CENTER_DWELL starts (t=20)
    // Move to down before DWELL_MS (150ms) elapses — passthrough
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
    h.Tick(CENTER_AXIS, 0, false, true, 20);    // CENTER_DWELL starts
    h.Tick(RIGHT,       0, false, true, 30);    // passthrough — path [4,6]
    h.GripRelease(40);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({4, 6});
}

// ---------------------------------------------------------------------------
// CENTER_DWELL fires after holding at center
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, DwellFiresOnHold) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,          false, true, 10);     // NODE_ACTIVE(2)
    h.Tick(0, CENTER_AXIS, false, true, 20);     // CENTER_DWELL t=20
    // Hold below RELEASE_THRESHOLD for DWELL_MS (150ms) — should emit at t=170
    h.Tick(0, CENTER_AXIS, false, true, 170);    // 150ms elapsed → emit [2]
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2});
}

TEST(ComboWindowEngine, DwellNotFiredBeforeTimeout) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,          false, true, 10);
    h.Tick(0, CENTER_AXIS, false, true, 20);
    h.Tick(0, CENTER_AXIS, false, true, 169);   // 149ms — not yet
    EXPECT_EQ(h.eventCount, 0);
}

TEST(ComboWindowEngine, DwellInterruptedContinuesToNewNode) {
    // Axis returns to center then flicks up again before dwell completes → path [2,2]
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,          false, true, 10);    // NODE_ACTIVE(2)
    h.Tick(0, CENTER_AXIS, false, true, 20);    // CENTER_DWELL t=20
    h.Tick(0, UP,          false, true, 50);    // 30ms < DWELL_MS → passthrough to NODE_ACTIVE(2)
    h.GripRelease(60);
    ASSERT_EQ(h.eventCount, 1);
    h.ExpectPath({2, 2});
}

// ---------------------------------------------------------------------------
// Cancel (thumbstick button)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, CancelFromIdle) {
    TestHarness h;
    // Pressing thumbstick in IDLE is a no-op (toggle HUD — not this engine's concern).
    h.Tick(0, 0, true, false, 10);
    EXPECT_EQ(h.eventCount, 0);
}

TEST(ComboWindowEngine, CancelFromNodeActive) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,  false, true,  10);    // NODE_ACTIVE(2)
    h.Tick(0, UP,  true,  true,  20);    // thumbstick press → cancel
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
    h.Tick(0, MID, false, true, 20);    // BETWEEN_NODES
    h.Tick(0, MID, true,  true, 30);    // thumbstick → cancel
    EXPECT_EQ(h.eventCount, 0);
}

// ---------------------------------------------------------------------------
// BETWEEN_NODES timeout
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, BetweenNodesTimeout) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP,  false, true, 0);     // NODE_ACTIVE(2)
    h.Tick(0, MID, false, true, 10);    // BETWEEN_NODES at t=10
    // Jump past timeout
    h.Tick(0, MID, false, true, 2011);  // 2001ms > BETWEEN_NODES_TIMEOUT_MS
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
    h.Tick(MID,   MID,false, true, 20);    // between
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
// Combos disabled passthrough
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, CombosDisabledWhenNoGrip) {
    // Without grip, axis events must NOT be consumed regardless of axis value.
    TestHarness h;
    for (int t = 0; t < 10; t++) {
        bool consumed = h.Tick(0, UP, false, false, t * 10);
        EXPECT_FALSE(consumed) << "frame " << t;
    }
    EXPECT_EQ(h.eventCount, 0);
}

// ---------------------------------------------------------------------------
// Silent cancel (OS overlay)
// ---------------------------------------------------------------------------

TEST(ComboWindowEngine, SilentCancelDiscardsPath) {
    TestHarness h;
    h.GripPress(0);
    h.Tick(0, UP, false, true, 10);
    h.engine.CancelSilent();
    EXPECT_EQ(h.eventCount, 0);
    EXPECT_EQ(h.engine.GetPathLength(), 0);
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
