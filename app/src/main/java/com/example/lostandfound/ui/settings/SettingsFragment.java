package com.example.lostandfound.ui.settings;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import com.bumptech.glide.Glide;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.example.lostandfound.BuildConfig;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Profile;
import com.example.lostandfound.data.remote.SessionManager;
import com.example.lostandfound.data.remote.SupabaseConfig;
import com.example.lostandfound.data.repository.AuthRepository;
import com.example.lostandfound.data.repository.ProfileRepository;
import com.example.lostandfound.databinding.DialogEditProfileBinding;
import com.example.lostandfound.databinding.FragmentSettingsBinding;
import com.example.lostandfound.databinding.ItemSettingsRowBinding;
import com.example.lostandfound.ui.auth.AuthActivity;
import com.example.lostandfound.ui.common.PhotoSourcePicker;
import com.example.lostandfound.util.ProfileUtils;

public class SettingsFragment extends Fragment {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final String CAPTURE_DIR = "captures/avatar";

    private FragmentSettingsBinding binding;
    private SessionManager sessionManager;
    private AuthRepository authRepository;
    private ProfileRepository profileRepository;

    private DialogEditProfileBinding editBinding;
    private AlertDialog editDialog;
    private byte[] pendingAvatarBytes = null;
    private boolean pendingAvatarPicked = false;
    /** Set when the user clears the photo in the edit dialog. Distinct from
     *  "no change", which is what pendingAvatarPicked == false means. */
    private boolean pendingAvatarRemoved = false;

    /** Same gallery-or-camera chooser the Create Report form uses. */
    private final PhotoSourcePicker avatarPicker = new PhotoSourcePicker(
            this, CAPTURE_DIR, this::handlePickedPhoto);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        sessionManager = SessionManager.getInstance(requireContext());
        authRepository = new AuthRepository(requireContext());
        profileRepository = new ProfileRepository(requireContext());

        avatarPicker.onRestoreInstanceState(savedInstanceState, "state_avatar_capture_path");
        avatarPicker.purgeStaleCaptures();

