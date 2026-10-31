package com.visionguide.phone;

import android.graphics.PointF;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * InstanceTracker — Multi-Object Aggregation, Instance Tracking (1, 2, 3),
 * Change-Only Speech Gating, and Conflict-Aware Turn Guidance.
 *
 * Solves:
 *   1. Object Aggregation: Rather than saying "a chair, a chair, a chair",
 *      groups them: "There are 3 chairs ahead: Chair 1 is centered 1.6m away, two on your sides."
 *   2. Persistent Instance Labeling (1, 2, 3):
 *      Tracks individual instances. If Chair 1 moves out of view, announces:
 *      "One chair has moved out of view; 2 chairs remain."
 *   3. Strict Change-Only Silence:
 *      Remembers previously spoken states. Does NOT repeat unless an instance
 *      drastically moves to a different box or distance band.
 *   4. Multi-Obstacle Conflict Avoidance & Panoramic Turn Guidance:
 *      Checks both sides. If there's an obstacle on the right and a hazard/hole on the left,
 *      NEVER directs the user into danger. Tells them to stop, and turn slowly left and right
 *      to scan for an open exit, announcing when a clear path is found.
 */
final class InstanceTracker {

    public static final class TrackedInstance {
        final int id;
        final String label;
        RectF box;
        String region;
        String gridCell;
        float distanceMeters;
        String distanceBand;
        boolean moving;
        long firstSeen;
        long lastSeen;
        String lastSpokenSignature = "";
        boolean wasAnnounced = false;

        TrackedInstance(int id, String label, RectF box, String region, String gridCell,
                        float distanceMeters, String distanceBand, boolean moving, long now) {
            this.id = id;
            this.label = label;
            this.box = box;
            this.region = region;
            this.gridCell = gridCell;
            this.distanceMeters = distanceMeters;
            this.distanceBand = distanceBand;
            this.moving = moving;
            this.firstSeen = now;
            this.lastSeen = now;
        }

        String displayName() {
            return capitalize(label) + " " + id;
        }

        String stateSignature() {
            return region + "|" + distanceBand + "|" + moving;
        }
    }

    private final Map<String, List<TrackedInstance>> activeTracks = new HashMap<>();
    private final Map<String, Integer> nextIdPerLabel = new HashMap<>();

    // Panoramic Turn Search State
    private boolean isSearchingTurn = false;
    private long lastConflictAlertTime = 0;
    private long lastExitSpokenTime = 0;

    void reset() {
        activeTracks.clear();
        nextIdPerLabel.clear();
        isSearchingTurn = false;
        lastConflictAlertTime = 0;
        lastExitSpokenTime = 0;
    }

    /**
     * Updates instance tracking for the current frame.
     */
    void update(List<Detection> detections, DualCameraDepthEstimator depthEstimator,
                int width, int height, long now) {

        Map<String, List<Detection>> byLabel = new HashMap<>();
        for (Detection d : detections) {
            String label = d.label.toLowerCase(Locale.US);
            byLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(d);
        }

        for (Map.Entry<String, List<Detection>> entry : byLabel.entrySet()) {
            String label = entry.getKey();
            List<Detection> currentList = entry.getValue();
            List<TrackedInstance> existingTracks = activeTracks.computeIfAbsent(label, k -> new ArrayList<>());

            // Match each detection to the nearest existing track (greedy spatial matching)
            boolean[] matchedTrack = new boolean[existingTracks.size()];
            for (Detection d : currentList) {
                float dist = depthEstimator != null ? depthEstimator.estimateDistanceMeters(d, width, height) : 3.0f;
                String region = GuidanceEngine.regionFor(d, width);
                String cell = GuidanceEngine.gridCellFor(d, width, height);
                String distBand = depthEstimator != null ? depthEstimator.formatDistanceSpoken(dist) : GuidanceEngine.distanceBand(d, width, height);

                int bestIdx = -1;
                float bestDist = Float.MAX_VALUE;
                for (int i = 0; i < existingTracks.size(); i++) {
                    if (matchedTrack[i]) continue;
                    TrackedInstance t = existingTracks.get(i);
                    float dx = Math.abs(t.box.centerX() - d.centerX());
                    float dy = Math.abs(t.box.centerY() - d.centerY());
                    float diff = (float) Math.hypot(dx, dy);
                    if (diff < (width * 0.35f) && diff < bestDist) {
                        bestDist = diff;
                        bestIdx = i;
                    }
                }

                if (bestIdx >= 0) {
                    // Update existing track
                    matchedTrack[bestIdx] = true;
                    TrackedInstance t = existingTracks.get(bestIdx);
                    t.box = d.box;
                    t.region = region;
                    t.gridCell = cell;
                    t.distanceMeters = dist;
                    t.distanceBand = distBand;
                    t.lastSeen = now;
                } else {
                    // New instance
                    int nextId = nextIdPerLabel.getOrDefault(label, 1);
                    nextIdPerLabel.put(label, nextId + 1);
                    TrackedInstance newInst = new TrackedInstance(
                            nextId, label, d.box, region, cell, dist, distBand, false, now);
                    existingTracks.add(newInst);
                }
            }
        }
    }

