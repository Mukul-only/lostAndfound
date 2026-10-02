package com.example.lostandfound.ui.dialogs;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.widget.TextView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.example.lostandfound.R;
import com.example.lostandfound.data.repository.ReportRepository;
import com.example.lostandfound.ui.common.DropdownStyling;
import com.example.lostandfound.databinding.DialogReportAbuseBinding;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class ReportAbuseDialog extends BottomSheetDialogFragment {

    private static final int SHEET_ID = com.google.android.material.R.id.design_bottom_sheet;

    /** Paints the real sheet dark, so no white survives around or below the
     *  rounded top corners. Scoped to this dialog only.
     *
     *  <p>The tint must be cleared as well as the drawable set:
     *  BottomSheetDialog reads {@code android:background} from its theme and
     *  applies it as a background tint on this same view. The app theme
     *  descends from Theme.MaterialComponents.Light, so that value is white and
     *  it would tint any dark drawable white again. */
    private void applyDarkSheetSurface() {
        Dialog dialog = getDialog();
        if (dialog == null) return;
        View sheet = dialog.findViewById(SHEET_ID);
        if (sheet != null) {
            if (sheet instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) sheet).setBackgroundTintList(null);
            }
            // #181818 with 20dp top corners: the sheet surface, drawn by the sheet
            // itself so the area below this wrap_content view is covered too.
            // No elevation here: a shadow on a sheet inside this dialog window
            // renders against the window surface and turned the whole sheet black.
            sheet.setBackgroundResource(R.drawable.bg_bottom_sheet);
        }
    }

    private DialogReportAbuseBinding binding;
    private ReportRepository reportRepository;
    private String reportId;

    private static final String[] REASON_KEYS = {
            "SPAM", "SCAM_FRAUD", "HARASSMENT", "INAPPROPRIATE_CONTENT", "OTHER"
    };
    private static final String[] REASON_LABELS = {
            "Spam", "Scam or Fraud", "Harassment / Threats", "Inappropriate / Offensive Content", "Other"
    };

    public static ReportAbuseDialog newInstance(String reportId) {
        ReportAbuseDialog dialog = new ReportAbuseDialog();
        Bundle args = new Bundle();
        args.putString("report_id", reportId);
        dialog.setArguments(args);
        return dialog;
    }

    /** Scoped dark sheet surface. The app theme descends from
     *  Theme.MaterialComponents.Light, whose bottom sheet paints white. Only
     *  this dialog opts in, so SubmitClaimBottomSheet and every other sheet or
     *  dialog keep their current surface. */
    @Override
    public int getTheme() {
        return R.style.ThemeOverlay_Foundit_Spotify_BottomSheetDialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = DialogReportAbuseBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        reportRepository = new ReportRepository(requireContext());

        // The sheet is taller than this wrap_content view, so the area below it
        // is painted by the BottomSheetDialog's own sheet, not by this layout.
        // The theme override alone does not reliably reach it under the Light
        // parent theme, which is what left a white band under the rounded top
        // corners. Setting the sheet's background directly is what actually
        // makes the whole surface dark.
        applyDarkSheetSurface();

        if (getArguments() != null) {
            reportId = getArguments().getString("report_id");
        }

        // MaterialAutoCompleteTextView, styled by the app's TextInputLayout
        // style so the reason field matches every other input. Same labels, same
        // order, same keys as the Spinner this replaced.
        // The option rows inherit the Light parent theme's item text style, which
        // renders dark on this dark popup. AutoCompleteTextView exposes no
        // itemTextColor API, so the row colour is forced in the adapter instead.
        final int optionTextColor = androidx.core.content.ContextCompat
                .getColor(requireContext(), R.color.spotify_text);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(requireContext(),
                android.R.layout.simple_list_item_1, REASON_LABELS) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                if (row instanceof TextView) {
                    ((TextView) row).setTextColor(optionTextColor);
                }
                return row;
            }
        };
        binding.inputAbuseReason.setAdapter(adapter);
        // MaterialAutoCompleteTextView installs its own popup background
        // (mtrl_popupmenu_background: 4dp corners, ?attr/colorSurface fill), so
        // android:popupBackground in the layout is ignored. Replace it outright.
        DropdownStyling.applyRoundedPopup(binding.inputAbuseReason);
        binding.inputAbuseReason.setOnItemClickListener((parent, rowView, position, id) -> {
            binding.inputAbuseReason.setText(REASON_LABELS[position], false);
            binding.tilAbuseReason.setError(null);
        });

        binding.btnSubmitAbuseReport.setOnClickListener(v -> {
            binding.tvAbuseError.setVisibility(View.GONE);
            binding.tilAbuseReason.setError(null);
            binding.tilAbuseDetails.setError(null);
            String details = binding.etAbuseDetails.getText() != null ? binding.etAbuseDetails.getText().toString().trim() : "";

            // A reason must be chosen explicitly, so the placeholder cannot be
            // submitted as if it were a selection.
            Object selected = binding.inputAbuseReason.getText();
            int pos = -1;
            if (selected != null) {
                String chosen = selected.toString().trim();
                for (int i = 0; i < REASON_LABELS.length; i++) {
                    if (REASON_LABELS[i].equals(chosen)) { pos = i; break; }
                }
            }
            if (pos < 0) {
                binding.tilAbuseReason.setError(getString(R.string.abuse_reason_prompt));
                binding.tvAbuseError.setText(R.string.abuse_reason_prompt);
                binding.tvAbuseError.setVisibility(View.VISIBLE);
                return;
            }
            String reason = REASON_KEYS[pos];

            if (details.length() < 5) {
                binding.tilAbuseDetails.setError(getString(R.string.abuse_details_too_short));
                binding.tvAbuseError.setText(R.string.abuse_details_too_short);
                binding.tvAbuseError.setVisibility(View.VISIBLE);
                return;
            }
            binding.tilAbuseReason.setError(null);
            binding.tilAbuseDetails.setError(null);

            binding.progressAbuseReport.setVisibility(View.VISIBLE);
            binding.btnSubmitAbuseReport.setEnabled(false);

            reportRepository.submitAbuseReport(reportId, reason, details, new ReportRepository.DataCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean data) {
                    Toast.makeText(requireContext(), R.string.abuse_submitted, Toast.LENGTH_LONG).show();
                    dismiss();
                }

                @Override
                public void onError(String message) {
                    binding.progressAbuseReport.setVisibility(View.GONE);
                    binding.btnSubmitAbuseReport.setEnabled(true);
                    binding.tvAbuseError.setText(message);
                    binding.tvAbuseError.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
