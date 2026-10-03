package com.example.lostandfound.ui.map;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import com.example.lostandfound.R;
import com.example.lostandfound.databinding.ActivityLocationPickerBinding;
import com.example.lostandfound.util.CampusBoundaryConfig;
import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.OnMapReadyCallback;
import com.google.android.gms.maps.SupportMapFragment;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import java.util.Locale;

public class LocationPickerActivity extends AppCompatActivity implements OnMapReadyCallback {
    public static final String EXTRA_MODE = "extra_mode";
    public static final String MODE_PICK_FOUND_LOCATION = "mode_pick_found";
    public static final String MODE_PICK_LOST_LOCATION = "mode_pick_lost";
    public static final String MODE_PROPOSE_MEETING = "mode_propose_meeting";
    public static final String MODE_VIEW_LOCATION = "mode_view_location";

    public static final String EXTRA_INITIAL_LAT = "extra_initial_lat";
    public static final String EXTRA_INITIAL_LNG = "extra_initial_lng";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_NOTE = "extra_note";

    public static final String RESULT_LATITUDE = "result_latitude";
    public static final String RESULT_LONGITUDE = "result_longitude";
    public static final String RESULT_NOTE = "result_note";

    // Standard marker label for meeting locations
    public static final String LABEL_MEETING = "Meet";
    public static final String LABEL_FOUND_ITEM = "Where item was found";

    private ActivityLocationPickerBinding binding;
    private GoogleMap googleMap;
    private Marker currentMarker;
    private LatLng selectedLatLng;
    private LatLng dragStartLatLng;

    private String currentMode = MODE_PICK_FOUND_LOCATION;
    private Double initialLat = null;
    private Double initialLng = null;
    private String customTitle = null;
    private String customNote = null;

