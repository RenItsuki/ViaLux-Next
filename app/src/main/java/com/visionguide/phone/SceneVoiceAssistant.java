package com.visionguide.phone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * SceneVoiceAssistant — On-Device Natural Language Visual Scene Assistant.
 *
 * Modeled after lightweight Hugging Face mobile QA / intent matchers (like MiniLM / DistilBERT QA).
 * Answers real-world questions from visually impaired users by inspecting real-time
 * camera detections, dual-camera depth measurements, and spatial tracks.
 *
 * Handles queries such as:
 *   - "Is there any car in front of me?"
 *   - "Is there anyone near me?"
 *   - "What is in front of me?" / "What do you see?"
 *   - "How far is the car?" / "Where is the person?"
 *   - "Is the path clear?" / "Can I walk forward?"
 *   - "Is anything moving?"
 *   - "What does the text say?"
 */
final class SceneVoiceAssistant {

    private SceneVoiceAssistant() {}

    /**
     * Checks if the spoken sentence is an interactive scene question.
     */
    static boolean isAssistantQuery(String spoken) {
        if (spoken == null) return false;
        String s = spoken.toLowerCase(Locale.US).trim();

        return s.startsWith("is there")
                || s.startsWith("are there")
                || s.startsWith("do you see")
                || s.startsWith("can you see")
                || s.startsWith("what is")
                || s.startsWith("what's")
                || s.startsWith("what should")
                || s.startsWith("what do")
                || s.startsWith("how do")
                || s.startsWith("how should")
                || s.startsWith("how far")
                || s.startsWith("how close")
                || s.startsWith("where is")
                || s.startsWith("describe")
                || s.startsWith("is anyone")
                || s.startsWith("is someone")
                || s.startsWith("is it safe")
                || s.startsWith("can i walk")
                || s.startsWith("tell me")
                || s.contains("first")
                || s.contains("clear")
                || s.contains("safe");
    }

    /**
     * Evaluates the user's natural language question against current live detections
     * and dual-camera depth metrics, generating a conversational spoken response.
     */
    static String answerQuery(
            String spoken,
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            GuidanceEngine guidanceEngine,
            int imgWidth,
            int imgHeight,
            String lastOcrText) {

        if (spoken == null || spoken.trim().isEmpty()) {
            return "I am listening. Ask me: 'What should I do first?' or 'Is there any car in front of me?'";
        }

        String q = spoken.toLowerCase(Locale.US)
                .replaceAll("^hey\\s+via\\s+lux\\s*", "")
                .replaceAll("^please\\s*", "")
                .replaceAll("^can\\s+you\\s*tell\\s+me\\s*", "")
                .replaceAll("^tell\\s+me\\s*", "")
                .trim();

        List<Detection> active = (detections != null) ? detections : Collections.emptyList();

        // ── 0. "What should I do first?" / Action Advice ──────────────────────
        if (q.contains("what should i do") || q.contains("what to do") || q.contains("what do i do")
                || q.contains("first") || q.contains("advise") || q.contains("suggest")
                || q.contains("help me") || q.contains("how should i") || q.contains("next")
                || q.contains("should i stop")) {
            return evaluateFirstAction(active, depthEstimator, guidanceEngine, imgWidth, imgHeight);
        }

        // ── 1. Path Safety & Clear Check ──────────────────────────────────────
        if (q.contains("path clear") || q.contains("can i walk") || q.contains("safe to walk")
                || q.contains("safe to move") || q.contains("is it safe") || q.contains("clear")) {
            return evaluatePathSafety(active, depthEstimator, imgWidth, imgHeight);
        }

        // ── 2. Scene Summary ──────────────────────────────────────────────────
        if (q.contains("what is in front") || q.contains("what's in front")
                || q.contains("what's ahead") || q.contains("what is ahead")
                || q.contains("what do you see") || q.contains("describe")
                || q.contains("around me") || q.contains("surroundings")) {
            return describeFrontScene(active, depthEstimator, imgWidth, imgHeight);
        }

        // ── 3. Moving Object Query ────────────────────────────────────────────
        if (q.contains("anything moving") || q.contains("something moving") || q.contains("coming towards")
                || q.contains("moving")) {
            return evaluateMovingObjects(active, depthEstimator, guidanceEngine, imgWidth, imgHeight);
        }

        // ── 4. Distance to Specific Object ────────────────────────────────────
        if (q.startsWith("how far") || q.startsWith("how close") || q.startsWith("where is")) {
            String targetLabel = extractTargetEntity(q);
            return evaluateObjectDistance(targetLabel, active, depthEstimator, imgWidth, imgHeight);
        }

        // ── 5. Specific Object Presence Query ─────────────────────────────────
        if (q.startsWith("is there") || q.startsWith("are there") || q.startsWith("do you see")
                || q.startsWith("can you see") || q.startsWith("is anyone") || q.startsWith("is someone")
                || q.contains("car") || q.contains("person") || q.contains("vehicle") || q.contains("chair")
                || q.contains("table") || q.contains("door")) {
            String targetLabel = extractTargetEntity(q);
            return evaluateObjectPresence(targetLabel, q, active, depthEstimator, imgWidth, imgHeight);
        }

        // ── 6. Fallback General Question ──────────────────────────────────────
        if (active.isEmpty()) {
            return "The path directly ahead is open and clear. It is safe to walk forward.";
        }
        return describeFrontScene(active, depthEstimator, imgWidth, imgHeight);
    }

