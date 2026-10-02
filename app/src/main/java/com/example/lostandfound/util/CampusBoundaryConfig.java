package com.example.lostandfound.util;

import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Authoritative geographic configuration for the National Institute of Technology Tiruchirappalli
 * (NIT Trichy), Tamil Nadu, India campus boundary.
 *
 * Source: OpenStreetMap (OSM) Way 100857261 ("National Institute of Technology Trichy", Thuvakudi,
 * Tiruchirappalli, Tamil Nadu 620015, India).
 * Bounding Box: Latitude [10.7517140, 10.7751656], Longitude [78.8041608, 78.8266969].
 */
public final class CampusBoundaryConfig {

    private CampusBoundaryConfig() {}

    public static final String CAMPUS_NAME = "National Institute of Technology Tiruchirappalli (NIT Trichy)";

    // Central landmark: Administrative quad & central campus
    public static final double CENTER_LATITUDE = 10.763385;
    public static final double CENTER_LONGITUDE = 78.815029;
    public static final LatLng CAMPUS_CENTER = new LatLng(CENTER_LATITUDE, CENTER_LONGITUDE);

    public static final float DEFAULT_ZOOM = 16.0f;
    public static final float MIN_ZOOM = 14.5f;
    public static final float MAX_ZOOM = 20.0f;

    // Bounding box limits with safety envelope for camera framing
    public static final double BBOX_LAT_MIN = 10.7517140;
    public static final double BBOX_LAT_MAX = 10.7751656;
    public static final double BBOX_LNG_MIN = 78.8041608;
    public static final double BBOX_LNG_MAX = 78.8266969;

    // Camera target constraint envelope (allows slight visual breathing room around campus edges)
    public static final LatLngBounds CAMERA_BOUNDS = new LatLngBounds(
            new LatLng(10.7480, 78.8000),
            new LatLng(10.7780, 78.8300)
    );

    // Exact NIT Trichy campus polygon vertices (Latitude, Longitude) from OSM Way 100857261
    private static final double[][] POLYGON_VERTICES = {
            {10.7643598, 78.8041608},
            {10.7636660, 78.8053598},
            {10.7612979, 78.8073470},
            {10.7600133, 78.8086388},
            {10.7589581, 78.8100640},
            {10.7580787, 78.8112225},
            {10.7575679, 78.8119629},
            {10.7571655, 78.8127349},
            {10.7568727, 78.8132290},
            {10.7555905, 78.8156994},
            {10.7547261, 78.8173996},
            {10.7540632, 78.8188680},
            {10.7517140, 78.8260312},
            {10.7559094, 78.8266969},
            {10.7629959, 78.8246711},
            {10.7631047, 78.8246130},
            {10.7675741, 78.8222254},
            {10.7729137, 78.8204561},
            {10.7731079, 78.8179952},
            {10.7749224, 78.8169095},
            {10.7749328, 78.8167500},
            {10.7751656, 78.8138687},
            {10.7701651, 78.8123159},
            {10.7686944, 78.8122892},
            {10.7687813, 78.8118212},
            {10.7689078, 78.8114457},
            {10.7686654, 78.8114457},
            {10.7684862, 78.8113062},
            {10.7678960, 78.8117032},
            {10.7667155, 78.8119392},
            {10.7674164, 78.8084523},
            {10.7669316, 78.8083021},
            {10.7671529, 78.8076369},
            {10.7664256, 78.8073043},
            {10.7665100, 78.8066606},
            {10.7663202, 78.8066392},
            {10.7643598, 78.8041608}
    };

    public static List<LatLng> getCampusPolygon() {
        List<LatLng> points = new ArrayList<>(POLYGON_VERTICES.length);
        for (double[] vertex : POLYGON_VERTICES) {
            points.add(new LatLng(vertex[0], vertex[1]));
        }
        return Collections.unmodifiableList(points);
    }

    /**
     * Determines whether the given latitude/longitude coordinates fall strictly within
     * the verified NIT Trichy campus boundary using the ray-casting algorithm.
     *
     * @param latitude  Latitude in decimal degrees
     * @param longitude Longitude in decimal degrees
     * @return true if point is inside campus, false otherwise
     */
    public static boolean isInsideCampus(Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return false;
        }

        // Fast bounding-box rejection
        if (latitude < BBOX_LAT_MIN || latitude > BBOX_LAT_MAX
                || longitude < BBOX_LNG_MIN || longitude > BBOX_LNG_MAX) {
            return false;
        }

        boolean inside = false;
        int n = POLYGON_VERTICES.length;
        int j = n - 1;

        for (int i = 0; i < n; i++) {
            double lati = POLYGON_VERTICES[i][0];
            double lngi = POLYGON_VERTICES[i][1];
            double latj = POLYGON_VERTICES[j][0];
            double lngj = POLYGON_VERTICES[j][1];

            if ((lati > latitude) != (latj > latitude)) {
                double intersectLng = lngi + (latitude - lati) * (lngj - lngi) / (latj - lati);
                if (longitude < intersectLng) {
                    inside = !inside;
                }
            }
            j = i;
        }

        return inside;
    }

    public static String getOutOfBoundsErrorMessage() {
        return "Location is outside NIT Trichy campus. Pins must be placed within campus boundaries.";
    }
}
