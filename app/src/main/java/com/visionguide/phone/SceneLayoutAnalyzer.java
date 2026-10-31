package com.visionguide.phone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SceneLayoutAnalyzer — Intelligent Spatial & Environmental Scene Understanding.
 *
 * Deduces whether the user is Indoors or Outdoors and recognizes room types
 * (e.g., dining room, office, living room, bedroom, kitchen, street, sidewalk)
 * based on the spatial composition of detected objects.
 *
 * It maps out 3 walking corridors (Left, Center, Right) and determines:
 *   1. Environmental context ("We are indoors in a dining room").
 *   2. Obstacle layout ("A dining table is in your center path 1.8m ahead with chairs around it").
 *   3. Recommended walking path ("The right corridor is open and clear").
 */
final class SceneLayoutAnalyzer {

    public enum EnvironmentType {
        INDOORS,
        OUTDOORS,
        UNKNOWN
    }

    public enum RoomType {
        DINING_ROOM("dining room"),
        LIVING_ROOM("living room"),
        OFFICE_ROOM("office or workspace"),
        BEDROOM("bedroom"),
        KITCHEN("kitchen"),
        INDOOR_SPACE("room"),
        STREET("street with vehicles"),
        SIDEWALK("pedestrian sidewalk"),
        OUTDOOR_SPACE("outdoor area"),
        GENERAL("area");

        final String displayName;
        RoomType(String name) { this.displayName = name; }
    }

    // ── Indoor indicators (COCO dataset classes) ───────────────────────────
    private static final Set<String> INDOOR_OBJECTS = new HashSet<>(Arrays.asList(
            "chair", "couch", "bed", "dining table", "tv", "laptop", "mouse", "remote",
            "keyboard", "microwave", "oven", "toaster", "sink", "refrigerator", "book",
            "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush",
            "potted plant", "cup", "fork", "knife", "spoon", "bowl"
    ));

    // ── Outdoor indicators ────────────────────────────────────────────────
    private static final Set<String> OUTDOOR_OBJECTS = new HashSet<>(Arrays.asList(
            "car", "bus", "truck", "traffic light", "fire hydrant", "stop sign",
            "parking meter", "bicycle", "motorcycle", "train", "airplane", "boat",
            "zebra crossing", "crosswalk", "curb"
    ));

    public static final class LayoutInfo {
        final EnvironmentType environment;
        final RoomType roomType;
        final String environmentSummary;
        final String layoutSummary;
        final String recommendedCorridor; // "left", "center", "right"
        final boolean centerBlocked;
        final Detection primaryCenterObstacle;
        final float centerObstacleDistance;

        LayoutInfo(
                EnvironmentType environment,
                RoomType roomType,
                String environmentSummary,
                String layoutSummary,
                String recommendedCorridor,
                boolean centerBlocked,
                Detection primaryCenterObstacle,
                float centerObstacleDistance) {
            this.environment = environment;
            this.roomType = roomType;
            this.environmentSummary = environmentSummary;
            this.layoutSummary = layoutSummary;
            this.recommendedCorridor = recommendedCorridor;
            this.centerBlocked = centerBlocked;
            this.primaryCenterObstacle = primaryCenterObstacle;
            this.centerObstacleDistance = centerObstacleDistance;
        }
    }

    /**
     * Checks if the user's spoken sentence is inquiring about the room, layout, or environment.
     */
    static boolean isLayoutQuery(String spoken) {
        if (spoken == null) return false;
        String s = spoken.toLowerCase(Locale.US).trim();
        return s.contains("where are we")
                || s.contains("are we inside")
                || s.contains("are we outside")
                || s.contains("am i inside")
                || s.contains("am i outside")
                || s.contains("inside or outside")
                || s.contains("what room")
                || s.contains("which room")
                || s.contains("what place")
                || s.contains("describe room")
                || s.contains("room layout")
                || s.contains("layout")
                || s.contains("describe layout")
                || s.contains("what is the layout")
                || s.contains("what's the layout")
                || s.contains("is this inside")
                || s.contains("is this outside")
                || s.contains("environment");
    }