        displayUserInfo();
        setupActions();
        refreshProfileFromServer();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (binding != null) {
            displayUserInfo();
        }
    }

    private void displayUserInfo() {
        binding.tvProfileName.setText(sessionManager.getDisplayName());
        binding.tvProfileEmail.setText(sessionManager.getUserEmail());
        renderAvatar(sessionManager.getAvatarPath());
        binding.tvAppVersion.setText(getString(R.string.settings_version, BuildConfig.VERSION_NAME));
    }

    private void renderAvatar(String avatarPath) {
        String url = SupabaseConfig.getPublicAvatarUrl(avatarPath);
        if (url != null) {
            binding.ivProfilePhoto.setVisibility(View.VISIBLE);
            binding.ivProfilePhoto.setImageTintList(null);
            binding.ivProfilePlaceholderIcon.setVisibility(View.GONE);
            Glide.with(this)
                    .load(SupabaseConfig.getGlideUrl(url))
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(binding.ivProfilePhoto);
        } else {
            Glide.with(this).clear(binding.ivProfilePhoto);
            binding.ivProfilePhoto.setVisibility(View.GONE);
            binding.ivProfilePlaceholderIcon.setVisibility(View.VISIBLE);
            binding.ivProfilePlaceholderIcon.setImageTintList(
                    ColorStateList.valueOf(requireContext().getColor(R.color.spotify_text2)));
        }
    }

    private void setupActions() {
        binding.btnEditProfile.setOnClickListener(v -> showEditProfileDialog());

        // item_settings_row.xml hardcodes none of its content, so every row has
        // to be populated here or it renders as an empty, unlabelled tap target.
        bindRow(binding.rowSafetyGuidelines, R.drawable.ic_shield,
                R.string.settings_safety_guidelines, R.string.settings_safety_description,
                v -> showInfoDialog(R.string.settings_safety_guidelines,
                        R.string.settings_safety_message,
                        R.string.settings_safety_understood));

        bindRow(binding.rowReportProblem, R.drawable.ic_report_problem,
                R.string.settings_report_problem, R.string.settings_report_problem_description,
                v -> showInfoDialog(R.string.settings_report_problem,
                        R.string.settings_report_problem_message,
                        R.string.settings_report_problem_understood));

        bindRow(binding.rowPrivacy, R.drawable.ic_lock,
                R.string.settings_privacy, R.string.settings_privacy_description,
                v -> showInfoDialog(R.string.settings_privacy,
                        R.string.settings_privacy_message,
                        R.string.settings_privacy_understood));

        bindRow(binding.rowClearCache, R.drawable.ic_photo,
                R.string.settings_clear_cache, R.string.settings_clear_cache_description,
                v -> clearImageCache());

        binding.btnSignOut.setOnClickListener(v -> confirmSignOut());
    }

    private void clearImageCache() {
        Context context = requireContext().getApplicationContext();
        // Glide.clearDiskCache() deletes files synchronously and asserts it is
        // called off the main thread, so running it from the click listener
        // threw IllegalArgumentException and took the process down.
        new Thread(() -> {
            Glide.get(context).clearDiskCache();
            mainHandler.post(() -> {
                // In-memory cache is main-thread only.
                Glide.get(context).clearMemory();
                if (isAdded()) {
                    Toast.makeText(requireContext(), R.string.settings_clear_cache_done,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    /** Populates one included settings row, then wires its tap target. */
    private void bindRow(ItemSettingsRowBinding row, int iconRes, int titleRes,
                         int descriptionRes, View.OnClickListener listener) {
        row.ivSettingsRowIcon.setImageResource(iconRes);
        row.tvSettingsRowTitle.setText(titleRes);
        row.tvSettingsRowDescription.setText(descriptionRes);
        row.getRoot().setOnClickListener(listener);
    }

    private void showInfoDialog(int titleRes, int messageRes) {
        showInfoDialog(titleRes, messageRes, android.R.string.ok);
    }

    private void showInfoDialog(int titleRes, int messageRes, int dismissRes) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(dismissRes, null)
                .show();
    }

    private void confirmSignOut() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_sign_out)
                .setMessage(R.string.settings_sign_out_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_sign_out, (dialog, which) -> signOut())
                .show();
    }

    private void signOut() {
        // logout() clears the session, profile directory and unread counts, then
        // runs onComplete on both the success and failure paths.
        authRepository.logout(() -> {
            if (!isAdded()) return;
            Intent intent = new Intent(requireContext(), AuthActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            requireActivity().finish();
        });
    }

    /** Pull the authoritative profile so an avatar or name changed on another
        device shows up. A failure here is not fatal: the locally cached values
        from {@link #displayUserInfo()} already rendered. */
    private void refreshProfileFromServer() {
        profileRepository.fetchMyProfile(new ProfileRepository.DataCallback<Profile>() {
            @Override
            public void onSuccess(Profile profile) {
                if (binding == null || profile == null) return;
                binding.tvProfileName.setText(profile.getFullName());
                renderAvatar(profile.getAvatarUrl());
            }

            @Override
            public void onError(String message) {
                // Keep the locally cached profile visible.
            }
        });
    }

    private void showEditProfileDialog() {
        if (editDialog != null && editDialog.isShowing()) return;

        pendingAvatarPicked = false;
        pendingAvatarBytes = null;
        pendingAvatarRemoved = false;

        editBinding = DialogEditProfileBinding.inflate(getLayoutInflater());
        editBinding.etEditProfileName.setText(sessionManager.getDisplayName());

        editDialog = new MaterialAlertDialogBuilder(requireContext())
                .setView(editBinding.getRoot())
                .create();

        editBinding.btnEditPickPhoto.setOnClickListener(v ->
                avatarPicker.showSourceDialog(
                        getString(R.string.profile_add_photo),
                        getString(R.string.profile_photo_source_subtitle)));

        // Removing only stages the change; nothing is written until Save, so
        // Cancel still discards it.
        editBinding.btnEditPhotoRemove.setOnClickListener(v -> {
            pendingAvatarRemoved = true;
            pendingAvatarPicked = false;
            pendingAvatarBytes = null;
            renderEditPreview(sessionManager.getAvatarPath());
        });

        editBinding.btnEditProfileSave.setOnClickListener(v -> saveEditedProfile());
        editBinding.btnEditProfileCancel.setOnClickListener(v -> {
            pendingAvatarPicked = false;
            pendingAvatarBytes = null;
            pendingAvatarRemoved = false;
            editDialog.dismiss();
        });

        renderEditPreview(sessionManager.getAvatarPath());
        editDialog.show();
    }

    /** Gallery or camera result. Compression, EXIF rotation and downscaling all
        live in the repository so this stays a thin hand-off. */
    private void handlePickedPhoto(Uri uri) {
        if (uri == null) return;
        try {
            pendingAvatarBytes = profileRepository.processAvatarImage(requireContext(), uri);
            pendingAvatarPicked = true;
            pendingAvatarRemoved = false;
            renderEditPreview(sessionManager.getAvatarPath());
        } catch (Exception e) {
            Toast.makeText(requireContext(), R.string.profile_photo_error,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void renderEditPreview(String currentAvatarPath) {
        if (editBinding == null) return;
        boolean hasPhoto = SupabaseConfig.getPublicAvatarUrl(currentAvatarPath) != null;
        editBinding.btnEditPhotoRemove.setVisibility(hasPhoto ? View.VISIBLE : View.GONE);

        if (pendingAvatarRemoved) {
            Glide.with(this).clear(editBinding.ivEditPhotoPreview);
            editBinding.ivEditPhotoPreview.setVisibility(View.GONE);
            editBinding.ivEditPhotoPlaceholder.setVisibility(View.VISIBLE);
            return;
        }
        if (pendingAvatarPicked && pendingAvatarBytes != null) {
            editBinding.ivEditPhotoPreview.setVisibility(View.VISIBLE);
            editBinding.ivEditPhotoPreview.setImageTintList(null);
            editBinding.ivEditPhotoPlaceholder.setVisibility(View.GONE);
            Glide.with(this)
                    .load(pendingAvatarBytes)
                    .circleCrop()
                    .into(editBinding.ivEditPhotoPreview);
            return;
        }
        String url = SupabaseConfig.getPublicAvatarUrl(currentAvatarPath);
        if (url != null) {
            editBinding.ivEditPhotoPreview.setVisibility(View.VISIBLE);
            editBinding.ivEditPhotoPreview.setImageTintList(null);
            editBinding.ivEditPhotoPlaceholder.setVisibility(View.GONE);
            Glide.with(this)
                    .load(SupabaseConfig.getGlideUrl(url))
                    .circleCrop()
                    .placeholder(R.drawable.ic_person)
                    .error(R.drawable.ic_person)
                    .into(editBinding.ivEditPhotoPreview);
        } else {
            Glide.with(this).clear(editBinding.ivEditPhotoPreview);
            editBinding.ivEditPhotoPreview.setVisibility(View.GONE);
            editBinding.ivEditPhotoPlaceholder.setVisibility(View.VISIBLE);
        }
    }

    private void saveEditedProfile() {
        if (editBinding == null) return;
        editBinding.tvEditProfileError.setVisibility(View.GONE);

        String name = editBinding.etEditProfileName.getText() != null
                ? editBinding.etEditProfileName.getText().toString().trim() : "";
        String validationError = ProfileUtils.validateDisplayName(name);
        if (validationError != null) {
            editBinding.tilEditProfileName.setError(validationError);
            return;
        }
        editBinding.tilEditProfileName.setError(null);

        setEditLoading(true);

        // Removal and upload are mutually exclusive; removal is checked first so a
        // staged-but-cleared photo can never be uploaded by accident.
        ProfileRepository.DataCallback<Profile> onSaved = new ProfileRepository.DataCallback<Profile>() {
                    @Override
                    public void onSuccess(Profile profile) {
                        if (!isAdded()) return;
                        setEditLoading(false);
                        if (editDialog != null) editDialog.dismiss();
                        Toast.makeText(requireContext(), R.string.profile_saved, Toast.LENGTH_SHORT).show();
                        displayUserInfo();
                    }

                    @Override
                    public void onError(String message) {
                        if (!isAdded() || editBinding == null) return;
                        setEditLoading(false);
                        editBinding.tvEditProfileError.setText(message);
                        editBinding.tvEditProfileError.setVisibility(View.VISIBLE);
                    }
                };

        if (pendingAvatarRemoved) {
            profileRepository.removeAvatar(onSaved);
        } else {
            profileRepository.saveProfile(name, pendingAvatarPicked ? pendingAvatarBytes : null, onSaved);
        }
    }

    private void setEditLoading(boolean loading) {
        if (editBinding == null) return;
        editBinding.progressEditProfile.setVisibility(loading ? View.VISIBLE : View.GONE);
        editBinding.btnEditProfileSave.setEnabled(!loading);
        editBinding.btnEditProfileCancel.setEnabled(!loading);
        editBinding.btnEditPickPhoto.setEnabled(!loading);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // So a photo taken before a rotation is not lost.
        avatarPicker.onSaveInstanceState(outState, "state_avatar_capture_path");
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (editDialog != null && editDialog.isShowing()) {
            editDialog.dismiss();
        }
        editDialog = null;
        editBinding = null;
        binding = null;
    }
}
