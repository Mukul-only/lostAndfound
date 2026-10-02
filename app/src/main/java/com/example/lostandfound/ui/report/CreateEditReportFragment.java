package com.example.lostandfound.ui.report;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.lostandfound.BuildConfig;
import com.example.lostandfound.MainActivity;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Report;
import com.example.lostandfound.data.model.RpcResponse;
import com.example.lostandfound.data.repository.ReportRepository;
import com.example.lostandfound.databinding.FragmentCreateEditReportBinding;
import com.example.lostandfound.ui.common.PhotoSourcePicker;
import com.example.lostandfound.ui.map.LocationPickerActivity;
import com.example.lostandfound.util.CampusBoundaryConfig;
import com.example.lostandfound.ui.common.DropdownStyling;
import com.example.lostandfound.util.DateUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Calendar;
import java.util.Locale;

/**
 * The Create/Edit report form, hosted as a tab inside MainActivity.
 *
 * Navigation ownership: MainActivity owns the bottom bar, tab selection, Back
 * and the unsaved-changes guard. This Fragment only owns the form, so the
 * bottom navigation is never duplicated here.
 */
public class CreateEditReportFragment extends Fragment {
    public static final String EXTRA_EDIT_MODE = "extra_edit_mode";
    public static final String EXTRA_REPORT_ID = "report_id";
    public static final String EXTRA_REPORT_TYPE = "type";

    /** Camera captures land in this subdirectory of the app cache; the
        FileProvider exposes only this path. */
    private static final String CAPTURE_DIR = "captures/reports";
    private static final int MAX_DIMENSION = 1024;
    private static final int JPEG_QUALITY = 80;

    private FragmentCreateEditReportBinding binding;
    private ReportRepository reportRepository;

    private boolean isEditMode = false;
    private String editingReportId = null;
    private String currentType = Report.TYPE_LOST;
    /** Type requested by the host before this view existed. */
    private String pendingType = null;
    /** True while the toggle is being set from code, so the dirty flag is not
        raised by a programmatic type change (e.g. Home's LOST/FOUND action). */
    private boolean applyingInitialType = false;

    private byte[] selectedImageBytes = null;
    private String selectedImageExtension = "jpg";

    private Double selectedLatitude = null;
    private Double selectedLongitude = null;
    private String storedIncidentTime = null; // Stored in canonical HH:mm format
    private String selectedIncidentDate = null; // Stored in canonical yyyy-MM-dd format

    // Selected dropdown positions (0 = placeholder)
    private int selectedCategoryPosition = 0;
    private int selectedLocationPosition = 0;

    /** Set by any user edit; guards nav-away and Back with a confirm dialog. */
    private boolean formDirty = false;

    private static final String[] CATEGORY_KEYS = {
            "", "ELECTRONICS", "CARDS_ID", "KEYS", "BAGS_WALLETS", "CLOTHING", "BOOKS_STATIONERY", "OTHER"
    };
    private static final String[] CATEGORY_LABELS = {
            "-- Select a Category --", "Electronics", "Cards & Student ID", "Keys", "Bags & Wallets", "Clothing", "Books", "Other"
    };

    private static final String[] LOCATION_KEYS = {
            "", "Library", "Student Center", "Science Hall", "Dining Hall", "Sports Complex", "Engineering Quad", "Dormitories", "Other"
    };
    private static final String[] LOCATION_LABELS = {
            "-- Select a Campus Location --", "Library", "Student Center", "Science Hall", "Dining Hall", "Sports Complex", "Engineering Quad", "Dormitories", "Other"
    };

    /** Shared gallery-or-camera chooser; owns the FileProvider capture contract. */
    private final PhotoSourcePicker photoPicker = new PhotoSourcePicker(
            this, CAPTURE_DIR, uri -> {
                if (getView() != null) {
                    binding.btnPickPhoto.setEnabled(true);
                    processSelectedImage(uri);
                }
            });

