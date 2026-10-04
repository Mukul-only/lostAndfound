package com.example.lostandfound.ui.auth;

import android.content.Intent;
import android.os.Bundle;
import android.util.Patterns;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import com.example.lostandfound.MainActivity;
import com.example.lostandfound.data.remote.SupabaseConfig;
import com.example.lostandfound.data.repository.AuthRepository;
import com.example.lostandfound.databinding.ActivityAuthBinding;
import com.google.android.material.tabs.TabLayout;

public class AuthActivity extends AppCompatActivity {
    private ActivityAuthBinding binding;
    private AuthRepository authRepository;
    private boolean isRegisterMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Keep light mode locked; AuthActivity can be the entry point before MainActivity sets it.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        super.onCreate(savedInstanceState);
        binding = ActivityAuthBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        authRepository = new AuthRepository(this);

        if (!SupabaseConfig.isConfigured()) {
            binding.cardConfigWarning.setVisibility(View.VISIBLE);
        }

        setupTabs();
        setupSubmitButton();
        applyStatusBarInset();
    }

    /**
     * Pushes the auth form below the status bar on Android 15+ edge-to-edge
     * by adding the status bar height to the inner layout's existing top padding.
     */
    private void applyStatusBarInset() {
        final int basePaddingTop = binding.layoutAuthContent.getPaddingTop();
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                binding.layoutAuthContent, (v, insets) -> {
            int top = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            v.setPadding(v.getPaddingLeft(), basePaddingTop + top,
                    v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
    }

    private void setupTabs() {
        binding.tabLayoutAuth.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                isRegisterMode = (tab.getPosition() == 1);
                binding.layoutFullName.setVisibility(isRegisterMode ? View.VISIBLE : View.GONE);
                binding.btnSubmitAuth.setText(isRegisterMode ? "Create Account" : "Sign In");
                binding.tvErrorMessage.setVisibility(View.GONE);
                binding.tvEmailNotice.setVisibility(View.GONE);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}

            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void setupSubmitButton() {
        binding.btnSubmitAuth.setOnClickListener(v -> {
            binding.tvErrorMessage.setVisibility(View.GONE);
            binding.tvEmailNotice.setVisibility(View.GONE);

            String email = binding.etEmail.getText() != null ? binding.etEmail.getText().toString().trim() : "";
            String password = binding.etPassword.getText() != null ? binding.etPassword.getText().toString().trim() : "";
            String fullName = binding.etFullName.getText() != null ? binding.etFullName.getText().toString().trim() : "";

            if (isRegisterMode && fullName.length() < 2) {
                binding.tvErrorMessage.setText("Please enter your student name (at least 2 letters).");
                binding.tvErrorMessage.setVisibility(View.VISIBLE);
                return;
            }

            if (email.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                binding.tvErrorMessage.setText("Please enter a valid campus email address.");
                binding.tvErrorMessage.setVisibility(View.VISIBLE);
                return;
            }

            if (password.length() < 6) {
                binding.tvErrorMessage.setText("Password must be at least 6 characters.");
                binding.tvErrorMessage.setVisibility(View.VISIBLE);
                return;
            }

            setLoading(true);

            if (isRegisterMode) {
                authRepository.signUp(fullName, email, password, new AuthRepository.AuthCallback() {
                    @Override
                    public void onSuccess(boolean emailConfirmationRequired) {
                        setLoading(false);
                        if (emailConfirmationRequired) {
                            // Select the tab before revealing the notice: onTabSelected hides
                            // tvEmailNotice, so showing it first meant the switch wiped it
                            // immediately and the user saw a bare Sign In tab with no explanation.
                            binding.tabLayoutAuth.getTabAt(0).select();
                            binding.tvEmailNotice.setText(
                                    "Account created! Check your inbox to confirm your email address,"
                                            + " then sign in.");
                            binding.tvEmailNotice.setVisibility(View.VISIBLE);
                        } else {
                            navigateToMain();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        setLoading(false);
                        binding.tvErrorMessage.setText(message);
                        binding.tvErrorMessage.setVisibility(View.VISIBLE);
                    }
                });
            } else {
                authRepository.signIn(email, password, new AuthRepository.AuthCallback() {
                    @Override
                    public void onSuccess(boolean emailConfirmationRequired) {
                        setLoading(false);
                        navigateToMain();
                    }

                    @Override
                    public void onError(String message) {
                        setLoading(false);
                        binding.tvErrorMessage.setText(message);
                        binding.tvErrorMessage.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        binding.progressAuth.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnSubmitAuth.setEnabled(!loading);
    }

    private void navigateToMain() {
        Intent intent = new Intent(this, MainActivity.class);
        // FLAG_ACTIVITY_CLEAR_TASK destroys any existing MainActivity and creates a fresh one,
        // which is required so HomeFragment loads reports with the new session's token.
        // FLAG_ACTIVITY_CLEAR_TOP must NOT be combined here: it would reuse an existing
        // MainActivity instance (calling onNewIntent instead of onCreate) and leave the
        // old HomeFragment in place without triggering a fresh data load.
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }
}
