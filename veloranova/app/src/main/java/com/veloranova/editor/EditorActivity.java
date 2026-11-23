package com.veloranova.editor;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.OptIn;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public final class EditorActivity extends Activity {
    private static final int PICK_VIDEO = 1;
    private static final int BACKGROUND = 0xff151321;
    private static final int PANEL = 0xff242137;
    private static final int INK = 0xfff5f1ff;
    private static final int MUTED = 0xffc2bad8;
    private static final int ACCENT = 0xffbca9ff;
    private static final int MIN_CLIP_MS = 100;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService storage = Executors.newSingleThreadExecutor();

    private FrameLayout preview;
    private VideoView video;
    private TextView placeholder;
    private TextView status;
    private TextView startLabel;
    private TextView endLabel;
    private Button choose;
    private Button play;
    private Button save;
    private SeekBar startSlider;
    private SeekBar endSlider;
    private ProgressBar progress;

    private Uri clip;
    private int duration;
    private int start;
    private int end;
    private int resumePosition;
    private boolean busy;
    private Transformer transformer;
    private File exporting;

    private final Runnable monitorPlayback = new Runnable() {
        @Override
        public void run() {
            if (video.isPlaying() && video.getCurrentPosition() >= end) {
                pausePreview();
                video.seekTo(start);
                return;
            }
            handler.postDelayed(this, 35);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        buildLayout();
        refreshControls();
        if (state != null && state.getString("clip") != null) {
            openClip(Uri.parse(state.getString("clip")), state.getInt("start"), state.getInt("end"));
        }
    }

    private void buildLayout() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(page);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets safe = insets.getInsets(
                        android.view.WindowInsets.Type.systemBars()
                                | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            }
            return insets;
        });

        TextView title = text("VeloraNova", 32, INK);
        title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        add(page, title, 0);
        choose = button("เลือกวิดีโอ", false);
        choose.setOnClickListener(view -> chooseClip());
        add(page, choose, 14);

        preview = new FrameLayout(this);
        preview.setBackground(shape(0xff0d0c16, 18));
        preview.setClipToOutline(true);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(230));
        previewParams.topMargin = dp(16);
        page.addView(preview, previewParams);
        video = new VideoView(this);
        preview.addView(video, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        placeholder = text("เลือกวิดีโอเพื่อดูตัวอย่าง", 15, MUTED);
        placeholder.setGravity(Gravity.CENTER);
        preview.addView(placeholder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        play = button("เล่นช่วงที่เลือก", false);
        play.setOnClickListener(view -> togglePreview());
        add(page, play, 8);

        LinearLayout timeline = column();
        timeline.setBackground(shape(PANEL, 18));
        timeline.setPadding(dp(16), dp(12), dp(16), dp(8));
        add(page, timeline, 12);
        startLabel = text("", 15, INK);
        startSlider = slider("จุดเริ่มต้น", true);
        endLabel = text("", 15, INK);
        endSlider = slider("จุดสิ้นสุด", false);
        add(timeline, startLabel, 0);
        add(timeline, startSlider, 0);
        add(timeline, endLabel, 4);
        add(timeline, endSlider, 0);

        save = button("บันทึก MP4", true);
        save.setOnClickListener(view -> exportClip());
        add(page, save, 14);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        progress.setVisibility(View.GONE);
        add(page, progress, 4);
        status = text("", 14, MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        add(page, status, 8);
    }

    private void chooseClip() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("video/*");
        picker.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        try {
            startActivityForResult(picker, PICK_VIDEO);
        } catch (android.content.ActivityNotFoundException error) {
            status.setText("ไม่พบแอปสำหรับเลือกไฟล์วิดีโอ");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_VIDEO || result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri selected = data.getData();
        if (!ContentResolver.SCHEME_CONTENT.equals(selected.getScheme())) {
            status.setText("กรุณาเลือกไฟล์วิดีโอจากเครื่อง");
            return;
        }
        openClip(selected, 0, -1);
    }

    private void openClip(Uri selected, int requestedStart, int requestedEnd) {
        video.stopPlayback();
        clip = selected;
        duration = 0;
        start = 0;
        end = 0;
        resumePosition = 0;
        status.setText("กำลังเปิดวิดีโอ…");
        refreshControls();
        video.setOnPreparedListener(player -> {
            boolean firstPreparation = duration == 0;
            duration = video.getDuration();
            if (duration <= 0) {
                resetClip("อ่านความยาววิดีโอไม่ได้");
                return;
            }
            if (firstPreparation) {
                int minimum = Math.min(duration, MIN_CLIP_MS);
                start = Math.max(0, Math.min(requestedStart, duration - minimum));
                end = requestedEnd < 0 ? duration
                        : Math.max(start + minimum, Math.min(requestedEnd, duration));
                resumePosition = start;
                status.setText("");
            }
            video.seekTo(resumePosition);
            refreshControls();
        });
        video.setOnCompletionListener(player -> {
            pausePreview();
            video.seekTo(start);
        });
        video.setOnErrorListener((player, what, extra) -> {
            resetClip("เปิดวิดีโอนี้ไม่ได้ ลองเลือกไฟล์ MP4 จากเครื่อง");
            return true;
        });
        video.setVideoURI(selected);
    }

    private void resetClip(String message) {
        clip = null;
        duration = 0;
        start = 0;
        end = 0;
        status.setText(message);
        refreshControls();
    }

    private SeekBar slider(String description, boolean isStart) {
        SeekBar slider = new SeekBar(this);
        slider.setContentDescription(description);
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT));
        slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (!fromUser || duration == 0 || busy) {
                    return;
                }
                pausePreview();
                int minimum = Math.min(duration, MIN_CLIP_MS);
                if (isStart) {
                    start = Math.min(value, end - minimum);
                    video.seekTo(start);
                } else {
                    end = Math.max(value, start + minimum);
                    video.seekTo(Math.max(start, end - 1));
                }
                refreshControls();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {}

            @Override
            public void onStopTrackingTouch(SeekBar bar) {}
        });
        return slider;
    }

    private void togglePreview() {
        if (video.isPlaying()) {
            pausePreview();
            return;
        }
        if (video.getCurrentPosition() < start || video.getCurrentPosition() >= end - 40) {
            video.seekTo(start);
        }
        video.start();
        play.setText("หยุดชั่วคราว");
        handler.removeCallbacks(monitorPlayback);
        handler.post(monitorPlayback);
    }

    private void pausePreview() {
        video.pause();
        play.setText("เล่นช่วงที่เลือก");
        handler.removeCallbacks(monitorPlayback);
    }

    private void refreshControls() {
        boolean ready = duration > 0;
        choose.setEnabled(!busy);
        for (View control : new View[] {play, save, startSlider, endSlider}) {
            control.setEnabled(ready && !busy);
            control.setAlpha(ready && !busy ? 1f : 0.55f);
        }
        placeholder.setVisibility(ready ? View.GONE : View.VISIBLE);
        video.setVisibility(ready || clip != null ? View.VISIBLE : View.INVISIBLE);
        startSlider.setMax(duration);
        endSlider.setMax(duration);
        startSlider.setProgress(start);
        endSlider.setProgress(end);
        startLabel.setText("เริ่ม  " + time(start));
        endLabel.setText("สิ้นสุด  " + time(end));
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    private void exportClip() {
        if (clip == null || duration <= 0 || busy) {
            return;
        }
        pausePreview();
        busy = true;
        status.setText("กำลังสร้างไฟล์ MP4…");
        refreshControls();
        try {
            File output = File.createTempFile("veloranova-", ".mp4", getCacheDir());
            exporting = output;
            MediaItem media = new MediaItem.Builder()
                    .setUri(clip)
                    .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(start)
                            .setEndPositionMs(end == duration ? androidx.media3.common.C.TIME_END_OF_SOURCE : end)
                            .build())
                    .build();
            transformer = new Transformer.Builder(this)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(new Transformer.Listener() {
                        @Override
                        public void onCompleted(Composition composition, ExportResult result) {
                            transformer = null;
                            exporting = null;
                            storage.execute(() -> publish(output));
                        }

                        @Override
                        public void onError(Composition composition, ExportResult result, ExportException error) {
                            failExport("ส่งออกไม่ได้ ลองเลือกวิดีโอ MP4 ช่วงอื่น");
                        }
                    })
                    .build();
            transformer.start(new EditedMediaItem.Builder(media).build(), output.getAbsolutePath());
        } catch (IOException | RuntimeException error) {
            failExport("เริ่มส่งออกไม่ได้ ตรวจสอบไฟล์และพื้นที่จัดเก็บ");
        }
    }

    private void failExport(String message) {
        if (transformer != null) {
            transformer.cancel();
            transformer = null;
        }
        if (exporting != null) {
            deleteTemporaryFile(exporting);
            exporting = null;
        }
        finishExport(message);
    }

    private void publish(File output) {
        ContentResolver resolver = getContentResolver();
        Uri pending = null;
        String message;
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(new Date());
            ContentValues metadata = new ContentValues();
            metadata.put(MediaStore.Video.Media.DISPLAY_NAME, "VeloraNova-" + stamp + ".mp4");
            metadata.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            metadata.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/VeloraNova");
            metadata.put(MediaStore.Video.Media.IS_PENDING, 1);
            pending = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, metadata);
            if (pending == null) {
                throw new IOException("Unable to reserve destination");
            }
            try (OutputStream stream = resolver.openOutputStream(pending, "w")) {
                if (stream == null) {
                    throw new IOException("Unable to open destination");
                }
                Files.copy(output.toPath(), stream);
            }
            ContentValues completed = new ContentValues();
            completed.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (resolver.update(pending, completed, null, null) != 1) {
                throw new IOException("Unable to publish destination");
            }
            pending = null;
            message = "บันทึกใน Movies/VeloraNova แล้ว";
        } catch (IOException | RuntimeException error) {
            message = "บันทึกไม่ได้ ตรวจสอบพื้นที่จัดเก็บ";
        } finally {
            if (pending != null) {
                try {
                    resolver.delete(pending, null, null);
                } catch (RuntimeException error) {
                    android.util.Log.w("VeloraNova", "Unable to remove incomplete video", error);
                }
            }
            deleteTemporaryFile(output);
        }
        String result = message;
        runOnUiThread(() -> finishExport(result));
    }

    private void deleteTemporaryFile(File file) {
        if (!file.delete() && file.exists()) {
            android.util.Log.w("VeloraNova", "Unable to remove temporary export");
        }
    }

    private void finishExport(String message) {
        busy = false;
        if (!isDestroyed()) {
            status.setText(message);
            refreshControls();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (duration > 0) {
            video.seekTo(resumePosition);
        }
    }

    @Override
    protected void onPause() {
        resumePosition = video.getCurrentPosition();
        pausePreview();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (clip != null) {
            state.putString("clip", clip.toString());
            state.putInt("start", start);
            state.putInt("end", duration > 0 ? end : -1);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (transformer != null) {
            transformer.cancel();
        }
        if (exporting != null) {
            deleteTemporaryFile(exporting);
        }
        video.stopPlayback();
        storage.shutdown();
        super.onDestroy();
    }

    private static String time(int millis) {
        return String.format(Locale.ROOT, "%d:%02d.%01d", millis / 60000,
                millis / 1000 % 60, millis / 100 % 10);
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private void add(LinearLayout group, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(margin);
        group.addView(child, params);
    }

    private TextView text(String value, int size, int color) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        return label;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(label);
        button.setTextColor(primary ? BACKGROUND : INK);
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x34ffffff), shape(primary ? ACCENT : PANEL, 13), null));
        button.setMinHeight(dp(48));
        return button;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
