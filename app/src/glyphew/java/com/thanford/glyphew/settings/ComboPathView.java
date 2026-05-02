/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.igalia.wolvic.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only path disc for combo action blocks in Combos Settings.
 *
 * <p>Draws a circular ring (the joystick sweep area) and overlays:
 * <ul>
 *   <li>Path arcs from node to node along the ring (shorter-arc direction).
 *       Diametrically opposite nodes (e.g. 2-8, 4-6) draw a straight diameter
 *       line through the center instead.</li>
 *   <li>Start node: filled yellow dot; subsequent nodes: hollow ring.</li>
 *   <li>Multi-hit: 240° loop arc centered ON the node position + filled arrowhead
 *       at the arc end + "N×" badge just inside the ring.</li>
 *   <li>Serpentine ABABABAB… paths (length ≥ 4): converging quadratic bezier
 *       curves, opacity 0.14 (oldest) → 1.0 (newest).</li>
 *   <li>Unbound (null path): dim ring only.</li>
 * </ul>
 *
 * <p>All drawing is clipped to the disc boundary.
 *
 * <p>Node → angle (Android canvas, 0° = right, clockwise):
 * <pre>
 *   1(225°) 2(270°) 3(315°)
 *   4(180°)   5     6(  0°)
 *   7(135°) 8( 90°) 9( 45°)
 * </pre>
 * Push up = node 2 = top of disc; push right = node 6 = right of disc.
 */
public final class ComboPathView extends View {

    private static final float[] NODE_ANGLE_DEG = new float[10];
    static {
        NODE_ANGLE_DEG[2] = 270f; // up
        NODE_ANGLE_DEG[4] = 180f; // left
        NODE_ANGLE_DEG[6] =   0f; // right
        NODE_ANGLE_DEG[8] =  90f; // down
        NODE_ANGLE_DEG[1] = 225f; // up-left    (engine: node 1 = upper-left,  physical ↖)
        NODE_ANGLE_DEG[3] = 315f; // up-right   (engine: node 3 = upper-right, physical ↗)
        NODE_ANGLE_DEG[7] = 135f; // down-left  (engine: node 7 = lower-left,  physical ↙)
        NODE_ANGLE_DEG[9] =  45f; // down-right (engine: node 9 = lower-right, physical ↘)
    }

    private @Nullable int[] mPath;
    private int     mAccentColor;
    private boolean mIs8DirMode;

    private final Paint mBgPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mNodeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArcPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHollowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLoopPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBadgePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ComboPathView(Context context) {
        super(context);
        init(context);
    }

