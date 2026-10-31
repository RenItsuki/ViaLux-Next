package com.visionguide.phone;

import android.graphics.PointF;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GuidanceEngine — Evaluates merged detections and paces voice announcements.
 *
 * Designed for calm, clear accessibility:
 *   - Spatial Hysteresis: Anchored by object label, rough region (left/center/right),
 *     and distance band, preventing jitter from hand tremor.
 *   - Walking Filter: High-threshold motion tracking prevents walking ego-motion
 *     from falsely tagging every object as "moving".
 *   - Calm Speech Pacing: Restricts urgent interrupts to genuine collision hazards,
 *     ensuring sentences are spoken clearly and completely without constant interruptions.
 */
final class GuidanceEngine {
    private static final long CLEAR_MS = 15000; // 15 seconds between "path is clear" messages

    private final Map<String, TrackMemory> tracks = new HashMap<>();
    private final Map<String, SpokenState> spokenStates = new HashMap<>();
    private final InstanceTracker instanceTracker = new InstanceTracker();
    private long lastClear = 0;
    private long lastSpeechAt = 0;
    private int lastSpeechPriority = 0;

    void reset() {
        tracks.clear();
        spokenStates.clear();
        instanceTracker.reset();
        lastClear = 0;
        lastSpeechAt = 0;
        lastSpeechPriority = 0;
    }

    InstanceTracker getInstanceTracker() {
        return instanceTracker;
    }

    Guidance evaluate(List<Detection> detections, int width, int height, long now, AppSettings settings) {
        return evaluate(detections, width, height, now, settings, null);
    }

    Guidance evaluate(List<Detection> detections, int width, int height, long now, AppSettings settings, DualCameraDepthEstimator depthEstimator) {
        instanceTracker.update(detections, depthEstimator, width, height, now);

        // 1. Check for multi-obstacle conflict (e.g. car on right, hole/obstacle on left)
        String conflictMsg = instanceTracker.evaluateMultiObstacleConflict(detections, depthEstimator, width, height, now);
        if (conflictMsg != null) {
            return maybeSpeak(new Guidance(conflictMsg, true, null, false, Guidance.PRIORITY_URGENT),
                    "conflict", "conflict", now, settings);
        }

        // 2. Check if a tracked instance has exited the frame (delta-only update)
        String exitMsg = instanceTracker.checkExitedInstances(now);
        if (exitMsg != null) {
            return maybeSpeak(new Guidance(exitMsg, false, null, false, Guidance.PRIORITY_INFO),
                    "exit:" + exitMsg, "exit", now, settings);
        }

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
        String distance;
        if (depthEstimator != null) {
            float m = depthEstimator.estimateDistanceMeters(candidate, width, height);
            distance = depthEstimator.formatDistanceSpoken(m);
        } else {
            distance = distanceBand(candidate, width, height);
        }
        boolean moving  = memory.moving;
        float areaRatio = candidate.areaRatio(width, height);
        boolean emergency = region.equals("center") && (distance.contains("very close") || areaRatio >= 0.22f);

        expire(now);
        int priority = priorityFor(candidate, moving, emergency, areaRatio);

        // 3. Multi-object aggregation check: If multiple objects of same kind are visible
        int sameKindCount = countLabel(detections, candidate.label);
        String message;
        if (sameKindCount > 1) {
            String pluralLabel = InstanceTracker.pluralize(sameKindCount, candidate.label);
            String direction = suggestDirection(candidate, detections, width, height, distance, settings);
            message = "There are " + pluralLabel + " ahead: closest is " + (region.equals("center") ? "directly ahead" : "to your " + region) + ", " + distance + ". " + direction;
        } else {
            message = messageFor(candidate, region, cell, distance, detections, width, height, emergency, moving, settings);
        }

        Guidance guidance = new Guidance(message, emergency, candidate, moving, priority);
        String stateSignature = stateSignature(region, distance, emergency);
        Guidance spoken = maybeSpeak(guidance, candidate.label.toLowerCase(Locale.US), stateSignature, now, settings);
        if (spoken != null) memory.lastAnnounced = now;
        return spoken;
    }