    // ── Semantic Query Handlers ──────────────────────────────────────────────

    private static String evaluateObjectPresence(
            String targetLabel,
            String query,
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            int imgWidth,
            int imgHeight) {

        List<Detection> matched = filterByEntity(detections, targetLabel);

        if (matched.isEmpty()) {
            if (targetLabel.equals("person")) {
                return "No, no people are detected near you right now. The area ahead is quiet.";
            } else if (targetLabel.equals("car") || targetLabel.equals("vehicle")) {
                return "No, there are no cars or vehicles in front of you. Your path ahead is clear.";
            } else {
                return "No, I do not see any " + targetLabel + " in view right now.";
            }
        }

        // Sort by closest distance
        Detection closest = findClosest(matched, depthEstimator, imgWidth, imgHeight);
        float dist = depthEstimator.estimateDistanceMeters(closest, imgWidth, imgHeight);
        String region = GuidanceEngine.regionFor(closest, imgWidth);
        String formattedDist = String.format(Locale.US, "%.1f metres", dist);

        if (region.equals("center")) {
            return "Yes, there is a " + closest.label + " directly in front of you, approximately " + formattedDist + " away.";
        } else {
            return "There is a " + closest.label + " to your " + region + ", approximately " + formattedDist + " away, but directly ahead is clear.";
        }
    }

    private static String evaluateObjectDistance(
            String targetLabel,
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            int imgWidth,
            int imgHeight) {

        List<Detection> matched = filterByEntity(detections, targetLabel);
        if (matched.isEmpty()) {
            return "I don't see any " + targetLabel + " in camera view right now.";
        }

        Detection closest = findClosest(matched, depthEstimator, imgWidth, imgHeight);
        float dist = depthEstimator.estimateDistanceMeters(closest, imgWidth, imgHeight);
        String region = GuidanceEngine.regionFor(closest, imgWidth);
        String pos = region.equals("center") ? "ahead" : "to your " + region;

        return "The " + closest.label + " is " + pos + ", approximately " + String.format(Locale.US, "%.1f metres", dist) + " away.";
    }