    private final ActivityResultLauncher<Intent> locationPickerLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        Intent data = result.getData();
                        if (result.getResultCode() == Activity.RESULT_OK && data != null) {
                            double lat = data.getDoubleExtra(LocationPickerActivity.RESULT_LATITUDE, 0.0);
                            double lng = data.getDoubleExtra(LocationPickerActivity.RESULT_LONGITUDE, 0.0);
                            if (CampusBoundaryConfig.isInsideCampus(lat, lng)) {
                                selectedLatitude = lat;
                                selectedLongitude = lng;
                                updatePinUI();
                                markDirty();
                            } else {
                                Toast.makeText(requireContext(), CampusBoundaryConfig.getOutOfBoundsErrorMessage(), Toast.LENGTH_SHORT).show();
                            }
                        }
                    });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentCreateEditReportBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        reportRepository = new ReportRepository(requireContext());

        parseArguments();
        restoreFormState(savedInstanceState);
        photoPicker.onRestoreInstanceState(savedInstanceState, "state_capture_path");
        photoPicker.purgeStaleCaptures();
        setupSpinners();
        restoreDropdownLabels();
        setupTypeToggle();
        setupDatePicker();
        setupTimePicker();
        setupRealTimeErrorClearing();
        setupPhotoPicker();
        setupMapPinPicker();
        setupSubmitButton();

        applyPendingType();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }

    /** Restores in-memory form state across recreation (texts and scroll
        restore themselves via view ids; the picked photo is not restorable
        across process death and must be re-picked). */
    private void restoreFormState(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        currentType = savedInstanceState.getString("state_type", currentType);
        selectedCategoryPosition = savedInstanceState.getInt("state_cat", 0);
        selectedLocationPosition = savedInstanceState.getInt("state_loc", 0);
        selectedIncidentDate = savedInstanceState.getString("state_date");
        storedIncidentTime = savedInstanceState.getString("state_time");
        if (savedInstanceState.containsKey("state_lat")) {
            selectedLatitude = savedInstanceState.getDouble("state_lat");
        }
        if (savedInstanceState.containsKey("state_lng")) {
            selectedLongitude = savedInstanceState.getDouble("state_lng");
        }
        formDirty = savedInstanceState.getBoolean("state_dirty", false);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("state_type", currentType);
        outState.putInt("state_cat", selectedCategoryPosition);
        outState.putInt("state_loc", selectedLocationPosition);
        outState.putString("state_date", selectedIncidentDate);
        outState.putString("state_time", storedIncidentTime);
        if (selectedLatitude != null) outState.putDouble("state_lat", selectedLatitude);
        if (selectedLongitude != null) outState.putDouble("state_lng", selectedLongitude);
        outState.putBoolean("state_dirty", formDirty);
        photoPicker.onSaveInstanceState(outState, "state_capture_path");
    }

    private void restoreDropdownLabels() {
        if (selectedCategoryPosition > 0 && selectedCategoryPosition < CATEGORY_LABELS.length) {
            binding.actvReportCategory.setText(CATEGORY_LABELS[selectedCategoryPosition], false);
            binding.tilReportCategory.setActivated(true);
        }
        if (selectedLocationPosition > 0 && selectedLocationPosition < LOCATION_LABELS.length) {
            binding.actvReportLocation.setText(LOCATION_LABELS[selectedLocationPosition], false);
            binding.tilReportLocation.setActivated(true);
        }
    }

    private void markDirty() {
        if (applyingInitialType) return;
        formDirty = true;
    }

    private void parseArguments() {
        Bundle args = getArguments();
        isEditMode = args != null && args.getBoolean(EXTRA_EDIT_MODE, false);
        editingReportId = args != null ? args.getString(EXTRA_REPORT_ID) : null;

        String passedType = args != null ? args.getString(EXTRA_REPORT_TYPE) : null;
        if (passedType != null) {
            pendingType = passedType;
        }

        if (isEditMode) {
            binding.tvCreateHeaderTitle.setText("Edit Report");
            binding.btnSubmitReport.setText("Update Report");
        }
    }

    /** Applies a type the host requested before (or right after) this view was
        built. Runs without raising the dirty flag so a pristine form opened via
        Home's LOST/FOUND action does not immediately ask to discard. */
    private void applyPendingType() {
        if (pendingType == null) return;
        String type = Report.TYPE_FOUND.equalsIgnoreCase(pendingType) ? Report.TYPE_FOUND : Report.TYPE_LOST;
        pendingType = null;
        if (currentType.equals(type)) return;
        currentType = type;
        applyingInitialType = true;
        binding.toggleType.check(type.equals(Report.TYPE_FOUND) ? R.id.btnToggleFound : R.id.btnToggleLost);
        binding.layoutFoundSpecifics.setVisibility(type.equals(Report.TYPE_FOUND) ? View.VISIBLE : View.GONE);
        updateTypeToggleStyles();
        updateMapPinPickerLabels();
        applyingInitialType = false;
    }

    private void setupMapPinPicker() {
        updateMapPinPickerLabels();

        binding.btnSetMapPin.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), LocationPickerActivity.class);
            intent.putExtra(LocationPickerActivity.EXTRA_MODE, LocationPickerActivity.MODE_PICK_FOUND_LOCATION);
            if (selectedLatitude != null && selectedLongitude != null) {
                intent.putExtra(LocationPickerActivity.EXTRA_INITIAL_LAT, selectedLatitude);
                intent.putExtra(LocationPickerActivity.EXTRA_INITIAL_LNG, selectedLongitude);
            }
            String pickerTitle = Report.TYPE_LOST.equals(currentType) ? "Last Seen Location" : "Where Item Was Found";
            intent.putExtra(LocationPickerActivity.EXTRA_TITLE, pickerTitle);
            locationPickerLauncher.launch(intent);
        });

        binding.btnRemoveMapPin.setOnClickListener(v -> {
            selectedLatitude = null;
            selectedLongitude = null;
            updatePinUI();
            markDirty();
        });

        updatePinUI();
    }

    private void updateMapPinPickerLabels() {
        // Only the label is swapped per report type. The explanatory paragraph
        // under it ("Drop a pin within NIT Trichy campus…") was removed along
        // with its view; the pin button and coordinates status say the same thing.
        if (Report.TYPE_LOST.equals(currentType)) {
            binding.tvMapPinLabel.setText("Last seen location (optional map pin)");
        } else {
            binding.tvMapPinLabel.setText("Where the item was found (optional map pin)");
        }
    }

    private void updatePinUI() {
        if (selectedLatitude != null && selectedLongitude != null) {
            binding.btnSetMapPin.setText("Change Map Pin");
            binding.btnRemoveMapPin.setVisibility(View.VISIBLE);
            binding.tvPinCoordinatesStatus.setText(String.format(Locale.US,
                    "Pinned at %.5f, %.5f (NIT Trichy)", selectedLatitude, selectedLongitude));
            binding.tvPinCoordinatesStatus.setTextColor(requireContext().getColor(R.color.spotify_green));
        } else {
            binding.btnSetMapPin.setText("Pin on Map");
            binding.btnRemoveMapPin.setVisibility(View.GONE);
            binding.tvPinCoordinatesStatus.setText("No pin set (approximate location from dropdown used)");
            binding.tvPinCoordinatesStatus.setTextColor(requireContext().getColor(R.color.spotify_text2));
        }
    }

    private void setupSpinners() {
        ArrayAdapter<String> catAdapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_dropdown_item_field, CATEGORY_LABELS);
        binding.actvReportCategory.setAdapter(catAdapter);

        ArrayAdapter<String> locAdapter = new ArrayAdapter<>(requireContext(), R.layout.spinner_dropdown_item_field, LOCATION_LABELS);
        binding.actvReportLocation.setAdapter(locAdapter);

        DropdownStyling.applyRoundedPopup(binding.actvReportCategory);
        DropdownStyling.applyRoundedPopup(binding.actvReportLocation);
    }

    private void onCategorySelected(int position) {
        if (position < 0) return;
        markDirty();
        selectedCategoryPosition = position;
        binding.tilReportCategory.setError(null);
        binding.tilReportCategory.setActivated(position > 0);
        if (position == 0) {
            // Show the floating hint instead of the placeholder label
            binding.actvReportCategory.setText("");
        }
    }

    private void onLocationSelected(int position) {
        if (position < 0) return;
        markDirty();
        selectedLocationPosition = position;
        binding.tilReportLocation.setError(null);
        binding.tilReportLocation.setActivated(position > 0);
        if (position == 0) {
            // Show the floating hint instead of the placeholder label
            binding.actvReportLocation.setText("");
        }
    }

    /** Segmented-control states: unselected options go quiet (transparent,
     * silver); the selected option elevates one surface step with the
     * LOST-red / FOUND-green voice. Transparent (not removed) strokes keep
     * bounds stable so switching never shifts layout. */
    private void updateTypeToggleStyles() {
        boolean lostChecked = binding.toggleType.getCheckedButtonId() == R.id.btnToggleLost;
        boolean foundChecked = binding.toggleType.getCheckedButtonId() == R.id.btnToggleFound;

        if (lostChecked) {
            binding.btnToggleLost.setBackgroundTintList(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_card)));
            binding.btnToggleLost.setStrokeColor(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_error)));
            binding.btnToggleLost.setTextColor(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_error)));
            binding.btnToggleLost.setIconTint(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_error)));
        } else {
            binding.btnToggleLost.setBackgroundTintList(ColorStateList.valueOf(requireContext().getColor(android.R.color.transparent)));
            binding.btnToggleLost.setStrokeColor(ColorStateList.valueOf(requireContext().getColor(android.R.color.transparent)));
            binding.btnToggleLost.setTextColor(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_text2)));
            binding.btnToggleLost.setIconTint(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_text2)));
        }

        if (foundChecked) {
            binding.btnToggleFound.setBackgroundTintList(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_card)));
            binding.btnToggleFound.setStrokeColor(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_green)));
            binding.btnToggleFound.setTextColor(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_green)));
            binding.btnToggleFound.setIconTint(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_green)));
        } else {
            binding.btnToggleFound.setBackgroundTintList(ColorStateList.valueOf(requireContext().getColor(android.R.color.transparent)));
            binding.btnToggleFound.setStrokeColor(ColorStateList.valueOf(requireContext().getColor(android.R.color.transparent)));
            binding.btnToggleFound.setTextColor(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_text2)));
            binding.btnToggleFound.setIconTint(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_text2)));
        }
    }

    private void setupTypeToggle() {
        binding.toggleType.check(Report.TYPE_FOUND.equals(currentType) ? R.id.btnToggleFound : R.id.btnToggleLost);
        binding.layoutFoundSpecifics.setVisibility(Report.TYPE_FOUND.equals(currentType) ? View.VISIBLE : View.GONE);
        updateTypeToggleStyles();

        binding.toggleType.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            markDirty();
            if (checkedId == R.id.btnToggleFound) {
                currentType = Report.TYPE_FOUND;
                binding.layoutFoundSpecifics.setVisibility(View.VISIBLE);
            } else {
                currentType = Report.TYPE_LOST;
                binding.layoutFoundSpecifics.setVisibility(View.GONE);
                // Clear found-specific validation errors when switching to LOST
                binding.tilPublicQuestion.setError(null);
                binding.tilFinderPrivateNotes.setError(null);
            }
            updateTypeToggleStyles();
            updateMapPinPickerLabels();
        });
    }

    private void setupDatePicker() {
        if (selectedIncidentDate == null) {
            selectedIncidentDate = DateUtils.getTodayIsoDate();
        }
        binding.etReportDate.setText(DateUtils.formatDisplayDateFromIso(selectedIncidentDate));
        binding.etReportDate.setOnClickListener(v -> {
            DatePickerDialog dialog = new DatePickerDialog(requireContext(),
                    R.style.ThemeOverlay_Foundit_Spotify_PickerDialog,
                    (view, y, m, d) -> {
                        selectedIncidentDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d);
                        binding.etReportDate.setText(DateUtils.formatDisplayDateFromIso(selectedIncidentDate));
                        binding.tilReportDate.setError(null);
                        markDirty();
                    }, yearOf(selectedIncidentDate), monthOf(selectedIncidentDate) - 1, dayOf(selectedIncidentDate));
            dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
            dialog.show();
        });
    }

    private int yearOf(String isoDate) {
        return Integer.parseInt(isoDate.substring(0, 4));
    }

    private int monthOf(String isoDate) {
        return Integer.parseInt(isoDate.substring(5, 7));
    }

    private int dayOf(String isoDate) {
        return Integer.parseInt(isoDate.substring(8, 10));
    }

    private void setupTimePicker() {
        // Repopulate after view recreation so a restored draft still shows it.
        if (storedIncidentTime != null) {
            binding.etReportTime.setText(DateUtils.formatDisplayTimeFromStorage(requireContext(), storedIncidentTime));
        }
        binding.etReportTime.setOnClickListener(v -> showTimePickerDialog());
    }

    private void showTimePickerDialog() {
        Calendar c = Calendar.getInstance();
        int initialHour = c.get(Calendar.HOUR_OF_DAY);
        int initialMinute = c.get(Calendar.MINUTE);

        if (storedIncidentTime != null && DateUtils.isStructuredTime(storedIncidentTime)) {
            try {
                String[] parts = storedIncidentTime.split(":");
                initialHour = Integer.parseInt(parts[0]);
                initialMinute = Integer.parseInt(parts[1]);
            } catch (Exception ignored) {}
        }

        boolean is24Hour = android.text.format.DateFormat.is24HourFormat(requireContext());
        TimePickerDialog dialog = new TimePickerDialog(requireContext(),
                R.style.ThemeOverlay_Foundit_Spotify_PickerDialog,
                (view, hourOfDay, minute) -> {
                    storedIncidentTime = DateUtils.formatTimeForStorage(hourOfDay, minute);
                    binding.etReportTime.setText(DateUtils.formatTimeForDisplay(requireContext(), hourOfDay, minute));
                    binding.tilReportTime.setError(null);
                    markDirty();
                }, initialHour, initialMinute, is24Hour);

        dialog.show();
    }

    private void setupRealTimeErrorClearing() {
        // Clear text input errors immediately on typing
        binding.etReportTitle.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                markDirty();
                if (binding.tilReportTitle.getError() != null && s.toString().trim().length() >= 3) {
                    binding.tilReportTitle.setError(null);
                }
            }
        });

        binding.etReportDescription.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                markDirty();
                if (binding.tilReportDescription.getError() != null && s.toString().trim().length() >= 5) {
                    binding.tilReportDescription.setError(null);
                }
            }
        });

        binding.etPublicQuestion.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                markDirty();
                if (binding.tilPublicQuestion.getError() != null && s.toString().trim().length() >= 3) {
                    binding.tilPublicQuestion.setError(null);
                }
            }
        });

        binding.etFinderPrivateNotes.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                markDirty();
                if (binding.tilFinderPrivateNotes.getError() != null && s.toString().trim().length() >= 2) {
                    binding.tilFinderPrivateNotes.setError(null);
                }
            }
        });

        // Clear dropdown errors immediately on a valid selection
        binding.actvReportCategory.setOnItemClickListener((parent, view, position, id) -> onCategorySelected(position));
        binding.actvReportLocation.setOnItemClickListener((parent, view, position, id) -> onLocationSelected(position));
    }

    private void setupPhotoPicker() {
        binding.ivPhotoPreview.setImageTintList(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_text2)));
        binding.btnPickPhoto.setOnClickListener(v -> {
            binding.btnPickPhoto.setEnabled(false);
            photoPicker.showSourceDialog(getString(R.string.report_photo_source_title),
                    getString(R.string.report_photo_source_subtitle));
        });
        binding.btnRemovePhoto.setOnClickListener(v -> clearSelectedImage());
        updatePhotoButton();
    }

    /** "Add photo" until one is chosen, then "Change photo". */
    private void updatePhotoButton() {
        binding.btnPickPhoto.setText(selectedImageBytes != null ? R.string.report_change_photo : R.string.report_add_photo);
    }

    private void clearSelectedImage() {
        selectedImageBytes = null;
        photoPicker.reset();
        markDirty();
        binding.ivPhotoPreview.setImageDrawable(null);
        binding.ivPhotoPreview.setImageTintList(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_text2)));
        binding.ivPhotoPreview.setImageResource(R.drawable.ic_photo);
        binding.btnRemovePhoto.setVisibility(View.GONE);
        updatePhotoButton();
    }

    private void processSelectedImage(Uri uri) {
        Bitmap bitmap = decodeScaledBitmap(uri);
        if (bitmap == null) {
            Toast.makeText(requireContext(), R.string.report_photo_load_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        // Camera files (and some gallery picks) carry EXIF rotation that a raw
        // decode ignores; without this a portrait shot lands on its side.
        bitmap = applyExifRotation(uri, bitmap);
        if (bitmap == null) {
            Toast.makeText(requireContext(), R.string.report_photo_load_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        // Sample-size decoding only halves, so finish with an exact cap: keeps
        // the upload payload the same size the gallery path always produced.
        bitmap = scaleToMaxDimension(bitmap, MAX_DIMENSION);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
        selectedImageBytes = baos.toByteArray();
        selectedImageExtension = "jpg";

        if (getView() == null) return;
        binding.ivPhotoPreview.setImageTintList(null);
        binding.ivPhotoPreview.setImageBitmap(bitmap);
        binding.btnRemovePhoto.setVisibility(View.VISIBLE);
        updatePhotoButton();
        markDirty();
    }

    private static Bitmap scaleToMaxDimension(Bitmap bitmap, int maxDim) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= maxDim && height <= maxDim) return bitmap;

        float ratio = (float) width / height;
        int targetWidth;
        int targetHeight;
        if (ratio > 1) {
            targetWidth = maxDim;
            targetHeight = (int) (maxDim / ratio);
        } else {
            targetHeight = maxDim;
            targetWidth = (int) (maxDim * ratio);
        }
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }

    /** Downsamples during decode so a 12MP capture never fully enters memory. */
    private Bitmap decodeScaledBitmap(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(is, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION);
            try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
                return BitmapFactory.decodeStream(is, null, opts);
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.w("CreateReport", "Image decode failed", e);
            return null;
        }
    }

    private static int sampleSizeFor(int width, int height, int maxDim) {
        int sample = 1;
        while (width / sample > maxDim * 2 || height / sample > maxDim * 2) {
            sample *= 2;
        }
        return sample;
    }

    private Bitmap applyExifRotation(Uri uri, Bitmap bitmap) {
        try (InputStream is = requireContext().getContentResolver().openInputStream(uri)) {
            if (is == null) return bitmap;
            ExifInterface exif = new ExifInterface(is);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees = 0;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) {
                degrees = 90;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_180) {
                degrees = 180;
            } else if (orientation == ExifInterface.ORIENTATION_ROTATE_270) {
                degrees = 270;
            }
            if (degrees == 0) return bitmap;

            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) bitmap.recycle();
            return rotated;
        } catch (Exception e) {
            // A missing/odd EXIF block must not cost us the photo.
            return bitmap;
        }
    }

    private void setupSubmitButton() {
        binding.btnSubmitReport.setOnClickListener(v -> handleSubmit());
    }

    private void handleSubmit() {
        binding.tvCreateError.setVisibility(View.GONE);

        View firstInvalidView = null;

        // 1. Title validation (Required, min 3 characters)
        String title = text(binding.etReportTitle.getText());
        if (title.length() < 3) {
            binding.tilReportTitle.setError("Item title is required (at least 3 characters).");
            firstInvalidView = binding.tilReportTitle;
        } else {
            binding.tilReportTitle.setError(null);
        }

        // 2. Category validation (Required, must select non-default item)
        String category = null;
        if (selectedCategoryPosition <= 0 || selectedCategoryPosition >= CATEGORY_KEYS.length || CATEGORY_KEYS[selectedCategoryPosition].isEmpty()) {
            binding.tilReportCategory.setError("Please select a category");
            binding.tilReportCategory.setActivated(true);
            if (firstInvalidView == null) {
                firstInvalidView = binding.tilReportCategory;
            }
        } else {
            binding.tilReportCategory.setError(null);
            binding.tilReportCategory.setActivated(false);
            category = CATEGORY_KEYS[selectedCategoryPosition];
        }

        // 3. Approximate Campus Location validation (Required)
        String campusLocation = null;
        if (selectedLocationPosition <= 0 || selectedLocationPosition >= LOCATION_KEYS.length || LOCATION_KEYS[selectedLocationPosition].isEmpty()) {
            binding.tilReportLocation.setError("Please select a campus location");
            binding.tilReportLocation.setActivated(true);
            if (firstInvalidView == null) {
                firstInvalidView = binding.tilReportLocation;
            }
        } else {
            binding.tilReportLocation.setError(null);
            binding.tilReportLocation.setActivated(false);
            campusLocation = LOCATION_KEYS[selectedLocationPosition];
        }

        // 4. Incident Date validation (Required)
        if (selectedIncidentDate == null || selectedIncidentDate.trim().isEmpty()) {
            binding.tilReportDate.setError("Please select the incident date.");
            if (firstInvalidView == null) {
                firstInvalidView = binding.tilReportDate;
            }
        } else {
            binding.tilReportDate.setError(null);
        }

        // 5. Incident Time validation (Required, structured HH:mm)
        if (storedIncidentTime == null || storedIncidentTime.trim().isEmpty()) {
            binding.tilReportTime.setError("Please choose the approximate incident time.");
            if (firstInvalidView == null) {
                firstInvalidView = binding.tilReportTime;
            }
        } else {
            binding.tilReportTime.setError(null);
        }

        // 6. Public Description validation (Required, min 5 characters)
        String description = text(binding.etReportDescription.getText());
        if (description.length() < 5) {
            binding.tilReportDescription.setError("Please provide a description (at least 5 characters).");
            if (firstInvalidView == null) {
                firstInvalidView = binding.tilReportDescription;
            }
        } else {
            binding.tilReportDescription.setError(null);
        }

        // 7. Conditional Validation for FOUND Reports
        String publicQuestion = null;
        String finderPrivateNotes = null;
        if (Report.TYPE_FOUND.equals(currentType)) {
            publicQuestion = text(binding.etPublicQuestion.getText());
            if (publicQuestion.length() < 3) {
                binding.tilPublicQuestion.setError("Please enter a public verification question to prevent false claims.");
                if (firstInvalidView == null) {
                    firstInvalidView = binding.tilPublicQuestion;
                }
            } else {
                binding.tilPublicQuestion.setError(null);
            }

            finderPrivateNotes = text(binding.etFinderPrivateNotes.getText());
            if (finderPrivateNotes.length() < 2) {
                binding.tilFinderPrivateNotes.setError("Please enter a secret identifying detail so you can verify answers.");
                if (firstInvalidView == null) {
                    firstInvalidView = binding.tilFinderPrivateNotes;
                }
            } else {
                binding.tilFinderPrivateNotes.setError(null);
            }
        }

        // Map pin must stay inside NIT Trichy campus (applies to LOST and FOUND)
        if (selectedLatitude != null && selectedLongitude != null
                && !CampusBoundaryConfig.isInsideCampus(selectedLatitude, selectedLongitude)) {
            Toast.makeText(requireContext(), CampusBoundaryConfig.getOutOfBoundsErrorMessage(), Toast.LENGTH_SHORT).show();
            if (firstInvalidView == null) {
                firstInvalidView = binding.btnSetMapPin;
            }
        }

        // If any validation failed, scroll to and focus the first invalid field
        if (firstInvalidView != null) {
            final View focusTarget = firstInvalidView;
            binding.scrollCreateReport.post(() -> {
                // Offset upward so the header band does not cover the invalid field
                int target = Math.max(0, focusTarget.getTop() - (int) (48 * getResources().getDisplayMetrics().density));
                binding.scrollCreateReport.smoothScrollTo(0, target);
                focusTarget.requestFocus();
            });
            return;
        }

        // Disable repeated submissions while request is in flight
        setLoading(true);

        Report report = new Report();
        report.setType(currentType);
        report.setTitle(title);
        report.setCategory(category);
        report.setDescription(description);
        report.setCampusLocation(campusLocation);
        report.setIncidentDate(selectedIncidentDate);
        report.setIncidentTimeApprox(storedIncidentTime);
        report.setPublicVerificationQuestion(Report.TYPE_FOUND.equals(currentType) ? publicQuestion : null);

        if (selectedLatitude != null && selectedLongitude != null) {
            report.setLatitude(selectedLatitude);
            report.setLongitude(selectedLongitude);
        }

        final String finalPrivateNotes = finderPrivateNotes;

        if (selectedImageBytes != null) {
            if (BuildConfig.DEBUG) {
                Log.d("CreateReport", "Uploading report photo (" + selectedImageBytes.length + " bytes)...");
            }
            reportRepository.uploadReportPhoto(selectedImageBytes, selectedImageExtension, new ReportRepository.DataCallback<String>() {
                @Override
                public void onSuccess(String storagePath) {
                    report.setImageUrl(storagePath);
                    publishReport(report, finalPrivateNotes);
                }

                @Override
                public void onError(String message) {
                    showError("Photo upload failed: " + message);
                }
            });
        } else {
            publishReport(report, finalPrivateNotes);
        }
    }

    private String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private void publishReport(Report report, String finderPrivateNotes) {
        reportRepository.createReport(report, finderPrivateNotes, new ReportRepository.DataCallback<RpcResponse>() {
            @Override
            public void onSuccess(RpcResponse response) {
                String reportId = response != null ? response.getReportId() : null;
                if (getView() == null) return;

                // Success: clear the draft so returning to Create starts fresh and
                // the published report cannot be resubmitted by accident.
                resetForm();
                setLoading(false);
                Toast.makeText(requireContext(), "Report published successfully!", Toast.LENGTH_SHORT).show();

                if (reportId != null && !reportId.isEmpty()) {
                    Intent intent = new Intent(requireContext(), ReportDetailActivity.class);
                    intent.putExtra("report_id", reportId);
                    startActivity(intent);
                } else if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).selectTabById(R.id.nav_home);
                }
            }

            @Override
            public void onError(String message) {
                showError(message);
            }
        });
    }

    /** Errors and in-flight buttons both touch the view, and either callback
        can land after the view is gone. */
    private void showError(String message) {
        if (getView() == null) return;
        setLoading(false);
        binding.tvCreateError.setText(message);
        binding.tvCreateError.setVisibility(View.VISIBLE);
    }

    private void setLoading(boolean loading) {
        if (getView() == null) return;
        binding.progressCreateReport.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnSubmitReport.setEnabled(!loading);
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
        @Override
        public void afterTextChanged(Editable s) {}
    }

    /** True once the user has changed anything the host must warn about before
        navigating away. */
    public boolean isDirty() {
        return formDirty;
    }

    /** Returns the form to its pristine state. Called by the host after the
        user confirms discarding, and after a successful publish. */
    public void resetForm() {
        formDirty = false;
        isEditMode = false;
        editingReportId = null;
        currentType = Report.TYPE_LOST;
        selectedImageBytes = null;
        selectedLatitude = null;
        selectedLongitude = null;
        selectedIncidentDate = DateUtils.getTodayIsoDate();
        storedIncidentTime = null;
        selectedCategoryPosition = 0;
        selectedLocationPosition = 0;

        if (getView() == null) return;

        applyingInitialType = true;
        binding.etReportTitle.setText("");
        binding.etReportDescription.setText("");
        binding.etPublicQuestion.setText("");
        binding.etFinderPrivateNotes.setText("");
        binding.etReportDate.setText(DateUtils.formatDisplayDateFromIso(selectedIncidentDate));
        binding.etReportTime.setText("");
        binding.tvCreateError.setVisibility(View.GONE);

        binding.actvReportCategory.setText("", false);
        binding.actvReportLocation.setText("", false);
        binding.tilReportCategory.setError(null);
        binding.tilReportCategory.setActivated(false);
        binding.tilReportLocation.setError(null);
        binding.tilReportLocation.setActivated(false);
        binding.tilReportTitle.setError(null);
        binding.tilReportDescription.setError(null);
        binding.tilReportDate.setError(null);
        binding.tilReportTime.setError(null);
        binding.tilPublicQuestion.setError(null);
        binding.tilFinderPrivateNotes.setError(null);

        binding.ivPhotoPreview.setImageTintList(ColorStateList.valueOf(requireContext().getColor(R.color.spotify_text2)));
        binding.ivPhotoPreview.setImageResource(R.drawable.ic_photo);
        binding.btnRemovePhoto.setVisibility(View.GONE);
        photoPicker.reset();
        binding.btnPickPhoto.setEnabled(true);
        updatePhotoButton();

        binding.toggleType.check(R.id.btnToggleLost);
        binding.layoutFoundSpecifics.setVisibility(View.GONE);
        updateTypeToggleStyles();
        updateMapPinPickerLabels();
        updatePinUI();
        applyingInitialType = false;
    }

    /** Report type requested by the host before this tab is first shown. */
    public void setInitialType(String type) {
        pendingType = type;
        if (getView() != null) {
            applyPendingType();
        }
    }

    /** Lets the host open this form in edit mode for an existing report. */
    public void setEditTarget(String reportId) {
        Bundle args = getArguments();
        if (args == null) {
            args = new Bundle();
            setArguments(args);
        }
        args.putBoolean(EXTRA_EDIT_MODE, true);
        args.putString(EXTRA_REPORT_ID, reportId);
        if (getView() != null) {
            binding.tvCreateHeaderTitle.setText("Edit Report");
            binding.btnSubmitReport.setText("Update Report");
        }
    }
}
