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
 * NavigationGuide — provides walking turn-by-turn instructions using the
 * Google Maps Directions API. Camera object-detection guidance runs alongside;
 * navigation steps are PRIORITY_INFO and can be interrupted by object alerts.
 *
 * SECURITY: mapsApiKey must never be hard-coded. It is loaded from AppSettings
 * (user-entered in SettingsDialog and stored in SharedPreferences).
 *
 * LIMITATION: Walking routes may not include all sidewalks or pedestrian paths.
 * Navigation complements camera awareness; it does not replace it.
 */
final class NavigationGuide {
    private static final String TAG = "NavigationGuide";
    private static final String DIRECTIONS_BASE =
            "https://maps.googleapis.com/maps/api/directions/json";
    private static final float STEP_ARRIVAL_METRES = 20f;

    interface StepCallback {
        /** Called on the main thread with the next step instruction, or an error. */
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
        http = new OkHttpClient();
        executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Start navigation to a destination.
     *
     * @param destination  Free-text destination (e.g. "Nehru Park, Delhi")
     * @param mapsApiKey   Google Maps API key (from user settings, never hard-coded)
     * @param callback     Receives step instructions on the main thread
     */
    void start(String destination, String mapsApiKey, StepCallback callback) {
        if (destination == null || destination.trim().isEmpty()) {
            callback.onStep("Please enter a destination.", true);
            return;
        }
        if (mapsApiKey == null || mapsApiKey.isEmpty()) {
            callback.onStep("Maps API key is not set. Add it in Settings.", true);
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            callback.onStep("Location permission is required for navigation.", true);
            return;
        }
        this.stepCallback = callback;
        this.active = true;

        locationClient = LocationServices.getFusedLocationProviderClient(context);
        locationClient.getLastLocation().addOnSuccessListener(location -> {
            if (location != null) {
                fetchRoute(location, destination, mapsApiKey);
            } else {
                startLocationUpdates(destination, mapsApiKey);
            }
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

    // ── Location updates ───────────────────────────────────────────────────

    @SuppressWarnings("MissingPermission")
    private void startLocationUpdates(String destination, String mapsApiKey) {
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000)
                .setMinUpdateIntervalMillis(3000)
                .build();
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult lr) {
                Location loc = lr.getLastLocation();
                if (loc != null && steps.isEmpty()) {
                    fetchRoute(loc, destination, mapsApiKey);
                } else if (loc != null) {
                    updateProgress(loc);
                }
            }
        };
        locationClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
    }

    // ── Route fetching ─────────────────────────────────────────────────────

    private void fetchRoute(Location origin, String destination, String mapsApiKey) {
        executor.execute(() -> {
            try {
                String originStr = origin.getLatitude() + "," + origin.getLongitude();
                String destEnc  = URLEncoder.encode(destination, "UTF-8");
                String url = DIRECTIONS_BASE
                        + "?origin=" + originStr
                        + "&destination=" + destEnc
                        + "&mode=walking"
                        + "&key=" + mapsApiKey;

                Request request = new Request.Builder().url(url).get().build();
                try (Response response = http.newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        notifyError("Could not reach the Maps service.");
                        return;
                    }
                    List<RouteStep> parsed = parseDirections(response.body().string());
                    if (parsed.isEmpty()) {
                        notifyError("No walking route found to " + destination + ".");
                        return;
                    }
                    steps = parsed;
                    currentStep = 0;
                    lastLocation = origin;
                    notifyStep(steps.get(0).instruction);
                    startLocationUpdates(destination, mapsApiKey);
                }
            } catch (IOException e) {
                Log.w(TAG, "Route fetch failed: " + e.getMessage());
                notifyError("Navigation is unavailable. Check your internet connection.");
            } catch (Exception e) {
                Log.w(TAG, "Route parse failed: " + e.getMessage());
                notifyError("Could not parse route.");
            }
        });
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

    // ── Response parsing ───────────────────────────────────────────────────

    private static List<RouteStep> parseDirections(String json) throws Exception {
        List<RouteStep> result = new ArrayList<>();
        JSONObject root   = new JSONObject(json);
        JSONArray  routes = root.optJSONArray("routes");
        if (routes == null || routes.length() == 0) return result;
        JSONArray legs = routes.getJSONObject(0).optJSONArray("legs");
        if (legs == null || legs.length() == 0) return result;
        JSONArray stepsArr = legs.getJSONObject(0).optJSONArray("steps");
        if (stepsArr == null) return result;
        for (int i = 0; i < stepsArr.length(); i++) {
            JSONObject s = stepsArr.getJSONObject(i);
            String html  = s.optString("html_instructions", "");
            String text  = html.replaceAll("<[^>]+>", "").trim();
            JSONObject endLoc = s.optJSONObject("end_location");
            double lat = endLoc != null ? endLoc.optDouble("lat", 0) : 0;
            double lng = endLoc != null ? endLoc.optDouble("lng", 0) : 0;
            if (!text.isEmpty()) result.add(new RouteStep(text, lat, lng));
        }
        return result;
    }

    // ── Callbacks on main thread ───────────────────────────────────────────

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
