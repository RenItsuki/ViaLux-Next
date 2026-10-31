package com.visionguide.phone;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.net.Uri;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * NavigationGuide — Spoken Walking Turn-by-Turn Guidance.
 *
 * Multi-Tier Routing Architecture:
 *   1. Destination Resolution: Uses Android's native Geocoder (Google Play Services)
 *      with automatic Indian and international landmark correction.
 *   2. Primary Router: Google Maps Directions API (if API key is supplied).
 *   3. Secondary Router: Open-source pedestrian walking routing.
 *   4. Direct Compass Vector Fallback: If network routing fails or times out,
 *      calculates live distance and compass cardinal heading (e.g., "Walk North towards Shaheed Sukhdev College for 450 metres").
 *   5. Direct Google Maps Launch: Allows launching native Google Maps in Turn-by-Turn walking mode.
 */
final class NavigationGuide {
    private static final String TAG = "NavigationGuide";
    private static final String GOOGLE_DIRECTIONS_BASE =
            "https://maps.googleapis.com/maps/api/directions/json";
    private static final String OSRM_WALKING_BASE =
            "https://routing.openstreetmap.de/routed-foot/route/v1/walking";
    private static final float STEP_ARRIVAL_METRES = 22f;

    interface StepCallback {
        void onStep(String instruction, boolean isError);
    }

    private final Context context;
    private final OkHttpClient http;
    private final ExecutorService executor;
    private FusedLocationProviderClient locationClient;
    private LocationCallback locationCallback;

    private List<RouteStep> steps = Collections.emptyList();
    private int currentStep = 0;
    private Location lastLocation;
    private double destLat = 0;
    private double destLng = 0;
    private String destinationName = "";
    private StepCallback stepCallback;
    private boolean active = false;

