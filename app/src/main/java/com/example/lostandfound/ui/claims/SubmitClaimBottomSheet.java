package com.example.lostandfound.ui.claims;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Report;
import com.example.lostandfound.data.model.RpcResponse;
import com.example.lostandfound.data.repository.ClaimRepository;
import com.example.lostandfound.databinding.DialogSubmitClaimBinding;
import com.example.lostandfound.ui.common.SheetStyling;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class SubmitClaimBottomSheet extends BottomSheetDialogFragment {
    public interface OnClaimSubmittedListener {
        void onClaimSubmitted();
    }

    private static final int SHEET_ID = com.google.android.material.R.id.design_bottom_sheet;

    private DialogSubmitClaimBinding binding;
    private ClaimRepository claimRepository;
    private Report report;
    private OnClaimSubmittedListener listener;

    public static SubmitClaimBottomSheet newInstance(Report report) {
        SubmitClaimBottomSheet fragment = new SubmitClaimBottomSheet();
        Bundle args = new Bundle();
        args.putSerializable("report", report);
        fragment.setArguments(args);
        return fragment;
    }

    public void setOnClaimSubmittedListener(OnClaimSubmittedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogSubmitClaimBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public int getTheme() {
        return R.style.ThemeOverlay_Foundit_Spotify_BottomSheetDialog;
    }

    @Override
    public void onStart() {
        super.onStart();
        applyDarkSheetSurface();
        SheetStyling.expandForKeyboard(getDialog());
    }

    /**
     * Paints the sheet surface ourselves. Identical to ReportAbuseDialog: the
     * theme's android:background resolves to white under this app's Light parent
     * theme, so the sheet would render light without this.
     */
    private void applyDarkSheetSurface() {
        Dialog dialog = getDialog();
        if (dialog == null) return;
        View sheet = dialog.findViewById(SHEET_ID);
        if (sheet != null) {
            if (sheet instanceof ViewGroup) {
                ((ViewGroup) sheet).setBackgroundTintList(null);
            }
            // No elevation: a shadow on a sheet inside this dialog window renders
            // against the window surface and turns the whole sheet black.
            sheet.setBackgroundResource(R.drawable.bg_bottom_sheet);
        }
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        claimRepository = new ClaimRepository(requireContext());

        if (getArguments() != null) {
            report = (Report) getArguments().getSerializable("report");
        }

        if (report == null) {
            dismiss();
            return;
        }

        setupViews();
        setupSubmitButton();
    }

    private void setupViews() {
        if (report.isFound()) {
            binding.tvClaimDialogTitle.setText("Claim This Found Item");
            binding.tvClaimPrompt.setText("Provide descriptive evidence to verify you are the rightful owner.");
            String question = report.getPublicVerificationQuestion();
            if (question != null && !question.trim().isEmpty()) {
                binding.cardDialogQuestion.setVisibility(View.VISIBLE);
                binding.tvDialogQuestion.setText(question);
            }
        } else {
            binding.tvClaimDialogTitle.setText("I Found This Item");
            binding.tvClaimPrompt.setText("Let the owner know where you found it and describe matching details.");
        }
    }

    private void setupSubmitButton() {
        binding.btnSubmitClaim.setOnClickListener(v -> {
            binding.tvClaimError.setVisibility(View.GONE);
            String evidence = binding.etClaimEvidence.getText() != null ? binding.etClaimEvidence.getText().toString().trim() : "";

            if (evidence.length() < 5) {
                binding.tvClaimError.setText("Please provide details (at least 5 characters).");
                binding.tvClaimError.setVisibility(View.VISIBLE);
                return;
            }

            setLoading(true);

            claimRepository.submitClaim(report.getId(), evidence, new ClaimRepository.DataCallback<RpcResponse>() {
                @Override
                public void onSuccess(RpcResponse data) {
                    setLoading(false);
                    Toast.makeText(requireContext(), "Response submitted to report owner!", Toast.LENGTH_SHORT).show();
                    if (listener != null) {
                        listener.onClaimSubmitted();
                    }
                    dismiss();
                }

                @Override
                public void onError(String message) {
                    setLoading(false);
                    if (message != null && (message.contains("already submitted") || message.contains("uq_claims_report_claimant"))) {
                        binding.tvClaimError.setText("You have already submitted a response for this report. Each student can submit only one response per item.");
                    } else {
                        binding.tvClaimError.setText(message);
                    }
                    binding.tvClaimError.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void setLoading(boolean loading) {
        binding.progressSubmitClaim.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnSubmitClaim.setEnabled(!loading);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