    /**
     * Analyzes detected objects and spatial depth to determine environment and room layout.
     */
    static LayoutInfo analyze(
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            int imgWidth,
            int imgHeight) {

        if (detections == null || detections.isEmpty()) {
            return new LayoutInfo(
                    EnvironmentType.UNKNOWN,
                    RoomType.GENERAL,
                    "No objects are in immediate view.",
                    "The area ahead appears open with no detected obstacles.",
                    "center",
                    false,
                    null,
                    999f
            );
        }

        int indoorScore = 0;
        int outdoorScore = 0;

        boolean hasDiningTable = false;
        boolean hasBed = false;
        boolean hasCouch = false;
        boolean hasTv = false;
        boolean hasOfficeTech = false;
        boolean hasKitchenAppliance = false;
        boolean hasVehicle = false;
        boolean hasTrafficMarker = false;

        List<Detection> leftObjects = new ArrayList<>();
        List<Detection> centerObjects = new ArrayList<>();
        List<Detection> rightObjects = new ArrayList<>();

        for (Detection d : detections) {
            String label = d.label.toLowerCase(Locale.US);

            if (INDOOR_OBJECTS.contains(label)) {
                indoorScore += 3;
            } else if (OUTDOOR_OBJECTS.contains(label)) {
                outdoorScore += 3;
            }

            if (label.equals("dining table")) { hasDiningTable = true; indoorScore += 5; }
            if (label.equals("bed")) { hasBed = true; indoorScore += 6; }
            if (label.equals("couch")) { hasCouch = true; indoorScore += 4; }
            if (label.equals("tv")) { hasTv = true; indoorScore += 4; }
            if (label.equals("laptop") || label.equals("keyboard") || label.equals("mouse")) {
                hasOfficeTech = true; indoorScore += 4;
            }
            if (label.equals("refrigerator") || label.equals("microwave") || label.equals("oven") || label.equals("sink")) {
                hasKitchenAppliance = true; indoorScore += 5;
            }
            if (label.equals("car") || label.equals("bus") || label.equals("truck") || label.equals("motorcycle")) {
                hasVehicle = true; outdoorScore += 5;
            }
            if (label.equals("traffic light") || label.equals("stop sign") || label.equals("fire hydrant")) {
                hasTrafficMarker = true; outdoorScore += 5;
            }

            // Group into left, center, right corridors
            String region = GuidanceEngine.regionFor(d, imgWidth);
            if (region.equals("left")) leftObjects.add(d);
            else if (region.equals("right")) rightObjects.add(d);
            else centerObjects.add(d);
        }

        // Determine environment
        EnvironmentType env;
        if (indoorScore >= outdoorScore + 2) {
            env = EnvironmentType.INDOORS;
        } else if (outdoorScore >= indoorScore + 2) {
            env = EnvironmentType.OUTDOORS;
        } else if (indoorScore > 0) {
            env = EnvironmentType.INDOORS;
        } else if (outdoorScore > 0) {
            env = EnvironmentType.OUTDOORS;
        } else {
            env = EnvironmentType.UNKNOWN;
        }

        // Determine specific room or outdoor space
        RoomType roomType;
        if (env == EnvironmentType.INDOORS) {
            if (hasBed) roomType = RoomType.BEDROOM;
            else if (hasKitchenAppliance) roomType = RoomType.KITCHEN;
            else if (hasDiningTable) roomType = RoomType.DINING_ROOM;
            else if (hasOfficeTech) roomType = RoomType.OFFICE_ROOM;
            else if (hasCouch || hasTv) roomType = RoomType.LIVING_ROOM;
            else roomType = RoomType.INDOOR_SPACE;
        } else if (env == EnvironmentType.OUTDOORS) {
            if (hasVehicle || hasTrafficMarker) roomType = RoomType.STREET;
            else roomType = RoomType.SIDEWALK;
        } else {
            roomType = RoomType.GENERAL;
        }

        // Evaluate corridor obstacles
        Detection centerClosest = null;
        float centerDist = Float.MAX_VALUE;
        for (Detection d : centerObjects) {
            float dist = depthEstimator != null ? depthEstimator.estimateDistanceMeters(d, imgWidth, imgHeight) : 3.0f;
            if (dist < centerDist) {
                centerDist = dist;
                centerClosest = d;
            }
        }

        boolean centerBlocked = centerClosest != null && centerDist < 3.2f;

        // Choose recommended corridor
        String recommendedCorridor;
        if (!centerBlocked) {
            recommendedCorridor = "center";
        } else {
            // Find which side is clearer
            float leftMinDist = minDistance(leftObjects, depthEstimator, imgWidth, imgHeight);
            float rightMinDist = minDistance(rightObjects, depthEstimator, imgWidth, imgHeight);
            recommendedCorridor = (rightMinDist >= leftMinDist) ? "right" : "left";
        }

        // Synthesize environment summary
        String envSummary;
        if (env == EnvironmentType.INDOORS) {
            envSummary = "We are indoors, in what appears to be a " + roomType.displayName + ".";
        } else if (env == EnvironmentType.OUTDOORS) {
            envSummary = "We are outdoors, along a " + roomType.displayName + ".";
        } else {
            envSummary = "We are in an open area.";
        }

        // Synthesize layout summary
        StringBuilder layoutSb = new StringBuilder();
        if (centerBlocked && centerClosest != null) {
            String distStr = String.format(Locale.US, "%.1f", centerDist);
            layoutSb.append("A ").append(centerClosest.label).append(" is directly ahead in your center path about ")
                    .append(distStr).append(" meters away. ");
            layoutSb.append("The ").append(recommendedCorridor).append(" corridor is open to walk past it.");
        } else {
            layoutSb.append("Your center walking corridor is open and clear. ");
            if (!leftObjects.isEmpty() || !rightObjects.isEmpty()) {
                Detection sideObj = !rightObjects.isEmpty() ? rightObjects.get(0) : leftObjects.get(0);
                String sideRegion = GuidanceEngine.regionFor(sideObj, imgWidth);
                float dist = depthEstimator != null ? depthEstimator.estimateDistanceMeters(sideObj, imgWidth, imgHeight) : 2.5f;
                layoutSb.append("There is a ").append(sideObj.label).append(" to your ").append(sideRegion)
                        .append(" about ").append(String.format(Locale.US, "%.1f", dist)).append(" meters away.");
            }
        }

        return new LayoutInfo(
                env,
                roomType,
                envSummary,
                layoutSb.toString().trim(),
                recommendedCorridor,
                centerBlocked,
                centerClosest,
                centerDist
        );
    }

    private static float minDistance(List<Detection> list, DualCameraDepthEstimator depthEstimator, int w, int h) {
        if (list.isEmpty()) return 999f;
        float min = Float.MAX_VALUE;
        for (Detection d : list) {
            float dist = depthEstimator != null ? depthEstimator.estimateDistanceMeters(d, w, h) : 3.0f;
            if (dist < min) min = dist;
        }
        return min;
    }

    /**
     * Directly answers natural questions about room type, indoor/outdoor status, or layout.
     */
    static String answerLayoutQuery(
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            int imgWidth,
            int imgHeight) {

        LayoutInfo info = analyze(detections, depthEstimator, imgWidth, imgHeight);

        if (info.environment == EnvironmentType.INDOORS) {
            return info.environmentSummary + " " + info.layoutSummary;
        } else if (info.environment == EnvironmentType.OUTDOORS) {
            return info.environmentSummary + " " + info.layoutSummary;
        } else {
            if (detections == null || detections.isEmpty()) {
                return "The view directly ahead is completely clear of obstacles. It appears to be an open space.";
            }
            return "Based on your surroundings: " + info.layoutSummary;
        }
    }
}