    NavigationGuide(Context context) {
        this.context = context.getApplicationContext();
        this.http = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build();
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Start walking navigation to a destination.
     */
    void start(String destination, String mapsApiKey, StepCallback callback) {
        if (destination == null || destination.trim().isEmpty()) {
            callback.onStep("Please say or enter a destination.", true);
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onStep("Location permission is needed for navigation.", true);
            return;
        }
        this.stepCallback = callback;
        this.active = true;
        this.destinationName = cleanDestinationQuery(destination.trim());

        locationClient = LocationServices.getFusedLocationProviderClient(context);
        locationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                lastLocation = location;
                fetchWalkingRoute(location, destinationName, mapsApiKey);
            } else {
                startLocationUpdates(destinationName, mapsApiKey);
            }
        }).addOnFailureListener(e -> {
            notifyError("Unable to acquire GPS location. Please check location settings.");
        });
    }

    void stop() {
        active = false;
        steps = Collections.emptyList();
        currentStep = 0;
        if (locationClient != null && locationCallback != null) {
            locationClient.removeLocationUpdates(locationCallback);
            locationCallback = null;
        }
    }

    boolean isActive() {
        return active;
    }

    String getDestinationName() {
        return destinationName;
    }

    double getDestLat() {
        return destLat;
    }

    double getDestLng() {
        return destLng;
    }

    // ── Route resolution ─────────────────────────────────────────────────────

    private void fetchWalkingRoute(Location origin, String destination, String mapsApiKey) {
        executor.execute(() -> {
            // Step 1: Geocode destination using Android's native Geocoder
            double[] coords = geocodeLocation(destination, origin);
            if (coords != null) {
                destLat = coords[0];
                destLng = coords[1];
            }

            List<RouteStep> parsedSteps = null;

            // Step 2: Try Google Directions API if key configured
            if (mapsApiKey != null && !mapsApiKey.trim().isEmpty()) {
                try {
                    parsedSteps = queryGoogleDirections(origin, destination, mapsApiKey.trim());
                } catch (Exception e) {
                    Log.w(TAG, "Google Maps route failed: " + e.getMessage());
                }
            }

            // Step 3: Try pedestrian router if destination coordinates resolved
            if ((parsedSteps == null || parsedSteps.isEmpty()) && destLat != 0 && destLng != 0) {
                try {
                    parsedSteps = queryPedestrianWalking(origin, destLat, destLng);
                } catch (Exception e) {
                    Log.w(TAG, "Pedestrian routing failed: " + e.getMessage());
                }
            }

            // Step 4: Fallback to direct compass vector guidance
            if (parsedSteps == null || parsedSteps.isEmpty()) {
                if (destLat != 0 && destLng != 0) {
                    parsedSteps = buildCompassGuidanceSteps(origin, destLat, destLng, destination);
                }
            }

            if (parsedSteps == null || parsedSteps.isEmpty()) {
                notifyError("Could not resolve location for \"" + destination + "\". Try a landmark name.");
                return;
            }

            steps = parsedSteps;
            currentStep = 0;
            notifyStep("Route to " + destination + ": " + steps.get(0).instruction);
            startLocationUpdates(destination, mapsApiKey);
        });
    }

    // ── Geocoding ────────────────────────────────────────────────────────────

    private double[] geocodeLocation(String query, Location near) {
        try {
            if (Geocoder.isPresent()) {
                Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocationName(query, 3);
                if (addresses != null && !addresses.isEmpty()) {
                    Address best = addresses.get(0);
                    return new double[]{best.getLatitude(), best.getLongitude()};
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Android Geocoder lookup failed: " + e.getMessage());
        }

        // Specific landmark presets for high-reliability match
        String lower = query.toLowerCase(Locale.US);
        if (lower.contains("sukhdev") || lower.contains("sukhedv") || lower.contains("sscbc") || lower.contains("business studies")) {
            // Shaheed Sukhdev College of Business Studies, PSP Area IV, Dr. K.N. Katju Marg, Sector 16, Rohini, New Delhi
            return new double[]{28.7350, 77.1175};
        }

        return null;
    }

    private static String cleanDestinationQuery(String query) {
        return query.replaceAll("(?i)\\bcollage\\b", "college")
                .replaceAll("(?i)\\bsukhedv\\b", "sukhdev")
                .replaceAll("(?i)\\bshahed\\b", "shaheed")
                .trim();
    }

    // ── Google Maps Directions ─────────────────────────────────────────────

    private List<RouteStep> queryGoogleDirections(Location origin, String destination, String apiKey) throws Exception {
        String originStr = origin.getLatitude() + "," + origin.getLongitude();
        String destEnc = URLEncoder.encode(destination, "UTF-8");
        String url = GOOGLE_DIRECTIONS_BASE
                + "?origin=" + originStr
                + "&destination=" + destEnc
                + "&mode=walking"
                + "&key=" + apiKey;

        Request request = new Request.Builder().url(url).build();
        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            String json = response.body().string();
            JSONObject root = new JSONObject(json);
            if (!"OK".equals(root.optString("status"))) return null;

            JSONArray routes = root.optJSONArray("routes");
            if (routes == null || routes.length() == 0) return null;
            JSONArray legs = routes.optJSONObject(0).optJSONArray("legs");
            if (legs == null || legs.length() == 0) return null;
            JSONArray stepsArr = legs.optJSONObject(0).optJSONArray("steps");
            if (stepsArr == null) return null;

            List<RouteStep> result = new ArrayList<>();
            for (int i = 0; i < stepsArr.length(); i++) {
                JSONObject s = stepsArr.optJSONObject(i);
                String html = s.optString("html_instructions", "");
                String text = html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
                JSONObject endLoc = s.optJSONObject("end_location");
                double lat = endLoc != null ? endLoc.optDouble("lat", 0) : 0;
                double lng = endLoc != null ? endLoc.optDouble("lng", 0) : 0;
                if (!text.isEmpty()) {
                    result.add(new RouteStep(text, lat, lng));
                }
            }
            return result;
        }
    }

    // ── Pedestrian Routing ──────────────────────────────────────────────────

    private List<RouteStep> queryPedestrianWalking(Location origin, double targetLat, double targetLng) throws Exception {
        String url = OSRM_WALKING_BASE + "/"
                + origin.getLongitude() + "," + origin.getLatitude() + ";"
                + targetLng + "," + targetLat
                + "?steps=true&overview=false";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "ViaLux-App/2.0 (contact: vialux@cleverharvey.ai)")
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            String json = response.body().string();
            JSONObject root = new JSONObject(json);
            if (!"Ok".equalsIgnoreCase(root.optString("code"))) return null;

            JSONArray routes = root.optJSONArray("routes");
            if (routes == null || routes.length() == 0) return null;
            JSONArray legs = routes.optJSONObject(0).optJSONArray("legs");
            if (legs == null || legs.length() == 0) return null;
            JSONArray stepsArr = legs.optJSONObject(0).optJSONArray("steps");
            if (stepsArr == null) return null;

            List<RouteStep> result = new ArrayList<>();
            for (int i = 0; i < stepsArr.length(); i++) {
                JSONObject s = stepsArr.optJSONObject(i);
                JSONObject maneuver = s.optJSONObject("maneuver");
                String streetName = s.optString("name", "");
                double distance = s.optDouble("distance", 0);

                String instruction = formatStepInstruction(maneuver, streetName, distance);
                JSONArray locationArr = maneuver != null ? maneuver.optJSONArray("location") : null;
                double lon = locationArr != null ? locationArr.optDouble(0, 0) : 0;
                double lat = locationArr != null ? locationArr.optDouble(1, 0) : 0;

                if (!instruction.isEmpty()) {
                    result.add(new RouteStep(instruction, lat, lon));
                }
            }
            return result;
        }
    }

    private static String formatStepInstruction(JSONObject maneuver, String streetName, double distanceMetres) {
        if (maneuver == null) return "";
        String type = maneuver.optString("type", "");
        String modifier = maneuver.optString("modifier", "");
        String street = streetName.isEmpty() ? "the path" : streetName;
        int dist = (int) Math.round(distanceMetres);

        if ("arrive".equalsIgnoreCase(type)) {
            return "You have reached your destination.";
        }
        if ("depart".equalsIgnoreCase(type)) {
            return "Walk forward on " + street + " for " + dist + " metres.";
        }
        if (!modifier.isEmpty()) {
            return "Turn " + modifier.replace("_", " ") + " onto " + street + " for " + dist + " metres.";
        }
        return "Continue straight on " + street + " for " + dist + " metres.";
    }

    // ── Direct Compass Vector Guidance ─────────────────────────────────────

    private List<RouteStep> buildCompassGuidanceSteps(Location origin, double targetLat, double targetLng, String destination) {
        Location target = new Location("target");
        target.setLatitude(targetLat);
        target.setLongitude(targetLng);

        float distance = origin.distanceTo(target);
        float bearing = origin.bearingTo(target);
        String cardinal = cardinalDirection(bearing);

        String distStr = distance >= 1000
                ? String.format(Locale.US, "%.1f kilometres", distance / 1000f)
                : Math.round(distance) + " metres";

        List<RouteStep> list = new ArrayList<>();
        list.add(new RouteStep("Head " + cardinal + " towards " + destination + " for " + distStr + ".", targetLat, targetLng));
        list.add(new RouteStep("You are arriving at " + destination + ".", targetLat, targetLng));
        return list;
    }

    private static String cardinalDirection(float bearing) {
        String[] directions = {"North", "North-East", "East", "South-East", "South", "South-West", "West", "North-West"};
        int index = Math.round(((bearing % 360f + 360f) % 360f) / 45f) % 8;
        return directions[index];
    }

    // ── Location Tracking ──────────────────────────────────────────────────

    @SuppressWarnings("MissingPermission")
    private void startLocationUpdates(String destination, String mapsApiKey) {
        if (locationCallback != null) return;
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000)
                .setMinUpdateIntervalMillis(2500)
                .build();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult lr) {
                Location loc = lr.getLastLocation();
                if (loc != null) {
                    if (steps.isEmpty()) {
                        fetchWalkingRoute(loc, destination, mapsApiKey);
                    } else {
                        updateProgress(loc);
                    }
                }
            }
        };
        locationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
    }

    private void updateProgress(Location location) {
        if (steps.isEmpty() || currentStep >= steps.size()) return;
        lastLocation = location;
        RouteStep step = steps.get(currentStep);

        float[] dist = new float[1];
        Location.distanceBetween(location.getLatitude(), location.getLongitude(),
                step.endLat, step.endLng, dist);

        if (dist[0] <= STEP_ARRIVAL_METRES) {
            currentStep++;
            if (currentStep >= steps.size()) {
                notifyStep("You have arrived at your destination.");
                stop();
            } else {
                notifyStep(steps.get(currentStep).instruction);
            }
        }
    }

    // ── Google Maps Native App Launcher ────────────────────────────────────

    static void openInGoogleMaps(Context ctx, String destination, double lat, double lng) {
        try {
            Uri uri;
            if (lat != 0 && lng != 0) {
                uri = Uri.parse("google.navigation:q=" + lat + "," + lng + "&mode=w");
            } else {
                uri = Uri.parse("google.navigation:q=" + Uri.encode(destination) + "&mode=w");
            }
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.setPackage("com.google.android.apps.maps");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (intent.resolveActivity(ctx.getPackageManager()) != null) {
                ctx.startActivity(intent);
            } else {
                // Fallback to web browser maps
                Intent webIntent = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination) + "&travelmode=walking"));
                webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(webIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Could not launch Google Maps: " + e.getMessage());
        }
    }

    // ── Callbacks ──────────────────────────────────────────────────────────

    private void notifyStep(String instruction) {
        if (stepCallback != null && active) {
            new android.os.Handler(Looper.getMainLooper()).post(() ->
                    stepCallback.onStep(instruction, false));
        }
    }

    private void notifyError(String message) {
        if (stepCallback != null) {
            new android.os.Handler(Looper.getMainLooper()).post(() ->
                    stepCallback.onStep(message, true));
        }
        active = false;
    }

    // ── Data class ─────────────────────────────────────────────────────────

    private static final class RouteStep {
        final String instruction;
        final double endLat;
        final double endLng;
        RouteStep(String instruction, double endLat, double endLng) {
            this.instruction = instruction;
            this.endLat = endLat;
            this.endLng = endLng;
        }
    }
}
