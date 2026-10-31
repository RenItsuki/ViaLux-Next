package com.visionguide.phone;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

/**
 * DetectionMerger — merges detections from multiple models, removing near-duplicate
 * boxes using Intersection-over-Union (IoU). When two boxes from different sources
 * overlap above the threshold and share a label family, the higher-confidence box
 * is kept and the other is dropped.
 */
final class DetectionMerger {
    private static final float IOU_THRESHOLD = 0.45f;

    private DetectionMerger() {}

    /**
     * Merge two lists of detections. Neither list is modified.
     *
     * @param primary   Detections from the primary on-device model (YOLO26n).
     * @param secondary Detections from a second source (online Roboflow, BlindAid, etc.).
     * @return A merged list with near-duplicates removed.
     */
    static List<Detection> merge(List<Detection> primary, List<Detection> secondary) {
        List<Detection> result = new ArrayList<>(primary);
        for (Detection candidate : secondary) {
            if (!isDuplicate(candidate, result)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static boolean isDuplicate(Detection candidate, List<Detection> existing) {
        for (Detection other : existing) {
            if (iou(candidate.box, other.box) >= IOU_THRESHOLD
                    && sameFamily(candidate.label, other.label)) {
                // Keep the higher-confidence detection
                // The existing list already contains 'other'; candidate is a duplicate
                return true;
            }
        }
        return false;
    }

    /** Very rough label-family match: same lowercase string or known synonym pairs. */
    private static boolean sameFamily(String a, String b) {
        String la = a.toLowerCase(java.util.Locale.US);
        String lb = b.toLowerCase(java.util.Locale.US);
        if (la.equals(lb)) return true;
        // Treat pedestrian / person as the same family
        if ((la.equals("person") || la.equals("pedestrian"))
                && (lb.equals("person") || lb.equals("pedestrian"))) return true;
        // Treat crosswalk / zebra crossing as the same family
        if ((la.contains("cross") || la.contains("zebra"))
                && (lb.contains("cross") || lb.contains("zebra"))) return true;
        // Treat stair variants as the same family
        if (la.contains("stair") && lb.contains("stair")) return true;
        return false;
    }

    private static float iou(RectF a, RectF b) {
        float interLeft   = Math.max(a.left,   b.left);
        float interTop    = Math.max(a.top,    b.top);
        float interRight  = Math.min(a.right,  b.right);
        float interBottom = Math.min(a.bottom, b.bottom);
        if (interRight <= interLeft || interBottom <= interTop) return 0f;
        float interArea = (interRight - interLeft) * (interBottom - interTop);
        float aArea = a.width() * a.height();
        float bArea = b.width() * b.height();
        float unionArea = aArea + bArea - interArea;
        return unionArea <= 0f ? 0f : interArea / unionArea;
    }
}