    private final ActivityResultLauncher<String[]> locationPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(),
            result -> {
                boolean fineGranted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_FINE_LOCATION));
                boolean coarseGranted = Boolean.TRUE.equals(result.get(Manifest.permission.ACCESS_COARSE_LOCATION));
                if (fineGranted || coarseGranted) {
                    enableMyLocationOnMap(true);
                } else {
                    Toast.makeText(this, "Location permission not granted. You can still tap or drag the pin anywhere within campus.", Toast.LENGTH_LONG).show();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLocationPickerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        parseIntentExtras();
        setupUIForMode();

        binding.btnPickerBack.setOnClickListener(v -> finish());
        binding.btnCloseViewOnly.setOnClickListener(v -> finish());

        SupportMapFragment mapFragment = (SupportMapFragment) getSupportFragmentManager().findFragmentById(R.id.mapFragment);
        if (mapFragment == null) {
            mapFragment = SupportMapFragment.newInstance();
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.mapContainer, mapFragment)
                    .commitNow();
        }
        mapFragment.getMapAsync(this);

        binding.fabMyLocation.setOnClickListener(v -> handleMyLocationButton());
        binding.btnConfirmLocation.setOnClickListener(v -> handleConfirmSelection());
    }

    private void parseIntentExtras() {
        if (getIntent().hasExtra(EXTRA_MODE)) {
            currentMode = getIntent().getStringExtra(EXTRA_MODE);
        }
        if (getIntent().hasExtra(EXTRA_INITIAL_LAT)) {
            initialLat = getIntent().getDoubleExtra(EXTRA_INITIAL_LAT, CampusBoundaryConfig.CENTER_LATITUDE);
        }
        if (getIntent().hasExtra(EXTRA_INITIAL_LNG)) {
            initialLng = getIntent().getDoubleExtra(EXTRA_INITIAL_LNG, CampusBoundaryConfig.CENTER_LONGITUDE);
        }
        customTitle = getIntent().getStringExtra(EXTRA_TITLE);
        customNote = getIntent().getStringExtra(EXTRA_NOTE);
    }

    private void setupUIForMode() {
        if (MODE_VIEW_LOCATION.equals(currentMode)) {
            String displayTitle = customTitle != null ? customTitle : LABEL_FOUND_ITEM;
            binding.tvPickerTitle.setText(displayTitle);
            binding.tvPickerSubtitle.setVisibility(View.VISIBLE);
            binding.tvPickerSubtitle.setText(CampusBoundaryConfig.CAMPUS_NAME);
            binding.layoutSafetyNotice.setVisibility(View.GONE);
            binding.layoutMeetingNote.setVisibility(View.GONE);
            binding.btnConfirmLocation.setVisibility(View.GONE);
            binding.btnCloseViewOnly.setVisibility(View.VISIBLE);
            binding.fabMyLocation.setVisibility(View.GONE);
        } else if (MODE_PROPOSE_MEETING.equals(currentMode)) {
            binding.tvPickerTitle.setText(R.string.location_propose_title);
            // No "NIT Trichy Campus • Meet" subtext here; the title carries it.
            binding.tvPickerSubtitle.setVisibility(View.GONE);
            binding.layoutSafetyNotice.setVisibility(View.VISIBLE);
            binding.tvSafetyNoticeText.setText("Safety First: Pick an active, well-lit public campus location (e.g. Octagon, Library entrance, or Admin Quad). Pins must stay inside campus grounds.");
            binding.layoutMeetingNote.setVisibility(View.VISIBLE);
            binding.btnConfirmLocation.setText(R.string.location_propose_confirm);
            if (customNote != null) {
                binding.etMeetingNote.setText(customNote);
            }
        } else {
            // Location pick for a report. The labels follow the report type:
            // a lost item was lost somewhere, a found item was found somewhere.
            // Everything else in this branch (pin placement, campus validation,
            // confirm/result handling) is type-agnostic and shared.
            boolean isLostPick = MODE_PICK_LOST_LOCATION.equals(currentMode);
            binding.tvPickerTitle.setText(isLostPick ? "Pin Where Item Was Lost" : "Pin Where Item Was Found");
            binding.tvPickerSubtitle.setVisibility(View.VISIBLE);
            binding.tvPickerSubtitle.setText("NIT Trichy Campus");
            binding.layoutSafetyNotice.setVisibility(View.VISIBLE);
            binding.tvSafetyNoticeText.setText(isLostPick
                    ? "Campus Safety Notice: Mark the approximate public area inside NIT Trichy where you lost it. Do not pin dorm rooms or sensitive locations."
                    : "Campus Safety Notice: Mark the approximate public area inside NIT Trichy where you found it. Do not pin dorm rooms or sensitive locations.");
            binding.layoutMeetingNote.setVisibility(View.GONE);
            binding.btnConfirmLocation.setText(isLostPick ? "Confirm Lost Location" : "Confirm Found Location");
        }
    }

    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;
        googleMap.getUiSettings().setZoomControlsEnabled(true);
        googleMap.getUiSettings().setCompassEnabled(true);

        // Restrict camera movement and zoom to stay focused on NIT Trichy campus
        googleMap.setLatLngBoundsForCameraTarget(CampusBoundaryConfig.CAMERA_BOUNDS);
        googleMap.setMinZoomPreference(CampusBoundaryConfig.MIN_ZOOM);
        googleMap.setMaxZoomPreference(CampusBoundaryConfig.MAX_ZOOM);

        boolean isViewOnly = MODE_VIEW_LOCATION.equals(currentMode);
        boolean isMeeting = MODE_PROPOSE_MEETING.equals(currentMode) || LABEL_MEETING.equals(customTitle);

        LatLng targetPosition;
        if (initialLat != null && initialLng != null) {
            targetPosition = new LatLng(initialLat, initialLng);
            String markerLabel = isMeeting ? LABEL_MEETING : (customTitle != null ? customTitle : LABEL_FOUND_ITEM);
            setOrUpdateMarker(targetPosition, markerLabel, !isViewOnly);
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(targetPosition, CampusBoundaryConfig.DEFAULT_ZOOM));
        } else {
            // Initial camera at NIT Trichy campus center
            targetPosition = CampusBoundaryConfig.CAMPUS_CENTER;
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(targetPosition, CampusBoundaryConfig.DEFAULT_ZOOM));
            if (!isViewOnly) {
                // Drop default pin at NIT Trichy campus center
                String markerLabel = isMeeting ? LABEL_MEETING : "Selected Pin";
                setOrUpdateMarker(targetPosition, markerLabel, true);
            }
        }

        if (!isViewOnly) {
            googleMap.setOnMapClickListener(latLng -> {
                if (!CampusBoundaryConfig.isInsideCampus(latLng.latitude, latLng.longitude)) {
                    Toast.makeText(this, CampusBoundaryConfig.getOutOfBoundsErrorMessage(), Toast.LENGTH_SHORT).show();
                    return; // Retain previous valid pin
                }
                String markerLabel = isMeeting ? LABEL_MEETING : "Selected Pin";
                setOrUpdateMarker(latLng, markerLabel, true);
            });

            googleMap.setOnMarkerDragListener(new GoogleMap.OnMarkerDragListener() {
                @Override
                public void onMarkerDragStart(@NonNull Marker marker) {
                    dragStartLatLng = marker.getPosition();
                }

                @Override
                public void onMarkerDrag(@NonNull Marker marker) {
                    updateCoordinateText(marker.getPosition());
                }

                @Override
                public void onMarkerDragEnd(@NonNull Marker marker) {
                    LatLng endPos = marker.getPosition();
                    if (!CampusBoundaryConfig.isInsideCampus(endPos.latitude, endPos.longitude)) {
                        Toast.makeText(LocationPickerActivity.this, CampusBoundaryConfig.getOutOfBoundsErrorMessage(), Toast.LENGTH_SHORT).show();
                        // Revert back to previous valid position
                        if (selectedLatLng != null) {
                            marker.setPosition(selectedLatLng);
                            updateCoordinateText(selectedLatLng);
                        } else if (dragStartLatLng != null) {
                            marker.setPosition(dragStartLatLng);
                            selectedLatLng = dragStartLatLng;
                            updateCoordinateText(selectedLatLng);
                        }
                        return;
                    }
                    selectedLatLng = endPos;
                    updateCoordinateText(selectedLatLng);
                }
            });
        }
    }

    private void setOrUpdateMarker(LatLng latLng, String title, boolean draggable) {
        selectedLatLng = latLng;
        boolean isMeeting = MODE_PROPOSE_MEETING.equals(currentMode) || LABEL_MEETING.equals(customTitle);
        String finalTitle = isMeeting ? LABEL_MEETING : title;

        if (currentMarker != null) {
            currentMarker.setPosition(latLng);
            currentMarker.setTitle(finalTitle);
        } else if (googleMap != null) {
            MarkerOptions options = new MarkerOptions()
                    .position(latLng)
                    .title(finalTitle)
                    .draggable(draggable)
                    .icon(BitmapDescriptorFactory.defaultMarker(
                            isMeeting
                                    ? BitmapDescriptorFactory.HUE_AZURE
                                    : BitmapDescriptorFactory.HUE_RED
                    ));
            currentMarker = googleMap.addMarker(options);
            if (currentMarker != null && MODE_VIEW_LOCATION.equals(currentMode)) {
                currentMarker.showInfoWindow();
            }
        }
        updateCoordinateText(latLng);
    }

    private void updateCoordinateText(LatLng latLng) {
        if (latLng == null) return;
        boolean inside = CampusBoundaryConfig.isInsideCampus(latLng.latitude, latLng.longitude);
        String coords = String.format(Locale.US, "Coordinates: %.5f, %.5f%s",
                latLng.latitude, latLng.longitude,
                inside ? "" : " (Outside NIT Trichy boundary)");
        if (MODE_VIEW_LOCATION.equals(currentMode) && customNote != null && !customNote.trim().isEmpty()) {
            coords += "\nNote: " + customNote.trim();
        }
        binding.tvSelectedCoordinates.setText(coords);
    }

    private void handleMyLocationButton() {
        if (hasLocationPermission()) {
            enableMyLocationOnMap(true);
        } else {
            // Request permission explicitly only on tap
            locationPermissionLauncher.launch(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            });
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @SuppressLint("MissingPermission")
    private void enableMyLocationOnMap(boolean animate) {
        if (!hasLocationPermission() || googleMap == null) return;

        try {
            googleMap.setMyLocationEnabled(true);
            LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            Location bestLocation = null;
            if (locationManager != null) {
                Location gpsLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                Location netLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                if (gpsLoc != null && netLoc != null) {
                    bestLocation = gpsLoc.getTime() > netLoc.getTime() ? gpsLoc : netLoc;
                } else if (gpsLoc != null) {
                    bestLocation = gpsLoc;
                } else {
                    bestLocation = netLoc;
                }
            }

            if (bestLocation != null) {
                LatLng currentPos = new LatLng(bestLocation.getLatitude(), bestLocation.getLongitude());
                if (!CampusBoundaryConfig.isInsideCampus(currentPos.latitude, currentPos.longitude)) {
                    Toast.makeText(this, "Your current GPS location is outside NIT Trichy campus. Please place a pin inside campus grounds.", Toast.LENGTH_LONG).show();
                    return;
                }

                if (animate) {
                    googleMap.animateCamera(CameraUpdateFactory.newLatLngZoom(currentPos, 16.5f));
                }
                if (!MODE_VIEW_LOCATION.equals(currentMode)) {
                    boolean isMeeting = MODE_PROPOSE_MEETING.equals(currentMode) || LABEL_MEETING.equals(customTitle);
                    setOrUpdateMarker(currentPos, isMeeting ? LABEL_MEETING : "Selected Pin", true);
                }
            }
        } catch (SecurityException ignored) {}
    }

    private void handleConfirmSelection() {
        if (selectedLatLng == null) {
            Toast.makeText(this, "Please select a location on the map first.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!CampusBoundaryConfig.isInsideCampus(selectedLatLng.latitude, selectedLatLng.longitude)) {
            Toast.makeText(this, CampusBoundaryConfig.getOutOfBoundsErrorMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        Intent data = new Intent();
        data.putExtra(RESULT_LATITUDE, selectedLatLng.latitude);
        data.putExtra(RESULT_LONGITUDE, selectedLatLng.longitude);

        if (MODE_PROPOSE_MEETING.equals(currentMode)) {
            String note = binding.etMeetingNote.getText() != null ? binding.etMeetingNote.getText().toString().trim() : "";
            if (note.length() > 250) {
                Toast.makeText(this, "Meeting note cannot exceed 250 characters.", Toast.LENGTH_SHORT).show();
                return;
            }
            data.putExtra(RESULT_NOTE, note);
        }

        setResult(RESULT_OK, data);
        finish();
    }
}