    private static int countLabel(List<Detection> detections, String label) {
        int count = 0;
        String l = label.toLowerCase(Locale.US);
        for (Detection d : detections) {
            if (d.label.equalsIgnoreCase(l)) count++;
        }
        return count;
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
            int streak = old == null ? 0 : rawMotion ? Math.min(6, old.motionStreak + 1) : Math.max(0, old.motionStreak - 1);
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

        // Only true emergencies jump the speech delay
        boolean priorityJump = settings.priorityOverride
                && guidance.priority == Guidance.PRIORITY_URGENT
                && lastSpeechPriority < Guidance.PRIORITY_URGENT;
        boolean due = lastSpeechAt == 0 || now - lastSpeechAt >= settings.speechDelayMs;
        boolean urgentDue = guidance.priority == Guidance.PRIORITY_URGENT && now - lastSpeechAt >= 2500;

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
                || label.equals("truck") || label.equals("train"))         priority = 950;
        else if (label.equals("bicycle") || label.equals("motorcycle"))    priority = 850;
        else if (isHazardLabel(label))                                      priority = 800;
        else                                                                priority = 500;

        if (isMoving(detection, width, height)) priority += 300;
        return priority + detection.areaRatio(width, height) * 100f + detection.confidence;
    }

    // ── Priority ───────────────────────────────────────────────────────────

    private static int priorityFor(Detection detection, boolean moving, boolean emergency, float areaRatio) {
        if (emergency) return Guidance.PRIORITY_URGENT;
        if (moving && areaRatio >= 0.12f) return Guidance.PRIORITY_URGENT;
        if (isImportantLabel(detection.label) || isHazardLabel(detection.label) || moving) return Guidance.PRIORITY_IMPORTANT;
        return Guidance.PRIORITY_INFO;
    }

    // ── Label classification ───────────────────────────────────────

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

    static boolean isHazardLabel(String rawLabel) {
        String label = rawLabel.toLowerCase(Locale.US);
        return label.equals("escalator")
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

        if (emergency) {
            return "Caution: " + label + " directly ahead. " + direction;
        }

        if (isHazardLabel(label)) {
            String pos = region.equals("center") ? "ahead" : "to your " + region;
            String cautious = "Possible " + label + " " + pos + ", " + distance + ".";
            return appendDirection(cautious, direction);
        }

        if (moving) {
            String position = region.equals("center") ? "ahead" : "to your " + region;
            return appendDirection("Moving " + label + " " + position + ", " + distance + ".", direction);
        }

        return appendDirection(label + " " + (region.equals("center") ? "ahead" : "on your " + region) + ", " + distance + ".", direction);
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
        if (leftBlocked && rightBlocked) return "Stop. Both sides are blocked; turn slowly to scan for a safe exit.";
        if (leftBlocked)  return "Guide right.";
        if (rightBlocked) return "Guide left.";
        if (region.equals("left"))  return "Guide slightly right.";
        if (region.equals("right")) return "Guide slightly left.";
        return "Guide right.";
    }

    private static boolean isVerySide(Detection detection, int width, float edgeIgnoreRatio) {
        return detection.centerX() < width * edgeIgnoreRatio
                || detection.centerX() > width * (1f - edgeIgnoreRatio);
    }

    private static boolean blocked(List<Detection> detections, Detection candidate, String region, int width, int height) {
        for (Detection detection : detections) {
            if (detection == candidate) continue;
            if (regionFor(detection, width).equals(region) && detection.areaRatio(width, height) >= 0.08f) return true;
        }
        return false;
    }

    private static boolean moved(PointF oldCenter, PointF newCenter, int width, int height, float threshold) {
        float dx = (newCenter.x - oldCenter.x) / Math.max(1f, width);
        float dy = (newCenter.y - oldCenter.y) / Math.max(1f, height);
        return Math.hypot(dx, dy) >= threshold;
    }

    private static String trackKey(Detection detection, int width, int height) {
        return detection.label.toLowerCase(Locale.US) + ":" + regionFor(detection, width);
    }

    private static String stateSignature(String region, String distance, boolean emergency) {
        return region + "|" + distance + "|" + emergency;
    }

    private void expire(long now) {
        tracks.entrySet().removeIf(entry -> now - entry.getValue().lastSeen > 2500);
        spokenStates.entrySet().removeIf(entry -> now - entry.getValue().spokenAt > 40000);
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
