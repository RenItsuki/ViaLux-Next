package com.visionguide.phone;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * NavigationGuide — provides walking turn-by-turn spoken guidance.
 *
 * Dual-Engine Architecture:
 *   1. Google Maps Directions API: Used if an API key is provided and authorized.
 *   2. OpenStreetMap / OSRM Walking Engine: Free built-in fallback requiring zero API keys,
 *      geocoding destinations via Nominatim and routing over public pedestrian paths.
 *
 * Navigation instructions are PRIORITY_INFO and can be interrupted by obstacle alerts.
 */
final class NavigationGuide {
    private static final String TAG = "NavigationGuide";
    private static final String GOOGLE_DIRECTIONS_BASE =
            "https://maps.googleapis.com/maps/api/directions/json";
    private static final String NOMINATIM_BASE =
            "https://nominatim.openstreetmap.org/search";
    private static final String OSRM_WALKING_BASE =
            "https://router.project-osrm.org/route/v1/walking";
    private static final float STEP_ARRIVAL_METRES = 20f;

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
    private StepCallback stepCallback;
    private boolean active = false;

    NavigationGuide(Context context) {
        this.context = context.getApplicationContext();
        this.http = new OkHttpClient();
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Start walking navigation to a destination.
     *
     * @param destination  Address or place name (e.g. "Pharmacy", "Library", "Metro station").
     * @param mapsApiKey   Optional Google Maps API key (if empty or invalid, automatically uses OpenStreetMap).
     * @param callback     Receives step-by-step guidance on the main thread.
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

        locationClient = LocationServices.getFusedLocationProviderClient(context);
        locationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                fetchWalkingRoute(location, destination.trim(), mapsApiKey);
            } else {
                startLocationUpdates(destination.trim(), mapsApiKey);
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

    // ── Route resolution (Google Maps with OSRM fallback) ───────────────────

    private void fetchWalkingRoute(Location origin, String destination, String mapsApiKey) {
        executor.execute(() -> {
            List<RouteStep> parsedSteps = null;

            // 1. Try Google Maps if an API key is configured
            if (mapsApiKey != null && !mapsApiKey.trim().isEmpty()) {
                try {
                    parsedSteps = queryGoogleDirections(origin, destination, mapsApiKey.trim());
                } catch (Exception e) {
                    Log.w(TAG, "Google Maps route query failed; falling back to OpenStreetMap: " + e.getMessage());
                }
            }

            // 2. Fall back to OpenStreetMap / OSRM (zero-config, keyless)
            if (parsedSteps == null || parsedSteps.isEmpty()) {
                try {
                    parsedSteps = queryOsrmWalking(origin, destination);
                } catch (Exception e) {
                    Log.w(TAG, "OSRM route query failed: " + e.getMessage());
                }
            }

            if (parsedSteps == null || parsedSteps.isEmpty()) {
                notifyError("Could not find walking route to \"" + destination + "\".");
                return;
            }

            steps = parsedSteps;
            currentStep = 0;
            lastLocation = origin;
            notifyStep("Route found: " + steps.get(0).instruction);
            startLocationUpdates(destination, mapsApiKey);
        });
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
            if (!"OK".equals(root.optString("status"))) {
                Log.w(TAG, "Google Maps status: " + root.optString("status"));
                return null;
            }
            return parseGoogleDirections(root);
        }
    }

    private static List<RouteStep> parseGoogleDirections(JSONObject root) {
        List<RouteStep> result = new ArrayList<>();
        JSONArray routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) return result;
        JSONArray legs = routes.optJSONObject(0).optJSONArray("legs");
        if (legs == null || legs.length() == 0) return result;
        JSONArray stepsArr = legs.optJSONObject(0).optJSONArray("steps");
        if (stepsArr == null) return result;

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

    // ── OpenStreetMap / OSRM (Zero-Key Fallback) ────────────────────────────

    private List<RouteStep> queryOsrmWalking(Location origin, String destination) throws Exception {
        // Step A: Geocode destination name with Nominatim
        double[] destCoords = geocodeNominatim(destination, origin);
        if (destCoords == null) return null;

        // Step B: Query OSRM walking engine
        String url = OSRM_WALKING_BASE + "/"
                + origin.getLongitude() + "," + origin.getLatitude() + ";"
                + destCoords[1] + "," + destCoords[0]
                + "?steps=true&overview=false";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "ViaLux-VisionGuide/2.0")
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

                String instruction = formatOsrmInstruction(maneuver, streetName, distance);
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

    private double[] geocodeNominatim(String query, Location near) {
        try {
            String url = NOMINATIM_BASE
                    + "?q=" + URLEncoder.encode(query, "UTF-8")
                    + "&format=json&limit=1"
                    + "&lat=" + near.getLatitude() + "&lon=" + near.getLongitude();

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "ViaLux-VisionGuide/2.0")
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) return null;
                JSONArray arr = new JSONArray(response.body().string());
                if (arr.length() == 0) return null;
                JSONObject item = arr.getJSONObject(0);
                return new double[]{item.getDouble("lat"), item.getDouble("lon")};
            }
        } catch (Exception e) {
            Log.w(TAG, "Nominatim geocode failed: " + e.getMessage());
            return null;
        }
    }

    private static String formatOsrmInstruction(JSONObject maneuver, String streetName, double distanceMetres) {
        if (maneuver == null) return "";
        String type = maneuver.optString("type", "");
        String modifier = maneuver.optString("modifier", "");
        String street = streetName.isEmpty() ? "the path" : streetName;
        int dist = (int) Math.round(distanceMetres);

        if ("arrive".equalsIgnoreCase(type)) {
            return "You have reached your destination.";
        }
        if ("depart".equalsIgnoreCase(type)) {
            return "Start walking on " + street + " for " + dist + " metres.";
        }
        if (!modifier.isEmpty()) {
            return "Turn " + modifier.replace("_", " ") + " onto " + street + " for " + dist + " metres.";
        }
        return "Continue straight on " + street + " for " + dist + " metres.";
    }

    // ── Location Tracking ──────────────────────────────────────────────────

    @SuppressWarnings("MissingPermission")
    private void startLocationUpdates(String destination, String mapsApiKey) {
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 4000)
                .setMinUpdateIntervalMillis(2500)
                .build();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult lr) {
                Location loc = lr.getLastLocation();
                if (loc != null && steps.isEmpty()) {
                    fetchWalkingRoute(loc, destination, mapsApiKey);
                } else if (loc != null) {
                    updateProgress(loc);
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

    private void notifyStep(String instruction) {
        if (stepCallback != null && active) {
            android.os.Handler main = new android.os.Handler(Looper.getMainLooper());
            main.post(() -> stepCallback.onStep(instruction, false));
        }
    }

    private void notifyError(String message) {
        if (stepCallback != null) {
            android.os.Handler main = new android.os.Handler(Looper.getMainLooper());
            main.post(() -> stepCallback.onStep(message, true));
        }
        active = false;
    }

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
