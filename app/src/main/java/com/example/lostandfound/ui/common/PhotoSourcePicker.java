package com.example.lostandfound.ui.common;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.example.lostandfound.R;
import com.example.lostandfound.databinding.DialogPhotoSourceBinding;

import java.io.File;
import java.io.IOException;

/**
 * One photo-source chooser shared by every screen that picks an image, so
 * "gallery or camera" looks and behaves the same everywhere.
 *
 * Owns the camera contract: a full-resolution capture written through a
 * FileProvider (never the thumbnail in the result Intent), the pending-capture
 * path across recreation, and temp-file cleanup. The caller only receives the
 * chosen Uri and does its own decoding, previewing and upload.
 *
 * No CAMERA permission is declared or requested: the camera app owns that and
 * we only ever receive a file back.
 */
public class PhotoSourcePicker {

    public interface Callback {
        /** The user chose an image. Called on the main thread. */
        void onImagePicked(Uri uri);
    }

    /** Subdirectory of the FileProvider cache path this picker writes into. */
    private final String captureDir;
    private final Callback callback;
    private final Fragment host;

    /** True while a picker or camera is in flight, so taps cannot double-launch. */
    private boolean busy = false;
    private Uri pendingCaptureUri = null;
    private File pendingCaptureFile = null;

    private final ActivityResultLauncher<PickVisualMediaRequest> galleryLauncher;
    private final ActivityResultLauncher<String> legacyGalleryLauncher;
    private final ActivityResultLauncher<Intent> takePictureLauncher;

    /**
     * Must be created from a Fragment field initializer so the launchers are
     * registered before the fragment reaches STARTED. Registration happens here
     * rather than in field initializers because it needs {@code host} and
     * {@code callback}, which Java assigns after those initializers run.
     */
    public PhotoSourcePicker(Fragment host, String captureDir, Callback callback) {
        this.host = host;
        this.captureDir = captureDir;
        this.callback = callback;

        galleryLauncher = host.registerForActivityResult(
                new ActivityResultContracts.PickVisualMedia(), uri -> {
                    settle();
                    if (uri != null) callback.onImagePicked(uri);
                });

        legacyGalleryLauncher = host.registerForActivityResult(
                new ActivityResultContracts.GetContent(), uri -> {
                    settle();
                    if (uri != null) callback.onImagePicked(uri);
                });

        // TakePicture writes the full-resolution file to EXTRA_OUTPUT rather
        // than returning a thumbnail in the result Intent.
        takePictureLauncher = host.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    Uri captured = pendingCaptureUri;
                    boolean ok = result.getResultCode() == android.app.Activity.RESULT_OK;
                    if (ok && captured != null) {
                        callback.onImagePicked(captured);
                    }
                    // Cancelled: keep whatever was already chosen. Either way
                    // the bytes are in memory now, so the temp file is done.
                    clearPendingCapture();
                });
    }

    /** Shows the gallery-or-camera dialog. */
    public void showSourceDialog(String title, String subtitle) {
        DialogPhotoSourceBinding sheet = DialogPhotoSourceBinding.inflate(host.getLayoutInflater());
        androidx.appcompat.app.AlertDialog dialog =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(host.requireContext())
                        .setView(sheet.getRoot())
                        .create();

        if (title != null) sheet.tvPhotoSourceTitle.setText(title);
        if (subtitle != null) sheet.tvPhotoSourceSubtitle.setText(subtitle);
        sheet.cardChooseGallery.setOnClickListener(v -> {
            dialog.dismiss();
            launchGallery();
        });
        sheet.cardTakePhoto.setOnClickListener(v -> {
            dialog.dismiss();
            launchCamera();
        });
        // Cancelling must leave any previously chosen photo untouched.
        sheet.btnCancelPhotoSource.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void launchGallery() {
        if (busy) return;
        if (ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(host.requireContext())) {
            busy = true;
            galleryLauncher.launch(new PickVisualMediaRequest.Builder()
                    .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                    .build());
        } else {
            busy = true;
            legacyGalleryLauncher.launch("image/*");
        }
    }

    /**
     * Delegates capture to the camera app, which writes the full-resolution
     * JPEG into our cache through a per-intent URI grant.
     */
    private void launchCamera() {
        if (busy) return;
        if (!hasCameraApp()) {
            toast(host.getString(R.string.report_no_camera_app));
            return;
        }
        try {
            File captureFile = createCaptureFile();
            Uri captureUri = FileProvider.getUriForFile(
                    host.requireContext(),
                    host.getString(R.string.fileprovider_authority),
                    captureFile);
            pendingCaptureUri = captureUri;
            pendingCaptureFile = captureFile;

            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, captureUri);
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (intent.resolveActivity(host.requireContext().getPackageManager()) == null) {
                clearPendingCapture();
                toast(host.getString(R.string.report_no_camera_app));
                return;
            }
            busy = true;
            takePictureLauncher.launch(intent);
        } catch (IOException | IllegalArgumentException e) {
            clearPendingCapture();
            toast(host.getString(R.string.report_camera_failed));
        }
    }

    private boolean hasCameraApp() {
        return new Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .resolveActivity(host.requireContext().getPackageManager()) != null;
    }

    private File createCaptureFile() throws IOException {
        File dir = new File(host.requireContext().getCacheDir(), captureDir);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Could not create capture directory");
        }
        return new File(dir, "capture_" + System.currentTimeMillis() + ".jpg");
    }

    /**
     * Keeps the capture target across recreation so a photo taken before a
     * rotation is not lost. The previous temp file is reused, not recreated.
     */
    public void onRestoreInstanceState(Bundle savedInstanceState, String key) {
        if (savedInstanceState == null) return;
        String path = savedInstanceState.getString(key);
        if (path == null) return;
        File file = new File(path);
        if (!file.exists()) return;
        try {
            pendingCaptureFile = file;
            pendingCaptureUri = FileProvider.getUriForFile(
                    host.requireContext(),
                    host.getString(R.string.fileprovider_authority),
                    file);
        } catch (IllegalArgumentException e) {
            file.delete();
        }
    }

    public void onSaveInstanceState(Bundle outState, String key) {
        if (pendingCaptureFile != null) {
            outState.putString(key, pendingCaptureFile.getAbsolutePath());
        }
    }

    /** Removes leftovers from earlier sessions (crashes, force-stop). */
    public void purgeStaleCaptures() {
        if (!host.isAdded()) return;
        File dir = new File(host.requireContext().getCacheDir(), captureDir);
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.equals(pendingCaptureFile)) f.delete();
        }
    }

    public void clearPendingCapture() {
        if (pendingCaptureFile != null) {
            pendingCaptureFile.delete();
        }
        pendingCaptureFile = null;
        pendingCaptureUri = null;
    }

    /** Clears state and deletes any temp file, e.g. after publishing a form. */
    public void reset() {
        clearPendingCapture();
        busy = false;
    }

    public boolean isBusy() {
        return busy;
    }

    private void settle() {
        busy = false;
    }

    private void toast(String message) {
        if (host.isAdded()) {
            android.widget.Toast.makeText(host.requireContext(), message, android.widget.Toast.LENGTH_LONG).show();
        }
    }
}