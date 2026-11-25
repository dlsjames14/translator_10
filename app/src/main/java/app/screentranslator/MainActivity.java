package app.screentranslator;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

/** Settings screen: engine choice, keys or model download, language, and the Start / Stop buttons. */
public class MainActivity extends Activity {

    private static final int REQUEST_CAPTURE = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;
    private static final int REQUEST_MICROPHONE = 1003;
    private static final int REQUEST_WELCOME = 1004;
    /** Opened by the bubble when audio translation needs the microphone permission. */
    static final String ACTION_ASK_MICROPHONE = "app.screentranslator.action.ASK_MICROPHONE";
    private static final int REQUEST_IMPORT = 1003;
    static final String OPENAI_KEYS_URL = "https://platform.openai.com/api-keys";
    static final String GEMINI_KEYS_URL = "https://aistudio.google.com/apikey";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable modelTicker = new Runnable() {
        @Override
        public void run() {
            refreshModelStatus();
            refreshVoiceStatus();
            refreshChecklist();
            if (Prefs.ENGINE_ON_DEVICE.equals(prefs.engine())) {
                refreshSections();
            }
            handler.postDelayed(this, 1000);
        }
    };

    private Prefs prefs;

    // Engine choice
    private RadioButton onDeviceOption;
    private RadioButton geminiOption;
    private RadioButton openAiOption;
    private LinearLayout onDevicePanel;
    private LinearLayout geminiPanel;
    private LinearLayout openAiPanel;

    // On-device AI
    private RadioButton smallModelOption;
    private RadioButton largeModelOption;
    private TextView memoryNote;
    private TextView modelStatus;
    private Button downloadButton;
    private Button cancelButton;
    private Button deleteButton;
    private Button importButton;
    private CheckBox mobileDataBox;
    private volatile String importMessage;   // set while a model file is being copied

    // Keys and models
    private EditText geminiKeyField;
    private EditText geminiModelField;
    private EditText openAiKeyField;
    private EditText openAiModelField;

    // Common
    private EditText languageField;
    private CheckBox originalBox;
    private CheckBox summaryBox;
    private CheckBox waitSentencesBox;
    private ScrollView scroll;
    private Button startButton;
    private LinearLayout checklist;
    private String checklistState;
    private Spinner languageList;
    private final LinearLayout[] sectionBodies = new LinearLayout[5];
    private final TextView[] sectionSummaries = new TextView[5];
    private final TextView[] sectionArrows = new TextView[5];
    private TextView geminiKeyNote;
    private TextView openAiKeyNote;
    private TextView audioStatus;
    private Button audioAllowButton;

    // Read aloud
    private CheckBox readAloudBox;
    private RadioButton slowOption;
    private RadioButton normalOption;
    private RadioButton fastOption;
    private Speaker testSpeaker;
    private RadioButton phoneVoiceOption;
    private RadioButton aiVoiceOption;
    private LinearLayout aiVoicePanel;
    private EditText voiceKeyField;
    private Spinner aiVoiceSpinner;
    private Spinner moodSpinner;
    private RadioButton bestQualityOption;
    private RadioButton fastQualityOption;
    private RadioButton openAiVoiceOption;
    private LinearLayout openAiVoicePanel;
    private EditText openAiVoiceKeyField;
    private Spinner openAiVoiceSpinner;
    private Spinner openAiMoodSpinner;
    private boolean syncingKeys;
    private boolean loading;   // filling in the saved settings: don't save while doing so
    private TextView aiVoiceStatus;
    private TextView statusView;
    private Button stopButton;
    private boolean waitingForOverlayPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        drawBehindSystemBars();
        setContentView(buildScreen());

        loading = true;
        linkKeyFields(geminiKeyField, voiceKeyField);   // one Gemini key, shown in both places
        linkKeyFields(openAiKeyField, openAiVoiceKeyField);   // one OpenAI key, shown in both places
        loadFields();
        loading = false;
        refreshSections();
        refreshChecklist();