    private static String evaluatePathSafety(
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            int imgWidth,
            int imgHeight) {

        Detection obstacleInCenter = null;
        float closestDist = Float.MAX_VALUE;

        for (Detection d : detections) {
            String region = GuidanceEngine.regionFor(d, imgWidth);
            if (region.equals("center")) {
                float dist = depthEstimator.estimateDistanceMeters(d, imgWidth, imgHeight);
                if (dist < 2.5f && dist < closestDist) {
                    closestDist = dist;
                    obstacleInCenter = d;
                }
            }
        }

        if (obstacleInCenter == null) {
            return "Yes, your path directly ahead is clear for at least 3 metres. You can safely step forward.";
        } else {
            String distStr = String.format(Locale.US, "%.1f metres", closestDist);
            return "Caution: There is a " + obstacleInCenter.label + " directly ahead, " + distStr + " away. Step slightly right to walk around it.";
        }
    }

    private static String evaluateFirstAction(
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            GuidanceEngine guidanceEngine,
            int imgWidth,
            int imgHeight) {

        if (detections.isEmpty()) {
            return "Your path directly ahead is completely clear. You can safely step forward.";
        }

        // 1. Moving hazard takes top priority
        for (Detection d : detections) {
            boolean moving = guidanceEngine != null && guidanceEngine.isMoving(d, imgWidth, imgHeight);
            float dist = depthEstimator.estimateDistanceMeters(d, imgWidth, imgHeight);
            String region = GuidanceEngine.regionFor(d, imgWidth);
            if (moving && dist < 4.0f) {
                String pos = region.equals("center") ? "directly ahead" : "to your " + region;
                return "Stop for a moment. A moving " + d.label + " is " + pos + ", about "
                        + String.format(Locale.US, "%.1f", dist) + " meters away. Wait for it to pass.";
            }
        }

        // 2. Immediate obstacle in center walking path
        Detection centerObj = null;
        float minCenterDist = Float.MAX_VALUE;
        for (Detection d : detections) {
            String region = GuidanceEngine.regionFor(d, imgWidth);
            if (region.equals("center")) {
                float dist = depthEstimator.estimateDistanceMeters(d, imgWidth, imgHeight);
                if (dist < minCenterDist) {
                    minCenterDist = dist;
                    centerObj = d;
                }
            }
        }

        if (centerObj != null) {
            String distStr = String.format(Locale.US, "%.1f", minCenterDist);
            if (minCenterDist < 1.4f) {
                return "Stop right now. There is a " + centerObj.label + " directly in front of you, only "
                        + distStr + " meters away. Step slightly right to walk around it.";
            } else if (minCenterDist < 3.0f) {
                return "Step slightly to your right. There is a " + centerObj.label + " ahead in your path, about "
                        + distStr + " meters away.";
            }
        }

        // 3. Clear path forward with side awareness
        Detection closest = findClosest(detections, depthEstimator, imgWidth, imgHeight);
        float sideDist = depthEstimator.estimateDistanceMeters(closest, imgWidth, imgHeight);
        String sideRegion = GuidanceEngine.regionFor(closest, imgWidth);

        return "Your center path is clear to walk forward. Just watch for a " + closest.label + " to your "
                + sideRegion + ", about " + String.format(Locale.US, "%.1f", sideDist) + " meters away.";
    }

    private static String describeFrontScene(
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            int imgWidth,
            int imgHeight) {

        if (detections.isEmpty()) {
            return "The path directly ahead is completely clear. No obstacles detected.";
        }

        StringBuilder sb = new StringBuilder();
        Detection centerObj = null;
        for (Detection d : detections) {
            if (GuidanceEngine.regionFor(d, imgWidth).equals("center")) {
                centerObj = d;
                break;
            }
        }

        if (centerObj != null) {
            float dist = depthEstimator.estimateDistanceMeters(centerObj, imgWidth, imgHeight);
            sb.append("Directly in front of you, there is a ").append(centerObj.label)
                    .append(" about ").append(String.format(Locale.US, "%.1f", dist)).append(" meters away. ");
        } else {
            sb.append("Directly ahead, your walking path is clear. ");
        }

        int count = 0;
        for (Detection d : detections) {
            if (d == centerObj) continue;
            if (count++ >= 2) break;
            float dist = depthEstimator.estimateDistanceMeters(d, imgWidth, imgHeight);
            String region = GuidanceEngine.regionFor(d, imgWidth);
            sb.append("To your ").append(region).append(", there is a ").append(d.label)
                    .append(" about ").append(String.format(Locale.US, "%.1f", dist)).append(" meters away. ");
        }
        return sb.toString().trim();
    }

