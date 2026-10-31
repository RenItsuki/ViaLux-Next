package com.visionguide.phone;

import android.graphics.PointF;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GuidanceEngine — evaluates a merged list of detections each frame and decides
 * what (if anything) to speak. Extends the original 3×3 grid + motion/distance
 * logic with hazard-class support (stairs, curbs, crossings, etc.) and cautious
 * wording for classes that have not been validated on real footage.
 */
final class GuidanceEngine {
    private static final long CLEAR_MS = 4000;

    private final Map<String, TrackMemory> tracks = new HashMap<>();
    private final Map<String, SpokenState> spokenStates = new HashMap<>();
    private long lastClear = 0;
    private long lastSpeechAt = 0;
    private int lastSpeechPriority = 0;

    void reset() {
        tracks.clear();
        spokenStates.clear();
        lastClear = 0;
        lastSpeechAt = 0;
        lastSpeechPriority = 0;
    }

    Guidance evaluate(List<Detection> detections, int width, int height, long now, AppSettings settings) {
        updateTracks(detections, width, height, now, settings);
        Detection candidate = bestDetection(detections, width, height, settings);
        if (candidate == null) {
            expire(now);
            if (now - lastClear >= CLEAR_MS) {
                lastClear = now;
                return maybeSpeak(
                        new Guidance("Path is clear.", false, null, false, Guidance.PRIORITY_INFO),
                        "clear", "clear", now, settings);
            }
            return null;
        }

        String key = trackKey(candidate, width, height);
        TrackMemory memory = tracks.get(key);
        if (memory == null) return null;

        String region   = regionFor(candidate, width);
        String cell     = gridCellFor(candidate, width, height);
        String distance = distanceBand(candidate, width, height);
        boolean moving  = memory.moving;
        boolean emergency = region.equals("center") && distance.equals("very close");
        expire(now);
        int priority = priorityFor(candidate, moving, emergency);
        String message = messageFor(candidate, region, cell, distance, detections, width, height, emergency, moving, settings);
        Guidance guidance = new Guidance(message, emergency, candidate, moving, priority);
        String stateSignature = stateSignature(cell, distance, moving, emergency);
        Guidance spoken = maybeSpeak(guidance, key, stateSignature, now, settings);
        if (spoken != null) memory.lastAnnounced = now;
        return spoken;
    }

    boolean isMoving(Detection detection, int width, int height) {
        TrackMemory memory = tracks.get(trackKey(detection, width, height));
        return memory != null && memory.moving;
    }

    // ── Grid helpers (package-visible for OverlayView) ─────────────────────

    static String gridCellFor(Detection detection, int width, int height) {
        int col = detection.centerX() < width / 3f ? 0 : detection.centerX() > width * 2f / 3f ? 2 : 1;
        int row = detection.centerY() < height / 3f ? 0 : detection.centerY() > height * 2f / 3f ? 2 : 1;
        String[] rows = {"upper", "middle", "lower"};
        String[] cols = {"left", "middle", "right"};
        if (row == 1 && col == 1) return "center";
        return rows[row] + " " + cols[col];
    }

    static String regionFor(Detection detection, int width) {
        if (detection.centerX() < width / 3f) return "left";
        if (detection.centerX() > width * 2f / 3f) return "right";
        return "center";
    }

    // ── Track updates ──────────────────────────────────────────────────────

    private void updateTracks(List<Detection> detections, int width, int height, long now, AppSettings settings) {
        for (Detection detection : detections) {
            if (!isRelevant(detection, width, height, settings)) continue;
            String key = trackKey(detection, width, height);
            TrackMemory old = tracks.get(key);
            PointF center = new PointF(detection.centerX(), detection.centerY());
            boolean rawMotion = old != null && moved(old.center, center, width, height, settings.motionThreshold);
            int streak = old == null ? 0 : rawMotion ? Math.min(5, old.motionStreak + 1) : Math.max(0, old.motionStreak - 1);
            long announced = old == null ? 0 : old.lastAnnounced;
            tracks.put(key, new TrackMemory(center, now, announced, streak, streak >= settings.motionStreakFrames));
        }
    }

    // ── Speech gate ────────────────────────────────────────────────────────

