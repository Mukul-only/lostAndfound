package com.example.lostandfound.util;

import android.content.Context;
import androidx.core.content.ContextCompat;
import com.example.lostandfound.R;
import com.example.lostandfound.data.model.Claim;
import com.example.lostandfound.data.model.Report;

public class StatusUtils {
    public static String getStatusLabel(String status) {
        if (status == null) return "Open";
        switch (status) {
            case Report.STATUS_HANDOVER_ARRANGED:
                return "Handover Arranged";
            case Report.STATUS_RETURNED:
                return "Returned";
            case Report.STATUS_CLOSED:
                return "Closed";
            case Report.STATUS_OPEN:
            default:
                return "Open";
        }
    }

    /** Status color for dark surfaces (DESIGN.md functional palette):
     *  open green, arranged bright silver, returned blue, closed silver. */
    public static int getStatusColor(Context context, String status) {
        if (status == null) return ContextCompat.getColor(context, R.color.spotify_green);
        switch (status) {
            case Report.STATUS_HANDOVER_ARRANGED:
                return ContextCompat.getColor(context, R.color.spotify_text3);
            case Report.STATUS_RETURNED:
                return ContextCompat.getColor(context, R.color.spotify_info);
            case Report.STATUS_CLOSED:
                return ContextCompat.getColor(context, R.color.spotify_text2);
            case Report.STATUS_OPEN:
            default:
                return ContextCompat.getColor(context, R.color.status_open);
        }
    }

    /** Claim status label for dark surfaces. Claims store raw enum-ish
     *  uppercase values; the card shows sentence case. */
    public static String getClaimStatusLabel(String status) {
        if (status == null) return "Pending";
        switch (status.toUpperCase()) {
            case Claim.STATUS_ACCEPTED:
                return "Accepted";
            case Claim.STATUS_REJECTED:
                return "Declined";
            case Claim.STATUS_PENDING:
            default:
                return "Pending";
        }
    }

    /** Claim status colour: pending is the DESIGN.md warning voice, accepted is
     *  the brand green, declined is the negative red. */
    public static int getClaimStatusColor(Context context, String status) {
        if (status == null) return ContextCompat.getColor(context, R.color.spotify_warning);
        switch (status.toUpperCase()) {
            case Claim.STATUS_ACCEPTED:
                return ContextCompat.getColor(context, R.color.spotify_green);
            case Claim.STATUS_REJECTED:
                return ContextCompat.getColor(context, R.color.spotify_error);
            case Claim.STATUS_PENDING:
            default:
                return ContextCompat.getColor(context, R.color.spotify_warning);
        }
    }

    public static String getCategoryLabel(String category) {
        if (category == null) return "Other";
        switch (category) {
            case "ELECTRONICS": return "Electronics";
            case "CARDS_ID": return "Cards & Student ID";
            case "KEYS": return "Keys";
            case "BAGS_WALLETS": return "Bags & Wallets";
            case "CLOTHING": return "Clothing";
            case "BOOKS_STATIONERY": return "Books";
            case "OTHER":
            default:
                return "Other";
        }
    }

    /** Card metadata icon for a stored category key. Keys unchanged. */
    public static int getCategoryIconRes(String category) {
        if (category == null) return R.drawable.ic_cat_other;
        switch (category) {
            case "ELECTRONICS": return R.drawable.ic_cat_electronics;
            case "CARDS_ID": return R.drawable.ic_cat_cards_id;
            case "KEYS": return R.drawable.ic_cat_keys;
            case "BAGS_WALLETS": return R.drawable.ic_cat_bags_wallets;
            case "CLOTHING": return R.drawable.ic_cat_clothing;
            case "BOOKS_STATIONERY": return R.drawable.ic_cat_books_stationery;
            case "OTHER":
            default:
                return R.drawable.ic_cat_other;
        }
    }

    public static boolean isValidStatusTransition(String currentStatus, String targetStatus) {
        if (currentStatus == null || targetStatus == null) return false;
        if (currentStatus.equals(targetStatus)) return true;

        switch (currentStatus) {
            case Report.STATUS_OPEN:
                return targetStatus.equals(Report.STATUS_HANDOVER_ARRANGED)
                        || targetStatus.equals(Report.STATUS_RETURNED)
                        || targetStatus.equals(Report.STATUS_CLOSED);
            case Report.STATUS_HANDOVER_ARRANGED:
                return targetStatus.equals(Report.STATUS_RETURNED)
                        || targetStatus.equals(Report.STATUS_CLOSED);
            case Report.STATUS_RETURNED:
            case Report.STATUS_CLOSED:
                return false; // Terminal states
            default:
                return false;
        }
    }
}