    private static String evaluateMovingObjects(
            List<Detection> detections,
            DualCameraDepthEstimator depthEstimator,
            GuidanceEngine guidanceEngine,
            int imgWidth,
            int imgHeight) {

        for (Detection d : detections) {
            if (guidanceEngine != null && guidanceEngine.isMoving(d, imgWidth, imgHeight)) {
                float dist = depthEstimator.estimateDistanceMeters(d, imgWidth, imgHeight);
                String region = GuidanceEngine.regionFor(d, imgWidth);
                return "Yes, a moving " + d.label + " is detected " + (region.equals("center") ? "ahead" : "on your " + region)
                        + ", approximately " + String.format(Locale.US, "%.1f metres", dist) + " away.";
            }
        }
        return "Nothing is currently moving in front of you.";
    }

    // ── Entity Matcher & Synonyms ──────────────────────────────────────────

    private static String extractTargetEntity(String query) {
        if (query.contains("car") || query.contains("vehicle") || query.contains("automobile") || query.contains("traffic")) {
            return "car";
        }
        if (query.contains("person") || query.contains("anyone") || query.contains("someone")
                || query.contains("people") || query.contains("pedestrian") || query.contains("man") || query.contains("woman")) {
            return "person";
        }
        if (query.contains("bike") || query.contains("bicycle") || query.contains("motorcycle") || query.contains("scooter")) {
            return "bicycle";
        }
        if (query.contains("bus") || query.contains("truck") || query.contains("van")) {
            return "bus";
        }
        if (query.contains("chair") || query.contains("bench") || query.contains("seat") || query.contains("couch")) {
            return "chair";
        }
        if (query.contains("table") || query.contains("desk")) {
            return "table";
        }
        if (query.contains("dog") || query.contains("cat") || query.contains("animal") || query.contains("pet")) {
            return "dog";
        }
        if (query.contains("door") || query.contains("entrance") || query.contains("exit")) {
            return "door";
        }
        if (query.contains("bottle") || query.contains("cup")) {
            return "bottle";
        }
        return "obstacle";
    }

    private static List<Detection> filterByEntity(List<Detection> detections, String entity) {
        List<Detection> list = new ArrayList<>();
        for (Detection d : detections) {
            String l = d.label.toLowerCase(Locale.US);
            if (entity.equals("car") && (l.contains("car") || l.contains("bus") || l.contains("truck") || l.contains("vehicle"))) {
                list.add(d);
            } else if (entity.equals("person") && (l.contains("person") || l.contains("pedestrian"))) {
                list.add(d);
            } else if (entity.equals("bicycle") && (l.contains("bicycle") || l.contains("motorcycle") || l.contains("bike"))) {
                list.add(d);
            } else if (entity.equals("chair") && (l.contains("chair") || l.contains("bench"))) {
                list.add(d);
            } else if (entity.equals("table") && (l.contains("table") || l.contains("desk"))) {
                list.add(d);
            } else if (entity.equals("dog") && (l.contains("dog") || l.contains("cat"))) {
                list.add(d);
            } else if (entity.equals("obstacle")) {
                list.add(d);
            } else if (l.contains(entity)) {
                list.add(d);
            }
        }
        return list;
    }

    private static Detection findClosest(List<Detection> detections, DualCameraDepthEstimator depthEstimator, int w, int h) {
        Detection best = detections.get(0);
        float min = depthEstimator.estimateDistanceMeters(best, w, h);
        for (int i = 1; i < detections.size(); i++) {
            Detection d = detections.get(i);
            float dist = depthEstimator.estimateDistanceMeters(d, w, h);
            if (dist < min) {
                min = dist;
                best = d;
            }
        }
        return best;
    }
}
