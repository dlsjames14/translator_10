package app.screentranslator;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs while the translator is on. It owns:
 * - the screen capture (Android's MediaProjection, mirrored into an ImageReader),
 * - the floating bubble,
 * - the "drag a box" selection layer with its Translate once / Live switch,
 * - the result card (once) and the live panel (Live mode).
 * Everything that touches the capture or the overlays runs on the main thread.
 */
public class TranslatorService extends Service {

    static final String ACTION_START = "app.screentranslator.action.START";
    static final String ACTION_STOP = "app.screentranslator.action.STOP";
    /** Sent by the app after the microphone permission was granted: allow audio translation now. */
    static final String ACTION_ALLOW_AUDIO = "app.screentranslator.action.ALLOW_AUDIO";
    static final int AUDIO_PHONE = 1;          // audio translation of the phone's sound
    static final int AUDIO_MIC = 2;            // audio translation of the microphone
    private static final int AUDIO_CLIP_SECONDS = 12;             // longest clip sent to Gemini / ChatGPT
    private static final int AUDIO_CLIP_SECONDS_ON_DEVICE = 20;   // Gemma hears up to 30 s
    private static final int AUDIO_MAX_WAITING_CLIPS = 3;         // more than this: skip the oldest
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String CHANNEL_ID = "translator";
    private static final int NOTIFICATION_ID = 42;
    /** Time for the screen to redraw after our own overlays disappear, so they aren't in the screenshot. */
    private static final long SETTLE_MS = 180;
    private static final long MAX_WAIT_MS = 1200;

    // Live mode tuning
    private static final long LIVE_TICK_MS = 300;             // how often the screen is checked for changes
    private static final long LIVE_SETTLE_MS = 700;           // wait until the screen stops changing...
    private static final long LIVE_MAX_DELAY_MS = 3000;       // ...but never longer than this (e.g. videos)
    private static final long LIVE_MIN_INTERVAL_MS = 1500;    // at most one request this often
    private static final long LIVE_MAX_INTERVAL_MS = 8000;    // slowest pace when nothing new turns up
    private static final long LIVE_ERROR_BACKOFF_MS = 6000;
    private static final long LIVE_AUTO_PAUSE_MS = 20 * 60 * 1000L;
    private static final int LIVE_SAMPLES = 40000;            // pixels compared per check
    private static final int LIVE_PIXEL_DIFF = 28;            // brightness change that counts as "different"
    private static final float LIVE_BIG_CHANGE = 0.05f;       // 5% of the area: e.g. scrolled to new content
    private static final int LIVE_SUMMARY_SENTENCES = 3;      // summarize after about this many new sentences...
    private static final long LIVE_SUMMARY_WAIT_MS = 6000;    // ...or when no new text came for this long
    private static final int LIVE_SUMMARY_MAX_CHARS = 900;
    private static final int LIVE_SUMMARY_CONTEXT = 3;        // earlier summaries given as context

    private static final int COLOR_WATCHING = 0xFF43A047;
    private static final int COLOR_BUSY = 0xFFFFB300;
    private static final int COLOR_PAUSED = 0xFF9E9E9E;
    private static final int COLOR_LIVE_BUBBLE = 0xFFE53935;
    private static final int MASK_COLOR = 0xFF808080;

    private static volatile boolean running;

    static boolean isRunning() {
        return running;
    }

    /** State of one live translation session. */
    private static final class LiveSession {
        final boolean wholeScreen;
        Rect region;          // area being watched, in screen pixels
        Point screen;         // screen size when the area was chosen
        LiveLines lines;      // what the panel shows, and sentences waiting to finish
        int audioSource;      // 0 for watching the screen; AUDIO_PHONE or AUDIO_MIC for audio translation
        final List<short[]> audioQueue = new ArrayList<>();   // clips of speech waiting to be translated
        final List<String> reading = new ArrayList<>();       // text being translated right now (shown greyed)
        boolean summaryMode;
        final List<Translation.Line> pending = new ArrayList<>();               // waiting to be summarized
        final ArrayDeque<String> summaries = new ArrayDeque<>();
        long lastNewLineAt;
        int[] currentSig;     // what the area looks like now
        int[] sentSig;        // what it looked like when last translated
        int lastFrameChecked = -1;
        long startedAt;
        long lastChangeAt;
        long pendingSince;
        long sentAt;
        long nextAllowedAt;
        long errorUntil;
        long minInterval = LIVE_MIN_INTERVAL_MS;   // depends on the engine
        long maxInterval = LIVE_MAX_INTERVAL_MS;
        long interval = LIVE_MIN_INTERVAL_MS;
        boolean inFlight;
        boolean paused;
        boolean panelAtBottom;
        int checks;
        String lastError;

        LiveSession(boolean wholeScreen, Rect region, Point screen) {
            this.wholeScreen = wholeScreen;
            this.region = region;
            this.screen = screen;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // Reads the screenshot's text on the phone, to show it while Gemini or ChatGPT translate it.
    private final ExecutorService previewWorker = Executors.newSingleThreadExecutor();
    private final ScreenText previewReader = new ScreenText();
    // The translation engine. Used only on the worker thread.
    private TranslationEngine engine;
    private String engineIdentity;
    // What was translated while the bubble is on (see TranslationMemory).
    private final TranslationMemory memory = new TranslationMemory();

    private Context ui;
    private WindowManager windowManager;

    // Screen capture
    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private Image latestFrame;
    private int frameCount;
    private int captureWidth;
    private int captureHeight;
    private int captureDpi;

    // Overlays
    private View bubble;
    private GradientDrawable bubbleBackground;
    private WindowManager.LayoutParams bubbleParams;
    private View selectionLayer;
    private View card;
    private TextView cardTitle;
    private TextView cardBody;
    private TextView cardCopy;
    private TextView cardSummaryChip;
    private String cardLanguage = Prefs.DEFAULT_LANGUAGE;
    private int requestId;
    private EngineSettings cardSettings;
    private String cardFullText;       // the full translation on the card
    private String cardSummary;        // its short summary, once made
    private boolean cardShowingSummary;
    private boolean cardSummarizing;
    private String cardProgressMessage;   // while translating: the engine's latest message…
    private List<String> cardReading;     // …and the text it's translating (shown greyed)

    // Reading aloud
    private Speaker speaker;
    private AudioListener audioListener;
    private LinearLayout closeTarget;     // the ✕ shown while dragging the bubble: drop it there to turn off
    private TextView closeCircle;
    private boolean overCloseTarget;
    private TextView cardFix;             // "Open app" on a card that shows a problem the app can fix
    private boolean foregroundWithMicrophone;   // the service may record (needs the permission at start)
    private TextView cardSpeak;
    private String cardSpokenText;
    private boolean cardShowOriginal;

    // Live mode
    private LiveSession live;
    private LivePanel livePanel;
    private final Runnable liveTick = new Runnable() {
        @Override
        public void run() {
            if (live == null) {
                return;
            }
            tickLive();
            main.postDelayed(this, LIVE_TICK_MS);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            start(intent);
        } else if (ACTION_STOP.equals(action)) {
            shutdown();
        } else if (ACTION_ALLOW_AUDIO.equals(action)) {
            if (running) {
                goForeground();   // now with the microphone allowed
                toast("Listening is allowed. Tap the bubble, then 🎧 Audio.");
            } else {
                stopSelf();
            }
        } else if (!running) {
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        removeOverlays();
        releaseCapture();
        releaseSpeaker();
        worker.execute(this::closeEngine);   // frees the on-device AI's memory
        worker.shutdown();
        previewWorker.execute(previewReader::close);
        previewWorker.shutdown();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Screen rotated: let the window context catch up, then keep everything on screen.
        main.postDelayed(this::fitOverlaysToScreen, 200);
    }

    // ----------------------------------------------------------------------------------------
    // Start / stop

    private void start(Intent intent) {
        goForeground();   // Android requires this before the screen capture can begin.

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class)
                : legacyResultData(intent);

        removeOverlays();
        releaseCapture();
        if (data == null) {
            toast("Screen capture permission was missing. Open Screen Translator and tap Start again.");
            shutdown();
            return;
        }
        try {
            prepareWindows();
            startCapture(resultCode, data);
            showBubble();
            Prefs tipPrefs = new Prefs(this);
            if (!tipPrefs.bubbleTipShown()) {
                tipPrefs.setBubbleTipShown();
                toast("Tap the round bubble to translate. Drag it to move it, or onto ✕ to turn it off.");
            }
            running = true;
        } catch (RuntimeException e) {
            toast("Couldn't start screen capture: " + e.getMessage());
            shutdown();
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent legacyResultData(Intent intent) {
        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private void goForeground() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Screen Translator", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shown while the translator bubble is on");
        notifications.createNotificationChannel(channel);

        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, TranslatorService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_translate)
                .setContentTitle("Screen Translator is on")
                .setContentText("Tap the bubble in any app to translate.")
                .setContentIntent(openApp)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_translate), "Stop", stop).build())
                .build();

        // Recording (audio translation) is only allowed if the service says so when it starts, and
        // Android only lets it say so once the microphone permission is granted.
        boolean canRecord = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
        if (canRecord) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
            startForeground(NOTIFICATION_ID, notification, types);
            foregroundWithMicrophone = canRecord;
        } catch (RuntimeException e) {
            // Android refused the microphone part (e.g. the app wasn't open just now): carry on without it.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            foregroundWithMicrophone = false;
        }
    }

    private void shutdown() {
        running = false;
        requestId++;
        removeOverlays();
        releaseSpeaker();
        releaseCapture();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    // ----------------------------------------------------------------------------------------
    // Screen capture

    private void prepareWindows() {
        if (windowManager != null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            ui = createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        } else {
            ui = this;
        }
        windowManager = ui.getSystemService(WindowManager.class);
    }

    private void startCapture(int resultCode, Intent data) {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        final MediaProjection session = manager.getMediaProjection(resultCode, data);
        if (session == null) {
            throw new IllegalStateException("permission was not granted");
        }
        projection = session;
        session.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // The user tapped "Stop sharing" in the status bar, or Android ended it.
                if (projection == session) {
                    toast("Screen sharing ended, so Screen Translator turned off.");
                    shutdown();
                }
            }
        }, main);

        Point size = screenSize();
        captureWidth = size.x;
        captureHeight = size.y;
        captureDpi = ui.getResources().getDisplayMetrics().densityDpi;
        reader = newReader(captureWidth, captureHeight);
        virtualDisplay = session.createVirtualDisplay("ScreenTranslator", captureWidth, captureHeight, captureDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, main);
    }

    @SuppressLint("WrongConstant")
    private ImageReader newReader(int width, int height) {
        ImageReader imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
        imageReader.setOnImageAvailableListener(this::onFrame, main);
        return imageReader;
    }

    /**
     * Keeps only the newest frame, so a capture is instant when needed. Android only sends a new
     * frame when something on screen changed, which is also what Live mode relies on.
     */
    private void onFrame(ImageReader source) {
        if (source != reader) {
            return;
        }
        Image image;
        try {
            image = source.acquireLatestImage();
        } catch (IllegalStateException e) {
            return;
        }
        if (image == null) {
            return;
        }
        if (latestFrame != null) {
            latestFrame.close();
        }
        latestFrame = image;
        frameCount++;
    }

    /** If the phone was rotated, resize the capture to match the screen. */
    private void matchCaptureToScreen() {
        if (virtualDisplay == null) {
            return;
        }
        Point size = screenSize();
        if (size.x == captureWidth && size.y == captureHeight) {
            return;
        }
        captureWidth = size.x;
        captureHeight = size.y;
        if (latestFrame != null) {
            latestFrame.close();
            latestFrame = null;
        }
        ImageReader old = reader;
        reader = newReader(captureWidth, captureHeight);
        virtualDisplay.resize(captureWidth, captureHeight, captureDpi);
        virtualDisplay.setSurface(reader.getSurface());
        old.setOnImageAvailableListener(null, null);
        old.close();
    }

    private void releaseCapture() {
        if (latestFrame != null) {
            latestFrame.close();
            latestFrame = null;
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (reader != null) {
            reader.setOnImageAvailableListener(null, null);
            reader.close();
            reader = null;
        }
        if (projection != null) {
            MediaProjection old = projection;
            projection = null;
            try {
                old.stop();
            } catch (RuntimeException ignored) {
                // Already stopped.
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Point screenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect bounds = windowManager.getMaximumWindowMetrics().getBounds();
            return new Point(bounds.width(), bounds.height());
        }
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new Point(metrics.widthPixels, metrics.heightPixels);
    }

    /** Copies a frame into a bitmap (the bitmap may be a little wider than the frame). */
    private static Bitmap copyFrame(Image frame) {
        int height = frame.getHeight();
        Image.Plane plane = frame.getPlanes()[0];
        int paddedWidth = plane.getRowStride() / plane.getPixelStride();
        ByteBuffer pixels = plane.getBuffer();
        pixels.rewind();
        int needed = paddedWidth * height * 4;
        if (pixels.remaining() < needed) {
            // Some phones leave the last row short; pad it so the copy below fits.
            ByteBuffer padded = ByteBuffer.allocate(needed);
            padded.put(pixels);
            padded.rewind();
            pixels = padded;
        }
        Bitmap full = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
        full.copyPixelsFromBuffer(pixels);
        return full;
    }

    /** Converts a rectangle in screen pixels to frame pixels, clipped to the frame. */
    private static Rect toFrame(Rect screenRect, Point screen, int frameWidth, int frameHeight) {
        float sx = frameWidth / (float) screen.x;
        float sy = frameHeight / (float) screen.y;
        Rect rect = new Rect(Math.round(screenRect.left * sx), Math.round(screenRect.top * sy),
                Math.round(screenRect.right * sx), Math.round(screenRect.bottom * sy));
        if (!rect.intersect(0, 0, frameWidth, frameHeight)) {
            rect.setEmpty();
        }
        return rect;
    }

    private static Bitmap cut(Bitmap full, Rect box, int frameWidth, int frameHeight) {
        Rect area = box.isEmpty() ? new Rect(0, 0, frameWidth, frameHeight) : box;
        Bitmap result = Bitmap.createBitmap(full, area.left, area.top, area.width(), area.height());
        if (result != full) {
            full.recycle();
        }
        return result;
    }

    /** "Translate once": cuts out the chosen area (or the whole frame). Closes the frame. */
    private static Bitmap crop(Image frame, Rect area, Point screen) {
        try {
            int width = frame.getWidth();
            int height = frame.getHeight();
            Bitmap full = copyFrame(frame);
            Rect box = area == null ? new Rect(0, 0, width, height) : toFrame(area, screen, width, height);
            return cut(full, box, width, height);
        } finally {
            frame.close();
        }
    }

    /** Live mode: cuts out the watched area with our own panel and bubble painted over in grey. */
    private static Bitmap cropMasked(Image frame, Rect region, Point screen, List<Rect> masks) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        Bitmap full = copyFrame(frame);
        Canvas canvas = new Canvas(full);
        Paint grey = new Paint();
        grey.setColor(MASK_COLOR);
        for (Rect mask : masks) {
            Rect inFrame = toFrame(mask, screen, width, height);
            if (!inFrame.isEmpty()) {
                canvas.drawRect(inFrame, grey);
            }
        }
        return cut(full, toFrame(region, screen, width, height), width, height);
    }

    // ----------------------------------------------------------------------------------------
    // Selecting an area

    private void startSelection() {
        if (selectionLayer != null || virtualDisplay == null) {
            return;
        }
        hideCard();
        if (live == null) {
            matchCaptureToScreen();   // (Live mode keeps watching underneath until a new choice is made)
        }
        final Point screen = new Point(captureWidth, captureHeight);
        final Prefs prefs = new Prefs(this);
        final String[] mode = {prefs.selectionMode()};

        FrameLayout layer = new FrameLayout(ui);
        final SelectionView selection = new SelectionView(ui,
                area -> onAreaChosen(area, screen, Prefs.MODE_LIVE.equals(mode[0])));
        layer.addView(selection, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 🎧 Audio: instead of choosing an area, choose what to listen to.
        final LinearLayout audioChoices = audioChoices();
        layer.addView(audioChoices, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // "Once | ● Live | 🎧 Audio" switch
        LinearLayout modes = new LinearLayout(ui);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modes.setPadding(dp(4), dp(4), dp(4), dp(4));
        modes.setBackground(Ui.rounded(0xCC000000, dp(26), 0x66FFFFFF, dp(1)));
        final TextView onceTab = modeTab("Once");
        final TextView liveTab = modeTab("● Live");
        final TextView audioTab = modeTab("🎧 Audio");
        final Runnable paintTabs = () -> {
            boolean audio = Prefs.MODE_AUDIO.equals(mode[0]);
            styleTab(onceTab, Prefs.MODE_ONCE.equals(mode[0]));
            styleTab(liveTab, Prefs.MODE_LIVE.equals(mode[0]));
            styleTab(audioTab, audio);
            selection.setLive(Prefs.MODE_LIVE.equals(mode[0]));
            selection.setVisibility(audio ? View.INVISIBLE : View.VISIBLE);
            audioChoices.setVisibility(audio ? View.VISIBLE : View.GONE);
        };
        View.OnClickListener pick = v -> {
            mode[0] = v == audioTab ? Prefs.MODE_AUDIO : v == liveTab ? Prefs.MODE_LIVE : Prefs.MODE_ONCE;
            prefs.setSelectionMode(mode[0]);
            paintTabs.run();
        };
        onceTab.setOnClickListener(pick);
        liveTab.setOnClickListener(pick);
        audioTab.setOnClickListener(pick);
        paintTabs.run();
        modes.addView(onceTab);
        modes.addView(liveTab);
        modes.addView(audioTab);
        FrameLayout.LayoutParams modesParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        modesParams.bottomMargin = dp(150);
        layer.addView(modes, modesParams);

        TextView cancel = new TextView(ui);
        cancel.setText("Cancel");
        cancel.setTextColor(Color.WHITE);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cancel.setTypeface(Typeface.DEFAULT_BOLD);
        cancel.setPadding(dp(28), dp(12), dp(28), dp(12));
        cancel.setBackground(Ui.rounded(0xCC000000, dp(24), 0x66FFFFFF, dp(1)));
        cancel.setOnClickListener(v -> {
            removeSelection();
            if (bubble != null) {
                bubble.setVisibility(View.VISIBLE);
            }
        });
        FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        cancelParams.bottomMargin = dp(88);
        layer.addView(cancel, cancelParams);

        WindowManager.LayoutParams params = overlayParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        windowManager.addView(layer, params);
        selectionLayer = layer;
        if (bubble != null) {
            bubble.setVisibility(View.INVISIBLE);
        }
    }

    private TextView modeTab(String label) {
        TextView tab = new TextView(ui);
        tab.setText(label);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tab.setTypeface(Typeface.DEFAULT_BOLD);
        tab.setGravity(Gravity.CENTER);
        tab.setPadding(dp(16), dp(10), dp(16), dp(10));
        return tab;
    }

    /** The two buttons of the 🎧 Audio tab. */
    private LinearLayout audioChoices() {
        LinearLayout box = new LinearLayout(ui);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setBackground(Ui.rounded(0xE6000000, dp(22), 0x66FFFFFF, dp(1)));
        TextView heading = new TextView(ui);
        heading.setText("Translate what's being said");
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setGravity(Gravity.CENTER);
        box.addView(heading);
        box.addView(audioChoice("📱  Sound on this phone",
                "Videos, livestreams, voice messages: even without subtitles", () -> startAudio(false)));
        box.addView(audioChoice("🎙  Microphone",
                "People talking near you: a conversation, a class, an announcement", () -> startAudio(true)));
        return box;
    }

    private View audioChoice(String title, String note, Runnable action) {
        LinearLayout choice = new LinearLayout(ui);
        choice.setOrientation(LinearLayout.VERTICAL);
        choice.setPadding(dp(16), dp(12), dp(16), dp(12));
        choice.setBackground(Ui.rounded(0xFFFFFFFF, dp(16)));
        TextView name = new TextView(ui);
        name.setText(title);
        name.setTextColor(0xFF1B1C1F);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        choice.addView(name);
        TextView detail = new TextView(ui);
        detail.setText(note);
        detail.setTextColor(0xFF5F6368);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        choice.addView(detail);
        choice.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(290), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        choice.setLayoutParams(params);
        return choice;
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setBackground(selected ? Ui.rounded(0xFFFFFFFF, dp(22)) : null);
        tab.setTextColor(selected ? 0xFF1B1C1F : 0xFFFFFFFF);
    }

    private void onAreaChosen(final Rect area, final Point screen, boolean liveMode) {
        stopLive();
        if (liveMode) {
            removeSelection();
            startLive(area, screen);
            return;
        }
        removeSelection();   // removed right away so it isn't in the screenshot
        if (bubble != null) {
            bubble.setVisibility(View.INVISIBLE);
        }
        final int framesBefore = frameCount;
        final long removedAt = SystemClock.uptimeMillis();
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!running) {
                    return;
                }
                long waited = SystemClock.uptimeMillis() - removedAt;
                boolean fresh = frameCount > framesBefore && latestFrame != null;
                if (!fresh && waited < MAX_WAIT_MS) {
                    main.postDelayed(this, 50);
                    return;
                }
                Image frame = latestFrame;
                latestFrame = null;
                if (bubble != null) {
                    bubble.setVisibility(View.VISIBLE);
                }
                boolean cardAtBottom = area == null || area.centerY() < screen.y / 2;
                Prefs prefs = new Prefs(TranslatorService.this);
                showCard(cardAtBottom, prefs.language());
                if (frame == null) {
                    setCardResult("Couldn't read the screen. Please try again.", false);
                    return;
                }
                Bitmap shot;
                try {
                    shot = crop(frame, area, screen);
                } catch (RuntimeException e) {
                    setCardResult("Couldn't read the screen. Please try again.", false);
                    return;
                }
                translateOnce(shot, prefs);
            }
        }, SETTLE_MS);
    }

    // ----------------------------------------------------------------------------------------
    // Translate once

    private void translateOnce(final Bitmap shot, Prefs prefs) {
        final int id = ++requestId;
        final EngineSettings settings = new EngineSettings(prefs);
        cardSettings = settings;
        cardShowOriginal = settings.showOriginal;
        cardProgressMessage = null;
        cardReading = null;
        memory.useLanguage(settings.language);
        final Translation.Request request = new Translation.Request(settings.language, settings.showOriginal,
                cardStatus(id), memory.copy(), null, null, null);
        request.reading = texts -> main.post(() -> showCardReading(id, texts));
        final CountDownLatch previewDone = startPreview(shot, settings, request.reading);
        worker.execute(() -> {
            String text;
            boolean ok;
            boolean fixable = false;
            try {
                Translation.Result result = engineFor(settings).translateOnce(shot, request);
                text = result.text;
                ok = true;
                // Remember it, so later translations stay consistent with it.
                if (!result.lines.isEmpty()) {
                    memory.addAll(result.lines);
                } else if (!Translation.NO_TEXT.equals(text.trim())) {
                    memory.addTranslationOnly(Speaker.translationsOnly(text, settings.showOriginal));
                }
            } catch (TranslateException e) {
                text = e.getMessage();
                ok = false;
                fixable = e.fatal;   // e.g. a missing or wrong key: fixed in the app
            } catch (RuntimeException e) {
                text = "Something went wrong: " + e.getMessage();
                ok = false;
            } finally {
                awaitQuietly(previewDone);
                shot.recycle();
            }
            final String result = text;
            final boolean success = ok;
            final boolean canFix = fixable;
            main.post(() -> {
                if (id == requestId && card != null) {
                    setCardResult(result, success);
                    cardFix.setVisibility(canFix ? View.VISIBLE : View.GONE);
                }
            });
        });
    }

    /** While translating: the text found on screen, greyed, under the progress message. */
    private void showCardReading(int id, List<String> texts) {
        if (id != requestId || card == null || cardFullText != null || texts.isEmpty()) {
            return;
        }
        cardReading = new ArrayList<>(texts);
        showCardProgress();
    }

    private void showCardProgress() {
        if (card == null || cardBody == null) {
            return;
        }
        String message = cardProgressMessage == null ? "Translating…" : cardProgressMessage;
        if (cardReading == null || cardReading.isEmpty()) {
            cardBody.setText(message);
            return;
        }
        android.text.SpannableStringBuilder body = new android.text.SpannableStringBuilder(message + "\n\n");
        int start = body.length();
        int chars = 0;
        for (String text : cardReading) {
            if (chars > 600) {
                body.append("…");
                break;
            }
            body.append(text).append("\n");
            chars += text.length();
        }
        body.setSpan(new android.text.style.ForegroundColorSpan(Ui.isDark(ui) ? 0x99FFFFFF : 0x8A000000),
                start, body.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        body.setSpan(new android.text.style.RelativeSizeSpan(0.9f), start, body.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        cardBody.setText(body);
    }

    /** Shows the engine's progress messages on the card while it's working. */
    private Translation.StatusListener cardStatus(int id) {
        return message -> main.post(() -> {
            if (id == requestId && card != null && cardBody != null && cardFullText == null) {
                cardProgressMessage = message;
                showCardProgress();
            }
        });
    }

    /** Makes the short summary of the card's translation (1–3 sentences), then shows it if still wanted. */
    private void summarizeCard() {
        final String text = cardFullText;
        final EngineSettings settings = cardSettings;
        if (text == null || settings == null || cardSummarizing) {
            return;
        }
        final int id = requestId;
        cardSummarizing = true;
        final Translation.Request request = new Translation.Request(settings.language, settings.showOriginal,
                message -> main.post(() -> {
                    if (id == requestId && card != null && cardShowingSummary && cardSummary == null) {
                        cardBody.setText(message);
                    }
                }));
        worker.execute(() -> {
            String summary = null;
            String problem = null;
            try {
                summary = engineFor(settings).summarize(text, "", request);
                if (summary.isEmpty()) {
                    problem = "The AI didn't send a summary. Tap Summary to try again.";
                }
            } catch (TranslateException e) {
                problem = e.getMessage();
            } catch (RuntimeException e) {
                problem = "Something went wrong: " + e.getMessage();
            }
            final String result = summary;
            final String error = problem;
            main.post(() -> {
                if (id != requestId || card == null) {
                    return;
                }
                cardSummarizing = false;
                if (error != null) {
                    cardShowingSummary = false;
                    showCardFull();
                    toast("Couldn't summarize: " + error);
                    return;
                }
                cardSummary = result;
                if (cardShowingSummary) {
                    showCardSummary();
                    if (new Prefs(this).readAloud()) {
                        say(cardSpokenText, cardLanguage, true);
                    }
                }
            });
        });
    }

    /**
     * The engine for these settings, created on first use and kept while the settings stay the same
     * (the on-device AI takes a few seconds to load). Called on the background thread only.
     */
    private TranslationEngine engineFor(EngineSettings settings) {
        String identity = settings.identity();
        if (engine == null || !identity.equals(engineIdentity)) {
            closeEngine();
            engine = settings.create(getApplicationContext());
            engineIdentity = identity;
        }
        return engine;
    }

    /** Called on the background thread only. */
    private void closeEngine() {
        if (engine != null) {
            engine.close();
            engine = null;
            engineIdentity = null;
        }
    }

    // ----------------------------------------------------------------------------------------
    // Live mode

    private void startLive(Rect area, Point screen) {
        hideCard();
        boolean whole = area == null;
        LiveSession session = new LiveSession(whole, whole ? wholeScreenRegion() : new Rect(area), new Point(screen));
        long now = SystemClock.uptimeMillis();
        session.startedAt = now;
        session.lastChangeAt = now;   // lets the screen settle after the selection layer is gone
        session.panelAtBottom = whole || area.centerY() < screen.y / 2;
        Prefs prefs = new Prefs(this);
        EngineSettings settings = new EngineSettings(prefs);
        session.minInterval = settings.liveMinIntervalMs();
        session.maxInterval = settings.liveMaxIntervalMs();
        session.interval = session.minInterval;
        session.lines = new LiveLines(prefs.waitForSentences(), true,
                line -> memory.add(line.original, line.translation));
        beginSession(session, prefs, settings);
        livePanel.setStatus("Watching for new text…", COLOR_WATCHING);
    }

    /** Shared by Live and audio translation: settings, the panel, the red bubble, the ticking. */
    private void beginSession(LiveSession session, Prefs prefs, EngineSettings settings) {
        session.summaryMode = prefs.summaryMode();
        memory.useLanguage(settings.language);
        live = session;

        livePanel = new LivePanel(ui, prefs.language(), new LivePanel.Actions() {
            @Override
            public void onPauseToggle() {
                if (live == null) {
                    return;
                }
                if (live.paused) {
                    resumeLive();
                } else {
                    pauseLive(null);
                }
            }

            @Override
            public void onClear() {
                // A fresh start, e.g. for the next video: the list, and the earlier text the AI
                // is reminded of. (What's on screen right now still isn't translated again.)
                if (livePanel != null) {
                    livePanel.clear();
                }
                memory.clear();
                if (live != null) {
                    live.pending.clear();
                    live.summaries.clear();
                    live.reading.clear();
                    live.lines.forgetOlder(5);   // keep only what's probably still on screen
                }
            }

            @Override
            public void onSummaryToggle() {
                toggleLiveSummary();
            }

            @Override
            public void onMove() {
                moveLivePanel();
            }

            @Override
            public void onClose() {
                stopLive();
            }

            @Override
            public void onCopy(CharSequence text) {
                copyToClipboard(text);
            }

            @Override
            public void onSpeak(CharSequence text) {
                say(text.toString(), new Prefs(TranslatorService.this).language(), true);
            }

            @Override
            public void onVoiceToggle() {
                Prefs current = new Prefs(TranslatorService.this);
                boolean on = !current.readAloud();
                current.setReadAloud(on);
                if (livePanel != null) {
                    livePanel.setVoice(on);
                }
                if (!on) {
                    stopSpeaking();
                }
            }
        });
        livePanel.setVoice(prefs.readAloud());
        livePanel.setSummary(session.summaryMode);
        WindowManager.LayoutParams params = overlayParams(cardWidth(), livePanelHeight(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        placeLivePanel(params, session.panelAtBottom);
        windowManager.addView(livePanel.root, params);

        if (bubble != null) {
            bubble.setVisibility(View.VISIBLE);
        }
        setBubbleLive(true);
        main.removeCallbacks(liveTick);
        main.postDelayed(liveTick, LIVE_TICK_MS);
    }

    // ----------------------------------------------------------------------------------------
    // Audio translation (uses the Live panel)

    /** 🎧 Audio: listen to the phone's sound or the microphone, and translate what's said. */
    private void startAudio(boolean microphone) {
        removeSelection();
        stopLive();
        hideCard();
        if (bubble != null) {
            bubble.setVisibility(View.VISIBLE);
        }
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED || !foregroundWithMicrophone) {
            askForMicrophone();
            return;
        }
        Prefs prefs = new Prefs(this);
        EngineSettings settings = new EngineSettings(prefs);
        LiveSession session = new LiveSession(true, wholeScreenRegion(), new Point(captureWidth, captureHeight));
        long now = SystemClock.uptimeMillis();
        session.startedAt = now;
        session.lastChangeAt = now;
        session.panelAtBottom = true;
        session.audioSource = microphone ? AUDIO_MIC : AUDIO_PHONE;
        session.minInterval = 0;
        session.maxInterval = 0;
        // In speech, saying something again is new, so repeats aren't left out.
        session.lines = new LiveLines(prefs.waitForSentences(), false,
                line -> memory.add(line.original, line.translation));
        beginSession(session, prefs, settings);
        livePanel.setAudio((microphone ? "🎙 Mic · " : "📱 Phone sound · ") + prefs.language(),
                "Listening. Whatever is said " + (microphone ? "near the phone" : "in videos, streams or "
                        + "voice messages on this phone") + " appears here in " + prefs.language()
                        + ", a sentence at a time, a moment after it's spoken. Tap a line to hear it, long-press "
                        + "to copy it. ≡ gives short summaries instead."
                        + (microphone ? " Use headphones if you turn on 🔊, so the phone doesn't hear itself."
                        : " (Some apps don't allow their sound to be recorded; they stay silent here.)"));
        if (startListening(session)) {
            livePanel.setStatus("Listening…", COLOR_WATCHING);
        }
    }

    /** Starts (or restarts, after a pause) the listening for an audio session. */
    private boolean startListening(final LiveSession session) {
        stopListening();
        EngineSettings settings = new EngineSettings(new Prefs(this));
        int maxSeconds = Prefs.ENGINE_ON_DEVICE.equals(settings.engine)
                ? AUDIO_CLIP_SECONDS_ON_DEVICE : AUDIO_CLIP_SECONDS;
        boolean microphone = session.audioSource == AUDIO_MIC;
        try {
            audioListener = new AudioListener(this, microphone ? null : projection, maxSeconds,
                    new AudioListener.Callback() {
                        @Override
                        public void onClip(short[] pcm) {
                            main.post(() -> onAudioClip(session, pcm));
                        }

                        @Override
                        public void onProblem(String message) {
                            main.post(() -> {
                                if (live == session && livePanel != null) {
                                    stopListening();
                                    pauseLive(message);
                                }
                            });
                        }
                    });
            audioListener.start();
            return true;
        } catch (IllegalStateException e) {
            audioListener = null;
            if (livePanel != null) {
                pauseLive(e.getMessage());
            }
            return false;
        }
    }

    private void stopListening() {
        AudioListener listener = audioListener;
        audioListener = null;
        if (listener != null) {
            listener.stop();
        }
    }

    /** The microphone permission is missing: open the app to ask for it. */
    private void askForMicrophone() {
        toast("To translate audio, allow Screen Translator to record audio. Opening the app…");
        Intent open = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_ASK_MICROPHONE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(open);
        } catch (RuntimeException e) {
            toast("Open Screen Translator and tap \"Allow listening\" under Audio translation.");
        }
    }

    private void onAudioClip(LiveSession session, short[] pcm) {
        if (live != session || livePanel == null || session.paused) {
            return;
        }
        session.audioQueue.add(pcm);
        sendAudio(session);
    }

    /** Sends the waiting clips (joined into one, up to the engine's limit) when the AI is free. */
    private void sendAudio(final LiveSession session) {
        long now = SystemClock.uptimeMillis();
        if (live != session || livePanel == null || session.paused || session.inFlight
                || session.audioQueue.isEmpty() || now < session.errorUntil) {
            return;
        }
        // Far behind (the AI is slower than the speech): skip the oldest clips, so it stays current.
        int skipped = 0;
        while (session.audioQueue.size() > AUDIO_MAX_WAITING_CLIPS) {
            skipped += session.audioQueue.remove(0).length;
        }
        if (skipped > 0) {
            livePanel.addNote("The AI couldn't keep up, so " + Math.round(skipped / (double) SpeechChunker.RATE)
                    + " seconds were skipped.");
        }
        final EngineSettings settings = new EngineSettings(new Prefs(this));
        int maxSamples = (Prefs.ENGINE_ON_DEVICE.equals(settings.engine)
                ? AUDIO_CLIP_SECONDS_ON_DEVICE : AUDIO_CLIP_SECONDS) * SpeechChunker.RATE * 3 / 2;
        List<short[]> clips = new ArrayList<>();
        int samples = 0;
        while (!session.audioQueue.isEmpty()) {
            short[] next = session.audioQueue.get(0);
            if (!clips.isEmpty() && samples + next.length > maxSamples) {
                break;
            }
            clips.add(session.audioQueue.remove(0));
            samples += next.length;
        }
        session.inFlight = true;
        session.sentAt = now;
        session.checks++;
        livePanel.setStatus("Translating what was said…", COLOR_BUSY);
        memory.useLanguage(settings.language);
        final boolean microphone = session.audioSource == AUDIO_MIC;
        final byte[] wav = Wav.fromPcm16(Wav.join(clips), SpeechChunker.RATE);
        final Translation.Request request = new Translation.Request(settings.language, settings.showOriginal,
                liveStatus(session), memory.copy(), session.lines.shownForAi(), null,
                session.lines.waitForSentences ? session.lines.waitingLines() : null);
        request.reading = texts -> main.post(() -> showReading(session, texts));
        worker.execute(() -> {
            List<Translation.Line> lines = null;
            TranslateException problem = null;
            try {
                lines = engineFor(settings).translateSpeech(wav, request, microphone);
            } catch (TranslateException e) {
                problem = e;
            } catch (RuntimeException e) {
                problem = new TranslateException("Something went wrong: " + e.getMessage(), false);
            }
            final List<Translation.Line> result = lines;
            final TranslateException failure = problem;
            main.post(() -> onLiveResult(session, result, failure, settings.showOriginal));
        });
    }

    /** Every tick while translating audio: finished sentences, summaries, and the mic hearing the app itself. */
    private void tickAudio(LiveSession session, long now) {
        if (audioListener != null) {
            // While the app reads aloud, the microphone would hear it: count that as silence.
            audioListener.setMuted(session.audioSource == AUDIO_MIC && speaker != null && speaker.isSpeaking());
        }
        if (session.inFlight || now < session.errorUntil) {
            return;
        }
        // An unfinished sentence and nobody speaking for a while: it was finished after all.
        boolean quiet = session.audioQueue.isEmpty();
        List<Translation.Line> released = session.lines.releaseFinished(now, quiet);
        if (!released.isEmpty()) {
            showProgress(session);
            deliver(session, released, new Prefs(this).showOriginal(), now);
            livePanel.setStatus(watchingText(session), COLOR_WATCHING);
        }
        if (summaryDue(session, now)) {
            summarizeLive(session);
            return;
        }
        sendAudio(session);
    }

    private void stopLive() {
        stopListening();
        main.removeCallbacks(liveTick);
        live = null;
        if (livePanel != null) {
            stopSpeaking();
            removeWindow(livePanel.root);
            livePanel = null;
        }
        setBubbleLive(false);
    }

    private void pauseLive(String note) {
        LiveSession session = live;
        if (session == null || livePanel == null) {
            return;
        }
        session.paused = true;
        if (session.audioSource != 0) {
            stopListening();   // the microphone (or sound capture) is off while paused
            session.audioQueue.clear();
        }
        livePanel.setPaused(true);
        livePanel.setStatus("Paused", COLOR_PAUSED);
        if (note != null) {
            livePanel.addNote(note);
        }
    }

    private void resumeLive() {
        LiveSession session = live;
        if (session == null || livePanel == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        session.paused = false;
        session.startedAt = now;
        session.lastChangeAt = now;
        session.interval = session.minInterval;
        session.nextAllowedAt = 0;
        session.errorUntil = 0;
        session.lastError = null;
        livePanel.setPaused(false);
        livePanel.setStatus(watchingText(session), COLOR_WATCHING);
        if (session.audioSource != 0 && !startListening(session)) {
            return;   // (paused again, with the reason)
        }
    }

    /**
     * Runs every LIVE_TICK_MS. Compares the watched area with how it looked at the last
     * translation, and sends it again once something changed and the screen has settled
     * (or has kept changing for a while, as with videos).
     */
    private void tickLive() {
        LiveSession session = live;
        if (session == null || session.paused || selectionLayer != null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - session.startedAt > LIVE_AUTO_PAUSE_MS) {
            pauseLive("Paused after 20 minutes to save your OpenAI credit. Tap Resume to keep going.");
            return;
        }
        if (session.audioSource != 0) {
            tickAudio(session, now);
            return;
        }

        if (latestFrame != null && frameCount != session.lastFrameChecked) {
            session.lastFrameChecked = frameCount;
            int[] signature = signature(latestFrame, session, overlayRects());
            if (signature != null && (session.currentSig == null || isChange(session.currentSig, signature))) {
                session.currentSig = signature;
                session.lastChangeAt = now;
            }
        }
        if (session.inFlight || now < session.errorUntil) {
            return;
        }
        releaseFinishedWaiting(session, now);
        if (summaryDue(session, now)) {
            summarizeLive(session);
            return;
        }
        if (session.currentSig == null) {
            return;
        }

        int changed = session.sentSig == null ? session.currentSig.length
                : countChanged(session.sentSig, session.currentSig);
        if (session.sentSig != null && !isChange(changed, session.currentSig.length)) {
            session.pendingSince = 0;   // nothing new since the last translation
            return;
        }
        if (session.pendingSince == 0) {
            session.pendingSince = now;
        }
        long allowedAt = session.nextAllowedAt;
        if (changed >= session.currentSig.length * LIVE_BIG_CHANGE) {
            // A big change (e.g. scrolled to new content): don't wait for the slow pace.
            allowedAt = Math.min(allowedAt, session.sentAt + session.minInterval);
        }
        if (now < allowedAt) {
            return;
        }
        boolean settled = now - session.lastChangeAt >= LIVE_SETTLE_MS;
        boolean waitedLong = now - session.pendingSince >= LIVE_MAX_DELAY_MS;
        if (settled || waitedLong) {
            sendLive(session, now);
        }
    }

    private void sendLive(final LiveSession session, long now) {
        if (latestFrame == null || livePanel == null) {
            return;
        }
        final Bitmap shot;
        try {
            shot = cropMasked(latestFrame, session.region, session.screen, overlayRects());
        } catch (RuntimeException e) {
            session.errorUntil = now + LIVE_MIN_INTERVAL_MS;
            return;
        }
        session.inFlight = true;
        session.sentSig = session.currentSig;
        session.pendingSince = 0;
        session.sentAt = now;
        session.checks++;
        livePanel.setStatus("Translating…", COLOR_BUSY);

        final EngineSettings settings = new EngineSettings(new Prefs(this));
        session.minInterval = settings.liveMinIntervalMs();
        session.maxInterval = settings.liveMaxIntervalMs();
        memory.useLanguage(settings.language);
        final Translation.Request request = new Translation.Request(settings.language, settings.showOriginal,
                liveStatus(session), memory.copy(), session.lines.shownForAi(), session.lines.shownKeys(),
                session.lines.waitForSentences ? session.lines.waitingLines() : null);
        request.reading = texts -> main.post(() -> showReading(session, texts));
        final CountDownLatch previewDone = startPreview(shot, settings, request.reading);
        worker.execute(() -> {
            List<Translation.Line> lines = null;
            TranslateException problem = null;
            try {
                lines = engineFor(settings).translateLive(shot, request);
            } catch (TranslateException e) {
                problem = e;
            } catch (RuntimeException e) {
                problem = new TranslateException("Something went wrong: " + e.getMessage(), false);
            } finally {
                awaitQuietly(previewDone);   // the preview may still be reading the same screenshot
                shot.recycle();
            }
            final List<Translation.Line> result = lines;
            final TranslateException failure = problem;
            main.post(() -> onLiveResult(session, result, failure, settings.showOriginal));
        });
    }

    /**
     * With Gemini or ChatGPT, reads the screenshot's text on the phone meanwhile (a fraction of a
     * second), so it can be shown while the AI translates it. The on-device engine reads the text
     * itself and reports it. Returns when the screenshot is free again (null if no preview runs).
     */
    private CountDownLatch startPreview(final Bitmap shot, EngineSettings settings, Translation.Reading reading) {
        if (Prefs.ENGINE_ON_DEVICE.equals(settings.engine)) {
            return null;
        }
        final CountDownLatch done = new CountDownLatch(1);
        try {
            previewWorker.execute(() -> {
                try {
                    List<String> texts = previewReader.read(shot);
                    if (!texts.isEmpty()) {
                        reading.onReading(texts);
                    }
                } catch (TranslateException | RuntimeException | LinkageError ignored) {
                    // Only a preview: the translation itself doesn't depend on it.
                } finally {
                    done.countDown();
                }
            });
        } catch (RuntimeException e) {
            return null;   // (shutting down)
        }
        return done;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        if (latch == null) {
            return;
        }
        try {
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The text being translated right now, shown greyed in the panel until the translation arrives. */
    private void showReading(LiveSession session, List<String> texts) {
        if (live != session || livePanel == null || !session.inFlight) {
            return;   // (the translation already arrived)
        }
        session.reading.clear();
        for (String text : texts) {
            String key = Translation.normalize(text);
            if (key.isEmpty() || (session.audioSource == 0 && session.lines.alreadyShown(key))) {
                continue;   // already translated above
            }
            boolean waiting = false;
            for (Translation.Line line : session.lines.waitingLines()) {
                if (TranslationMemory.coveredBy(key, Translation.normalize(line.original))) {
                    waiting = true;   // shown with its draft translation already
                }
            }
            if (!waiting) {
                session.reading.add(text);
            }
        }
        showProgress(session);
    }

    /** Updates the greyed "in progress" part: sentences waiting to finish, and text being translated. */
    private void showProgress(LiveSession session) {
        if (livePanel == null || live != session) {
            return;
        }
        livePanel.setInProgress(session.lines.waitForSentences && !session.summaryMode
                ? session.lines.waitingLines() : null, session.reading);
    }

    private Translation.StatusListener liveStatus(LiveSession session) {
        return message -> main.post(() -> {
            if (live == session && livePanel != null && !session.paused) {
                livePanel.setStatus(message, COLOR_BUSY);
            }
        });
    }

    private void onLiveResult(LiveSession session, List<Translation.Line> lines, TranslateException failure,
                              boolean showOriginal) {
        if (live != session || livePanel == null) {
            return;   // Live was stopped or restarted meanwhile
        }
        session.inFlight = false;
        session.reading.clear();
        showProgress(session);
        long now = SystemClock.uptimeMillis();

        if (failure != null) {
            String error = failure.getMessage();
            if (!error.equals(session.lastError)) {
                livePanel.addNote(error);
            }
            session.lastError = error;
            session.sentSig = null;   // this screen still needs translating
            if (failure.fatal) {
                pauseLive(null);
            } else {
                session.errorUntil = now + (failure.retryAfterMs > 0 ? failure.retryAfterMs : LIVE_ERROR_BACKOFF_MS);
                livePanel.setStatus("Trying again shortly…", COLOR_BUSY);
            }
            return;
        }
        session.lastError = null;

        // Keep only what's new; unfinished sentences wait for the rest (see LiveLines).
        List<Translation.Line> fresh = session.lines.take(lines, now);
        if (fresh.isEmpty()) {
            // Nothing new (e.g. an animation changed the screen): check less often for a while.
            session.interval = Math.min(session.interval * 2, session.maxInterval);
        } else {
            session.interval = session.minInterval;
            deliver(session, fresh, showOriginal, now);
        }
        if (session.lines.hasWaiting()) {
            session.interval = session.minInterval;   // keep an eye on the sentence that's being finished
        }
        showProgress(session);
        session.nextAllowedAt = session.sentAt + session.interval;
        if (summaryDue(session, now) && !session.paused) {
            summarizeLive(session);
            return;
        }
        if (!session.paused) {
            livePanel.setStatus(watchingText(session), COLOR_WATCHING);
        }
        if (session.audioSource != 0) {
            sendAudio(session);   // clips that came in meanwhile
        }
    }

    private void showLiveLines(List<Translation.Line> lines, boolean showOriginal) {
        livePanel.addLines(lines, showOriginal);
        Prefs prefs = new Prefs(this);
        if (prefs.readAloud()) {
            StringBuilder spoken = new StringBuilder();
            for (Translation.Line line : lines) {
                spoken.append(line.translation).append('\n');
            }
            say(spoken.toString(), prefs.language(), false);   // queued after anything still being read
        }
    }

    /** Shows new lines (or keeps them for the next summary). */
    private void deliver(LiveSession session, List<Translation.Line> lines, boolean showOriginal, long now) {
        if (lines.isEmpty()) {
            return;
        }
        if (session.summaryMode) {
            session.pending.addAll(lines);   // summarized together once a few sentences have come
            session.lastNewLineAt = now;
        } else {
            showLiveLines(lines, showOriginal);
        }
    }

    /**
     * Checked every tick: an unfinished sentence that stopped changing (nothing on screen moved for a
     * while) is finished after all, e.g. a sentence without a full stop. It's shown now.
     */
    private void releaseFinishedWaiting(LiveSession session, long now) {
        if (!session.lines.hasWaiting() || livePanel == null) {
            return;
        }
        boolean screenStill = session.sentSig != null && session.currentSig != null
                && !isChange(countChanged(session.sentSig, session.currentSig), session.currentSig.length);
        List<Translation.Line> released = session.lines.releaseFinished(now, screenStill);
        if (!released.isEmpty()) {
            showProgress(session);
            deliver(session, released, new Prefs(this).showOriginal(), now);
            if (!session.paused) {
                livePanel.setStatus(watchingText(session), COLOR_WATCHING);
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Live summaries

    /** Time for a summary: about three new sentences, a long text, or a pause in new text. */
    private static boolean summaryDue(LiveSession session, long now) {
        if (!session.summaryMode || session.pending.isEmpty() || session.inFlight) {
            return false;
        }
        int sentences = 0;
        int chars = 0;
        for (Translation.Line line : session.pending) {
            sentences += Translation.sentenceCount(line.translation);
            chars += line.translation.length();
        }
        return sentences >= LIVE_SUMMARY_SENTENCES || chars >= LIVE_SUMMARY_MAX_CHARS
                || now - session.lastNewLineAt >= LIVE_SUMMARY_WAIT_MS;
    }

    private void summarizeLive(final LiveSession session) {
        if (livePanel == null || session.pending.isEmpty()) {
            return;
        }
        final List<Translation.Line> batch = new ArrayList<>(session.pending);
        session.pending.clear();
        session.inFlight = true;
        livePanel.setStatus("Summarizing…", COLOR_BUSY);
        final EngineSettings settings = new EngineSettings(new Prefs(this));
        final Translation.Request request = new Translation.Request(settings.language, settings.showOriginal,
                liveStatus(session));
        final String text = Prompts.linesForSummary(batch);
        StringBuilder earlier = new StringBuilder();
        for (String summary : session.summaries) {
            earlier.append("- ").append(summary).append('\n');
        }
        final String context = earlier.toString();
        worker.execute(() -> {
            String summary = null;
            TranslateException problem = null;
            try {
                summary = engineFor(settings).summarize(text, context, request);
            } catch (TranslateException e) {
                problem = e;
            } catch (RuntimeException e) {
                problem = new TranslateException("Something went wrong: " + e.getMessage(), false);
            }
            final String result = summary;
            final TranslateException failure = problem;
            main.post(() -> onLiveSummary(session, batch, result, failure, settings.showOriginal));
        });
    }

    private void onLiveSummary(LiveSession session, List<Translation.Line> batch, String summary,
                               TranslateException failure, boolean showOriginal) {
        if (live != session || livePanel == null) {
            return;
        }
        session.inFlight = false;
        long now = SystemClock.uptimeMillis();
        session.nextAllowedAt = Math.max(session.nextAllowedAt, now + session.minInterval);
        if (failure != null || summary == null || summary.isEmpty()) {
            // Nothing is lost: show the lines themselves instead.
            String error = failure == null ? "The AI didn't send a summary." : failure.getMessage();
            if (!error.equals(session.lastError)) {
                livePanel.addNote("Couldn't summarize, so here are the lines. " + error);
            }
            session.lastError = error;
            showLiveLines(batch, showOriginal);
            if (failure != null && failure.fatal) {
                pauseLive(null);
                return;
            }
            if (failure != null) {
                session.errorUntil = now + (failure.retryAfterMs > 0 ? failure.retryAfterMs : LIVE_ERROR_BACKOFF_MS);
            }
        } else {
            session.lastError = null;
            livePanel.addSummary(summary, Prompts.linesForSummary(batch));
            session.summaries.addLast(summary);
            while (session.summaries.size() > LIVE_SUMMARY_CONTEXT) {
                session.summaries.removeFirst();
            }
            Prefs prefs = new Prefs(this);
            if (prefs.readAloud()) {
                say(summary, prefs.language(), false);
            }
        }
        if (!session.paused) {
            livePanel.setStatus(watchingText(session), COLOR_WATCHING);
        }
    }

    /** The ≡ chip in the Live panel: switch between every line and short summaries. */
    private void toggleLiveSummary() {
        LiveSession session = live;
        if (session == null || livePanel == null) {
            return;
        }
        Prefs prefs = new Prefs(this);
        boolean on = !session.summaryMode;
        prefs.setSummaryMode(on);
        session.summaryMode = on;
        livePanel.setSummary(on);
        if (on) {
            livePanel.addNote("Summary mode: every few sentences, a short summary appears here instead of each "
                    + "line. Tap ≡ again to see every line.");
        } else {
            if (!session.pending.isEmpty()) {
                showLiveLines(new ArrayList<>(session.pending), prefs.showOriginal());
                session.pending.clear();
            }
            livePanel.addNote("Showing every line again.");
        }
        if (!session.paused && !session.inFlight) {
            livePanel.setStatus(watchingText(session), COLOR_WATCHING);
        }
    }

    private static String watchingText(LiveSession session) {
        if (session.lines.hasWaiting()) {
            return "Waiting for the sentence to finish…";
        }
        String text = session.audioSource != 0
                ? "Listening · " + session.checks + (session.checks == 1 ? " clip" : " clips")
                : "Watching · " + session.checks + (session.checks == 1 ? " check" : " checks");
        if (session.summaryMode && !session.pending.isEmpty()) {
            text += " · " + session.pending.size() + " for the summary";
        }
        return text;
    }

    /**
     * A small brightness sample of the watched area, used to notice when its content changes.
     * Our own panel and bubble are skipped (-1), so updating them doesn't count as a change.
     */
    private static int[] signature(Image frame, LiveSession session, List<Rect> masks) {
        int width = frame.getWidth();
        int height = frame.getHeight();
        Rect area = toFrame(session.region, session.screen, width, height);
        if (area.isEmpty()) {
            return null;
        }
        List<Rect> hidden = new ArrayList<>(masks.size());
        for (Rect mask : masks) {
            hidden.add(toFrame(mask, session.screen, width, height));
        }
        Image.Plane plane = frame.getPlanes()[0];
        ByteBuffer pixels = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int limit = pixels.limit();

        int step = Math.max(4, (int) Math.sqrt((double) area.width() * area.height() / LIVE_SAMPLES));
        int cols = Math.max(1, area.width() / step);
        int rows = Math.max(1, area.height() / step);
        int[] signature = new int[cols * rows];
        int i = 0;
        for (int row = 0; row < rows; row++) {
            int y = area.top + row * step + step / 2;
            for (int col = 0; col < cols; col++, i++) {
                int x = area.left + col * step + step / 2;
                if (inside(hidden, x, y)) {
                    signature[i] = -1;
                    continue;
                }
                int offset = y * rowStride + x * pixelStride;
                if (offset + 2 >= limit) {
                    signature[i] = -1;
                    continue;
                }
                int red = pixels.get(offset) & 0xFF;
                int green = pixels.get(offset + 1) & 0xFF;
                int blue = pixels.get(offset + 2) & 0xFF;
                signature[i] = (red * 3 + green * 6 + blue) / 10;
            }
        }
        return signature;
    }

    private static boolean inside(List<Rect> rects, int x, int y) {
        for (Rect rect : rects) {
            if (rect.contains(x, y)) {
                return true;
            }
        }
        return false;
    }

    private static int countChanged(int[] before, int[] after) {
        if (before.length != after.length) {
            return after.length;
        }
        int changed = 0;
        for (int i = 0; i < after.length; i++) {
            int a = before[i];
            int b = after[i];
            if ((a < 0) != (b < 0)) {
                changed++;
            } else if (a >= 0 && Math.abs(a - b) > LIVE_PIXEL_DIFF) {
                changed++;
            }
        }
        return changed;
    }

    /** Small things like a blinking cursor don't count; a new line of text does. */
    private static boolean isChange(int changed, int total) {
        return changed >= Math.max(4, total * 0.0005f);
    }

    private static boolean isChange(int[] before, int[] after) {
        return isChange(countChanged(before, after), after.length);
    }

    /** The whole screen minus the status bar (clock, icons) and the navigation bar. */
    private Rect wholeScreenRegion() {
        int top = systemDimension("status_bar_height");
        int bottom = captureHeight > captureWidth ? systemDimension("navigation_bar_height") : 0;
        return new Rect(0, top, captureWidth, Math.max(top + 1, captureHeight - bottom));
    }

    @SuppressLint({"DiscouragedApi", "InternalInsetResource"})
    private int systemDimension(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    /** Where our own windows are on screen, so they can be left out of Live captures. */
    private List<Rect> overlayRects() {
        List<Rect> rects = new ArrayList<>(2);
        if (livePanel != null) {
            addScreenRect(rects, livePanel.root);
        }
        if (bubble != null && bubble.getVisibility() == View.VISIBLE) {
            addScreenRect(rects, bubble);
        }
        return rects;
    }

    private void addScreenRect(List<Rect> rects, View view) {
        if (!view.isAttachedToWindow() || view.getWidth() == 0) {
            return;
        }
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        Rect rect = new Rect(location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
        rect.inset(-dp(3), -dp(3));   // include anti-aliased edges
        rects.add(rect);
    }

    private int livePanelHeight() {
        return Math.max(dp(170), Math.round(screenSize().y * 0.30f));
    }

    private void placeLivePanel(WindowManager.LayoutParams params, boolean atBottom) {
        params.gravity = (atBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
        params.x = 0;
        params.y = atBottom ? dp(64) : dp(52);
    }

    private void moveLivePanel() {
        if (live == null || livePanel == null) {
            return;
        }
        live.panelAtBottom = !live.panelAtBottom;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) livePanel.root.getLayoutParams();
        placeLivePanel(params, live.panelAtBottom);
        windowManager.updateViewLayout(livePanel.root, params);
    }

    private void setBubbleLive(boolean on) {
        if (bubbleBackground != null) {
            bubbleBackground.setColor(on ? COLOR_LIVE_BUBBLE : Ui.ACCENT);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Floating bubble

    private void showBubble() {
        if (bubble != null) {
            return;
        }
        int size = dp(54);
        FrameLayout circle = new FrameLayout(ui);
        bubbleBackground = new GradientDrawable();
        bubbleBackground.setShape(GradientDrawable.OVAL);
        bubbleBackground.setColor(Ui.ACCENT);
        bubbleBackground.setStroke(dp(2), 0xCCFFFFFF);
        circle.setBackground(bubbleBackground);
        circle.setContentDescription("Translate screen");

        ImageView icon = new ImageView(ui);
        icon.setImageResource(R.drawable.ic_translate);
        icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        circle.addView(icon, new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER));

        bubbleParams = overlayParams(size, size,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        Point screen = screenSize();
        bubbleParams.x = screen.x - size - dp(6);
        bubbleParams.y = Math.round(screen.y * 0.35f);
        circle.setOnTouchListener(new BubbleTouch());
        windowManager.addView(circle, bubbleParams);
        bubble = circle;
    }

    /** While the bubble is dragged: a ✕ at the bottom of the screen. Dropping the bubble on it turns it off. */
    private void showCloseTarget() {
        if (closeTarget != null || windowManager == null) {
            return;
        }
        LinearLayout target = new LinearLayout(ui);
        target.setOrientation(LinearLayout.VERTICAL);
        target.setGravity(Gravity.CENTER_HORIZONTAL);
        closeCircle = new TextView(ui);
        closeCircle.setText("✕");
        closeCircle.setTextColor(Color.WHITE);
        closeCircle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        closeCircle.setGravity(Gravity.CENTER);
        target.addView(closeCircle, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView label = new TextView(ui);
        label.setText("Turn off");
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setShadowLayer(dp(3), 0, 0, 0xCC000000);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.topMargin = dp(6);
        target.addView(label, labelParams);
        int width = dp(120);
        int height = dp(100);
        WindowManager.LayoutParams params = overlayParams(width, height,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        Point screen = screenSize();
        params.x = (screen.x - width) / 2;
        params.y = screen.y - height - dp(56);
        try {
            windowManager.addView(target, params);
        } catch (RuntimeException e) {
            return;
        }
        closeTarget = target;
        overCloseTarget = false;
        paintCloseTarget();
    }

    private void updateCloseTarget() {
        if (closeTarget == null || bubble == null) {
            return;
        }
        Point screen = screenSize();
        float bubbleX = bubbleParams.x + bubbleParams.width / 2f;
        float bubbleY = bubbleParams.y + bubbleParams.height / 2f;
        float targetX = screen.x / 2f;
        float targetY = screen.y - dp(56) - dp(100) + dp(32);
        boolean over = Math.hypot(bubbleX - targetX, bubbleY - targetY) < dp(80);
        if (over != overCloseTarget) {
            overCloseTarget = over;
            paintCloseTarget();
            if (over) {
                bubble.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            }
        }
    }

    private void paintCloseTarget() {
        if (closeCircle == null) {
            return;
        }
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(overCloseTarget ? 0xFFD32F2F : 0xCC000000);
        circle.setStroke(dp(2), 0xCCFFFFFF);
        closeCircle.setBackground(circle);
        closeCircle.setScaleX(overCloseTarget ? 1.2f : 1f);
        closeCircle.setScaleY(overCloseTarget ? 1.2f : 1f);
    }

    private void hideCloseTarget() {
        if (closeTarget != null) {
            removeWindow(closeTarget);
            closeTarget = null;
            closeCircle = null;
        }
        overCloseTarget = false;
    }

    /** Tap = start selecting. Drag = move the bubble; it snaps to the nearest side, or turns off on ✕. */
    private final class BubbleTouch implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(ui).getScaledTouchSlop();
        private float downRawX;
        private float downRawY;
        private int downX;
        private int downY;
        private boolean moved;

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = bubbleParams.x;
                    downY = bubbleParams.y;
                    moved = false;
                    view.setAlpha(0.75f);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - downRawX;
                    float dy = event.getRawY() - downRawY;
                    if (!moved && Math.hypot(dx, dy) > slop) {
                        moved = true;
                    }
                    if (moved) {
                        bubbleParams.x = downX + Math.round(dx);
                        bubbleParams.y = downY + Math.round(dy);
                        keepBubbleOnScreen(false);
                        showCloseTarget();
                        updateCloseTarget();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    view.setAlpha(1f);
                    boolean turnOff = moved && overCloseTarget;
                    hideCloseTarget();
                    if (turnOff) {
                        toast("Screen Translator is off. Open the app to start it again.");
                        shutdown();
                    } else if (moved) {
                        keepBubbleOnScreen(true);
                    } else {
                        startSelection();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.setAlpha(1f);
                    hideCloseTarget();
                    return true;
                default:
                    return false;
            }
        }
    }

    private void keepBubbleOnScreen(boolean snapToSide) {
        if (bubble == null) {
            return;
        }
        Point screen = screenSize();
        int size = bubbleParams.width;
        int edge = dp(6);
        if (snapToSide) {
            bubbleParams.x = bubbleParams.x + size / 2 < screen.x / 2 ? edge : screen.x - size - edge;
        }
        bubbleParams.x = clamp(bubbleParams.x, 0, screen.x - size);
        bubbleParams.y = clamp(bubbleParams.y, dp(24), screen.y - size - dp(48));
        windowManager.updateViewLayout(bubble, bubbleParams);
    }

    private void fitOverlaysToScreen() {
        if (selectionLayer != null) {
            removeSelection();    // rotated mid-selection: start over
            if (bubble != null) {
                bubble.setVisibility(View.VISIBLE);
            }
        }
        if (live != null) {
            if (live.wholeScreen) {
                matchCaptureToScreen();
                live.screen = new Point(captureWidth, captureHeight);
                live.region = wholeScreenRegion();
                live.currentSig = null;
                live.lastChangeAt = SystemClock.uptimeMillis();
            } else {
                stopLive();
                toast("The screen rotated, so Live stopped. Tap the bubble to choose the area again.");
            }
        }
        keepBubbleOnScreen(true);
        if (card != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) card.getLayoutParams();
            params.width = cardWidth();
            windowManager.updateViewLayout(card, params);
        }
        if (livePanel != null) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) livePanel.root.getLayoutParams();
            params.width = cardWidth();
            params.height = livePanelHeight();
            windowManager.updateViewLayout(livePanel.root, params);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Result card (Translate once)

    private void showCard(boolean atBottom, String language) {
        hideCard();
        cardLanguage = language;
        final boolean dark = Ui.isDark(ui);
        final int maxHeight = Math.round(screenSize().y * 0.45f);

        LinearLayout box = new LinearLayout(ui) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST));
            }
        };
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(12), dp(12), dp(16));
        box.setBackground(Ui.rounded(dark ? 0xF5202124 : 0xFAFFFFFF, dp(18),
                dark ? 0x33FFFFFF : 0x22000000, dp(1)));

        LinearLayout header = new LinearLayout(ui);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        cardTitle = new TextView(ui);
        cardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cardTitle.setTypeface(Typeface.DEFAULT_BOLD);
        cardTitle.setLetterSpacing(0.08f);
        cardTitle.setSingleLine(true);
        cardTitle.setEllipsize(TextUtils.TruncateAt.END);
        cardTitle.setTextColor(dark ? Ui.ACCENT_ON_DARK : Ui.ACCENT);
        header.addView(cardTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        cardSpeak = chip("🔊", dark);
        cardSpeak.setContentDescription("Read aloud");
        cardSpeak.setOnClickListener(v -> {
            if (speaker != null && speaker.isSpeaking()) {
                stopSpeaking();
            } else {
                say(cardSpokenText, cardLanguage, true);
            }
        });
        header.addView(cardSpeak);

        cardSummaryChip = chip("Summary", dark);
        cardSummaryChip.setTooltipText("Switch between a short summary and the full translation");
        cardSummaryChip.setOnClickListener(v -> onCardSummaryChip());
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.leftMargin = dp(8);
        header.addView(cardSummaryChip, summaryParams);

        cardFix = chip("Open app", dark);
        cardFix.setTooltipText("Open Screen Translator to fix this");
        cardFix.setOnClickListener(v -> {
            hideCard();
            Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(open);
            } catch (RuntimeException e) {
                toast("Open Screen Translator from your home screen.");
            }
        });
        cardFix.setVisibility(View.GONE);
        LinearLayout.LayoutParams fixParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fixParams.leftMargin = dp(8);
        header.addView(cardFix, fixParams);

        cardCopy = chip("Copy", dark);
        cardCopy.setTooltipText("Copy the text");
        cardCopy.setOnClickListener(v -> copyToClipboard(cardBody.getText()));
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        copyParams.leftMargin = dp(8);
        header.addView(cardCopy, copyParams);

        TextView close = chip("✕", dark);
        close.setContentDescription("Close");
        close.setTooltipText("Close");
        close.setOnClickListener(v -> hideCard());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.leftMargin = dp(8);
        header.addView(close, closeParams);
        box.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(ui);
        cardBody = new TextView(ui);
        cardBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        cardBody.setTextColor(dark ? 0xFFECEDEF : 0xFF1B1C1F);
        cardBody.setLineSpacing(0, 1.18f);
        cardBody.setPadding(0, 0, dp(6), 0);
        scroll.addView(cardBody);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        scrollParams.topMargin = dp(8);
        box.addView(scroll, scrollParams);

        WindowManager.LayoutParams params = overlayParams(cardWidth(), ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        params.gravity = (atBottom ? Gravity.BOTTOM : Gravity.TOP) | Gravity.CENTER_HORIZONTAL;
        params.y = atBottom ? dp(64) : dp(52);
        windowManager.addView(box, params);
        card = box;

        cardTitle.setText(("Translating to " + language + "…").toUpperCase(Locale.ROOT));
        cardBody.setText("Reading the text on your screen…");
        cardCopy.setVisibility(View.GONE);
        cardSpeak.setVisibility(View.GONE);
        cardSummaryChip.setVisibility(View.GONE);
        cardFix.setVisibility(View.GONE);
        cardSpokenText = null;
        cardFullText = null;
        cardSummary = null;
        cardShowingSummary = false;
        cardSummarizing = false;
    }

    private void setCardResult(String text, boolean success) {
        if (card == null) {
            return;
        }
        boolean hasText = success && !Translation.NO_TEXT.equals(text.trim());
        cardFullText = hasText ? text : null;
        cardReading = null;
        cardSummary = null;
        cardSummarizing = false;
        cardCopy.setVisibility(success ? View.VISIBLE : View.GONE);
        cardSpeak.setVisibility(hasText ? View.VISIBLE : View.GONE);
        cardSummaryChip.setVisibility(hasText ? View.VISIBLE : View.GONE);
        if (!hasText) {
            cardTitle.setText(success ? cardLanguage.toUpperCase(Locale.ROOT) : "COULDN'T TRANSLATE");
            cardBody.setText(text);
            cardSpokenText = null;
            updateSpeakChip();
            return;
        }
        if (new Prefs(this).summaryMode()) {
            cardShowingSummary = true;
            showCardSummary();   // the summary is read aloud when it arrives
        } else {
            cardShowingSummary = false;
            showCardFull();
            if (new Prefs(this).readAloud()) {
                say(cardSpokenText, cardLanguage, true);
            }
        }
    }

    private void showCardFull() {
        if (card == null || cardFullText == null) {
            return;
        }
        cardTitle.setText(cardLanguage.toUpperCase(Locale.ROOT));
        cardBody.setText(cardShowOriginal ? styledPairs(cardFullText) : cardFullText);
        cardSpokenText = Speaker.translationsOnly(cardFullText, cardShowOriginal);
        cardSummaryChip.setText("Summary");
        cardSummaryChip.setContentDescription("Show a short summary");
        updateSpeakChip();
    }

    /**
     * "Original / → translation" blocks, shown as the original (small, grey) with the translation
     * under it. Text without that shape is shown as it is.
     */
    private CharSequence styledPairs(String text) {
        if (!text.contains("→")) {
            return text;
        }
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        int grey = Ui.isDark(ui) ? 0x99FFFFFF : 0x8A000000;
        for (String block : text.trim().split("\\n\\s*\\n")) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            boolean first = true;
            for (String raw : block.split("\n")) {
                String row = raw.trim();
                if (row.isEmpty()) {
                    continue;
                }
                if (!first) {
                    out.append('\n');
                }
                first = false;
                if (row.startsWith("→")) {
                    out.append(row.substring(1).trim());
                } else {
                    int start = out.length();
                    out.append(row);
                    out.setSpan(new android.text.style.ForegroundColorSpan(grey), start, out.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new android.text.style.RelativeSizeSpan(0.85f), start, out.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        return out;
    }

    /** Shows the summary, making it first if needed. */
    private void showCardSummary() {
        if (card == null || cardFullText == null) {
            return;
        }
        cardTitle.setText(("Summary · " + cardLanguage).toUpperCase(Locale.ROOT));
        cardSummaryChip.setText("Full");
        cardSummaryChip.setContentDescription("Show the full translation");
        if (cardSummary != null) {
            cardBody.setText(cardSummary);
            cardSpokenText = cardSummary;
        } else {
            cardBody.setText("Summarizing…");
            cardSpokenText = null;
            summarizeCard();
        }
        updateSpeakChip();
    }

    private void onCardSummaryChip() {
        stopSpeaking();
        cardShowingSummary = !cardShowingSummary;
        if (cardShowingSummary) {
            showCardSummary();
        } else {
            showCardFull();
        }
    }

    // ----------------------------------------------------------------------------------------
    // Reading aloud

    private Speaker speaker() {
        if (speaker == null) {
            speaker = new Speaker(this, new Speaker.Listener() {
                @Override
                public void onSpeakingChanged(boolean speaking) {
                    updateSpeakChip();
                }

                @Override
                public void onProblem(String message, boolean needsVoice) {
                    if (livePanel != null) {
                        livePanel.addNote(message);
                    } else {
                        toast(message);
                    }
                }
            });
        }
        return speaker;
    }

    private void say(String text, String language, boolean replace) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        speaker().speak(text, Speaker.Options.from(new Prefs(this), language), replace);
    }

    private void stopSpeaking() {
        if (speaker != null) {
            speaker.stop();
        }
    }

    private void releaseSpeaker() {
        if (speaker != null) {
            speaker.shutdown();
            speaker = null;
        }
    }

    private void updateSpeakChip() {
        if (cardSpeak == null) {
            return;
        }
        boolean speaking = speaker != null && speaker.isSpeaking();
        cardSpeak.setText(speaking ? "⏹" : "🔊");
        cardSpeak.setContentDescription(speaking ? "Stop reading" : "Read aloud");
    }

    private int cardWidth() {
        return Math.min(screenSize().x - dp(24), dp(560));
    }

    private TextView chip(String label, boolean dark) {
        TextView chip = new TextView(ui);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        chip.setTypeface(Typeface.DEFAULT_BOLD);
        chip.setTextColor(dark ? 0xFFECEDEF : 0xFF1B1C1F);
        chip.setGravity(Gravity.CENTER);
        chip.setMinWidth(dp(40));
        chip.setPadding(dp(14), dp(8), dp(14), dp(8));
        chip.setBackground(Ui.rounded(dark ? 0x26FFFFFF : 0x12000000, dp(16)));
        return chip;
    }

    private void copyToClipboard(CharSequence text) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("Translation", text));
        if (Build.VERSION.SDK_INT < 33) {
            toast("Copied");   // Android 13+ shows its own confirmation
        }
    }

    // ----------------------------------------------------------------------------------------
    // Overlay helpers

    private WindowManager.LayoutParams overlayParams(int width, int height, int flags) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.windowAnimations = 0;   // appear/disappear instantly, so they never leak into a screenshot
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(0);   // use whole-screen coordinates, same as the screenshot
        }
        return params;
    }

    private void removeSelection() {
        if (selectionLayer != null) {
            removeWindow(selectionLayer);
            selectionLayer = null;
        }
    }

    private void hideCard() {
        if (card != null) {
            stopSpeaking();
            removeWindow(card);
            card = null;
            cardSpeak = null;
            cardSummaryChip = null;
            cardFix = null;
            cardSpokenText = null;
            cardFullText = null;
            cardSummary = null;
            cardSummarizing = false;
        }
    }

    private void removeOverlays() {
        hideCloseTarget();
        removeSelection();
        hideCard();
        stopLive();
        if (bubble != null) {
            removeWindow(bubble);
            bubble = null;
            bubbleBackground = null;
        }
    }

    private void removeWindow(View view) {
        if (windowManager == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(view);
        } catch (IllegalArgumentException ignored) {
            // Already gone.
        }
    }

    private int dp(float value) {
        return Ui.dp(ui != null ? ui : this, value);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, Math.max(min, max)));
    }

    private void toast(String message) {
        main.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show());
    }
}