    public ComboPathView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context ctx) {
        int accent = ContextCompat.getColor(ctx, R.color.fd_accent);
        mAccentColor = accent;

        mBgPaint.setStyle(Paint.Style.FILL);
        mBgPaint.setColor(0xFF090d38);

        mNodeBgPaint.setStyle(Paint.Style.FILL);

        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setColor(0xFF1c2265); // thin dark guide ring, NOT accent

        mArcPaint.setStyle(Paint.Style.STROKE);
        mArcPaint.setColor(accent);
        mArcPaint.setStrokeCap(Paint.Cap.ROUND);

        mFillPaint.setStyle(Paint.Style.FILL);
        mFillPaint.setColor(accent);

        mHollowPaint.setStyle(Paint.Style.STROKE);
        mHollowPaint.setColor(accent);

        mLoopPaint.setStyle(Paint.Style.STROKE);
        mLoopPaint.setColor(accent);
        mLoopPaint.setStrokeCap(Paint.Cap.ROUND);

        mBadgePaint.setStyle(Paint.Style.FILL);
        mBadgePaint.setColor(accent);
        mBadgePaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setPath(@Nullable int[] path) {
        mPath = (path != null && path.length > 0) ? path : null;
        invalidate();
    }

    public void set8DirMode(boolean is8Dir) {
        mIs8DirMode = is8Dir;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int s = (w > 0 && h > 0)
                ? Math.min(w, h)
                : (int) (72f * getResources().getDisplayMetrics().density);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size  = (float) Math.min(getWidth(), getHeight());
        float cx    = size * 0.5f;
        float cy    = size * 0.5f;
        float ringR = size * 0.36f;

        float arcStroke  = Math.max(1.5f, size * 0.025f);
        float nodeR      = size * 0.045f;
        float nodeStroke = Math.max(1f,   size * 0.018f);
        float loopR      = size * 0.10f;
        float loopStroke = Math.max(1.5f, size * 0.022f);
        float badgeSp    = size * 0.11f;

        // Clip to disc boundary.
        int saveCount = canvas.save();
        Path clip = new Path();
        clip.addCircle(cx, cy, size * 0.5f - 1f, Path.Direction.CW);
        canvas.clipPath(clip);

        // Dark disc background fill.
        canvas.drawCircle(cx, cy, ringR, mBgPaint);
        // Thin dark guide ring border.
        mRingPaint.setStrokeWidth(Math.max(1f, size * 0.012f));
        mRingPaint.setAlpha(255);
        canvas.drawCircle(cx, cy, ringR, mRingPaint);

        if (mPath == null) {
            // Unbound: dim node dots (8 in 8-dir, 4 cardinals in 4-dir) + em-dash.
            mNodeBgPaint.setColor(0xFF10134a);
            float ubR = Math.max(1f, size * 0.021f);
            int[] ubNodes = mIs8DirMode
                    ? new int[]{1, 2, 3, 4, 6, 7, 8, 9}
                    : new int[]{2, 4, 6, 8};
            for (int n : ubNodes) {
                PointF p = nodePoint(n, cx, cy, ringR);
                canvas.drawCircle(p.x, p.y, ubR, mNodeBgPaint);
            }
            mBadgePaint.setColor(0xFF1e2260);
            mBadgePaint.setTextSize(size * 0.21f);
            mBadgePaint.setAlpha(255);
            canvas.drawText("—", cx, cy + size * 0.08f, mBadgePaint);
            mBadgePaint.setColor(mAccentColor); // restore
            canvas.restoreToCount(saveCount);
            return;
        }

        // Bound path: background node dots.
        // 8-dir: all 8 equal size. 4-dir: cardinals prominent, diagonals very dim.
        if (mIs8DirMode) {
            float bgR = Math.max(1f, size * 0.022f);
            mNodeBgPaint.setColor(0xFF252c75);
            for (int n : new int[]{1, 2, 3, 4, 6, 7, 8, 9}) {
                PointF p = nodePoint(n, cx, cy, ringR);
                canvas.drawCircle(p.x, p.y, bgR, mNodeBgPaint);
            }
        } else {
            float bgR_cardinal = Math.max(1f, size * 0.025f);
            float bgR_diag     = Math.max(1f, size * 0.015f);
            mNodeBgPaint.setColor(0xFF252c75);
            for (int n : new int[]{2, 4, 6, 8}) {
                PointF p = nodePoint(n, cx, cy, ringR);
                canvas.drawCircle(p.x, p.y, bgR_cardinal, mNodeBgPaint);
            }
            mNodeBgPaint.setColor(0xFF161a52);
            for (int n : new int[]{1, 3, 7, 9}) {
                PointF p = nodePoint(n, cx, cy, ringR);
                canvas.drawCircle(p.x, p.y, bgR_diag, mNodeBgPaint);
            }
        }

        RectF ringRect = new RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
        int[]   multiHit   = buildMultiHitCounts(mPath);
        boolean serpentine = isSerpentinePattern(mPath);
        int[]   deduped    = deduplicateConsecutive(mPath);

        // --- Arc segments ---
        mArcPaint.setStrokeWidth(arcStroke);
        if (serpentine) {
            drawSerpentineArcs(canvas, mPath, cx, cy, ringR, arcStroke);
        } else {
            mArcPaint.setAlpha(255);
            for (int i = 0; i + 1 < deduped.length; i++) {
                drawPathArc(canvas, deduped[i], deduped[i + 1],
                            cx, cy, ringR, ringRect, mArcPaint);
            }
        }

        // --- Multi-hit loop arcs + arrowheads + badges ---
        mLoopPaint.setStrokeWidth(loopStroke);
        mLoopPaint.setAlpha(255);
        for (int n = 1; n <= 9; n++) {
            if (n == 5 || multiHit[n] < 2) continue;
            drawLoopArc(canvas, n, cx, cy, ringR, loopR);
            drawBadge(canvas, n, cx, cy, ringR, loopR, badgeSp, multiHit[n]);
        }

        // --- Node dots (start = filled, others = hollow) ---
        mFillPaint.setAlpha(255);
        mHollowPaint.setStrokeWidth(nodeStroke);
        mHollowPaint.setAlpha(255);
        boolean isFirst = true;
        boolean[] drawn = new boolean[10];
        for (int n : deduped) {
            if (!validNode(n) || drawn[n]) continue;
            drawn[n] = true;
            PointF p = nodePoint(n, cx, cy, ringR);
            canvas.drawCircle(p.x, p.y, nodeR, isFirst ? mFillPaint : mHollowPaint);
            isFirst = false;
        }

        canvas.restoreToCount(saveCount);
    }

    // ── Drawing ──────────────────────────────────────────────────────────────────

    private void drawPathArc(Canvas canvas, int from, int to,
                             float cx, float cy, float ringR,
                             RectF ring, Paint paint) {
        if (!validNode(from) || !validNode(to)) return;
        float start = NODE_ANGLE_DEG[from];
        float diff  = ((NODE_ANGLE_DEG[to] - start) % 360f + 360f) % 360f;
        float sw    = paint.getStrokeWidth();

        if (Math.abs(diff - 180f) < 0.5f) {
            // Diametrically opposite nodes (2-8, 4-6, 1-9, 3-7):
            // draw a straight diameter line through the center.
            PointF p1 = nodePoint(from, cx, cy, ringR);
            PointF p2 = nodePoint(to,   cx, cy, ringR);
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint);
            float dx = p2.x - p1.x, dy = p2.y - p1.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len > 0) drawMidArrow(canvas,
                    p1.x + dx * 0.618f, p1.y + dy * 0.618f,
                    dx / len, dy / len, sw, 255);
            return;
        }

        float sweep = diff <= 180f ? diff : diff - 360f; // shorter arc
        canvas.drawArc(ring, start, sweep, false, paint);
        // Arrowhead at 0.618 along the arc, tangent to direction of travel.
        float midRad = (float) Math.toRadians(start + sweep * 0.618f);
        float tipX = cx + ringR * (float) Math.cos(midRad);
        float tipY = cy + ringR * (float) Math.sin(midRad);
        // CW (sweep≥0): tangent = (-sinθ, cosθ); CCW: (sinθ, -cosθ).
        float tanX = (sweep >= 0) ? -(float) Math.sin(midRad) :  (float) Math.sin(midRad);
        float tanY = (sweep >= 0) ?  (float) Math.cos(midRad) : -(float) Math.cos(midRad);
        drawMidArrow(canvas, tipX, tipY, tanX, tanY, sw, 255);
    }

    private void drawLoopArc(Canvas canvas, int node,
                             float cx, float cy, float ringR, float loopR) {
        float nodeRad = (float) Math.toRadians(NODE_ANGLE_DEG[node]);
        // Loop circle centered ON the node position (on the ring).
        float lcx = cx + ringR * (float) Math.cos(nodeRad);
        float lcy = cy + ringR * (float) Math.sin(nodeRad);

        // Gap faces the disc center (inward); arc sweeps 240° outward.
        float inward   = (NODE_ANGLE_DEG[node] + 180f) % 360f;
        float arcStart = (inward + 60f) % 360f;
        float arcEnd   = (arcStart + 240f) % 360f;

        RectF rect = new RectF(lcx - loopR, lcy - loopR, lcx + loopR, lcy + loopR);
        canvas.drawArc(rect, arcStart, 240f, false, mLoopPaint);

        // Arrowhead at arc end, pointing in the direction of CW travel.
        float arcEndRad = (float) Math.toRadians(arcEnd);
        float tipX = lcx + loopR * (float) Math.cos(arcEndRad);
        float tipY = lcy + loopR * (float) Math.sin(arcEndRad);
        // CW tangent at arcEndRad: (-sin, cos) in screen-coord (y-down) space.
        float tanX = -(float) Math.sin(arcEndRad);
        float tanY =  (float) Math.cos(arcEndRad);
        // Radial (outward) at arcEndRad.
        float radX = (float) Math.cos(arcEndRad);
        float radY = (float) Math.sin(arcEndRad);
        float arrowLen  = loopR * 0.55f;
        float arrowHalf = loopR * 0.32f;
        float bx = tipX - tanX * arrowLen;
        float by = tipY - tanY * arrowLen;

        Path arrow = new Path();
        arrow.moveTo(tipX, tipY);
        arrow.lineTo(bx + radX * arrowHalf, by + radY * arrowHalf);
        arrow.lineTo(bx - radX * arrowHalf, by - radY * arrowHalf);
        arrow.close();
        mFillPaint.setAlpha(255);
        canvas.drawPath(arrow, mFillPaint);
    }

    private void drawBadge(Canvas canvas, int node,
                           float cx, float cy, float ringR, float loopR,
                           float sp, int count) {
        float nodeRad = (float) Math.toRadians(NODE_ANGLE_DEG[node]);
        float nodeX = cx + ringR * (float) Math.cos(nodeRad);
        float nodeY = cy + ringR * (float) Math.sin(nodeRad);
        // The loop arc has a 120° gap facing the disc center (inward direction).
        // Place the badge in that gap — always outside the arc (1.2× loopR from
        // the node) and never occluded by the arc stroke.
        float inwardRad = (float) Math.toRadians((NODE_ANGLE_DEG[node] + 180f) % 360f);
        float bx = nodeX + (float) Math.cos(inwardRad) * loopR * 1.2f;
        float by = nodeY + (float) Math.sin(inwardRad) * loopR * 1.2f;
        mBadgePaint.setColor(mAccentColor);
        mBadgePaint.setTextSize(sp);
        mBadgePaint.setAlpha(255);
        canvas.drawText(count + "×", bx, by + sp * 0.38f, mBadgePaint);
    }

    private void drawSerpentineArcs(Canvas canvas, int[] path,
                                    float cx, float cy, float ringR, float strokeW) {
        PointF pa = nodePoint(path[0], cx, cy, ringR);
        PointF pb = nodePoint(path[1], cx, cy, ringR);
        float mx = (pa.x + pb.x) * 0.5f;
        float my = (pa.y + pb.y) * 0.5f;
        float dx = pb.x - pa.x, dy = pb.y - pa.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float px = (len > 0f) ? -dy / len : 0f;
        float py = (len > 0f) ?  dx / len : 1f;

        int nArcs = path.length - 1;
        for (int i = 0; i < nArcs; i++) {
            float t      = nArcs > 1 ? (float) i / (nArcs - 1) : 1f;
            float alpha  = 0.14f + 0.86f * t;
            float offset = ringR * (0.55f - 0.40f * t);
            int   side   = (i % 2 == 0) ? 1 : -1;

            PointF pf = nodePoint(path[i],     cx, cy, ringR);
            PointF pt = nodePoint(path[i + 1], cx, cy, ringR);
            float cpx = mx + px * offset * side;
            float cpy = my + py * offset * side;

            Path bezier = new Path();
            bezier.moveTo(pf.x, pf.y);
            bezier.quadTo(cpx, cpy, pt.x, pt.y);

            mArcPaint.setAlpha((int) (255 * alpha));
            mArcPaint.setStrokeWidth(strokeW);
            canvas.drawPath(bezier, mArcPaint);

            // Arrow at t=0.618 on the quadratic bezier.
            // Point: (1-t)²·P0 + 2t(1-t)·CP + t²·P2
            // Tangent: 2(1-t)·(CP-P0) + 2t·(P2-CP)
            final float T = 0.618f, TC = 1f - T; // TC = 0.382
            float midX = TC*TC*pf.x + 2*T*TC*cpx + T*T*pt.x;
            float midY = TC*TC*pf.y + 2*T*TC*cpy + T*T*pt.y;
            float tdx = 2*TC*(cpx - pf.x) + 2*T*(pt.x - cpx);
            float tdy = 2*TC*(cpy - pf.y) + 2*T*(pt.y - cpy);
            float tlen = (float) Math.sqrt(tdx * tdx + tdy * tdy);
            if (tlen > 0) drawMidArrow(canvas, midX, midY,
                    tdx / tlen, tdy / tlen, strokeW, (int) (255 * alpha));
        }
        mArcPaint.setAlpha(255);
    }

    /**
     * Draws a right-half arrowhead (tip → right-base → back-center) at (tipX, tipY)
     * pointing in (tanX, tanY). Right-half keeps adjacent reverse-direction arrows
     * (e.g. 6→8 vs 8→6) visually distinct.
     */
    private void drawMidArrow(Canvas canvas, float tipX, float tipY,
                              float tanX, float tanY, float strokeW, int alpha) {
        float arrowLen  = strokeW * 4.2f;
        float arrowHalf = strokeW * 2.2f;
        // Right perpendicular of the travel direction.
        float perpX = -tanY;
        float perpY =  tanX;
        float backX = tipX - tanX * arrowLen;
        float backY = tipY - tanY * arrowLen;
        Path arrow = new Path();
        arrow.moveTo(tipX, tipY);
        arrow.lineTo(backX + perpX * arrowHalf, backY + perpY * arrowHalf);
        arrow.lineTo(backX, backY);
        arrow.close();
        mFillPaint.setAlpha(alpha);
        canvas.drawPath(arrow, mFillPaint);
        mFillPaint.setAlpha(255);
    }

    // ── Analysis ─────────────────────────────────────────────────────────────────

    private static int[] buildMultiHitCounts(int[] path) {
        int[] counts = new int[10];
        int i = 0;
        while (i < path.length) {
            int n = path[i];
            if (n >= 1 && n <= 9) {
                int j = i + 1;
                while (j < path.length && path[j] == n) j++;
                if (j - i > counts[n]) counts[n] = j - i;
                i = j;
            } else {
                i++;
            }
        }
        return counts;
    }

    private static boolean isSerpentinePattern(int[] path) {
        if (path.length < 4) return false;
        int a = path[0], b = path[1];
        if (a == b) return false;
        for (int i = 2; i < path.length; i++) {
            if (path[i] != (i % 2 == 0 ? a : b)) return false;
        }
        return true;
    }

    @NonNull
    private static int[] deduplicateConsecutive(int[] path) {
        if (path.length == 0) return path;
        List<Integer> out = new ArrayList<>();
        out.add(path[0]);
        for (int i = 1; i < path.length; i++) {
            if (path[i] != path[i - 1]) out.add(path[i]);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    private static PointF nodePoint(int node, float cx, float cy, float r) {
        float rad = (float) Math.toRadians(NODE_ANGLE_DEG[node]);
        return new PointF(cx + r * (float) Math.cos(rad),
                          cy + r * (float) Math.sin(rad));
    }

    private static boolean validNode(int n) {
        return n >= 1 && n <= 9 && n != 5;
    }
}
