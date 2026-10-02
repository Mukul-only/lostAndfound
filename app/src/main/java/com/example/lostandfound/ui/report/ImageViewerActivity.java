package com.example.lostandfound.ui.report;

import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.example.lostandfound.R;
import com.example.lostandfound.data.remote.SupabaseConfig;
import com.example.lostandfound.databinding.ActivityImageViewerBinding;

/**
 * Fullscreen photo viewer with pinch-to-zoom, drag-to-pan when zoomed,
 * and double-tap to toggle zoom. Opened by tapping a report photo.
 */
public class ImageViewerActivity extends AppCompatActivity {
    public static final String EXTRA_STORAGE_PATH = "storage_path";

    private static final float MAX_SCALE = 4f;
    private static final float DOUBLE_TAP_SCALE = 2.5f;

    private ActivityImageViewerBinding binding;
    private final Matrix matrix = new Matrix();
    private final RectF displayRect = new RectF();
    private float baseScale = 1f;
    private float currentScale = 1f;

    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private float lastX;
    private float lastY;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityImageViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.btnViewerBack.setOnClickListener(v -> finish());
        binding.ivFullImage.setScaleType(android.widget.ImageView.ScaleType.MATRIX);

        scaleDetector = new ScaleGestureDetector(this, new ScaleListener());
        gestureDetector = new GestureDetector(this, new GestureListener());

        binding.ivFullImage.setOnTouchListener((v, event) -> {
            scaleDetector.onTouchEvent(event);
            gestureDetector.onTouchEvent(event);

            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                activePointerId = event.getPointerId(0);
                lastX = event.getX();
                lastY = event.getY();
            } else if (action == MotionEvent.ACTION_MOVE && !scaleDetector.isInProgress()) {
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex != -1 && currentScale > 1f) {
                    float x = event.getX(pointerIndex);
                    float y = event.getY(pointerIndex);
                    matrix.postTranslate(x - lastX, y - lastY);
                    clampTranslation();
                    binding.ivFullImage.setImageMatrix(matrix);
                    lastX = x;
                    lastY = y;
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                activePointerId = MotionEvent.INVALID_POINTER_ID;
            } else if (action == MotionEvent.ACTION_POINTER_UP) {
                int pointerIndex = event.getActionIndex();
                if (event.getPointerId(pointerIndex) == activePointerId) {
                    int newIndex = pointerIndex == 0 ? 1 : 0;
                    activePointerId = event.getPointerId(newIndex);
                    lastX = event.getX(newIndex);
                    lastY = event.getY(newIndex);
                }
            }
            return true;
        });

        String storagePath = getIntent().getStringExtra(EXTRA_STORAGE_PATH);
        String imageUrl = SupabaseConfig.getPublicImageUrl(storagePath);
        if (imageUrl == null || imageUrl.isEmpty()) {
            Toast.makeText(this, "Image not available", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Glide.with(this)
                .load(SupabaseConfig.getGlideUrl(imageUrl))
                .placeholder(R.drawable.ic_photo)
                .error(R.drawable.ic_photo)
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                Target<Drawable> target, boolean isFirstResource) {
                        Toast.makeText(ImageViewerActivity.this, "Could not load image", Toast.LENGTH_SHORT).show();
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                                   DataSource dataSource, boolean isFirstResource) {
                        binding.ivFullImage.post(() -> fitImageToView(resource));
                        return false;
                    }
                })
                .into(binding.ivFullImage);
    }

    private void fitImageToView(@NonNull Drawable drawable) {
        int viewWidth = binding.ivFullImage.getWidth();
        int viewHeight = binding.ivFullImage.getHeight();
        int drawableWidth = drawable.getIntrinsicWidth();
        int drawableHeight = drawable.getIntrinsicHeight();
        if (viewWidth == 0 || viewHeight == 0 || drawableWidth <= 0 || drawableHeight <= 0) return;

        baseScale = Math.min((float) viewWidth / drawableWidth, (float) viewHeight / drawableHeight);
        currentScale = 1f;
        matrix.setScale(baseScale, baseScale);
        matrix.postTranslate((viewWidth - drawableWidth * baseScale) / 2f,
                (viewHeight - drawableHeight * baseScale) / 2f);
        binding.ivFullImage.setImageMatrix(matrix);
    }

    private void clampTranslation() {
        Drawable drawable = binding.ivFullImage.getDrawable();
        if (drawable == null) return;
        displayRect.set(0, 0, drawable.getIntrinsicWidth(), drawable.getIntrinsicHeight());
        matrix.mapRect(displayRect);

        int viewWidth = binding.ivFullImage.getWidth();
        int viewHeight = binding.ivFullImage.getHeight();
        float dx = 0f;
        float dy = 0f;

        if (displayRect.width() <= viewWidth) {
            dx = (viewWidth - displayRect.width()) / 2f - displayRect.left;
        } else {
            if (displayRect.left > 0) dx = -displayRect.left;
            else if (displayRect.right < viewWidth) dx = viewWidth - displayRect.right;
        }
        if (displayRect.height() <= viewHeight) {
            dy = (viewHeight - displayRect.height()) / 2f - displayRect.top;
        } else {
            if (displayRect.top > 0) dy = -displayRect.top;
            else if (displayRect.bottom < viewHeight) dy = viewHeight - displayRect.bottom;
        }
        matrix.postTranslate(dx, dy);
    }

    private class ScaleListener extends ScaleGestureDetector.SimpleOnScaleGestureListener {
        @Override
        public boolean onScale(@NonNull ScaleGestureDetector detector) {
            float factor = detector.getScaleFactor();
            float newScale = currentScale * factor;
            if (newScale < 1f) factor = 1f / currentScale;
            else if (newScale > MAX_SCALE) factor = MAX_SCALE / currentScale;
            currentScale *= factor;
            matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
            clampTranslation();
            binding.ivFullImage.setImageMatrix(matrix);
            return true;
        }
    }

    private class GestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDoubleTap(@NonNull MotionEvent e) {
            float target = currentScale > 1f ? 1f : DOUBLE_TAP_SCALE;
            float factor = target / currentScale;
            currentScale = target;
            if (currentScale == 1f) {
                Drawable drawable = binding.ivFullImage.getDrawable();
                if (drawable != null) fitImageToView(drawable);
            } else {
                matrix.postScale(factor, factor, e.getX(), e.getY());
                clampTranslation();
                binding.ivFullImage.setImageMatrix(matrix);
            }
            return true;
        }
    }
}