    private Guidance maybeSpeak(Guidance guidance, String objectKey, String stateSignature, long now, AppSettings settings) {
        SpokenState oldState = spokenStates.get(objectKey);
        boolean sameObjectSameState = oldState != null && oldState.signature.equals(stateSignature);
        if (sameObjectSameState && guidance.priority <= oldState.priority && now - oldState.spokenAt < settings.repeatCooldownMs) {
            return null;
        }
        SpokenState oldSceneState = spokenStates.get("scene:" + stateSignature);
        boolean sameSceneSameState = oldSceneState != null
                && guidance.priority <= oldSceneState.priority
                && now - oldSceneState.spokenAt < settings.repeatCooldownMs;
        if (sameSceneSameState) return null;

        boolean priorityJump = settings.priorityOverride && guidance.priority > lastSpeechPriority;
        boolean due = lastSpeechAt == 0 || now - lastSpeechAt >= settings.speechDelayMs;
        boolean urgentDue = guidance.priority == Guidance.PRIORITY_URGENT && now - lastSpeechAt >= 1200;
        if (!due && !priorityJump && !urgentDue) return null;

        lastSpeechAt = now;
        lastSpeechPriority = guidance.priority;
        spokenStates.put(objectKey, new SpokenState(stateSignature, now, guidance.priority));
        spokenStates.put("scene:" + stateSignature, new SpokenState(stateSignature, now, guidance.priority));
        return guidance;
    }

    // ── Candidate selection ────────────────────────────────────────────────

    private Detection bestDetection(List<Detection> detections, int width, int height, AppSettings settings) {
        Detection best = null;
        for (Detection detection : detections) {
            if (!isRelevant(detection, width, height, settings)) continue;
            boolean tooFar = detection.areaRatio(width, height) < settings.notifyAreaRatio;
            boolean important = isImportantLabel(detection.label) || isHazardLabel(detection.label) || isMoving(detection, width, height);
            if (tooFar && !important) continue;
            if (best == null || score(detection, width, height) > score(best, width, height)) best = detection;
        }
        return best;
    }

    private static boolean isRelevant(Detection detection, int width, int height, AppSettings settings) {
        String label = detection.label.toLowerCase(Locale.US);
        return isImportantLabel(label)
                || isHazardLabel(label)
                || label.equals("chair")
                || label.equals("dining table")
                || detection.areaRatio(width, height) >= Math.min(0.02f, settings.notifyAreaRatio);
    }

    // ── Scoring ────────────────────────────────────────────────────────────

    private float score(Detection detection, int width, int height) {
        String label = detection.label.toLowerCase(Locale.US);
        float priority;
        if (label.equals("person") || label.equals("pedestrian"))          priority = 1000;
        else if (label.equals("car") || label.equals("bus")
                || label.equals("truck") || label.equals("train"))         priority = 900;
        else if (label.equals("bicycle") || label.equals("motorcycle"))    priority = 800;
        else if (isHazardLabel(label))                                      priority = 750;
        else                                                                priority = 500;
        if (isMoving(detection, width, height)) priority += 1200;
        return priority + detection.areaRatio(width, height) * 100f + detection.confidence;
    }

    // ── Priority ───────────────────────────────────────────────────────────

    private static int priorityFor(Detection detection, boolean moving, boolean emergency) {
        if (emergency || moving) return Guidance.PRIORITY_URGENT;
        if (isImportantLabel(detection.label) || isHazardLabel(detection.label)) return Guidance.PRIORITY_IMPORTANT;
        return Guidance.PRIORITY_INFO;
    }

    // ── Label classification ───────────────────────────────────────────────

    /** General-purpose important objects (people, vehicles). */
    static boolean isImportantLabel(String rawLabel) {
        String label = rawLabel.toLowerCase(Locale.US);
        return label.equals("person")
                || label.equals("pedestrian")
                || label.equals("car")
                || label.equals("bus")
                || label.equals("truck")
                || label.equals("train")
                || label.equals("bicycle")
                || label.equals("motorcycle");
    }

    /**
     * Accessibility hazard classes from BlindAid or a similar model.
     * These use cautious wording ("Possible ...") until validated on real footage.
     */
    static boolean isHazardLabel(String rawLabel) {
        String label = rawLabel.toLowerCase(Locale.US);
        return label.equals("stairs")
                || label.equals("stair")
                || label.equals("up stairs")
                || label.equals("down stairs")
                || label.equals("escalator")
                || label.equals("elevator")
                || label.equals("curb")
                || label.equals("pole")
                || label.equals("bench")
                || label.equals("zebra crossing")
                || label.equals("crosswalk")
                || label.equals("speed bump");
    }