    /**
     * Checks if any previously announced instances have moved out of the camera view.
     * Returns a spoken message such as: "One chair has moved out of view; 2 chairs remain."
     */
    String checkExitedInstances(long now) {
        if (now - lastExitSpokenTime < 4000) return null;

        for (Map.Entry<String, List<TrackedInstance>> entry : activeTracks.entrySet()) {
            String label = entry.getKey();
            List<TrackedInstance> list = entry.getValue();

            for (int i = list.size() - 1; i >= 0; i--) {
                TrackedInstance t = list.get(i);
                if (now - t.lastSeen > 2000) {
                    list.remove(i);
                    if (t.wasAnnounced) {
                        lastExitSpokenTime = now;
                        int remaining = list.size();
                        if (remaining == 0) {
                            return "All " + pluralize(2, label).replaceFirst("^\\d+\\s*", "") + " are now out of view.";
                        } else if (remaining == 1) {
                            return "One " + label + " has moved out of view; 1 " + label + " remains.";
                        } else {
                            return "One " + label + " has moved out of view; " + remaining + " " + pluralize(remaining, label).replaceFirst("^\\d+\\s*", "") + " remain.";
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Evaluates whether there is a multi-obstacle conflict (e.g. car on right, hole/obstacle on left).
     * If so, returns turn guidance prompting the user to stop and pan slowly left/right.
     */
    String evaluateMultiObstacleConflict(List<Detection> detections, DualCameraDepthEstimator depthEstimator,
                                         int width, int height, long now) {

        if (detections == null || detections.isEmpty()) {
            if (isSearchingTurn) {
                isSearchingTurn = false;
                return "Safe path found! Forward path is clear in this direction. You can walk forward now.";
            }
            return null;
        }

        List<Detection> leftObstacles = new ArrayList<>();
        List<Detection> centerObstacles = new ArrayList<>();
        List<Detection> rightObstacles = new ArrayList<>();

        for (Detection d : detections) {
            float dist = depthEstimator != null ? depthEstimator.estimateDistanceMeters(d, width, height) : 3.0f;
            String region = GuidanceEngine.regionFor(d, width);

            if (dist < 3.2f) {
                if (region.equals("left")) leftObstacles.add(d);
                else if (region.equals("right")) rightObstacles.add(d);
                else centerObstacles.add(d);
            }
        }

        boolean centerBlocked = !centerObstacles.isEmpty();
        boolean leftBlocked   = !leftObstacles.isEmpty();
        boolean rightBlocked  = !rightObstacles.isEmpty();

        // ── Case 1: Multi-Obstacle Conflict (Center blocked AND both sides blocked/hazardous) ──
        if (centerBlocked && leftBlocked && rightBlocked) {
            if (now - lastConflictAlertTime > 8000) {
                lastConflictAlertTime = now;
                isSearchingTurn = true;
                Detection c = centerObstacles.get(0);
                Detection l = leftObstacles.get(0);
                Detection r = rightObstacles.get(0);
                return "Stop right now! Obstacles on both sides: a " + r.label + " on your right, a " + l.label + " on your left, and a " + c.label + " directly ahead. Turn slowly to your left or right to scan for an open exit.";
            }
            return null;
        }

        // ── Case 2: During Turn Search, User Found a Clear Direction ──
        if (isSearchingTurn) {
            if (!centerBlocked && (!leftBlocked || !rightBlocked)) {
                isSearchingTurn = false;
                String clearSide = !leftBlocked ? "left" : "right";
                return "Safe path found! Center path is open in this direction. Clear walking space is on your " + clearSide + ". You can walk forward.";
            }
        }

        return null;
    }

    /**
     * Builds an aggregated multi-object scene description grouping same-kind objects.
     * e.g. "There are 3 chairs ahead: Chair 1 is centered 1.6m away, Chair 2 is to your left, and Chair 3 is to your right."
     */
    String buildAggregatedDescription(List<Detection> detections, DualCameraDepthEstimator depthEstimator,
                                      int width, int height) {

        if (detections == null || detections.isEmpty()) {
            return "The path directly ahead is completely clear.";
        }

        Map<String, List<TrackedInstance>> currentInstances = new HashMap<>();
        for (Map.Entry<String, List<TrackedInstance>> entry : activeTracks.entrySet()) {
            List<TrackedInstance> list = new ArrayList<>();
            for (TrackedInstance t : entry.getValue()) {
                if (System.currentTimeMillis() - t.lastSeen < 1200) {
                    list.add(t);
                }
            }
            if (!list.isEmpty()) {
                currentInstances.put(entry.getKey(), list);
            }
        }

        StringBuilder sb = new StringBuilder();

        for (Map.Entry<String, List<TrackedInstance>> entry : currentInstances.entrySet()) {
            String label = entry.getKey();
            List<TrackedInstance> list = entry.getValue();

            // Sort by distance (closest first)
            list.sort((a, b) -> Float.compare(a.distanceMeters, b.distanceMeters));

            if (list.size() > 1) {
                // Aggregation: "There are X [plural objects]"
                sb.append("There are ").append(list.size()).append(" ").append(pluralize(list.size(), label)).append(" ahead: ");
                for (int i = 0; i < list.size(); i++) {
                    TrackedInstance inst = list.get(i);
                    inst.wasAnnounced = true;
                    String pos = inst.region.equals("center") ? "directly ahead" : "to your " + inst.region;
                    String dist = String.format(Locale.US, "%.1f meters", inst.distanceMeters);

                    if (i == 0) {
                        sb.append(inst.displayName()).append(" is ").append(pos).append(" at ").append(dist);
                    } else {
                        sb.append(", and ").append(inst.displayName()).append(" is ").append(pos);
                    }
                }
                sb.append(". ");
            } else {
                TrackedInstance inst = list.get(0);
                inst.wasAnnounced = true;
                String pos = inst.region.equals("center") ? "directly in front of you" : "to your " + inst.region;
                sb.append("A ").append(inst.label).append(" is ").append(pos)
                        .append(" about ").append(String.format(Locale.US, "%.1f", inst.distanceMeters)).append(" meters away. ");
            }
        }

        return sb.toString().trim();
    }

    /**
     * Determines if a tracked object has undergone a drastic state change:
     *   - Moved from one region corridor to another (e.g. left -> center)
     *   - Distance band changed significantly (e.g. jumped from medium -> very close)
     */
    boolean hasDrasticChange(TrackedInstance inst) {
        String currentSig = inst.stateSignature();
        if (inst.lastSpokenSignature.isEmpty()) {
            inst.lastSpokenSignature = currentSig;
            return true;
        }

        if (!inst.lastSpokenSignature.equals(currentSig)) {
            // Check if region changed or distance band changed
            inst.lastSpokenSignature = currentSig;
            return true;
        }
        return false;
    }

    public static String pluralize(int count, String word) {
        if (count == 1) return "1 " + word;
        String w = word.toLowerCase(Locale.US);
        if (w.equals("person")) return count + " people";
        if (w.equals("bus")) return count + " buses";
        if (w.equals("couch")) return count + " couches";
        if (w.endsWith("s") || w.endsWith("sh") || w.endsWith("ch") || w.endsWith("x") || w.endsWith("z")) {
            return count + " " + word + "es";
        }
        return count + " " + word + "s";
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return "";
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }
}