        if (ACTION_ASK_MICROPHONE.equals(getIntent().getAction())) {
            askForMicrophone();
        } else if (savedInstanceState == null && !prefs.welcomeDone()) {
            if (isNewUser()) {
                openWelcome();   // first run: the step-by-step setup guide
            } else {
                prefs.setWelcomeDone();   // already set up in an earlier version
            }
        }
    }

    /** Shows the saved settings on the screen. */
    private void loadFields() {
        geminiKeyField.setText(prefs.geminiKey());
        geminiModelField.setText(prefs.geminiModel());
        openAiKeyField.setText(prefs.openAiKey());
        openAiModelField.setText(prefs.openAiModel());
        showLanguage(prefs.language());
        originalBox.setChecked(prefs.showOriginal());
        switch (prefs.speechRate()) {
            case Speaker.RATE_SLOW:
                slowOption.setChecked(true);
                break;
            case Speaker.RATE_FAST:
                fastOption.setChecked(true);
                break;
            default:
                normalOption.setChecked(true);
        }
        String voiceMode = prefs.voiceMode();
        if (Prefs.VOICE_AI.equals(voiceMode)) {
            aiVoiceOption.setChecked(true);
        } else if (Prefs.VOICE_OPENAI.equals(voiceMode)) {
            openAiVoiceOption.setChecked(true);
        } else {
            phoneVoiceOption.setChecked(true);
        }
        aiVoiceSpinner.setSelection(indexOf(Speaker.AI_VOICES, prefs.aiVoiceName()));
        openAiVoiceSpinner.setSelection(indexOf(OpenAiSpeech.VOICES, prefs.openAiVoiceName()));
        showVoicePanel(voiceMode);
        (Speaker.AI_MODEL_FAST.equals(prefs.aiVoiceModel()) ? fastQualityOption : bestQualityOption).setChecked(true);
        mobileDataBox.setChecked(prefs.downloadOverMobileData());
        (ModelStore.E4B.id.equals(prefs.onDeviceModel()) ? largeModelOption : smallModelOption).setChecked(true);
        switch (prefs.engine()) {
            case Prefs.ENGINE_GEMINI:
                geminiOption.setChecked(true);
                break;
            case Prefs.ENGINE_OPENAI:
                openAiOption.setChecked(true);
                break;
            default:
                onDeviceOption.setChecked(true);
        }
        showEnginePanel();
        showKeyNote(Prefs.ENGINE_GEMINI);
        showKeyNote(Prefs.ENGINE_OPENAI);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (ACTION_ASK_MICROPHONE.equals(intent.getAction())) {
            askForMicrophone();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshAudioStatus();
        checklistState = null;   // permissions may have changed in Android's settings
        refreshChecklist();
        refreshSections();
        handler.post(modelTicker);
        readAloudBox.setChecked(prefs.readAloud());   // may have been switched from the Live panel
        summaryBox.setChecked(prefs.summaryMode());   // this too
        if (waitingForOverlayPermission) {
            waitingForOverlayPermission = false;
            if (Settings.canDrawOverlays(this)) {
                continueStart();
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (testSpeaker != null) {
            testSpeaker.shutdown();
            testSpeaker = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(modelTicker);
        saveFields();
    }

    // ----------------------------------------------------------------------------------------
    // Starting and stopping

    private void onStartTapped() {
        saveFields();
        if (!engineReady()) {
            return;
        }
        if (TranslatorService.isRunning()) {
            Toast.makeText(this, "Already running. Tap the bubble in any app.", Toast.LENGTH_SHORT).show();
            moveTaskToBack(true);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Allow the floating bubble")
                    .setMessage("Screen Translator needs permission to appear on top of other apps, "
                            + "so the bubble and translations can show over any app.\n\n"
                            + "On the next screen, turn on \"Allow display over other apps\", then come back.\n\n"
                            + "If the switch is greyed out: open Settings → Apps → Screen Translator, tap ⋮ (top "
                            + "right) → \"Allow restricted settings\", then try again.")
                    .setPositiveButton("Open settings", (dialog, which) -> openOverlaySettings())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        continueStart();
    }

    /** Checks the selected engine has what it needs, and explains what's missing if not. */
    private boolean engineReady() {
        switch (prefs.engine()) {
            case Prefs.ENGINE_GEMINI:
                if (prefs.geminiKey().isEmpty()) {
                    showSection(0);
                    Toast.makeText(this, "First add your free Gemini key (steps on screen).", Toast.LENGTH_LONG).show();
                    geminiKeyField.setError("Paste your free Gemini API key first");
                    geminiKeyField.requestFocus();
                    return false;
                }
                return true;
            case Prefs.ENGINE_OPENAI:
                if (prefs.openAiKey().isEmpty()) {
                    showSection(0);
                    Toast.makeText(this, "First add your OpenAI key (steps on screen).", Toast.LENGTH_LONG).show();
                    openAiKeyField.setError("Paste your OpenAI API key first");
                    openAiKeyField.requestFocus();
                    return false;
                }
                return true;
            default:
                ModelStore.Spec spec = selectedModel();
                if (ModelStore.isReady(this, spec)) {
                    return true;
                }
                if (ModelStore.progress(this, spec) != null) {
                    showSection(0);
                    Toast.makeText(this, "The AI model is still downloading. Start again when it's ready.",
                            Toast.LENGTH_LONG).show();
                    return false;
                }
                new AlertDialog.Builder(this)
                        .setTitle("Download the on-device AI first")
                        .setMessage("The on-device AI needs a one-time download of " + spec.sizeText()
                                + ". After that it works without internet.")
                        .setPositiveButton("Download", (dialog, which) -> onDownloadTapped())
                        .setNegativeButton("Cancel", null)
                        .show();
                return false;
        }
    }

    private void continueStart() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Lets the "Stop" notification show. The app works even if they say no.
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
            return;
        }
        askForScreenCapture();
    }

    private void openOverlaySettings() {
        openOverlaySettings(true);
    }

    /** @param thenStart continue starting the bubble when the person comes back with the permission */
    private void openOverlaySettings(boolean thenStart) {
        waitingForOverlayPermission = thenStart;
        Toast.makeText(this, "Find Screen Translator and turn on \"Allow display over other apps\", then come back.",
                Toast.LENGTH_LONG).show();
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void askForScreenCapture() {
        MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
        Intent intent;
        if (Build.VERSION.SDK_INT >= 34) {
            // Always share the entire screen, so the bubble works in every app.
            intent = manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            intent = manager.createScreenCaptureIntent();
        }
        startActivityForResult(intent, REQUEST_CAPTURE);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            askForScreenCapture();
        } else if (requestCode == REQUEST_MICROPHONE) {
            refreshAudioStatus();
            if (canRecord()) {
                if (TranslatorService.isRunning()) {
                    // Tell the bubble now, while the app is open (Android requires that).
                    startService(new Intent(this, TranslatorService.class)
                            .setAction(TranslatorService.ACTION_ALLOW_AUDIO));
                    moveTaskToBack(true);
                } else {
                    Toast.makeText(this, "Listening is allowed. Tap Start, then in the bubble choose 🎧 Audio.",
                            Toast.LENGTH_LONG).show();
                }
            } else if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                Toast.makeText(this, "Android won't ask again. Allow the microphone for Screen Translator in "
                        + "Settings → Apps → Screen Translator → Permissions.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Audio translation

    private boolean canRecord() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void askForMicrophone() {
        if (canRecord()) {
            if (TranslatorService.isRunning()) {
                startService(new Intent(this, TranslatorService.class)
                        .setAction(TranslatorService.ACTION_ALLOW_AUDIO));
                moveTaskToBack(true);
            }
            refreshAudioStatus();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE);
    }

    private void refreshAudioStatus() {
        if (audioStatus == null) {
            return;
        }
        boolean allowed = canRecord();
        audioAllowButton.setVisibility(allowed ? View.GONE : View.VISIBLE);
        audioStatus.setText(allowed
                ? "✓ Listening is allowed. In the bubble, choose 🎧 Audio."
                : "Android asks once whether Screen Translator may record audio. It only listens while 🎧 Audio "
                + "is on (you'll see the microphone dot), and the sound goes only to the AI you chose.");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_WELCOME) {
            loading = true;
            loadFields();
            loading = false;
            checklistState = null;
            refreshChecklist();
            refreshSections();
            refreshStatus();
            if (data != null && data.getBooleanExtra(WelcomeActivity.EXTRA_START_NOW, false)) {
                onStartTapped();
            }
            return;
        }
        if (requestCode == REQUEST_IMPORT) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                importModel(data.getData());
            }
            return;
        }
        if (requestCode != REQUEST_CAPTURE) {
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            Intent service = new Intent(this, TranslatorService.class)
                    .setAction(TranslatorService.ACTION_START)
                    .putExtra(TranslatorService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(TranslatorService.EXTRA_RESULT_DATA, data);
            startForegroundService(service);
            Toast.makeText(this, "Bubble is on. Open any app and tap it to translate.", Toast.LENGTH_LONG).show();
            moveTaskToBack(true);
        } else {
            Toast.makeText(this, "Screen capture wasn't allowed, so the translator can't read your screen.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onStopTapped() {
        startService(new Intent(this, TranslatorService.class).setAction(TranslatorService.ACTION_STOP));
        statusView.postDelayed(this::refreshStatus, 300);
    }

    private void saveFields() {
        if (languageField == null) {
            return;
        }
        prefs.saveFields(openAiKeyField.getText().toString(), openAiModelField.getText().toString(),
                geminiKeyField.getText().toString(), geminiModelField.getText().toString(),
                currentLanguage(), originalBox.isChecked(), mobileDataBox.isChecked());
    }

    private void refreshStatus() {
        boolean running = TranslatorService.isRunning();
        if (running) {
            statusView.setText("● The bubble is on, using " + engineName() + ". Open any app and tap the "
                    + "bubble to translate. Changes here apply to the next translation.");
            statusView.setTextColor(Ui.isDark(this) ? Ui.ACCENT_ON_DARK : Ui.ACCENT);
        } else if (!Settings.canDrawOverlays(this)) {
            statusView.setText("Tap Start. Android will ask you to let the bubble show over other apps, then to "
                    + "share your screen with the app: allow both.");
            statusView.setTextColor(secondaryTextColor());
        } else {
            statusView.setText("Tap Start, then allow screen sharing when Android asks.");
            statusView.setTextColor(secondaryTextColor());
        }
        if (startButton != null) {
            startButton.setText(running ? "↗  Go to my apps" : "▶  Start translator bubble");
        }
        stopButton.setVisibility(running ? View.VISIBLE : View.GONE);
    }

    private String engineName() {
        switch (prefs.engine()) {
            case Prefs.ENGINE_GEMINI:
                return "Gemini";
            case Prefs.ENGINE_OPENAI:
                return "ChatGPT";
            default:
                return "the on-device AI";
        }
    }

    // ----------------------------------------------------------------------------------------
    // Engine choice

    private void onEngineChosen(String engine) {
        prefs.setEngine(engine);
        showEnginePanel();
        refreshStatus();
        refreshSections();
        refreshChecklist();
    }

    private void showEnginePanel() {
        String engine = prefs.engine();
        onDevicePanel.setVisibility(Prefs.ENGINE_ON_DEVICE.equals(engine) ? View.VISIBLE : View.GONE);
        geminiPanel.setVisibility(Prefs.ENGINE_GEMINI.equals(engine) ? View.VISIBLE : View.GONE);
        openAiPanel.setVisibility(Prefs.ENGINE_OPENAI.equals(engine) ? View.VISIBLE : View.GONE);
        refreshModelStatus();
    }

    // ----------------------------------------------------------------------------------------
    // On-device model download

    private ModelStore.Spec selectedModel() {
        return ModelStore.byId(prefs.onDeviceModel());
    }

    private void onModelChosen(ModelStore.Spec spec) {
        prefs.setOnDeviceModel(spec.id);
        refreshModelStatus();
    }

    private void onDownloadTapped() {
        saveFields();
        final ModelStore.Spec spec = selectedModel();
        String network = mobileDataBox.isChecked()
                ? "It will use Wi-Fi or mobile data, whichever is available."
                : "It will wait for Wi-Fi.";
        new AlertDialog.Builder(this)
                .setTitle("Download " + spec.name + "?")
                .setMessage("This is a one-time download of " + spec.sizeText() + ". " + network
                        + " You can leave this screen; Android keeps downloading and shows its progress in the "
                        + "notifications.")
                .setPositiveButton("Download", (dialog, which) -> {
                    try {
                        ModelStore.startDownload(this, spec, mobileDataBox.isChecked());
                    } catch (IllegalStateException e) {
                        new AlertDialog.Builder(this)
                                .setTitle("Can't download yet")
                                .setMessage(e.getMessage())
                                .setPositiveButton("OK", null)
                                .show();
                    }
                    refreshModelStatus();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void onCancelTapped() {
        ModelStore.cancelDownload(this, selectedModel());
        refreshModelStatus();
    }

    private void onDeleteTapped() {
        final ModelStore.Spec spec = selectedModel();
        new AlertDialog.Builder(this)
                .setTitle("Delete " + spec.name + "?")
                .setMessage("This frees " + spec.sizeText() + ". You can download it again later.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (!ModelStore.delete(this, spec)) {
                        Toast.makeText(this, "Couldn't delete the model file.", Toast.LENGTH_LONG).show();
                    }
                    refreshModelStatus();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void onImportTapped() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*");
        try {
            startActivityForResult(pick, REQUEST_IMPORT);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker is available on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    private void importModel(final Uri source) {
        final ModelStore.Spec spec = selectedModel();
        importMessage = "Copying the model file…";
        refreshModelStatus();
        new Thread(() -> {
            String result;
            try {
                ModelStore.importFrom(getApplicationContext(), source, spec,
                        copied -> importMessage = "Copying the model file… " + ModelStore.gb(copied));
                result = spec.name + " is ready.";
            } catch (IOException | SecurityException e) {
                result = "Couldn't use that file: " + e.getMessage();
            }
            final String message = result;
            handler.post(() -> {
                importMessage = null;
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                refreshModelStatus();
            });
        }, "model-import").start();
    }

    private void refreshModelStatus() {
        if (modelStatus == null || onDevicePanel.getVisibility() != View.VISIBLE) {
            return;
        }
        ModelStore.Spec spec = selectedModel();

        int ram = ModelStore.phoneRamGb(this);
        if (ram < spec.minRamGb) {
            memoryNote.setText("This phone has about " + ram + " GB of memory; " + spec.name + " works best with "
                    + spec.minRamGb + " GB or more. If translation is slow or the app closes, choose "
                    + (spec == ModelStore.E4B ? "E2B" : "Gemini") + " instead.");
            memoryNote.setVisibility(View.VISIBLE);
        } else {
            memoryNote.setVisibility(View.GONE);
        }

        String copying = importMessage;
        if (copying != null) {
            modelStatus.setText(copying);
            showButtons(false, false, false, false);
            return;
        }
        ModelStore.Progress progress = ModelStore.progress(this, spec);
        if (progress != null && !progress.failed()) {
            modelStatus.setText(ModelStore.describe(progress, spec));
            showButtons(false, true, false, false);
        } else if (progress != null) {
            modelStatus.setText(ModelStore.describe(progress, spec));
            showButtons(true, false, false, true);
        } else if (ModelStore.isReady(this, spec)) {
            modelStatus.setText("✓ " + spec.name + " is on this phone and ready. It works without internet.");
            showButtons(false, false, true, false);
        } else {
            modelStatus.setText("Not downloaded yet. One-time download of " + spec.sizeText()
                    + " (Wi-Fi recommended).");
            showButtons(true, false, false, true);
        }
    }

    private void showButtons(boolean download, boolean cancel, boolean delete, boolean importFile) {
        downloadButton.setVisibility(download ? View.VISIBLE : View.GONE);
        cancelButton.setVisibility(cancel ? View.VISIBLE : View.GONE);
        deleteButton.setVisibility(delete ? View.VISIBLE : View.GONE);
        importButton.setVisibility(importFile ? View.VISIBLE : View.GONE);
        mobileDataBox.setVisibility(download ? View.VISIBLE : View.GONE);
        boolean idle = importMessage == null;
        smallModelOption.setEnabled(idle);
        largeModelOption.setEnabled(idle);
    }

    // ----------------------------------------------------------------------------------------
    // Read aloud

    private void onTestVoice() {
        saveFields();
        if (testSpeaker != null && testSpeaker.unavailable()) {
            testSpeaker.shutdown();   // try again from scratch, e.g. after installing a speech app
            testSpeaker = null;
        }
        if (testSpeaker == null) {
            testSpeaker = new Speaker(this, new Speaker.Listener() {
                @Override
                public void onSpeakingChanged(boolean speaking) {
                    // Nothing to show.
                }

                @Override
                public void onProblem(String message, boolean needsVoice) {
                    showVoiceProblem(message, needsVoice);
                }
            });
        }
        testSpeaker.forgetProblems();
        testSpeaker.retryAiVoice();
        String language = prefs.language();
        testSpeaker.speak(Speaker.sample(language), Speaker.Options.from(prefs, language), true);
    }

    private void showVoiceProblem(String message, boolean needsVoice) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (!needsVoice) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Voice not installed")
                .setMessage("Your phone doesn't have a voice for " + prefs.language() + " yet.\n\n"
                        + "Tap Install voices, download the language in the list that opens (it's free and "
                        + "works offline), then come back and tap Test voice again.")
                .setPositiveButton("Install voices", (dialog, which) -> openVoiceSettings())
                .setNegativeButton("Close", null)
                .show();
    }

    private void openVoiceSettings() {
        Intent[] choices = {
                new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
                new Intent("com.android.settings.TTS_SETTINGS"),
                new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.tts")),
        };
        for (Intent intent : choices) {
            try {
                startActivity(intent);
                return;
            } catch (ActivityNotFoundException ignored) {
                // Try the next way.
            }
        }
        Toast.makeText(this, "Open the phone's Settings and search for \"Text-to-speech\".", Toast.LENGTH_LONG)
                .show();
    }

    // ----------------------------------------------------------------------------------------
    // Layout (built in code so the whole app has no layout files to keep in sync)

    private void drawBehindSystemBars() {
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN;
            if (!Ui.isDark(this)) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    private View buildScreen() {
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        column.setPadding(pad, pad, pad, Ui.dp(this, 28));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- Top: what it is, and the big Start button ----
        TextView title = text("Screen Translator", 28);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        add(column, title, 4, false);
        add(column, secondary("Tap the floating bubble in any app to translate what's on screen, keep "
                + "translating with ● Live, or translate speech with 🎧 Audio."), 4, false);

        statusView = text("", 15);
        statusView.setLineSpacing(0, 1.15f);
        add(column, statusView, 16, false);

        Button start = new Button(this);
        start.setText("▶  Start translator bubble");
        start.setAllCaps(false);
        start.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        start.setTypeface(Typeface.DEFAULT_BOLD);
        start.setTextColor(Color.WHITE);
        start.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
        start.setMinHeight(Ui.dp(this, 60));
        start.setOnClickListener(v -> onStartTapped());
        startButton = start;
        add(column, start, 10, true);

        stopButton = new Button(this);
        stopButton.setText("■  Stop translator");
        stopButton.setAllCaps(false);
        stopButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        stopButton.setOnClickListener(v -> onStopTapped());
        add(column, stopButton, 4, true);

        // ---- Setup checklist ----
        checklist = card();
        add(column, checklist, 16, true);

        // ---- 1. How to translate ----
        LinearLayout engineBody = section(column, "🧠", "How to translate", 0);
        RadioGroup engines = new RadioGroup(this);
        geminiOption = option("✨  Gemini (Google)  ·  recommended");
        onDeviceOption = option("📱  On this phone (Gemma 4)");
        openAiOption = option("💬  ChatGPT (OpenAI)");
        engines.addView(geminiOption);
        engines.addView(optionNote("Free, fast and very good. Needs internet and a free Google key."));
        engines.addView(onDeviceOption);
        engines.addView(optionNote("Free and private, works without internet. One-time "
                + ModelStore.E2B.sizeText() + " download; needs a newer phone."));
        engines.addView(openAiOption);
        engines.addView(optionNote("Very good, paid per use (a fraction of a cent each). Needs an OpenAI key "
                + "with credit."));
        onDeviceOption.setOnClickListener(v -> onEngineChosen(Prefs.ENGINE_ON_DEVICE));
        geminiOption.setOnClickListener(v -> onEngineChosen(Prefs.ENGINE_GEMINI));
        openAiOption.setOnClickListener(v -> onEngineChosen(Prefs.ENGINE_OPENAI));
        add(engineBody, engines, 0, true);

        onDevicePanel = panel();
        buildOnDevicePanel(onDevicePanel);
        add(engineBody, onDevicePanel, 10, true);

        geminiPanel = panel();
        buildGeminiPanel(geminiPanel);
        add(engineBody, geminiPanel, 10, true);

        openAiPanel = panel();
        buildOpenAiPanel(openAiPanel);
        add(engineBody, openAiPanel, 10, true);

        // ---- 2. Language ----
        LinearLayout languageBody = section(column, "🌐", "Language", 1);
        add(languageBody, label("Translate into"), 0, false);
        languageList = new Spinner(this);
        ArrayAdapter<String> languages = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                Ui.languageLabels());
        languages.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        languageList.setAdapter(languages);
        add(languageBody, languageList, 4, true);
        languageField = field("Type a language, e.g. Tamil",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        add(languageBody, languageField, 4, true);
        languageList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                languageField.setVisibility(position == Ui.LANGUAGES.length ? View.VISIBLE : View.GONE);
                if (!loading) {
                    saveFields();
                }
                refreshSections();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        originalBox = new CheckBox(this);
        originalBox.setText("Also show the original text above each translation");
        originalBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        add(languageBody, originalBox, 10, false);

        // ---- 3. Live & audio ----
        LinearLayout liveBody = section(column, "⚡", "Live, audio & summaries", 2);
        waitSentencesBox = new CheckBox(this);
        waitSentencesBox.setText("Wait for whole sentences");
        waitSentencesBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        waitSentencesBox.setChecked(prefs.waitForSentences());
        waitSentencesBox.setOnCheckedChangeListener((box, checked) -> {
            prefs.setWaitForSentences(checked);
            refreshSections();
        });
        add(liveBody, waitSentencesBox, 0, false);
        add(liveBody, optionNote("For captions and messages that appear word by word: each sentence is translated "
                + "once it's finished, instead of in pieces."), 0, false);

        summaryBox = new CheckBox(this);
        summaryBox.setText("Short summaries instead of full translations");
        summaryBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        summaryBox.setOnCheckedChangeListener((box, checked) -> {
            prefs.setSummaryMode(checked);
            refreshSections();
        });
        add(liveBody, summaryBox, 6, false);
        add(liveBody, optionNote("The gist in 1–3 sentences. You can also switch on any card (Summary / Full) "
                + "or with ≡ in the Live panel."), 0, false);

        add(liveBody, label("🎧 Audio translation"), 14, false);
        add(liveBody, secondary("Translates what's said in videos and streams on this phone, or by people near "
                + "you (microphone). Phone calls can't be translated: Android doesn't allow it."), 4, false);
        audioStatus = secondary("");
        add(liveBody, audioStatus, 8, false);
        audioAllowButton = smallButton("Allow listening", true);
        audioAllowButton.setOnClickListener(v -> askForMicrophone());
        add(liveBody, audioAllowButton, 4, false);
        add(liveBody, secondary("The app remembers what it translated while the bubble is on, so new text follows "
                + "on from the earlier lines. Tap 🗑 in the Live panel to start fresh."), 14, false);

        // ---- 4. Read aloud ----
        LinearLayout voiceBody = section(column, "🔊", "Read aloud", 3);
        readAloudBox = new CheckBox(this);
        readAloudBox.setText("Read every translation aloud automatically");
        readAloudBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        readAloudBox.setOnCheckedChangeListener((box, checked) -> {
            prefs.setReadAloud(checked);
            refreshSections();
        });
        add(voiceBody, readAloudBox, 0, false);
        add(voiceBody, optionNote("Or tap 🔊 on any translation, or a line in the Live panel."), 0, false);

        add(voiceBody, label("Voice"), 12, false);
        RadioGroup voices = new RadioGroup(this);
        phoneVoiceOption = option("Phone voice");
        aiVoiceOption = option("Natural AI voice (Gemini)");
        openAiVoiceOption = option("Natural AI voice (ChatGPT / OpenAI)");
        voices.addView(phoneVoiceOption);
        voices.addView(optionNote("Free and works offline. Clear, but sounds like a machine."));
        voices.addView(aiVoiceOption);
        voices.addView(optionNote("Sounds like a real person, with feeling. Uses your free Gemini key (daily "
                + "limits)."));
        voices.addView(openAiVoiceOption);
        voices.addView(optionNote("OpenAI's most natural voices. Paid: roughly 2–3 US cents per minute of speech."));
        phoneVoiceOption.setOnClickListener(v -> onVoiceModeChosen(Prefs.VOICE_PHONE));
        aiVoiceOption.setOnClickListener(v -> onVoiceModeChosen(Prefs.VOICE_AI));
        openAiVoiceOption.setOnClickListener(v -> onVoiceModeChosen(Prefs.VOICE_OPENAI));
        add(voiceBody, voices, 0, true);

        aiVoicePanel = panel();
        buildAiVoicePanel(aiVoicePanel);
        add(voiceBody, aiVoicePanel, 8, true);

        openAiVoicePanel = panel();
        buildOpenAiVoicePanel(openAiVoicePanel);
        add(voiceBody, openAiVoicePanel, 8, true);

        add(voiceBody, label("Voice speed"), 12, false);
        RadioGroup speeds = new RadioGroup(this);
        speeds.setOrientation(LinearLayout.HORIZONTAL);
        slowOption = option("Slow");
        normalOption = option("Normal");
        fastOption = option("Fast");
        slowOption.setOnClickListener(v -> prefs.setSpeechRate(Speaker.RATE_SLOW));
        normalOption.setOnClickListener(v -> prefs.setSpeechRate(Speaker.RATE_NORMAL));
        fastOption.setOnClickListener(v -> prefs.setSpeechRate(Speaker.RATE_FAST));
        speeds.addView(slowOption);
        speeds.addView(normalOption);
        speeds.addView(fastOption);
        add(voiceBody, speeds, 0, false);

        Button testVoice = smallButton("▶  Test voice", false);
        testVoice.setOnClickListener(v -> onTestVoice());
        add(voiceBody, testVoice, 6, false);

        aiVoiceStatus = text("", 13);
        aiVoiceStatus.setTextColor(Ui.isDark(this) ? 0xFFFFB74D : 0xFFB45309);
        aiVoiceStatus.setLineSpacing(0, 1.15f);
        aiVoiceStatus.setVisibility(View.GONE);
        add(voiceBody, aiVoiceStatus, 4, false);

        // ---- 5. Help ----
        LinearLayout helpBody = section(column, "❓", "Help & tips", 4);
        add(helpBody, helpRow("Translate what's on screen", "Tap the bubble, then drag a box over the text. "
                + "Or tap once anywhere to translate the whole screen."), 0, true);
        helpRow(helpBody, "● Live", "For chats, games and subtitles: choose ● Live after tapping the "
                + "bubble, then the area to watch. New text is translated as it appears.");
        helpRow(helpBody, "🎧 Audio", "Choose 🎧 Audio after tapping the bubble, then Sound on this phone "
                + "or Microphone.");
        helpRow(helpBody, "Move or turn off the bubble", "Drag it anywhere. To turn it off, drag it onto "
                + "the ✕ that appears at the bottom, or tap Stop here.");
        helpRow(helpBody, "The \"over other apps\" switch is greyed out?", "Android does this for apps installed "
                + "from a file. Open Settings → Apps → Screen Translator, tap ⋮ (top right) → \"Allow restricted "
                + "settings\", then turn the switch on.");
        helpRow(helpBody, "Black screen or nothing found?", "Some apps (banking, some video apps) block "
                + "screenshots, so they can't be translated. Try a smaller box around the text.");
        helpRow(helpBody, "Translations sound wrong?", "Gemini and ChatGPT translate better than the "
                + "on-device AI. In Live mode, let it run: each line uses the earlier ones as context.");
        Button guide = smallButton("Show the setup guide again", false);
        guide.setOnClickListener(v -> openWelcome());
        add(helpBody, guide, 12, false);

        refreshSections();
        return scroll;
    }

    // ----------------------------------------------------------------------------------------
    // Sections, checklist and the setup guide

    /** A card with a header that opens and closes it. Returns where its content goes. */
    private LinearLayout section(LinearLayout column, String emoji, String title, int index) {
        LinearLayout card = card();
        card.setPadding(0, 0, 0, 0);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 16);
        header.setPadding(pad, Ui.dp(this, 14), pad, Ui.dp(this, 14));
        TextView icon = text(emoji, 22);
        header.addView(icon, new LinearLayout.LayoutParams(Ui.dp(this, 38), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 17);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        words.addView(name);
        TextView summary = secondary("");
        summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        words.addView(summary);
        header.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView arrow = text("▾", 18);
        arrow.setTextColor(secondaryTextColor());
        header.addView(arrow);
        card.addView(header);

        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, 0, pad, pad);
        card.addView(body);
        header.setOnClickListener(v -> setSectionOpen(index, body.getVisibility() != View.VISIBLE));
        add(column, card, 12, true);

        sectionBodies[index] = body;
        sectionSummaries[index] = summary;
        sectionArrows[index] = arrow;
        boolean open = index == 0 && !engineIsSetUp();   // start with only what still needs doing open
        body.setVisibility(open ? View.VISIBLE : View.GONE);
        arrow.setText(open ? "▴" : "▾");
        return body;
    }

    private void setSectionOpen(int index, boolean open) {
        sectionBodies[index].setVisibility(open ? View.VISIBLE : View.GONE);
        sectionArrows[index].setText(open ? "▴" : "▾");
    }

    /** Opens a section and scrolls to it. */
    private void showSection(int index) {
        setSectionOpen(index, true);
        View card = (View) sectionBodies[index].getParent();
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, card.getTop() - Ui.dp(this, 8))));
    }

    /** The one-line summaries under each section's title. */
    private void refreshSections() {
        if (sectionSummaries[0] == null) {
            return;
        }
        String engine;
        switch (prefs.engine()) {
            case Prefs.ENGINE_GEMINI:
                engine = "Gemini · " + (prefs.geminiKey().isEmpty() ? "add your free key" : "key added ✓");
                break;
            case Prefs.ENGINE_OPENAI:
                engine = "ChatGPT · " + (prefs.openAiKey().isEmpty() ? "add your key" : "key added ✓");
                break;
            default:
                engine = "On this phone · " + (ModelStore.isReady(this, selectedModel()) ? "ready ✓"
                        : ModelStore.progress(this, selectedModel()) != null ? "downloading…" : "download needed");
                break;
        }
        sectionSummaries[0].setText(engine);
        sectionSummaries[1].setText("Into " + currentLanguage()
                + (originalBox != null && originalBox.isChecked() ? " · with the original" : ""));
        sectionSummaries[2].setText((prefs.waitForSentences() ? "Whole sentences" : "Word by word")
                + (prefs.summaryMode() ? " · summaries" : "")
                + " · audio " + (canRecord() ? "allowed ✓" : "not allowed yet"));
        String voice = Prefs.VOICE_AI.equals(prefs.voiceMode()) ? "Gemini voice"
                : Prefs.VOICE_OPENAI.equals(prefs.voiceMode()) ? "OpenAI voice" : "Phone voice";
        sectionSummaries[3].setText((prefs.readAloud() ? "On" : "Off") + " · " + voice);
        sectionSummaries[4].setText("How to use it, and fixes for common problems");
    }

    private boolean engineIsSetUp() {
        switch (prefs.engine()) {
            case Prefs.ENGINE_GEMINI:
                return !prefs.geminiKey().isEmpty();
            case Prefs.ENGINE_OPENAI:
                return !prefs.openAiKey().isEmpty();
            default:
                return ModelStore.isReady(this, selectedModel());
        }
    }

    /** What's done and what's left before translating, with a button for each thing left. */
    private void refreshChecklist() {
        if (checklist == null) {
            return;
        }
        boolean engineOk = engineIsSetUp();
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean micOk = canRecord();
        String state = engineOk + "/" + overlayOk + "/" + micOk + "/" + prefs.engine();
        if (state.equals(checklistState)) {
            return;   // unchanged: don't rebuild every second
        }
        checklistState = state;
        checklist.removeAllViews();
        TextView heading = text(engineOk && overlayOk ? "✓ Ready to translate" : "Finish setting up", 17);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        checklist.addView(heading);
        String engineText;
        switch (prefs.engine()) {
            case Prefs.ENGINE_GEMINI:
                engineText = engineOk ? "Gemini is ready" : "Add your free Gemini key";
                break;
            case Prefs.ENGINE_OPENAI:
                engineText = engineOk ? "ChatGPT is ready" : "Add your OpenAI key";
                break;
            default:
                engineText = engineOk ? "The on-device AI is ready" : "Download the on-device AI";
                break;
        }
        checkRow(engineOk, engineText, engineOk ? null : "Set up", () -> showSection(0));
        checkRow(overlayOk, overlayOk ? "Can show the bubble over other apps" : "Allow the bubble over other apps",
                overlayOk ? null : "Allow", () -> openOverlaySettings(false));
        checkRow(micOk, micOk ? "Listening allowed (for 🎧 Audio)" : "Optional: allow listening (for 🎧 Audio)",
                micOk ? null : "Allow", this::askForMicrophone);
        checklist.setVisibility(engineOk && overlayOk && micOk ? View.GONE : View.VISIBLE);
    }

    private void checkRow(boolean done, String label, String action, Runnable onAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView mark = text(done ? "✓" : "○", 18);
        mark.setTextColor(done ? (Ui.isDark(this) ? 0xFF81C784 : 0xFF2E7D32) : secondaryTextColor());
        row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 30), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView words = text(label, 15);
        if (done) {
            words.setTextColor(secondaryTextColor());
        }
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (action != null) {
            Button button = smallButton(action, true);
            button.setOnClickListener(v -> onAction.run());
            row.addView(button);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 8);
        checklist.addView(row, params);
    }

    private View helpRow(String title, String body) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(title, 15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(name);
        row.addView(secondary(body));
        return row;
    }

    private void helpRow(LinearLayout into, String title, String body) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, 12);
        into.addView(helpRow(title, body), params);
    }

    /** The language chosen in the list (or typed, for "Other…"). */
    private String currentLanguage() {
        if (languageList == null) {
            return prefs.language();
        }
        int position = languageList.getSelectedItemPosition();
        if (position >= 0 && position < Ui.LANGUAGES.length) {
            return Ui.LANGUAGES[position][0];
        }
        String typed = languageField.getText().toString().trim();
        return typed.isEmpty() ? Prefs.DEFAULT_LANGUAGE : typed;
    }

    private void showLanguage(String language) {
        int index = Ui.languageIndex(language);
        languageList.setSelection(index >= 0 ? index : Ui.LANGUAGES.length);
        languageField.setText(index >= 0 ? "" : language);
        languageField.setVisibility(index >= 0 ? View.GONE : View.VISIBLE);
    }

    private void openWelcome() {
        saveFields();
        startActivityForResult(new Intent(this, WelcomeActivity.class), REQUEST_WELCOME);
    }

    /** New users (no key, no model) see the setup guide first. */
    private boolean isNewUser() {
        return prefs.geminiKey().isEmpty() && prefs.openAiKey().isEmpty()
                && !ModelStore.isReady(this, ModelStore.E2B) && !ModelStore.isReady(this, ModelStore.E4B);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 16);
        card.setPadding(pad, pad, pad, pad);
        boolean dark = Ui.isDark(this);
        card.setBackground(Ui.rounded(dark ? 0xFF1E1F25 : 0xFFFFFFFF, Ui.dp(this, 18),
                dark ? 0x22FFFFFF : 0x14000000, Ui.dp(this, 1)));
        return card;
    }

    private void buildOnDevicePanel(LinearLayout panel) {
        panel.addView(secondary("The phone reads the text on screen (Korean, English and other Latin-alphabet "
                + "languages), then Google's Gemma 4 AI translates it with the whole screen as context, so "
                + "it follows meaning and tone instead of word by word. The first translation after starting "
                + "takes a few seconds while the AI loads."));

        TextView modelLabel = label("AI model");
        add(panel, modelLabel, 14, false);
        RadioGroup models = new RadioGroup(this);
        smallModelOption = option(ModelStore.E2B.name + " · " + ModelStore.E2B.sizeText() + " · faster");
        largeModelOption = option(ModelStore.E4B.name + " · " + ModelStore.E4B.sizeText() + " · better quality");
        models.addView(smallModelOption);
        models.addView(optionNote("Recommended. For phones with 8 GB of memory or more."));
        models.addView(largeModelOption);
        models.addView(optionNote("For phones with 12 GB of memory or more."));
        smallModelOption.setOnClickListener(v -> onModelChosen(ModelStore.E2B));
        largeModelOption.setOnClickListener(v -> onModelChosen(ModelStore.E4B));
        add(panel, models, 0, true);

        memoryNote = text("", 13);
        memoryNote.setTextColor(Ui.isDark(this) ? 0xFFFFB74D : 0xFFB45309);
        add(panel, memoryNote, 6, false);

        modelStatus = text("", 15);
        modelStatus.setTypeface(Typeface.DEFAULT_BOLD);
        add(panel, modelStatus, 12, false);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        downloadButton = smallButton("Download", true);
        downloadButton.setOnClickListener(v -> onDownloadTapped());
        cancelButton = smallButton("Cancel download", false);
        cancelButton.setOnClickListener(v -> onCancelTapped());
        deleteButton = smallButton("Delete model", false);
        deleteButton.setOnClickListener(v -> onDeleteTapped());
        buttons.addView(downloadButton);
        buttons.addView(cancelButton);
        buttons.addView(deleteButton);
        add(panel, buttons, 6, false);

        mobileDataBox = new CheckBox(this);
        mobileDataBox.setText("Allow download over mobile data");
        mobileDataBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        add(panel, mobileDataBox, 2, false);

        importButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        importButton.setText("Use a model file from this phone (.litertlm)");
        importButton.setAllCaps(false);
        importButton.setTextColor(Ui.isDark(this) ? Ui.ACCENT_ON_DARK : Ui.ACCENT);
        importButton.setOnClickListener(v -> onImportTapped());
        LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        importParams.leftMargin = -Ui.dp(this, 12);
        panel.addView(importButton, importParams);
    }

    private void buildAiVoicePanel(LinearLayout panel) {
        panel.addView(label("Gemini API key"));
        voiceKeyField = field("Paste your free key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(panel, voiceKeyField, 0, true);
        add(panel, secondary("The same free key the Gemini translation engine uses. It's saved only on this "
                + "phone."), 4, false);
        panel.addView(link("Get a free Gemini key ↗", GEMINI_KEYS_URL), linkParams());

        add(panel, label("Voice"), 10, false);
        String[] voiceLabels = new String[Speaker.AI_VOICES.length];
        for (int i = 0; i < voiceLabels.length; i++) {
            voiceLabels[i] = Speaker.AI_VOICES[i][0] + " (" + Speaker.AI_VOICES[i][1] + ")";
        }
        aiVoiceSpinner = spinner(voiceLabels, position -> prefs.setAiVoiceName(Speaker.AI_VOICES[position][0]));
        add(panel, aiVoiceSpinner, 0, true);

        add(panel, label("Speaking style"), 10, false);
        String[] moodLabels = new String[Speaker.AI_MOODS.length];
        for (int i = 0; i < moodLabels.length; i++) {
            moodLabels[i] = Speaker.AI_MOODS[i][1];
        }
        moodSpinner = spinner(moodLabels, position -> prefs.setAiVoiceMood(Speaker.AI_MOODS[position][0]));
        add(panel, moodSpinner, 0, true);

        add(panel, label("Quality"), 10, false);
        RadioGroup quality = new RadioGroup(this);
        quality.setOrientation(LinearLayout.HORIZONTAL);
        bestQualityOption = option("Most natural");
        fastQualityOption = option("Faster");
        bestQualityOption.setOnClickListener(v -> prefs.setAiVoiceModel(Speaker.AI_MODEL_BEST));
        fastQualityOption.setOnClickListener(v -> prefs.setAiVoiceModel(Speaker.AI_MODEL_FAST));
        quality.addView(bestQualityOption);
        quality.addView(fastQualityOption);
        add(panel, quality, 0, false);

        add(panel, secondary("Tap Test voice to hear it. Gemini's free tier allows a limited number of "
                + "readings per minute and per day; when they run out, the phone voice takes over "
                + "automatically. Google may use free-tier requests to improve its products."), 8, false);
    }

    private void buildOpenAiVoicePanel(LinearLayout panel) {
        panel.addView(label("OpenAI API key"));
        openAiVoiceKeyField = field("sk-…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(panel, openAiVoiceKeyField, 0, true);
        add(panel, secondary("The same key the ChatGPT translation engine uses. It's saved only on this phone. "
                + "Your OpenAI account needs credit (platform.openai.com → Billing)."), 4, false);
        panel.addView(link("Get an API key ↗", OPENAI_KEYS_URL), linkParams());

        add(panel, label("Voice"), 10, false);
        String[] voiceLabels = new String[OpenAiSpeech.VOICES.length];
        for (int i = 0; i < voiceLabels.length; i++) {
            String name = OpenAiSpeech.VOICES[i][0];
            String note = OpenAiSpeech.VOICES[i][1];
            voiceLabels[i] = Character.toUpperCase(name.charAt(0)) + name.substring(1)
                    + (note.isEmpty() ? "" : " (" + note + ")");
        }
        openAiVoiceSpinner = spinner(voiceLabels,
                position -> prefs.setOpenAiVoiceName(OpenAiSpeech.VOICES[position][0]));
        add(panel, openAiVoiceSpinner, 0, true);

        add(panel, label("Speaking style"), 10, false);
        String[] moodLabels = new String[Speaker.AI_MOODS.length];
        for (int i = 0; i < moodLabels.length; i++) {
            moodLabels[i] = Speaker.AI_MOODS[i][1];
        }
        openAiMoodSpinner = spinner(moodLabels, position -> prefs.setAiVoiceMood(Speaker.AI_MOODS[position][0]));
        add(panel, openAiMoodSpinner, 0, true);

        add(panel, secondary("Tap Test voice to hear it. Uses OpenAI's " + OpenAiSpeech.MODEL + " voice model "
                + "(OpenAI's older speech models stop working in January 2027). If it can't be reached or your "
                + "credit runs out, the phone voice takes over automatically."), 8, false);
    }

    /** Shows the settings for the chosen voice. */
    private void showVoicePanel(String mode) {
        aiVoicePanel.setVisibility(Prefs.VOICE_AI.equals(mode) ? View.VISIBLE : View.GONE);
        openAiVoicePanel.setVisibility(Prefs.VOICE_OPENAI.equals(mode) ? View.VISIBLE : View.GONE);
        // Both AI voices share one speaking style.
        int mood = indexOf(Speaker.AI_MOODS, prefs.aiVoiceMood());
        moodSpinner.setSelection(mood);
        openAiMoodSpinner.setSelection(mood);
    }

    /** Says so when the AI voice is resting because Google's free daily limit was reached. */
    private void refreshVoiceStatus() {
        if (aiVoiceStatus == null) {
            return;
        }
        long until = Prefs.VOICE_AI.equals(prefs.voiceMode()) ? prefs.aiVoicePausedUntil(prefs.geminiKey()) : 0;
        if (System.currentTimeMillis() < until) {
            aiVoiceStatus.setText(Speaker.dailyLimitMessage(until) + " Tap Test voice to try the AI voice again now.");
            aiVoiceStatus.setVisibility(View.VISIBLE);
        } else {
            aiVoiceStatus.setVisibility(View.GONE);
        }
    }

    private void onVoiceModeChosen(String mode) {
        prefs.setVoiceMode(mode);
        showVoicePanel(mode);
        refreshVoiceStatus();
        if (testSpeaker != null) {
            testSpeaker.stop();
        }
    }

    /** Keeps two boxes for the same key (translation engine and AI voice) showing the same text. */
    private void linkKeyFields(EditText first, EditText second) {
        first.addTextChangedListener(copyTo(second));
        second.addTextChangedListener(copyTo(first));
    }

    private TextWatcher copyTo(EditText other) {
        return new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
                // Not needed.
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                // Not needed.
            }

            @Override
            public void afterTextChanged(Editable text) {
                if (syncingKeys || other.getText().toString().equals(text.toString())) {
                    return;
                }
                syncingKeys = true;
                other.setText(text.toString());
                syncingKeys = false;
            }
        };
    }

    private interface Choice {
        void onChosen(int position);
    }

    private Spinner spinner(String[] labels, Choice choice) {
        Spinner spinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                choice.onChosen(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Keep the current choice.
            }
        });
        return spinner;
    }

    private static int indexOf(String[][] table, String id) {
        for (int i = 0; i < table.length; i++) {
            if (table[i][0].equals(id)) {
                return i;
            }
        }
        return 0;
    }

    private void buildGeminiPanel(LinearLayout panel) {
        panel.addView(label("Your free Gemini key"));
        add(panel, secondary("1. Tap \"Get a free key\" and sign in with Google.\n"
                + "2. Tap \"Create API key\", then Copy.\n"
                + "3. Come back and tap Paste."), 4, false);
        geminiKeyField = field("Paste your key", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(panel, keyRow(geminiKeyField, GEMINI_KEYS_URL, "Get a free key ↗"), 8, true);
        geminiKeyNote = secondary("");
        add(panel, geminiKeyNote, 2, false);
        watchKey(geminiKeyField, Prefs.ENGINE_GEMINI);
        add(panel, secondary("🔒 Saved only on this phone. The free tier has daily limits, and Google may use "
                + "free-tier requests to improve its products."), 6, false);

        LinearLayout advanced = advanced(panel);
        advanced.addView(label("Gemini model"));
        geminiModelField = field(Prefs.DEFAULT_GEMINI_MODEL, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        add(advanced, geminiModelField, 0, true);
        add(advanced, secondary("Leave empty for " + Prefs.DEFAULT_GEMINI_MODEL + " (fast, free tier). For better "
                + "quality try gemini-3.8-flash (smaller free limit)."), 4, false);
    }

    private void buildOpenAiPanel(LinearLayout panel) {
        panel.addView(label("Your OpenAI key"));
        add(panel, secondary("1. Tap \"Get a key\" and sign in to OpenAI.\n"
                + "2. Add a few dollars of credit under Billing.\n"
                + "3. Tap \"Create new secret key\", then Copy, and come back to tap Paste."), 4, false);
        openAiKeyField = field("sk-…", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        add(panel, keyRow(openAiKeyField, OPENAI_KEYS_URL, "Get a key ↗"), 8, true);
        openAiKeyNote = secondary("");
        add(panel, openAiKeyNote, 2, false);
        watchKey(openAiKeyField, Prefs.ENGINE_OPENAI);
        add(panel, secondary("🔒 Saved only on this phone. Billed separately from a ChatGPT subscription; each "
                + "translation costs a fraction of a cent."), 6, false);

        LinearLayout advanced = advanced(panel);
        advanced.addView(label("ChatGPT model"));
        openAiModelField = field(Prefs.DEFAULT_OPENAI_MODEL, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        add(advanced, openAiModelField, 0, true);
        add(advanced, secondary("Leave empty for " + Prefs.DEFAULT_OPENAI_MODEL + " (fast and low-cost)."), 4, false);
    }

    /** The key box with Paste, and a link to where the key is made. */
    private View keyRow(EditText keyField, String url, String linkText) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.addView(keyField, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button paste = smallButton("Paste", true);
        paste.setOnClickListener(v -> {
            String copied = Ui.clipboardText(this);
            if (copied.isEmpty()) {
                Toast.makeText(this, "Nothing copied yet. Copy the key on the website first.",
                        Toast.LENGTH_LONG).show();
            } else {
                keyField.setText(copied);
                saveFields();
            }
        });
        row.addView(paste);
        box.addView(row);
        box.addView(link(linkText, url), linkParams());
        return box;
    }

    /** Shows "✓ Key added", or a gentle warning if the key looks wrong, as it's typed or pasted. */
    private void watchKey(EditText keyField, String engine) {
        keyField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                showKeyNote(engine);
                if (!loading) {
                    saveFields();
                    refreshSections();
                    refreshChecklist();
                }
            }
        });
    }

    private void showKeyNote(String engine) {
        boolean gemini = Prefs.ENGINE_GEMINI.equals(engine);
        EditText keyField = gemini ? geminiKeyField : openAiKeyField;
        TextView note = gemini ? geminiKeyNote : openAiKeyNote;
        if (keyField == null || note == null) {
            return;
        }
        String key = keyField.getText().toString().trim();
        String warning = Ui.keyWarning(engine, key);
        if (key.isEmpty()) {
            note.setVisibility(View.GONE);
            return;
        }
        note.setVisibility(View.VISIBLE);
        if (warning != null) {
            note.setText("⚠ " + warning);
            note.setTextColor(Ui.isDark(this) ? 0xFFFFB74D : 0xFFB45309);
        } else {
            note.setText("✓ Key added");
            note.setTextColor(Ui.isDark(this) ? 0xFF81C784 : 0xFF2E7D32);
        }
    }

    /** A folded "Advanced" part of a panel, for settings most people never need. */
    private LinearLayout advanced(LinearLayout panel) {
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setVisibility(View.GONE);
        final Button toggle = new Button(this, null, android.R.attr.borderlessButtonStyle);
        toggle.setText("Advanced ▾");
        toggle.setAllCaps(false);
        toggle.setTextColor(secondaryTextColor());
        toggle.setOnClickListener(v -> {
            boolean open = body.getVisibility() != View.VISIBLE;
            body.setVisibility(open ? View.VISIBLE : View.GONE);
            toggle.setText(open ? "Advanced ▴" : "Advanced ▾");
        });
        panel.addView(toggle, linkParams());
        panel.addView(body);
        return body;
    }

    // ----------------------------------------------------------------------------------------
    // Small view helpers

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 14);
        panel.setPadding(pad, pad, pad, pad);
        boolean dark = Ui.isDark(this);
        panel.setBackground(Ui.rounded(dark ? 0x14FFFFFF : 0x0A000000, Ui.dp(this, 14),
                dark ? 0x22FFFFFF : 0x14000000, Ui.dp(this, 1)));
        return panel;
    }

    private RadioButton option(String label) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return button;
    }

    private TextView optionNote(String value) {
        TextView note = secondary(value);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setPadding(Ui.dp(this, 32), 0, 0, Ui.dp(this, 6));
        return note;
    }

    private Button smallButton(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        if (primary) {
            button.setTextColor(Color.WHITE);
            button.setBackgroundTintList(ColorStateList.valueOf(Ui.ACCENT));
        }
        return button;
    }

    private Button link(String label, String url) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(Ui.isDark(this) ? Ui.ACCENT_ON_DARK : Ui.ACCENT);
        button.setOnClickListener(v -> openLink(url));
        return button;
    }

    private LinearLayout.LayoutParams linkParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = -Ui.dp(this, 12);
        return params;
    }

    private void add(LinearLayout column, View view, int topDp, boolean fullWidth) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                fullWidth ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = Ui.dp(this, topDp);
        column.addView(view, params);
    }

    private TextView text(String value, float sizeSp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        return view;
    }

    private TextView secondary(String value) {
        TextView view = text(value, 14);
        view.setTextColor(secondaryTextColor());
        view.setLineSpacing(0, 1.15f);
        return view;
    }

    private TextView label(String value) {
        TextView view = text(value, 14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(true);
        field.setInputType(inputType);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        return field;
    }

    private int secondaryTextColor() {
        return Ui.isDark(this) ? 0xB3FFFFFF : 0xB3000000;
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }
}