    // ── Distance band ──────────────────────────────────────────────────────

    static String distanceBand(Detection detection, int width, int height) {
        float ratio = detection.areaRatio(width, height);
        if (ratio >= 0.25f) return "very close";
        if (ratio >= 0.10f) return "close";
        if (ratio >= 0.035f) return "medium distance";
        return "far";
    }

    // ── Message generation ─────────────────────────────────────────────────

    private static String messageFor(
            Detection detection, String region, String cell, String distance,
            List<Detection> detections, int width, int height,
            boolean emergency, boolean moving, AppSettings settings) {

        String direction = suggestDirection(detection, detections, width, height, distance, settings);
        String label = detection.label.toLowerCase(Locale.US);

        // Emergency override
        if (emergency) {
            return "Stop! " + label + " directly ahead. " + direction;
        }

        // Hazard classes use cautious wording
        if (isHazardLabel(label)) {
            String pos = region.equals("center") ? "ahead" : "to your " + region;
            String cautious = "Possible " + label + " " + pos + ", " + distance + ".";
            return appendDirection(cautious, direction);
        }

        // Moving object
        if (moving) {
            String position = region.equals("center") ? "ahead" : "to your " + region;
            return appendDirection("Moving " + label + " " + position + ", in the " + cell + ".", direction);
        }

        return appendDirection(label + " detected in the " + cell + ", " + distance + ".", direction);
    }

    private static String appendDirection(String base, String direction) {
        return direction.isEmpty() ? base : base + " " + direction;
    }

    // ── Direction suggestion ───────────────────────────────────────────────

    private static String suggestDirection(Detection candidate, List<Detection> detections,
                                           int width, int height, String distance, AppSettings settings) {
        if (distance.equals("far")) return "";
        if (isVerySide(candidate, width, settings.edgeIgnoreRatio)) return "";
        String region = regionFor(candidate, width);
        boolean leftBlocked  = blocked(detections, candidate, "left",  width, height);
        boolean rightBlocked = blocked(detections, candidate, "right", width, height);
        if (region.equals("left"))  return "Move right.";
        if (region.equals("right")) return "Move left.";
        if (leftBlocked && rightBlocked) return "Stop; both sides are blocked.";
        if (leftBlocked)  return "Move right.";
        if (rightBlocked) return "Move left.";
        return "Move slightly right.";
    }

    private static boolean isVerySide(Detection detection, int width, float edgeIgnoreRatio) {
        return detection.centerX() < width * edgeIgnoreRatio
                || detection.centerX() > width * (1f - edgeIgnoreRatio);
    }

    private static boolean blocked(List<Detection> detections, Detection candidate, String region, int width, int height) {
        for (Detection detection : detections) {
            if (detection == candidate) continue;
            if (regionFor(detection, width).equals(region) && detection.areaRatio(width, height) >= 0.06f) return true;
        }
        return false;
    }

    private static boolean moved(PointF oldCenter, PointF newCenter, int width, int height, float threshold) {
        float dx = (newCenter.x - oldCenter.x) / Math.max(1f, width);
        float dy = (newCenter.y - oldCenter.y) / Math.max(1f, height);
        return Math.hypot(dx, dy) >= threshold;
    }

    private static String trackKey(Detection detection, int width, int height) {
        return detection.label.toLowerCase(Locale.US) + ":" + gridCellFor(detection, width, height);
    }

    private static String stateSignature(String cell, String distance, boolean moving, boolean emergency) {
        return cell + "|" + distance + "|" + moving + "|" + emergency;
    }

    private void expire(long now) {
        tracks.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > 2000);
        spokenStates.entrySet().removeIf(entry -> now - entry.getValue().spokenAt > 30000);
    }

    // ── Inner classes ──────────────────────────────────────────────────────

    private static final class SpokenState {
        final String signature;
        final long spokenAt;
        final int priority;
        SpokenState(String signature, long spokenAt, int priority) {
            this.signature = signature; this.spokenAt = spokenAt; this.priority = priority;
        }
    }

    private static final class TrackMemory {
        final PointF center;
        final long lastSeen;
        long lastAnnounced;
        final int motionStreak;
        final boolean moving;
        TrackMemory(PointF center, long lastSeen, long lastAnnounced, int motionStreak, boolean moving) {
            this.center = center; this.lastSeen = lastSeen; this.lastAnnounced = lastAnnounced;
            this.motionStreak = motionStreak; this.moving = moving;
        }
    }
}
