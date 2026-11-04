package com.evc.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.graphics.SurfaceTexture
import android.view.TextureView
import android.view.Surface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaCodec
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.RectF
import android.widget.Button
import android.widget.PopupWindow
import android.widget.RadioGroup
import android.widget.RadioButton
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.TextWatcher
import android.text.Editable
import android.widget.Toast
import android.widget.VideoView
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ExportException
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.util.concurrent.Executors
import java.io.File
import java.io.FileInputStream
import kotlin.math.max
import kotlin.math.abs

enum class TrackClipKind { AUDIO, SUBTITLE }

data class TrackClip(
    val id: Long,
    val uri: Uri,
    val kind: TrackClipKind,
    val name: String,
    val startMs: Long,
    val sourceStartMs: Long,
    val durationMs: Long,
    val sourceDurationMs: Long
)

class MainActivity : Activity() {
    private lateinit var videoView: VideoView
    private lateinit var placementOverlay: PlacementOverlayView
    private var placementMode: PlacementOverlayView.Mode = PlacementOverlayView.Mode.NONE
    private lateinit var playerColorPreview: ImageView
    private val previewBitmaps = mutableMapOf<Int, Bitmap>()
    private var playerPreviewRequest = 0
    private lateinit var statusText: TextView
    private lateinit var emptyHint: TextView
    private lateinit var storyboardRows: LinearLayout
    private lateinit var selectionText: TextView
    private lateinit var timeModeButton: Button
    private lateinit var timelineView: TimelineView
    private val timelineSplitPoints = sortedSetOf<Long>()
    private val deletedTimelineRanges = mutableListOf<Pair<Long, Long>>()
    private var selectedTimelineRange: Pair<Long, Long>? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    // Keep timeline sampling independent so 120 storyboard frames cannot block the timeline strip.
    private val timelineThumbnailWorker = Executors.newSingleThreadExecutor()
    private val selectedFrames = linkedSetOf<Int>()
    // Anchor used for tap-then-long-press range toggling in the storyboard.
    private var storyboardRangeAnchorIndex: Int? = null
    private val frameViews = mutableMapOf<Int, FrameLayout>()
    private val frameBitmaps = mutableMapOf<Int, Bitmap>()
    // Exact timestamps for storyboard cells populated by image-based scene search.
    private val imageSearchFrameTimes = mutableMapOf<Int, Long>()
    private val deletedStoryboardFrames = linkedSetOf<Int>()
    private var selectedUri: Uri? = null
    private var activeTransformer: Transformer? = null
    private var videoDurationMs = 0L
    private var storyboardStartMs = 0L
    private var storyboardEndMs = 0L
    private var storyboardIntervalMs: Long? = null
    // The normal storyboard uses 120 cells; extra cells exist only for a custom interval/range.
    private var activeStoryboardFrameCount = DEFAULT_STORYBOARD_FRAME_COUNT
    private var storyboardGeneration = 0
    private data class RgbProfile(val red: Float, val green: Float, val blue: Float, val saturation: Float)
    private val frameColorProfiles = mutableMapOf<Int, RgbProfile>()
    // Best-effort, luminance-guided colorization for black-and-white footage.
    private var autoColorEnabled = false
    private val mergeUris = mutableListOf<Uri>()
    private val PICK_MERGE_VIDEOS = 7304
    private val PICK_REFERENCE_IMAGE = 7305
    private val PICK_VECTOR_VIDEOS = 7306
    private val PICK_EXTERNAL_AUDIO = 7307
    private val PICK_EXTERNAL_SUBTITLE = 7308
    private val PICK_OVERLAY_VIDEO = 7309
    private val PICK_APPEND_VIDEOS = 7310
    private var overlayVideoUri: Uri? = null
    private lateinit var overlayVideoLayer: FrameLayout
    private lateinit var overlayPreviewTexture: TextureView
    private var overlayPreviewPlayer: MediaPlayer? = null
    private var overlayPreviewSurface: Surface? = null
    private var overlayPreviewVideoWidth = 16
    private var overlayPreviewVideoHeight = 9
    private var overlayVideoXPercent = 70
    private var overlayVideoYPercent = 5
    private var overlayVideoWidthPercent = 25
    private var textXPercent = 5
    private var textYPercent = 5
    private var liveTextSize = 36
    private var externalAudioUri: Uri? = null
    private var externalSubtitleUri: Uri? = null
    private val trackClips = mutableListOf<TrackClip>()
    private var nextTrackClipId = 1L
    private lateinit var trackTimelineView: TrackTimelineView
    private var selectedTrackClipId: Long? = null
    private val vectorUris = mutableListOf<Uri>()
    private var referenceImageUri: Uri? = null
    // Crop margins are stored as percentages of the source frame and reused for export.
    private var cropLeftPct = 0
    private var cropTopPct = 0
    private var cropRightPct = 0
    private var cropBottomPct = 0
    private var cropSettingsSaved = false
    private lateinit var cropOverlay: FreeCropOverlayView
    private lateinit var freeCropControls: LinearLayout
    private var freeCropMode = false
    private data class EditorHistoryState(val splitPoints: List<Long>, val deletedRanges: List<Pair<Long, Long>>, val clips: List<TrackClip>, val profiles: Map<Int, RgbProfile>, val crop: List<Int>, val cropSaved: Boolean, val autoColor: Boolean, val overlayUri: Uri?, val text: String, val textX: Int, val textY: Int, val textSize: Int, val overlayX: Int, val overlayY: Int, val overlayWidth: Int, val merge: List<Uri>, val vector: List<Uri>, val reference: Uri?)
    private val undoHistory = java.util.ArrayDeque<EditorHistoryState>()
    private val redoHistory = java.util.ArrayDeque<EditorHistoryState>()
    private var restoringHistory = false
    private val maxHistorySteps = 200

    // Applies to original-quality stream-copy exports (main export, timeline, storyboard segments).
    private var outputContainerMatroska = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ensureSingleUndoRedoButtons()
        enableActionRailDragReordering()
        findViewById<Button>(R.id.outputChangesButton).setOnClickListener { exportCombinedChanges() }
        findViewById<Button>(R.id.splitOriginalButton).setOnClickListener { showOriginalSplitDialog() }
        findViewById<Button>(R.id.selectSeparateRangesButton).setOnClickListener { showOriginalRangeSelectionDialog(separate = true) }
        findViewById<Button>(R.id.selectContiguousRangeButton).setOnClickListener { showOriginalRangeSelectionDialog(separate = false) }
        findViewById<Button>(R.id.originalOutputButton).setOnClickListener { showOriginalOutputMenu() }
        findViewById<Button>(R.id.globalDeleteButton).setOnClickListener { recordUndoState(); deleteSelectionOrShowOptions() }
        findViewById<Button>(R.id.undoButton).setOnClickListener { undoEditorStep() }
        findViewById<Button>(R.id.redoButton).setOnClickListener { redoEditorStep() }
        val fixedColorControls = findViewById<View>(R.id.fixedColorControls)
        fixedColorControls.visibility = View.GONE
        findViewById<Button>(R.id.colorPanelButton).setOnClickListener {
            fixedColorControls.visibility = if (fixedColorControls.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (fixedColorControls.visibility == View.VISIBLE) statusText.text = "پنجرهٔ رنگ‌آمیزی باز شد؛ پس از تنظیم، دکمهٔ پایان را بزنید"
        }
        findViewById<Button>(R.id.finishColorPanelButton).setOnClickListener {
            fixedColorControls.visibility = View.GONE
            statusText.text = "تنظیمات رنگ حفظ شد؛ برای ویرایش دوباره دکمهٔ «رنگ‌آمیزی» را بزنید"
        }
        findViewById<Button>(R.id.autoColorButton).setOnClickListener {
            recordUndoState()
            autoColorEnabled = !autoColorEnabled
            findViewById<Button>(R.id.autoColorButton).text = if (autoColorEnabled) "Auto Color: روشن" else "Auto Color"
            statusText.post {
                statusText.text = if (autoColorEnabled)
                    "Auto Color روشن شد؛ رنگ‌آمیزی تقریبی بر اساس روشنایی تصویر در «خروجی تغییرات» اعمال می‌شود. تشخیص معنایی آسمان/درخت/صورت با مدل هوش مصنوعی انجام نمی‌شود."
                else "Auto Color خاموش شد"
            }
        }
        videoView = findViewById(R.id.videoView)
        val playerContainer = findViewById<FrameLayout>(R.id.playerContainer)
        // A real second-video preview sits above the source video and below the crop/placement handles.
        overlayVideoLayer = FrameLayout(this)
        overlayVideoLayer.visibility = View.GONE
        playerContainer.addView(overlayVideoLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlayPreviewTexture = TextureView(this)
        overlayVideoLayer.addView(overlayPreviewTexture, FrameLayout.LayoutParams(1, 1))
        overlayPreviewTexture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                startOverlayPreviewOnSurface(surfaceTexture)
            }
            override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) = updateOverlayPreviewLayout()
            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                releaseOverlayPreviewPlayer()
                return true
            }
            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
        }
        cropOverlay = FreeCropOverlayView()
        playerContainer.addView(cropOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        placementOverlay = PlacementOverlayView(this, { xPct, yPct ->
            when (placementMode) {
                PlacementOverlayView.Mode.TEXT -> { textXPercent = xPct; textYPercent = yPct }
                PlacementOverlayView.Mode.VIDEO -> {
                    overlayVideoXPercent = xPct
                    overlayVideoYPercent = yPct
                    updateOverlayPreviewLayout()
                }
                else -> Unit
            }
        }, { newSize ->
            liveTextSize = newSize.coerceIn(8, 160)
            placementOverlay.setTextPreview(findViewById<EditText>(R.id.overlayTextInput).text.toString(), liveTextSize)
        }, { xPct, yPct, widthPct ->
            overlayVideoXPercent = xPct
            overlayVideoYPercent = yPct
            overlayVideoWidthPercent = widthPct.coerceIn(5, 90)
            updateOverlayPreviewLayout()
        }, {
            if (placementMode == PlacementOverlayView.Mode.VIDEO && overlayVideoUri != null) {
                // Touch release commits the live position and immediately proceeds to the unified output action.
                overlayVideoXPercent = overlayVideoXPercent.coerceIn(0, 100 - overlayVideoWidthPercent.coerceIn(5, 90))
                overlayVideoYPercent = overlayVideoYPercent.coerceIn(0, 95)
                placementMode = PlacementOverlayView.Mode.NONE
                placementOverlay.visibility = View.GONE
                findViewById<View>(R.id.overlayVideoPanel).visibility = View.GONE
                statusText.text = "جایگاه و اندازهٔ ویدیوی رویی خودکار ثبت شد؛ در حال رفتن به خروجی تغییرات…"
                findViewById<Button>(R.id.outputChangesButton).performClick()
            }
        })
        placementOverlay.visibility = View.GONE
        playerContainer.addView(placementOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        findViewById<Button>(R.id.hidePlacementOverlayButton).setOnClickListener { placementMode = PlacementOverlayView.Mode.NONE; placementOverlay.visibility = View.GONE; findViewById<View>(R.id.textOverlayPanel).visibility = View.GONE }
        freeCropControls = findViewById(R.id.freeCropControls)
        findViewById<Button>(R.id.cropButton).setOnClickListener { toggleFreeCropMode() }
        findViewById<Button>(R.id.applyFreeCropButton).setOnClickListener {
            recordUndoState()
            readCropOverlay()
            if (!validCropMargins()) return@setOnClickListener
            cropSettingsSaved = true
            freeCropMode = false
            cropOverlay.visibility = View.GONE
            freeCropControls.visibility = View.GONE
            statusText.text = "محدودهٔ Free Crop ثبت شد: چپ $cropLeftPct٪، بالا $cropTopPct٪، راست $cropRightPct٪، پایین $cropBottomPct٪؛ برای ساخت فایل نهایی، دکمهٔ «خروجی تغییرات» را بزنید"
            Toast.makeText(this, "محدودهٔ برش آزاد ثبت شد", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.outputFormatButton).setOnClickListener {
            outputContainerMatroska = !outputContainerMatroska
            findViewById<Button>(R.id.outputFormatButton).text = if (outputContainerMatroska) "فرمت: MKV" else "فرمت: MP4"
            statusText.text = if (outputContainerMatroska)
                "فرمت خروجی بدون رمزگذاری مجدد: MKV؛ ترک‌ها با FFmpeg کپی می‌شوند"
            else "فرمت خروجی بدون رمزگذاری مجدد: MP4"
        }

        playerColorPreview = findViewById(R.id.playerColorPreview)
        statusText = findViewById(R.id.statusText)
        emptyHint = findViewById(R.id.emptyHint)
        storyboardRows = findViewById(R.id.storyboardRows)
        selectionText = findViewById(R.id.selectionText)
        timeModeButton = findViewById(R.id.timeModeButton)
        timelineView = findViewById(R.id.timelineView)
        trackTimelineView = findViewById(R.id.trackTimelineView)
        trackTimelineView.onClipSelected = { clip ->
            selectedTrackClipId = clip?.id
            statusText.text = clip?.let { "ترک انتخابی: ${it.name} • برای انتخاب چند ترک، هر ترک را نگه دارید؛ کشیدن وسط = جابه‌جایی، لبه‌ها = برش" }
                ?: "ترکی انتخاب نشده است"
        }
        trackTimelineView.onClipsChanged = { changed ->
            trackClips.clear(); trackClips.addAll(changed)
            selectedTrackClipId = trackTimelineView.selectedClipId
            syncLegacyTrackUris()
        }
        findViewById<Button>(R.id.splitTrackButton).setOnClickListener { splitSelectedTrackClip() }
        findViewById<Button>(R.id.deleteTrackButton).setOnClickListener { deleteSelectedTrackClip() }
        findViewById<Button>(R.id.resetTracksButton).setOnClickListener {
            recordUndoState()
            trackClips.clear(); selectedTrackClipId = null; externalAudioUri = null; externalSubtitleUri = null
            trackTimelineView.setClips(emptyList(), videoDurationMs)
            findViewById<Button>(R.id.addAudioButton).text = "افزودن صدا"
            findViewById<Button>(R.id.addSubtitleButton).text = "افزودن زیرنویس"
            statusText.text = "همهٔ ترک‌های افزوده‌شده پاک شدند"
        }
        timelineView.onSeek = { timeMs ->
            try { videoView.seekTo(timeMs.coerceIn(0L, videoDurationMs).toInt()) } catch (_: Exception) { }
        }
        timelineView.onSelectRange = { range ->
            selectedTimelineRange = range
            statusText.text = "قطعهٔ انتخابی: ${formatTime(range.first)} تا ${formatTime(range.second)}"
        }
        findViewById<Button>(R.id.splitTimelineButton).setOnClickListener {
            if (videoDurationMs <= 0L) { Toast.makeText(this, "ابتدا ویدیو انتخاب کنید", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (videoDurationMs < 2L) { Toast.makeText(this, "مدت ویدیو برای برش کافی نیست", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            // Cut at the actual player position at the instant the button is tapped,
            // not the last position manually selected on the timeline.
            val currentPosition = try { videoView.currentPosition.toLong() } catch (_: Exception) { timelineView.playheadMs }
            if (currentPosition <= 0L || currentPosition >= videoDurationMs) {
                Toast.makeText(this, "ویدیو را به زمان موردنظر پخش یا جابه‌جا کنید و دوباره Cut بزنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val at = currentPosition.coerceIn(1L, videoDurationMs - 1L)
            if (!timelineSplitPoints.contains(at)) recordUndoState()
            if (timelineSplitPoints.add(at)) {
                timelineView.setPlayhead(at)
                timelineView.setTimeline(videoDurationMs, timelineSplitPoints.toList(), deletedTimelineRanges)
                trackTimelineView.setClips(trackClips.toList(), videoDurationMs)
                selectedTimelineRange = null
                statusText.text = "Cut ثبت شد: ${formatTime(at)}؛ با هر بار زدن، برش دیگری در زمان فعلی پخش ثبت می‌شود"
            } else Toast.makeText(this, "در این زمان برشی ثبت شده است", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.deleteTimelineSegmentButton).setOnClickListener {
            val range = selectedTimelineRange
            if (range == null) { Toast.makeText(this, "ابتدا یک قطعه از نوار زمان را انتخاب کنید", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            if (deletedTimelineRanges.none { it.first == range.first && it.second == range.second }) deletedTimelineRanges.add(range)
            selectedTimelineRange = null
            timelineView.setTimeline(videoDurationMs, timelineSplitPoints.toList(), deletedTimelineRanges)
                trackTimelineView.setClips(trackClips.toList(), videoDurationMs)
            statusText.text = "قطعهٔ ${formatTime(range.first)} تا ${formatTime(range.second)} برای حذف از خروجی علامت خورد"
        }
        findViewById<Button>(R.id.restoreTimelineButton).setOnClickListener {
            deletedTimelineRanges.clear(); timelineSplitPoints.clear(); selectedTimelineRange = null
            timelineView.setTimeline(videoDurationMs, emptyList(), emptyList())
            statusText.text = "برش‌ها و حذف‌های نوار زمان بازگردانی شدند"
        }

        findViewById<Button>(R.id.openButton).setOnClickListener { chooseVideo() }
        val trackPanel = findViewById<View>(R.id.trackPanel)
        trackPanel.visibility = View.GONE
        findViewById<Button>(R.id.trackPanelButton).setOnClickListener {
            trackPanel.visibility = if (trackPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (trackPanel.visibility == View.VISIBLE) statusText.text = "ترک صدا یا زیرنویس اضافه کنید؛ برای ویدیو از انتخاب ویدیوی رویی استفاده می‌شود"
        }
        findViewById<Button>(R.id.finishTrackPanelButton).setOnClickListener {
            trackPanel.visibility = View.GONE
            statusText.text = "تنظیم ترک‌ها حفظ شد؛ برای ویرایش دوباره دکمهٔ «ترک‌ها» را بزنید"
        }
        findViewById<Button>(R.id.addAudioButton).setOnClickListener {
            statusText.text = "در حال باز کردن انتخاب‌گر فایل صوتی…"
            chooseExternalAudio()
        }
        findViewById<Button>(R.id.addSubtitleButton).setOnClickListener { chooseExternalSubtitle() }
        findViewById<Button>(R.id.addVideoTrackButton).setOnClickListener {
            trackPanel.visibility = View.GONE
            findViewById<View>(R.id.overlayVideoPanel).visibility = View.VISIBLE
            statusText.text = "برای افزودن ویدیوی رویی، انتخاب ویدیوی رویی را بزنید؛ این نسخه ویدیوی جداگانه را به‌صورت ترک مستقل تایم‌لاین پشتیبانی نمی‌کند"
        }
        val textOverlayPanel = findViewById<View>(R.id.textOverlayPanel)
        textOverlayPanel.visibility = View.GONE
        findViewById<Button>(R.id.textPlacementButton).setOnClickListener {
            textOverlayPanel.visibility = if (textOverlayPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (textOverlayPanel.visibility == View.VISIBLE) {
                val textInput = findViewById<EditText>(R.id.overlayTextInput)
                textInput.requestFocus()
                if (textInput.text.isNotBlank() && selectedUri != null) activatePlacementMode(PlacementOverlayView.Mode.TEXT)
                statusText.text = "متن را وارد کنید؛ برای جابه‌جایی روی پلیر بکشید و در پایان «پایان متن‌گذاری» را بزنید"
            } else if (placementMode == PlacementOverlayView.Mode.TEXT) {
                placementMode = PlacementOverlayView.Mode.NONE
                placementOverlay.visibility = View.GONE
            }
        }
        findViewById<Button>(R.id.finishTextPlacementButton).setOnClickListener {
            textOverlayPanel.visibility = View.GONE
            if (placementMode == PlacementOverlayView.Mode.TEXT) {
                placementMode = PlacementOverlayView.Mode.NONE
                placementOverlay.visibility = View.GONE
            }
            statusText.text = "تنظیم متن ذخیره شد؛ برای ویرایش دوباره دکمهٔ «متن‌گذاری» را بزنید"
        }
        val overlayVideoPanel = findViewById<View>(R.id.overlayVideoPanel)
        overlayVideoPanel.visibility = View.GONE
        findViewById<Button>(R.id.overlayVideoPanelButton).setOnClickListener {
            overlayVideoPanel.visibility = if (overlayVideoPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (overlayVideoPanel.visibility == View.VISIBLE) {
                statusText.text = "ویدیوی رویی را انتخاب کنید؛ پس از تنظیم، «پایان تنظیم ویدیوی رویی» را بزنید"
            }
        }
        findViewById<EditText>(R.id.overlayTextInput).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateLiveTextPreview() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<Button>(R.id.pickOverlayVideoButton).setOnClickListener {
            statusText.text = "در حال باز کردن انتخاب‌گر ویدیوی رویی…"
            chooseOverlayVideo()
        }
        findViewById<Button>(R.id.joinAfterButton).setOnClickListener { chooseVideosToAppend() }
        findViewById<Button>(R.id.mergeButton).setOnClickListener { if (mergeUris.isEmpty()) chooseVideosForMerge() else mergeSelectedVideos() }
        findViewById<Button>(R.id.vectorButton).setOnClickListener { chooseVideosForVector() }
        findViewById<Button>(R.id.advancedEditButton).setOnClickListener { showAdvancedEditDialog() }
        findViewById<Button>(R.id.imageSceneSearchButton).setOnClickListener { chooseReferenceImage() }
        findViewById<Button>(R.id.selectAllButton).setOnClickListener {
            if (selectedUri == null || videoDurationMs <= 0L) {
                Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectedFrames.clear()
            selectedFrames.addAll((0 until activeStoryboardFrameCount).filter { it !in deletedStoryboardFrames })
            refreshSelectionStyles()
            updateSelectionText()
        }
        findViewById<Button>(R.id.clearSelectionButton).setOnClickListener {
            selectedFrames.clear()
            refreshSelectionStyles()
            updateSelectionText()
        }
        findViewById<Button>(R.id.deleteSelectedButton).setOnClickListener { deleteSelectedStoryboardFrames() }
        findViewById<Button>(R.id.applyColorToSelectedButton).setOnClickListener { applyColorToSelectedFrames() }
        setupManualColorControls()
        timeModeButton.setOnClickListener { showTimeMode() }
        findViewById<Button>(R.id.applyStoryboardTimeRangeButton).setOnClickListener { applyStoryboardTimeRange() }
        findViewById<Button>(R.id.pickStoryboardStartButton).setOnClickListener { setStoryboardTimeFromPlayback(true) }
        findViewById<Button>(R.id.pickStoryboardEndButton).setOnClickListener { setStoryboardTimeFromPlayback(false) }

        val controller = MediaController(this)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener { mediaPlayer ->
            emptyHint.visibility = View.GONE
            playerColorPreview.visibility = View.GONE
            // Match the visible preview area to the source video's aspect ratio and center it.
            // Placement and crop overlays use the exact same bounds, so preview percentages
            // correspond to the actual encoded frame instead of the surrounding black area.
            playerContainer.post {
                updatePlayerViewport(mediaPlayer.videoWidth, mediaPlayer.videoHeight)
            }
            statusText.text = "ویدیو آمادهٔ پخش است"
            mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
                controller.setAnchorView(videoView)
                playerContainer.post { updatePlayerViewport(width, height) }
            }
        }
        videoView.setOnCompletionListener { statusText.text = "پخش تمام شد" }
        videoView.setOnErrorListener { _, what, extra ->
            statusText.text = "پخش این فایل ممکن نیست (خطا $what/$extra)"
            emptyHint.visibility = View.VISIBLE
            true
        }
        buildEmptyStoryboard()
        updateModeButtons()
        handleIncomingVideoIntent(intent)
    }

    /** Accepts one shared video for editing or multiple shared videos for Merge. */
    /** Long-press any tool (or either output button) and drag it to reorder items within the left rail. */
    private fun enableActionRailDragReordering() {
        val container = findViewById<LinearLayout>(R.id.standaloneActionsContainer)
        val scroll = findViewById<ScrollView>(R.id.standaloneActionsScroll)
        val handler = Handler(Looper.getMainLooper())
        var pressedView: View? = null
        var movingItem: View? = null
        var dragging = false
        var downRawY = 0f
        val startDrag = Runnable {
            val pressed = pressedView ?: return@Runnable
            val item = (pressed.parent as? View)?.takeIf { it.parent === container } ?: pressed
            if (item.parent === container) {
                movingItem = item
                dragging = true
                item.alpha = 0.72f
                scroll.requestDisallowInterceptTouchEvent(true)
                Toast.makeText(this, "برای جابه‌جایی، انگشت را نگه دارید و به بالا یا پایین بکشید", Toast.LENGTH_SHORT).show()
            }
        }
        fun moveItemTo(rawY: Float) {
            val item = movingItem ?: return
            val loc = IntArray(2)
            container.getLocationOnScreen(loc)
            val y = rawY - loc[1]
            var targetIndex = container.childCount - 1
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (child === item) continue
                if (y < child.top + child.height / 2f) { targetIndex = i; break }
            }
            val oldIndex = container.indexOfChild(item)
            if (oldIndex >= 0 && targetIndex != oldIndex) {
                container.removeView(item)
                targetIndex = targetIndex.coerceIn(0, container.childCount)
                container.addView(item, targetIndex)
            }
        }
        fun attachDrag(view: View) {
            view.setOnLongClickListener {
                // Long-click feedback is provided by the touch listener; preserve ordinary clicks.
                true
            }
            view.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        pressedView = v
                        downRawY = event.rawY
                        dragging = false
                        movingItem = null
                        handler.removeCallbacks(startDrag)
                        handler.postDelayed(startDrag, 500L)
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (dragging) {
                            moveItemTo(event.rawY)
                            true
                        } else {
                            false
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(startDrag)
                        val wasDragging = dragging
                        movingItem?.alpha = 1f
                        movingItem = null
                        pressedView = null
                        dragging = false
                        scroll.requestDisallowInterceptTouchEvent(false)
                        wasDragging
                    }
                    else -> dragging
                }
            }
        }
        for (i in 0 until container.childCount) {
            val item = container.getChildAt(i)
            attachDrag(item)
            if (item is ViewGroup) {
                for (j in 0 until item.childCount) attachDrag(item.getChildAt(j))
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingVideoIntent(intent)
    }

    private fun handleIncomingVideoIntent(incoming: Intent?) {
        if (incoming == null) return
        val action = incoming.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = mutableListOf<Uri>()
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            @Suppress("DEPRECATION")
            val streams = incoming.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM)
            streams?.forEach { (it as? Uri)?.let(uris::add) }
        } else {
            val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                incoming.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? Uri
            }
            uri?.let(uris::add)
        }
        incoming.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { if (it !in uris) uris.add(it) }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "ویدیوی اشتراک‌گذاری‌شده قابل دریافت نیست", Toast.LENGTH_LONG).show()
            return
        }
        if (uris.size > 1) {
            mergeUris.clear(); mergeUris.addAll(uris.distinct())
            findViewById<Button>(R.id.mergeButton).text = "Merge (${mergeUris.size})"
            statusText.text = "${mergeUris.size} ویدیو دریافت شد؛ برای ادغام، دکمهٔ Merge را بزنید"
            Toast.makeText(this, "ویدیوها به بخش Merge منتقل شدند", Toast.LENGTH_SHORT).show()
        } else {
            mergeUris.clear()
            loadSelectedVideo(uris.first(), false)
        }
    }

    private fun chooseVideosForVector() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_VECTOR_VIDEOS)
    }

    private fun showVectorPreview() {
        val count = vectorUris.size
        val cols = when (count) { 1 -> 1; 2, 3, 4 -> 2; else -> 3 }
        val rows = (count + cols - 1) / cols
        val grid = android.widget.GridLayout(this).apply {
            columnCount = cols; rowCount = rows
            setBackgroundColor(Color.BLACK)
            useDefaultMargins = false
            alignmentMode = android.widget.GridLayout.ALIGN_BOUNDS
        }
        val players = mutableListOf<VideoView>()
        vectorUris.forEachIndexed { index, uri ->
            val player = VideoView(this)
            val lp = android.widget.GridLayout.LayoutParams().apply {
                width = 0; height = 0
                columnSpec = android.widget.GridLayout.spec(index % cols, 1, 1f)
                rowSpec = android.widget.GridLayout.spec(index / cols, 1, 1f)
                setMargins(0, 0, 0, 0)
            }
            player.layoutParams = lp
            player.setVideoURI(uri)
            player.setOnPreparedListener { mp -> mp.isLooping = true }
            grid.addView(player); players.add(player)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "پیش‌نمایش چیدمان فشرده: $count ویدیو • فاصلهٔ قاب‌ها صفر"
                setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
                setPadding(4, 6, 4, 6)
            })
        }
        val dialog = AlertDialog.Builder(this).setTitle("Vector • پیش‌نمایش چیدمان")
            .setView(container)
            .setPositiveButton("پخش هم‌زمان", null)
            .setNeutralButton("انتخاب دوباره", null)
            .setNegativeButton("بستن", null).create()
        dialog.setOnShowListener {
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.78f).toInt())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                players.forEach { it.seekTo(0); it.start() }
                statusText.text = "Vector: پیش‌نمایش $count ویدیو؛ قاب‌ها بدون فاصله هستند. خروجی ترکیبی هنوز در این نسخه پیاده‌سازی نشده است."
                Toast.makeText(this, "پخش پیش‌نمایش آغاز شد؛ خروجی ترکیبی هنوز آماده نیست", Toast.LENGTH_LONG).show()
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { dialog.dismiss(); chooseVideosForVector() }
        }
        dialog.show()
    }

    private fun showAdvancedEditDialog() {
        // A non-modal floating panel lets the user keep interacting with the storyboard/player.
        val density = resources.displayMetrics.density
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(Color.rgb(11, 27, 56))
        }
        val heading = TextView(this).apply {
            text = "ویرایش پیشرفته"
            setTextColor(Color.rgb(255, 210, 31))
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (6 * density).toInt())
        }
        panel.addView(heading)
        fun addControl(label: String, maxValue: Int, initial: Int, onValue: (Int) -> Unit) {
            val valueLabel = TextView(this).apply {
                text = "$label: ${initial}%"; setTextColor(Color.WHITE); textSize = 13f
            }
            panel.addView(valueLabel)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val bar = SeekBar(this).apply {
                max = maxValue; progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        valueLabel.text = "$label: ${progress}%"
                        onValue(progress)
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            row.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "Rest"; textSize = 10f; isAllCaps = false
                setTextColor(Color.rgb(7, 20, 47)); setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(255, 210, 31)))
                minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                setPadding(6, 0, 6, 0)
                setOnClickListener { bar.progress = initial; valueLabel.text = "$label: ${initial}%"; onValue(initial) }
            }, LinearLayout.LayoutParams((96 * density).toInt(), (36 * density).toInt()))
            panel.addView(row)
        }
        var brightness = 100; var contrast = 100; var gamma = 100
        addControl("نور", 200, 100) { brightness = it }
        addControl("کنتراست", 200, 100) { contrast = it }
        addControl("گاما", 300, 100) { gamma = it }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val applyButton = Button(this).apply {
            text = "اعمال"; textSize = 11f; setTextColor(Color.rgb(7, 20, 47))
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(67, 217, 163)))
            setOnClickListener { Toast.makeText(this@MainActivity, "مقادیر نور $brightness٪، کنتراست $contrast٪ و گاما $gamma٪ انتخاب شدند؛ اتصال این تنظیمات به موتور خروجی در نسخهٔ بعدی لازم است.", Toast.LENGTH_LONG).show() }
        }
        val closeButton = Button(this).apply {
            text = "بستن"; textSize = 11f; setTextColor(Color.WHITE)
            setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(65, 91, 130)))
        }
        actions.addView(applyButton, LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f).apply { marginEnd = (4 * density).toInt() })
        actions.addView(closeButton, LinearLayout.LayoutParams(0, (44 * density).toInt(), 1f))
        panel.addView(actions)
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(panel)
        }
        val popup = PopupWindow(scroll, (minOf(280, (resources.displayMetrics.widthPixels / density * 0.68f).toInt()) * density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isTouchable = true
            isFocusable = false
            isOutsideTouchable = false
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(Color.rgb(11, 27, 56))
                cornerRadius = 12 * density
                setStroke((1 * density).toInt(), Color.rgb(255, 210, 31))
            })
            elevation = 12 * density
        }
        closeButton.setOnClickListener { popup.dismiss() }
        val root = findViewById<View>(R.id.mainRootLayout)
        popup.showAtLocation(root, Gravity.TOP or Gravity.START, (84 * density).toInt(), (72 * density).toInt())
    }

    private fun chooseVideosToAppend() {
        if (selectedUri == null) {
            Toast.makeText(this, "ابتدا ویدیوی اصلی را باز کنید؛ سپس Join را بزنید", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, PICK_APPEND_VIDEOS) }
        catch (_: Exception) { Toast.makeText(this, "انتخاب‌گر ویدیو در دسترس نیست", Toast.LENGTH_LONG).show() }
    }

    private fun chooseVideosForMerge() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_MERGE_VIDEOS)
    }

    @Deprecated("Uses Activity result callback for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == PICK_APPEND_VIDEOS) {
            val base = selectedUri
            if (base == null) {
                Toast.makeText(this, "ابتدا ویدیوی اصلی را انتخاب کنید", Toast.LENGTH_LONG).show()
                return
            }
            val picked = mutableListOf<Uri>()
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) picked.add(clip.getItemAt(i).uri) }
            data.data?.let { if (it !in picked) picked.add(it) }
            val ordered = (listOf(base) + picked).distinct()
            if (ordered.size < 2) {
                Toast.makeText(this, "یک یا چند ویدیوی متفاوت برای اتصال به انتهای ویدیوی اصلی انتخاب کنید", Toast.LENGTH_LONG).show()
                return
            }
            mergeUris.clear(); mergeUris.addAll(ordered)
            findViewById<Button>(R.id.mergeButton).text = "Join (${mergeUris.size})"
            statusText.text = "ترتیب اتصال آماده است: ویدیوی اصلی اول می‌آید و ویدیوهای انتخابی به انتهای آن اضافه می‌شوند. در حال ساخت خروجی…"
            mergeSelectedVideos()
        } else if (requestCode == PICK_MERGE_VIDEOS) {
            val picked = mutableListOf<Uri>()
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) picked.add(clip.getItemAt(i).uri) }
            data.data?.let { if (it !in picked) picked.add(it) }
            mergeUris.clear(); mergeUris.addAll(picked.distinct())
            if (mergeUris.size < 2) {
                mergeUris.clear()
                Toast.makeText(this, "برای Merge حداقل دو ویدیو انتخاب کنید", Toast.LENGTH_LONG).show()
                return
            }
            findViewById<Button>(R.id.mergeButton).text = "Merge (${mergeUris.size})"
            statusText.text = "${mergeUris.size} ویدیو برای ادغام آماده است؛ دکمهٔ Merge را دوباره بزنید"
        } else if (requestCode == PICK_VECTOR_VIDEOS) {
            val picked = mutableListOf<Uri>()
            data.clipData?.let { clip -> for (i in 0 until clip.itemCount) picked.add(clip.getItemAt(i).uri) }
            data.data?.let { if (it !in picked) picked.add(it) }
            vectorUris.clear(); vectorUris.addAll(picked.distinct().take(6))
            if (vectorUris.isEmpty()) return
            showVectorPreview()
        } else if (requestCode == REQUEST_VIDEO) {
            val uri = data.data ?: return
            loadSelectedVideo(uri, true)
        } else if (requestCode == PICK_EXTERNAL_AUDIO) {
            val picked = collectPickedUris(data)
            if (picked.isEmpty()) return
            picked.forEach { uri -> addTrackClip(uri, TrackClipKind.AUDIO) }
            findViewById<Button>(R.id.addAudioButton).text = "صداها (${trackClips.count { it.kind == TrackClipKind.AUDIO }}) ✓"
            statusText.text = "${picked.size} فایل صوتی به نوار ترک اضافه شد؛ لبه‌های هر قطعه را برای برش و وسط آن را برای جابه‌جایی بکشید."
        } else if (requestCode == PICK_EXTERNAL_SUBTITLE) {
            val picked = collectPickedUris(data)
            if (picked.isEmpty()) return
            picked.forEach { uri -> addTrackClip(uri, TrackClipKind.SUBTITLE) }
            findViewById<Button>(R.id.addSubtitleButton).text = "زیرنویس‌ها (${trackClips.count { it.kind == TrackClipKind.SUBTITLE }}) ✓"
            statusText.text = "${picked.size} فایل زیرنویس به نوار ترک اضافه شد؛ برای جابه‌جایی یا برش، قطعه را انتخاب و بکشید."
        } else if (requestCode == PICK_OVERLAY_VIDEO) {
            val uri = data.data ?: data.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri ?: return
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            // Verify that the provider actually lets us read the selected video before accepting it.
            try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    if (descriptor.length == 0L) throw IllegalStateException("فایل ویدیوی انتخابی خالی است")
                } ?: throw IllegalStateException("فایل انتخاب‌شده قابل خواندن نیست")
            } catch (e: Exception) {
                overlayVideoUri = null
                Toast.makeText(this, "ویدیوی رویی باز نشد: ${e.message ?: "دسترسی به فایل ناموفق بود"}", Toast.LENGTH_LONG).show()
                statusText.text = "انتخاب ویدیوی رویی ناموفق بود؛ فایل دیگری انتخاب کنید"
                return
            }
            overlayVideoUri = uri
            findViewById<Button>(R.id.pickOverlayVideoButton).text = "ویدیوی رویی ✓"
            showOverlayVideoPreview(uri)
            statusText.text = "ویدیوی رویی روی پلیر قرار گرفت؛ کادر را بکشید یا گوشه‌ها را تغییر دهید. با برداشتن لمس، تنظیمات ثبت و خروجی تغییرات اجرا می‌شود"
            activatePlacementMode(PlacementOverlayView.Mode.VIDEO)
        } else if (requestCode == PICK_REFERENCE_IMAGE) {
            referenceImageUri = data.data ?: return
            if (selectedUri == null || videoDurationMs <= 0L) {
                Toast.makeText(this, "حالا ویدیوی موردنظر را انتخاب کنید؛ سپس دوباره جست‌وجو با عکس را بزنید", Toast.LENGTH_LONG).show()
                return
            }
            searchVideoForImage(referenceImageUri!!, selectedUri!!)
        }
    }

    private fun chooseReferenceImage() {
        if (selectedUri == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا ویدیویی انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, PICK_REFERENCE_IMAGE) }
        catch (_: Exception) { Toast.makeText(this, "انتخاب‌گر عکس در دسترس نیست", Toast.LENGTH_LONG).show() }
    }

    private data class ImageMatch(val timeMs: Long, val score: Double, val bitmap: Bitmap)

    private fun searchVideoForImage(imageUri: Uri, videoUri: Uri) {
        statusText.text = "در حال جست‌وجوی صحنه‌های مشابه عکس…"
        findViewById<Button>(R.id.imageSceneSearchButton).isEnabled = false
        worker.execute {
            var retriever: MediaMetadataRetriever? = null
            try {
                val ref = contentResolver.openInputStream(imageUri)?.use { BitmapFactoryCompat.decode(it) }
                    ?: throw IllegalStateException("خواندن عکس ناموفق بود")
                val refSmall = Bitmap.createScaledBitmap(ref, 32, 32, true)
                ref.recycle()
                retriever = MediaMetadataRetriever().apply { setDataSource(this@MainActivity, videoUri) }
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (duration <= 0L) throw IllegalStateException("مدت ویدیو خوانده نشد")
                val matches = mutableListOf<ImageMatch>()
                var t = 0L
                while (t < duration) {
                    val frame = retriever.getFrameAtTime(t * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    if (frame != null) {
                        val small = Bitmap.createScaledBitmap(frame, 32, 32, true)
                        val score = imageSimilarity(refSmall, small)
                        if (score >= 0.72) matches.add(ImageMatch(t, score, Bitmap.createScaledBitmap(frame, 240, 135, true)))
                        small.recycle(); frame.recycle()
                    }
                    t += 1000L
                }
                refSmall.recycle()
                val filtered = matches.sortedByDescending { it.score }.fold(mutableListOf<ImageMatch>()) { acc, item ->
                    if (acc.none { kotlin.math.abs(it.timeMs - item.timeMs) < 2500L }) acc.add(item)
                    acc
                }.sortedBy { it.timeMs }.take(60)
                mainHandler.post {
                    findViewById<Button>(R.id.imageSceneSearchButton).isEnabled = true
                    if (filtered.isEmpty()) {
                        statusText.text = "صحنهٔ مشابه تازه‌ای پیدا نشد؛ اسلایدهای فعلی حفظ شدند. عکس واضح‌تر یا فریم نزدیک‌تری انتخاب کنید."
                        Toast.makeText(this, "نتیجه‌ای پیدا نشد", Toast.LENGTH_SHORT).show()
                    } else {
                        showImageMatches(filtered)
                        statusText.text = "${filtered.size} صحنهٔ مشابه در اسلایدهای استوری‌بورد نمایش داده شد؛ برای رفتن به هر صحنه همان اسلاید را لمس کنید."
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    findViewById<Button>(R.id.imageSceneSearchButton).isEnabled = true
                    statusText.text = "جست‌وجوی عکس ناموفق بود: ${e.message ?: "خطا"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            } finally { try { retriever?.release() } catch (_: Exception) { } }
        }
    }

    private fun imageSimilarity(a: Bitmap, b: Bitmap): Double {
        val pa = IntArray(32 * 32); val pb = IntArray(32 * 32)
        a.getPixels(pa, 0, 32, 0, 0, 32, 32); b.getPixels(pb, 0, 32, 0, 0, 32, 32)
        var diff = 0.0
        for (i in pa.indices) {
            val ca = pa[i]; val cb = pb[i]
            diff += (kotlin.math.abs(Color.red(ca)-Color.red(cb)) + kotlin.math.abs(Color.green(ca)-Color.green(cb)) + kotlin.math.abs(Color.blue(ca)-Color.blue(cb))) / 765.0
        }
        return 1.0 - diff / pa.size
    }

    /** Replaces the ordinary storyboard grid with image-search matches; cells are laid out in explicit rows. */
    private fun showImageMatches(matches: List<ImageMatch>) {
        if (matches.isEmpty()) return

        frameBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        frameBitmaps.clear()
        imageSearchFrameTimes.clear()
        selectedFrames.clear()
        storyboardRangeAnchorIndex = null
        storyboardRows.removeAllViews()
        frameViews.clear()
        activeStoryboardFrameCount = matches.size

        val availableWidthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density - 86f).coerceAtLeast(180f)
        val columns = ((availableWidthDp - 8f) / 68f).toInt().coerceIn(2, 6)
        matches.forEachIndexed { index, match ->
            imageSearchFrameTimes[index] = match.timeMs
            frameBitmaps[index] = match.bitmap
        }
        matches.indices.toList().chunked(columns).forEach { rowIndices ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            rowIndices.forEach { index ->
                val match = matches[index]
                val cell = makeFrameCell(index, match.bitmap, formatTime(match.timeMs))
                val params = LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                }
                row.addView(cell, params)
                frameViews[index] = cell
            }
            storyboardRows.addView(row)
        }
        updateSelectionText()
    }

    private object BitmapFactoryCompat {
        fun decode(input: java.io.InputStream): Bitmap? = android.graphics.BitmapFactory.decodeStream(input)
    }

    private fun mergeSelectedVideos() {
        val sources = mergeUris.toList()
        if (sources.size < 2) { chooseVideosForMerge(); return }
        val button = findViewById<Button>(R.id.mergeButton)
        button.isEnabled = false
        statusText.text = "در حال ادغام ${sources.size} ویدیو…"
        val output = File(cacheDir, "evc_merge_${System.currentTimeMillis()}.mp4")
        try {
            val items = sources.map { uri -> EditedMediaItem.Builder(MediaItem.fromUri(uri)).build() }
            val sequence = EditedMediaItemSequence.Builder(items).build()
            val composition = Composition.Builder(sequence).build()
            val transformer = Transformer.Builder(this).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    activeTransformer = null
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_Merged_${System.currentTimeMillis()}.mp4")
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                                put(MediaStore.Video.Media.IS_PENDING, 1)
                            }
                        }
                        val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("ساخت فایل در Movies/EVC ممکن نشد")
                        contentResolver.openOutputStream(outUri)?.use { out -> FileInputStream(output).use { it.copyTo(out) } }
                            ?: throw IllegalStateException("نوشتن فایل ادغام‌شده ناموفق بود")
                        if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                        writeOutputInfoReport(outUri)
                        output.delete(); mergeUris.clear(); button.text = "Merge"; button.isEnabled = true
                        statusText.text = "ویدیوها در Movies/EVC ادغام شدند"
                        Toast.makeText(this@MainActivity, "فایل ادغام‌شده ذخیره شد", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) { output.delete(); button.isEnabled = true; statusText.text = "ذخیرهٔ Merge ناموفق بود: ${e.message}" }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    activeTransformer = null; output.delete(); button.isEnabled = true
                    statusText.text = "ادغام ناموفق بود: ${exportException.message ?: "خطای پردازش ویدیو"}"
                    Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                }
            }).build()
            activeTransformer = transformer
            transformer.start(composition, output.absolutePath)
        } catch (e: Exception) {
            activeTransformer = null; output.delete(); button.isEnabled = true
            statusText.text = "شروع Merge ناموفق بود: ${e.message ?: "خطا"}"
        }
    }

    private fun loadSelectedVideo(uri: Uri, persistPermission: Boolean) {
        if (persistPermission) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        }
        selectedUri = uri
        storyboardStartMs = 0L
        storyboardEndMs = 0L
        storyboardIntervalMs = null
        activeStoryboardFrameCount = DEFAULT_STORYBOARD_FRAME_COUNT
        findViewById<EditText>(R.id.storyboardIntervalTime).setText("")
        timelineSplitPoints.clear()
        deletedTimelineRanges.clear()
        selectedTimelineRange = null
        timelineView.setTimeline(0L, emptyList(), emptyList())
        trackClips.clear(); selectedTrackClipId = null
        if (::trackTimelineView.isInitialized) trackTimelineView.setClips(emptyList(), 0L)
        externalAudioUri = null; externalSubtitleUri = null
        findViewById<Button>(R.id.addAudioButton).text = "افزودن صدا"
        findViewById<Button>(R.id.addSubtitleButton).text = "افزودن زیرنویس"
        timelineView.setThumbnails(emptyList())
        selectedFrames.clear()
        frameBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        frameBitmaps.clear()
        previewBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        previewBitmaps.clear()
        playerColorPreview.setImageDrawable(null)
        playerColorPreview.visibility = View.GONE
        deletedStoryboardFrames.clear()
        frameColorProfiles.clear()
        buildEmptyStoryboard()
        emptyHint.visibility = View.VISIBLE
        statusText.text = "در حال آماده‌سازی ویدیو و استخراج فریم‌ها…"
        videoView.setVideoURI(uri)
        videoView.requestFocus()
        loadStoryboard(uri)
    }

    /** Sets up manual RGB channel controls for the real video export. */
    private fun setupManualColorControls() {
        val red = findViewById<SeekBar>(R.id.redColorSeek)
        val green = findViewById<SeekBar>(R.id.greenColorSeek)
        val blue = findViewById<SeekBar>(R.id.blueColorSeek)
        val saturation = findViewById<SeekBar>(R.id.saturationSeek)
        val saturationValue = findViewById<TextView>(R.id.saturationValue)
        val redValue = findViewById<TextView>(R.id.redColorValue)
        val greenValue = findViewById<TextView>(R.id.greenColorValue)
        val blueValue = findViewById<TextView>(R.id.blueColorValue)
        fun updateLabels() {
            redValue.text = "قرمز ${"%.2f".format(java.util.Locale.US, red.progress / 100f)}"
            greenValue.text = "سبز ${"%.2f".format(java.util.Locale.US, green.progress / 100f)}"
            blueValue.text = "آبی ${"%.2f".format(java.util.Locale.US, blue.progress / 100f)}"
            saturationValue.text = "اشباع رنگ ${"%.2f".format(java.util.Locale.US, saturation.progress / 100f)}"
        }
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
                if (fromUser) refreshLiveColorPreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        red.setOnSeekBarChangeListener(listener)
        green.setOnSeekBarChangeListener(listener)
        blue.setOnSeekBarChangeListener(listener)
        saturation.setOnSeekBarChangeListener(listener)

        // Each Reset button restores only its own control to neutral (1.00).
        fun bindReset(buttonId: Int, seekBar: SeekBar) {
            findViewById<Button>(buttonId).setOnClickListener {
                seekBar.progress = 100
                updateLabels()
                refreshLiveColorPreview()
            }
        }
        bindReset(R.id.resetRedColorButton, red)
        bindReset(R.id.resetGreenColorButton, green)
        bindReset(R.id.resetBlueColorButton, blue)
        bindReset(R.id.resetSaturationButton, saturation)
        updateLabels()
    }

    /** Save the current RGB combination for every selected storyboard interval. */
    private fun applyColorToSelectedFrames() {
        recordUndoState()
        if (selectedUri == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا ویدیو را انتخاب کنید", Toast.LENGTH_SHORT).show(); return
        }
        if (selectedFrames.isEmpty()) {
            Toast.makeText(this, "ابتدا یک یا چند اسلاید را انتخاب کنید", Toast.LENGTH_LONG).show(); return
        }
        val profile = currentRgbProfile()
        selectedFrames.forEach { frameColorProfiles[it] = profile }
        refreshLiveColorPreview()
        statusText.text = "ترکیب رنگ فعلی برای ${selectedFrames.size} اسلاید ذخیره شد؛ برای هر اسلاید می‌توان ترکیب دیگری ثبت کرد"
        Toast.makeText(this, "تنظیم رنگ برای ${selectedFrames.size} اسلاید ذخیره شد", Toast.LENGTH_SHORT).show()
    }

    /** Updates storyboard thumbnails immediately and shows a tinted still-frame overlay in the player. */
    private fun refreshLiveColorPreview() {
        val current = currentRgbProfile()
        for ((index, original) in frameBitmaps) {
            if (original.isRecycled) continue
            val profile = if (selectedFrames.isEmpty() || index in selectedFrames) current else frameColorProfiles[index] ?: RgbProfile(1f, 1f, 1f, 1f)
            val tinted = tintBitmap(original, profile)
            previewBitmaps.put(index, tinted)?.let { old -> if (!old.isRecycled) old.recycle() }
            val cell = frameViews[index]
            val image = cell?.let { container ->
                (0 until container.childCount).mapNotNull { container.getChildAt(it) as? ImageView }.firstOrNull()
            }
            image?.setImageBitmap(tinted)
        }
        val source = selectedUri ?: run { playerColorPreview.visibility = View.GONE; return }
        val request = ++playerPreviewRequest
        val position = try { videoView.currentPosition.toLong().coerceAtLeast(0L) } catch (_: Exception) { 0L }
        playerColorPreview.visibility = View.VISIBLE
        statusText.text = "پیش‌نمایش زندهٔ رنگ در حال به‌روزرسانی است…"
        worker.execute {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, source)
                val frame = retriever.getFrameAtTime(position * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val tinted = tintBitmap(frame, current)
                    if (!frame.isRecycled) frame.recycle()
                    mainHandler.post {
                        if (request == playerPreviewRequest && !isFinishing) {
                            playerColorPreview.setImageBitmap(tinted)
                            playerColorPreview.visibility = View.VISIBLE
                            statusText.text = "پیش‌نمایش رنگ روی اسلایدها و فریم فعلی پلیر به‌روز شد"
                        } else if (!tinted.isRecycled) tinted.recycle()
                    }
                }
            } catch (_: Exception) {
                mainHandler.post {
                    if (request == playerPreviewRequest) {
                        playerColorPreview.visibility = View.GONE
                        statusText.text = "پیش‌نمایش اسلایدها به‌روز شد؛ گرفتن فریم پلیر ممکن نشد"
                    }
                }
            } finally { try { retriever?.release() } catch (_: Exception) {} }
        }
    }

    private fun tintBitmap(source: Bitmap, profile: RgbProfile): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (Color.red(c) * profile.red).coerceIn(0f, 255f)
            val g = (Color.green(c) * profile.green).coerceIn(0f, 255f)
            val b = (Color.blue(c) * profile.blue).coerceIn(0f, 255f)
            val gray = 0.213f * r + 0.715f * g + 0.072f * b
            val sat = profile.saturation
            pixels[i] = Color.argb(Color.alpha(c),
                (gray + (r - gray) * sat).toInt().coerceIn(0, 255),
                (gray + (g - gray) * sat).toInt().coerceIn(0, 255),
                (gray + (b - gray) * sat).toInt().coerceIn(0, 255))
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    private fun currentRgbProfile() = RgbProfile(
        findViewById<SeekBar>(R.id.redColorSeek).progress / 100f,
        findViewById<SeekBar>(R.id.greenColorSeek).progress / 100f,
        findViewById<SeekBar>(R.id.blueColorSeek).progress / 100f,
        findViewById<SeekBar>(R.id.saturationSeek).progress / 100f
    )

    /** Exports the full video with an independently saved RGB effect for each storyboard interval. */
    private fun exportColorizedVideo() {
        val source = selectedUri
        if (source == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا یک ویدیوی معتبر انتخاب کنید", Toast.LENGTH_LONG).show(); return
        }
        val sizeField = findViewById<android.widget.EditText>(R.id.targetSizeMb)
        val targetMb = sizeField.text.toString().trim().replace(',', '.').toFloatOrNull()
        if (targetMb == null || targetMb < 1f || targetMb > 4096f) {
            sizeField.error = "حجم هدف را بین 1 تا 4096 مگابایت وارد کنید"; return
        }
        val button = findViewById<Button>(R.id.outputChangesButton)
        button.isEnabled = false
        statusText.text = "در حال ساخت خروجی با اعمال تغییرات رنگ و حجم هدف؛ رمزگذاری مجدد انجام می‌شود…"
        val output = File(cacheDir, "evc_colorized_${System.currentTimeMillis()}.mp4")
        try {
            val total = activeStoryboardFrameCount
            val rangeStart = storyboardStartMs.coerceIn(0L, videoDurationMs)
            val rangeEnd = (if (storyboardEndMs > rangeStart) storyboardEndMs else videoDurationMs)
                .coerceIn(rangeStart, videoDurationMs)
            if (rangeEnd <= rangeStart) throw IllegalStateException("بازهٔ زمانی استوری‌بورد معتبر نیست")

            // Export only intervals for which the user explicitly saved a color profile.
            // Do not add neutral/unselected storyboard intervals to this output.
            val coloredIndices = frameColorProfiles.keys
                .filter { it in 0 until total && it !in deletedStoryboardFrames }
                .sorted()
            val intervals = coloredIndices.mapNotNull { index ->
                val startMs = storyboardTimestampForIndex(index, total, rangeStart, rangeEnd)
                val endMs = if (storyboardIntervalMs != null && storyboardIntervalMs!! > 0L) {
                    minOf(rangeEnd, startMs + storyboardIntervalMs!!)
                } else if (index + 1 < total) {
                    minOf(rangeEnd, storyboardTimestampForIndex(index + 1, total, rangeStart, rangeEnd))
                } else rangeEnd
                if (startMs >= rangeEnd || endMs <= startMs) null else Triple(index, startMs, endMs)
            }
            val items = intervals.map { (index, startMs, endMs) ->
                val profile = frameColorProfiles[index] ?: throw IllegalStateException("تنظیم رنگ اسلاید پیدا نشد")
                val effect = RgbAdjustment.Builder()
                    .setRedScale(profile.red).setGreenScale(profile.green).setBlueScale(profile.blue).build()
                val saturationEffect = HslAdjustment.Builder().adjustSaturation(((profile.saturation - 1f) * 100f).coerceIn(-100f, 100f)).build()
                val clippedMedia = MediaItem.Builder().setUri(source)
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build()
                EditedMediaItem.Builder(clippedMedia).setEffects(Effects(emptyList(), listOf(effect, saturationEffect))).build()
            }
            if (items.isEmpty()) throw IllegalStateException("ابتدا برای اسلایدهای موردنظر تنظیم رنگ ذخیره کنید؛ هیچ بازهٔ رنگی برای خروجی وجود ندارد")
            val selectedDurationMs = intervals.sumOf { it.third - it.second }.coerceAtLeast(1L)
            // Estimate bitrate from the total duration of colored intervals, not the full source video.
            val totalBitsPerSecond = (targetMb.toDouble() * 1024.0 * 1024.0 * 8.0 / (selectedDurationMs / 1000.0)).toInt()
            val videoBitrate = (totalBitsPerSecond - 128_000).coerceIn(200_000, 20_000_000)
            val encoderFactory = DefaultEncoderFactory.Builder(this)
                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(videoBitrate).build())
                .build()
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()
            val transformer = Transformer.Builder(this)
                .setEncoderFactory(encoderFactory)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        activeTransformer = null
                        button.isEnabled = true
                        try {
                            val values = ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_colored_intervals_${System.currentTimeMillis()}.mp4")
                                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                                if (android.os.Build.VERSION.SDK_INT >= 29) {
                                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                                    put(MediaStore.Video.Media.IS_PENDING, 1)
                                }
                            }
                            val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                                ?: throw IllegalStateException("ساخت فایل در Movies/EVC ممکن نشد")
                            contentResolver.openOutputStream(outUri)?.use { out -> FileInputStream(output).use { it.copyTo(out) } }
                                ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                            if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(outUri, ContentValues().apply {
                                put(MediaStore.Video.Media.IS_PENDING, 0)
                            }, null, null)
                            writeOutputInfoReport(outUri)
                            output.delete()
                            statusText.text = "فقط ${intervals.size} بازهٔ دارای تنظیم رنگ در Movies/EVC ذخیره شد؛ حجم هدف حدود ${targetMb} MB بود و رمزگذاری مجدد انجام شد."
                            Toast.makeText(this@MainActivity, "فقط بازه‌های رنگ‌گذاری‌شده ذخیره شدند؛ حجم نهایی ممکن است با هدف تفاوت داشته باشد", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            output.delete(); statusText.text = "ذخیرهٔ خروجی ناموفق بود: ${e.message ?: "خطا"}"
                            Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                        }
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        activeTransformer = null; output.delete(); button.isEnabled = true
                        statusText.text = "خروجی رنگی ناموفق بود: ${exportException.message ?: "خطای رمزگذاری"}"
                        Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                    }
                }).build()
            activeTransformer = transformer
            transformer.start(composition, output.absolutePath)
        } catch (e: Exception) {
            activeTransformer = null; output.delete(); button.isEnabled = true
            statusText.text = "شروع خروجی رنگی ناموفق بود: ${e.message ?: "خطا"}"
            Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
        }
    }

    // Experimental thumbnail-only colorization preview. It does not alter the source video
    // or the exported video; this first prototype uses luminance heuristics, not AI segmentation.
    private fun colorizeStoryboardPreview() {
        if (frameBitmaps.isEmpty()) {
            Toast.makeText(this, "ابتدا ویدیو را انتخاب کنید و منتظر استخراج اسلایدها بمانید", Toast.LENGTH_LONG).show()
            return
        }
        var processed = 0
        for ((index, original) in frameBitmaps) {
            if (original.isRecycled) continue
            val w = original.width
            val h = original.height
            val pixels = IntArray(w * h)
            original.getPixels(pixels, 0, w, 0, 0, w, h)
            for (i in pixels.indices) {
                val c = pixels[i]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                val chroma = maxOf(r, g, b) - minOf(r, g, b)
                if (chroma < 28 && y in 35..220) {
                    // Heuristic only: mid/darker tones receive a restrained green tint;
                    // brighter midtones receive a restrained warm tint.
                    pixels[i] = if (y < 118) {
                        Color.rgb((y * 0.72).toInt().coerceIn(0,255),
                                  (y * 1.08 + 8).toInt().coerceIn(0,255),
                                  (y * 0.70).toInt().coerceIn(0,255))
                    } else {
                        Color.rgb((y * 1.10 + 12).toInt().coerceIn(0,255),
                                  (y * 0.88 + 5).toInt().coerceIn(0,255),
                                  (y * 0.72).toInt().coerceIn(0,255))
                    }
                }
            }
            val preview = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            preview.setPixels(pixels, 0, w, 0, 0, w, h)
            val cell = frameViews[index]
            val image = cell?.let { container ->
                (0 until container.childCount).mapNotNull { container.getChildAt(it) as? ImageView }.firstOrNull()
            }
            image?.setImageBitmap(preview)
            processed++
        }
        statusText.text = "پیش‌نمایش رنگی‌سازی آزمایشی روی $processed اسلاید اعمال شد؛ ویدیوی اصلی و خروجی تغییر نکرده‌اند"
        Toast.makeText(this, "این نسخه فقط پیش‌نمایش اسلایدها را رنگی می‌کند؛ خروجی ویدیویی رنگی هنوز فعال نیست", Toast.LENGTH_LONG).show()
    }

    private fun collectPickedUris(data: Intent): List<Uri> {
        val picked = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) picked.add(clip.getItemAt(i).uri) }
        data.data?.let { if (it !in picked) picked.add(it) }
        picked.distinct().forEach { uri ->
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
        }
        return picked
    }

    private fun addTrackClip(uri: Uri, kind: TrackClipKind) {
        val name = displayNameForUri(uri)
        val sourceDuration = if (kind == TrackClipKind.AUDIO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: videoDurationMs
                retriever.release()
                duration.coerceAtLeast(1000L)
            } catch (_: Exception) { videoDurationMs.coerceAtLeast(1000L) }
        } else videoDurationMs.coerceAtLeast(1000L)
        val playhead = try { videoView.currentPosition.toLong() } catch (_: Exception) { timelineView.playheadMs }
        val clip = TrackClip(nextTrackClipId++, uri, kind, name, playhead.coerceAtLeast(0L), 0L,
            minOf(sourceDuration, (videoDurationMs - playhead).coerceAtLeast(1000L)).coerceAtLeast(1000L), sourceDuration)
        trackClips.add(clip)
        selectedTrackClipId = clip.id
        trackTimelineView.setClips(trackClips.toList(), videoDurationMs)
        trackTimelineView.selectClip(clip.id)
        syncLegacyTrackUris()
    }

    private fun syncLegacyTrackUris() {
        externalAudioUri = trackClips.firstOrNull { it.kind == TrackClipKind.AUDIO }?.uri
        externalSubtitleUri = trackClips.firstOrNull { it.kind == TrackClipKind.SUBTITLE }?.uri
    }

    private fun formatTrackTime(ms: Long): String {
        val seconds = (ms.coerceAtLeast(0L) / 1000L)
        return String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun captureEditorHistoryState() = EditorHistoryState(timelineSplitPoints.toList(), deletedTimelineRanges.toList(), trackClips.toList(), frameColorProfiles.toMap(), listOf(cropLeftPct, cropTopPct, cropRightPct, cropBottomPct), cropSettingsSaved, autoColorEnabled, overlayVideoUri, findViewById<EditText>(R.id.overlayTextInput).text.toString(), textXPercent, textYPercent, liveTextSize, overlayVideoXPercent, overlayVideoYPercent, overlayVideoWidthPercent, mergeUris.toList(), vectorUris.toList(), referenceImageUri)

    /** Keep one visible Undo and one visible Redo action, even if a layout revision accidentally duplicates them. */
    private fun ensureSingleUndoRedoButtons() {
        val root = findViewById<ViewGroup>(R.id.mainRootLayout)
        val allButtons = mutableListOf<Button>()
        fun collect(view: View) {
            if (view is Button) allButtons.add(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
        }
        collect(root)

        fun keepOne(targetId: Int, word: String, arrow: String) {
            val candidates = allButtons.filter { button ->
                button.id == targetId || button.text.toString().contains(word, ignoreCase = true) || button.text.toString().contains(arrow)
            }
            val keeper = candidates.firstOrNull { it.id == targetId } ?: candidates.firstOrNull()
            candidates.forEach { button ->
                if (button !== keeper) (button.parent as? ViewGroup)?.removeView(button)
            }
        }
        keepOne(R.id.undoButton, "undo", "↶")
        keepOne(R.id.redoButton, "redo", "↷")
    }

    private fun recordUndoState() {
        if (restoringHistory) return
        undoHistory.addLast(captureEditorHistoryState())
        while (undoHistory.size > maxHistorySteps) undoHistory.removeFirst()
        redoHistory.clear(); updateHistoryButtons()
    }

    private fun restoreEditorHistoryState(state: EditorHistoryState) {
        restoringHistory = true
        try {
            timelineSplitPoints.clear(); timelineSplitPoints.addAll(state.splitPoints)
            deletedTimelineRanges.clear(); deletedTimelineRanges.addAll(state.deletedRanges)
            trackClips.clear(); trackClips.addAll(state.clips)
            frameColorProfiles.clear(); frameColorProfiles.putAll(state.profiles)
            cropLeftPct=state.crop[0]; cropTopPct=state.crop[1]; cropRightPct=state.crop[2]; cropBottomPct=state.crop[3]; cropSettingsSaved=state.cropSaved
            autoColorEnabled=state.autoColor; overlayVideoUri=state.overlayUri
            findViewById<EditText>(R.id.overlayTextInput).setText(state.text)
            textXPercent=state.textX; textYPercent=state.textY; liveTextSize=state.textSize
            overlayVideoXPercent=state.overlayX; overlayVideoYPercent=state.overlayY; overlayVideoWidthPercent=state.overlayWidth
            mergeUris.clear(); mergeUris.addAll(state.merge); vectorUris.clear(); vectorUris.addAll(state.vector); referenceImageUri=state.reference
            selectedTrackClipId=null; selectedTimelineRange=null
            timelineView.setTimeline(videoDurationMs, timelineSplitPoints.toList(), deletedTimelineRanges)
            trackTimelineView.setClips(trackClips.toList(), videoDurationMs); syncLegacyTrackUris()
            findViewById<Button>(R.id.autoColorButton).text=if(autoColorEnabled) "Auto Color: روشن" else "Auto Color"
            findViewById<Button>(R.id.pickOverlayVideoButton).text=if(overlayVideoUri!=null) "ویدیوی رویی انتخاب شد ✓" else "انتخاب ویدیوی رویی"
            findViewById<Button>(R.id.mergeButton).text=if(mergeUris.isEmpty()) "Merge • چند ویدیو" else "Merge • ${mergeUris.size} ویدیو ✓"
            refreshSelectionStyles(); updateSelectionText(); updateOverlayPreviewLayout()
            statusText.text="مرحلهٔ ویرایش بازگردانی شد؛ فایل اصلی ویدیو تغییری نکرده است"
        } finally { restoringHistory=false }
        updateHistoryButtons()
    }

    private fun undoEditorStep() {
        if (undoHistory.isEmpty()) { Toast.makeText(this, "مرحله‌ای برای Undo وجود ندارد", Toast.LENGTH_SHORT).show(); return }
        redoHistory.addLast(captureEditorHistoryState()); restoreEditorHistoryState(undoHistory.removeLast()); statusText.text="Undo انجام شد"
    }

    private fun redoEditorStep() {
        if (redoHistory.isEmpty()) { Toast.makeText(this, "مرحله‌ای برای Redo وجود ندارد", Toast.LENGTH_SHORT).show(); return }
        undoHistory.addLast(captureEditorHistoryState()); restoreEditorHistoryState(redoHistory.removeLast()); statusText.text="Redo انجام شد"
    }

    private fun updateHistoryButtons() {
        findViewById<Button>(R.id.undoButton).apply { isEnabled=undoHistory.isNotEmpty(); alpha=if(isEnabled) 1f else 0.55f }
        findViewById<Button>(R.id.redoButton).apply { isEnabled=redoHistory.isNotEmpty(); alpha=if(isEnabled) 1f else 0.55f }
    }

    private fun splitSelectedTrackClip() {
        val selectedId = selectedTrackClipId ?: run { Toast.makeText(this, "ابتدا یک قطعهٔ صدا یا زیرنویس را انتخاب کنید", Toast.LENGTH_SHORT).show(); return }
        val clip = trackClips.firstOrNull { it.id == selectedId } ?: return
        val playhead = try { videoView.currentPosition.toLong() } catch (_: Exception) { timelineView.playheadMs }
        val offset = playhead - clip.startMs
        if (offset < 500L || offset > clip.durationMs - 500L) {
            Toast.makeText(this, "نشانگر پخش باید داخل قطعه و دست‌کم نیم ثانیه دور از لبه‌ها باشد", Toast.LENGTH_LONG).show(); return
        }
        val index = trackClips.indexOfFirst { it.id == selectedId }
        recordUndoState()
        val first = clip.copy(durationMs = offset)
        val second = clip.copy(id = nextTrackClipId++, name = clip.name + " (بخش ۲)", startMs = clip.startMs + offset,
            sourceStartMs = clip.sourceStartMs + offset, durationMs = clip.durationMs - offset)
        trackClips[index] = first
        trackClips.add(index + 1, second)
        selectedTrackClipId = second.id
        trackTimelineView.setClips(trackClips.toList(), videoDurationMs)
        trackTimelineView.selectClip(second.id)
        statusText.text = "ترک به دو بخش جدا تقسیم شد؛ هر بخش را مستقل می‌توانید جابه‌جا یا کوتاه کنید."
    }

    /** Delete currently selected items directly; only show the legacy options when nothing is selected. */
    private fun deleteSelectionOrShowOptions() {
        val trackIds = trackTimelineView.selectedClipIds.toSet()
        val hasSelection = selectedFrames.isNotEmpty() || trackIds.isNotEmpty() || selectedTrackClipId != null || selectedTimelineRange != null
        if (!hasSelection) {
            Toast.makeText(this, "ابتدا موردی را در همان بخش انتخاب کنید؛ برای انتخاب چند ترک، هر ترک را نگه دارید", Toast.LENGTH_LONG).show()
            return
        }
        var removed = 0
        if (selectedFrames.isNotEmpty()) {
            val count = selectedFrames.size
            deleteSelectedStoryboardFrames()
            removed += count
        }
        val idsToRemove = if (trackIds.isNotEmpty()) trackIds else setOfNotNull(selectedTrackClipId)
        if (idsToRemove.isNotEmpty()) {
            val before = trackClips.size
            trackClips.removeAll { it.id in idsToRemove }
            removed += before - trackClips.size
            selectedTrackClipId = null
            trackTimelineView.clearSelection()
            trackTimelineView.setClips(trackClips.toList(), videoDurationMs)
            syncLegacyTrackUris()
            findViewById<Button>(R.id.addAudioButton).text = if (trackClips.any { it.kind == TrackClipKind.AUDIO }) "صداها (${trackClips.count { it.kind == TrackClipKind.AUDIO }}) ✓" else "افزودن صدا"
            findViewById<Button>(R.id.addSubtitleButton).text = if (trackClips.any { it.kind == TrackClipKind.SUBTITLE }) "زیرنویس‌ها (${trackClips.count { it.kind == TrackClipKind.SUBTITLE }}) ✓" else "افزودن زیرنویس"
        }
        selectedTimelineRange?.let { range ->
            if (deletedTimelineRanges.none { it.first == range.first && it.second == range.second }) deletedTimelineRanges.add(range)
            selectedTimelineRange = null
            timelineView.setTimeline(videoDurationMs, timelineSplitPoints.toList(), deletedTimelineRanges)
            removed++
        }
        if (removed == 0) Toast.makeText(this, "مورد انتخاب‌شده‌ای برای حذف وجود ندارد", Toast.LENGTH_SHORT).show()
        else statusText.text = "$removed مورد انتخاب‌شده برای حذف/حذف از خروجی اعمال شد"
    }

    /** A central Delete menu for removable items across the main editor sections. */
    private fun showGlobalDeleteDialog() {
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (selectedFrames.isNotEmpty()) actions += "اسلایدهای انتخاب‌شده (${selectedFrames.size})" to { deleteSelectedStoryboardFrames() }
        if (selectedTrackClipId != null && trackClips.any { it.id == selectedTrackClipId }) actions += "قطعهٔ انتخاب‌شدهٔ صدا/زیرنویس" to { deleteSelectedTrackClip() }
        if (selectedTimelineRange != null) actions += "قطعهٔ انتخاب‌شده از نوار زمان" to {
            val range = selectedTimelineRange
            if (range != null) {
                if (deletedTimelineRanges.none { it.first == range.first && it.second == range.second }) deletedTimelineRanges.add(range)
                selectedTimelineRange = null
                timelineView.setTimeline(videoDurationMs, timelineSplitPoints.toList(), deletedTimelineRanges)
                trackTimelineView.setClips(trackClips.toList(), videoDurationMs)
                statusText.text = "قطعهٔ انتخاب‌شده برای حذف از خروجی علامت خورد"
            }
        }
        if (selectedFrames.any { frameColorProfiles.containsKey(it) }) actions += "تنظیم رنگ اسلایدهای انتخاب‌شده" to {
            selectedFrames.forEach { frameColorProfiles.remove(it) }
            refreshSelectionStyles()
            statusText.text = "تنظیم رنگ اسلایدهای انتخاب‌شده پاک شد"
        }
        if (overlayVideoUri != null) actions += "ویدیوی رویی" to {
            overlayVideoUri = null
            releaseOverlayPreviewPlayer()
            findViewById<Button>(R.id.pickOverlayVideoButton).text = "انتخاب ویدیوی رویی"
            placementMode = PlacementOverlayView.Mode.NONE
            placementOverlay.visibility = View.GONE
            statusText.text = "ویدیوی رویی حذف شد"
        }
        if (findViewById<EditText>(R.id.overlayTextInput).text.isNotBlank()) actions += "متن رویی" to {
            findViewById<EditText>(R.id.overlayTextInput).text.clear()
            statusText.text = "متن رویی پاک شد"
        }
        if (mergeUris.isNotEmpty()) actions += "فهرست ویدیوهای Merge/Join" to {
            mergeUris.clear()
            findViewById<Button>(R.id.mergeButton).text = "Merge • چند ویدیو"
            statusText.text = "فهرست ویدیوهای Merge/Join پاک شد"
        }
        if (referenceImageUri != null) actions += "عکس مرجع جست‌وجوی صحنه" to {
            referenceImageUri = null
            statusText.text = "عکس مرجع پاک شد"
        }
        if (vectorUris.isNotEmpty()) actions += "فهرست ویدیوهای Vector" to {
            vectorUris.clear()
            statusText.text = "فهرست ویدیوهای Vector پاک شد"
        }
        if (cropSettingsSaved || cropLeftPct != 0 || cropTopPct != 0 || cropRightPct != 0 || cropBottomPct != 0) actions += "تنظیمات Free Crop" to {
            cropLeftPct = 0; cropTopPct = 0; cropRightPct = 0; cropBottomPct = 0
            cropSettingsSaved = false; freeCropMode = false
            cropOverlay.visibility = View.GONE; freeCropControls.visibility = View.GONE
            statusText.text = "تنظیمات Free Crop پاک شد"
        }
        if (timelineSplitPoints.isNotEmpty() || deletedTimelineRanges.isNotEmpty()) actions += "همهٔ برش‌ها و حذف‌های نوار زمان" to {
            timelineSplitPoints.clear(); deletedTimelineRanges.clear(); selectedTimelineRange = null
            timelineView.setTimeline(videoDurationMs, emptyList(), emptyList())
            statusText.text = "برش‌ها و حذف‌های نوار زمان پاک شدند"
        }
        if (actions.isEmpty()) {
            Toast.makeText(this, "مورد قابل‌حذفی انتخاب نشده است؛ ابتدا یک اسلاید، قطعه یا مورد را انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        val labels = actions.map { it.first }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Delete • حذف عمومی")
            .setItems(labels) { dialog, which ->
                actions[which].second.invoke()
                dialog.dismiss()
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun deleteSelectedTrackClip() {
        val id = selectedTrackClipId ?: run { Toast.makeText(this, "ابتدا یک قطعهٔ ترک را انتخاب کنید", Toast.LENGTH_SHORT).show(); return }
        recordUndoState()
        trackClips.removeAll { it.id == id }
        selectedTrackClipId = null
        trackTimelineView.setClips(trackClips.toList(), videoDurationMs)
        syncLegacyTrackUris()
        findViewById<Button>(R.id.addAudioButton).text = if (trackClips.any { it.kind == TrackClipKind.AUDIO }) "صداها (${trackClips.count { it.kind == TrackClipKind.AUDIO }}) ✓" else "افزودن صدا"
        findViewById<Button>(R.id.addSubtitleButton).text = if (trackClips.any { it.kind == TrackClipKind.SUBTITLE }) "زیرنویس‌ها (${trackClips.count { it.kind == TrackClipKind.SUBTITLE }}) ✓" else "افزودن زیرنویس"
        statusText.text = "قطعهٔ انتخاب‌شده حذف شد"
    }

    private fun chooseExternalAudio() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, PICK_EXTERNAL_AUDIO) }
        catch (_: Exception) { Toast.makeText(this, "انتخاب‌گر فایل صوتی در دسترس نیست", Toast.LENGTH_LONG).show() }
    }

    private fun chooseExternalSubtitle() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-subrip", "text/plain", "text/vtt", "application/x-ass", "application/x-ssa", "application/octet-stream"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, PICK_EXTERNAL_SUBTITLE) }
        catch (_: Exception) { Toast.makeText(this, "انتخاب‌گر زیرنویس در دسترس نیست", Toast.LENGTH_LONG).show() }
    }

    private fun displayNameForUri(uri: Uri): String {
        var name: String? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)
        }
        return name ?: (uri.lastPathSegment ?: "track")
    }

    private fun copyUriToCache(uri: Uri, prefix: String): File {
        val displayName = displayNameForUri(uri)
        val extension = displayName.substringAfterLast('.', "bin").lowercase(java.util.Locale.ROOT)
            .filter { it.isLetterOrDigit() }.take(8).ifBlank { "bin" }
        val destination = File(cacheDir, "${prefix}_${System.currentTimeMillis()}.$extension")
        contentResolver.openInputStream(uri)?.use { input -> destination.outputStream().use { output -> input.copyTo(output) } }
            ?: throw IllegalStateException("خواندن فایل انتخاب‌شده ممکن نشد")
        if (destination.length() == 0L) {
            destination.delete()
            throw IllegalStateException("فایل انتخاب‌شده خالی است")
        }
        return destination
    }

    /** Export timeline audio/subtitle clips at their visible positions while stream-copying the source video. */
    private fun exportTrackTimeline() {
        val sourceUri = selectedUri ?: run { Toast.makeText(this, "ابتدا ویدیوی اصلی را انتخاب کنید", Toast.LENGTH_LONG).show(); return }
        val clips = trackClips.toList().filter { it.durationMs > 0L }
        if (clips.isEmpty()) return
        val button = findViewById<Button>(R.id.outputChangesButton)
        button.isEnabled = false
        statusText.text = "در حال ساخت خروجی از نوار ترک‌ها؛ کدک تصویر اصلی حفظ می‌شود…"
        worker.execute {
            val tempFiles = mutableListOf<File>()
            var output: File? = null
            var error: String? = null
            try {
                val sourceFile = copyUriToCache(sourceUri, "evc_tracks_source")
                tempFiles.add(sourceFile)
                val audioClips = clips.filter { it.kind == TrackClipKind.AUDIO }
                val subtitleClips = clips.filter { it.kind == TrackClipKind.SUBTITLE }
                val audioFiles = audioClips.map { clip -> copyUriToCache(clip.uri, "evc_timeline_audio").also { tempFiles.add(it) } }
                val subtitleFiles = subtitleClips.map { clip -> copyUriToCache(clip.uri, "evc_timeline_subtitle").also { tempFiles.add(it) } }
                output = File(cacheDir, "evc_timeline_tracks_${System.currentTimeMillis()}.${outputExtension()}")
                val args = mutableListOf("-y", "-i", sourceFile.absolutePath)
                // One independent input per clip lets split pieces be moved separately.
                audioFiles.forEach { args.addAll(listOf("-i", it.absolutePath)) }
                val subtitleInputIndexes = mutableListOf<Int>()
                subtitleFiles.forEachIndexed { index, file ->
                    val clip = subtitleClips[index]
                    args.addAll(listOf("-itsoffset", String.format(java.util.Locale.US, "%.3f", clip.startMs / 1000.0),
                        "-ss", String.format(java.util.Locale.US, "%.3f", clip.sourceStartMs / 1000.0),
                        "-t", String.format(java.util.Locale.US, "%.3f", clip.durationMs / 1000.0), "-i", file.absolutePath))
                    subtitleInputIndexes.add(1 + audioFiles.size + index)
                }
                val sourceHasAudio = try {
                    val extractor = MediaExtractor(); extractor.setDataSource(sourceFile.absolutePath)
                    val found = (0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    extractor.release(); found
                } catch (_: Exception) { false }
                if (audioClips.isNotEmpty()) {
                    val filters = mutableListOf<String>()
                    var mixCount = 0
                    if (sourceHasAudio) { filters.add("[0:a:0]asetpts=PTS-STARTPTS,volume=1[evc_orig]"); mixCount++ }
                    audioClips.forEachIndexed { index, clip ->
                        val inputIndex = index + 1
                        val start = String.format(java.util.Locale.US, "%.3f", clip.sourceStartMs / 1000.0)
                        val duration = String.format(java.util.Locale.US, "%.3f", clip.durationMs / 1000.0)
                        val delay = clip.startMs.coerceAtLeast(0L)
                        filters.add("[$inputIndex:a:0]atrim=start=$start:duration=$duration,asetpts=PTS-STARTPTS,adelay=${delay}|${delay}[evc_a$index]")
                        mixCount++
                    }
                    val mixInputs = mutableListOf<String>()
                    if (sourceHasAudio) mixInputs.add("[evc_orig]")
                    audioClips.indices.forEach { mixInputs.add("[evc_a$it]") }
                    filters.add(mixInputs.joinToString("") + "amix=inputs=$mixCount:duration=longest:dropout_transition=0[evc_mix]")
                    args.addAll(listOf("-filter_complex", filters.joinToString(";"), "-map", "0:v:0", "-map", "[evc_mix]", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k"))
                } else {
                    args.addAll(listOf("-map", "0:v:0", "-map", "0:a?", "-c:v", "copy", "-c:a", "copy"))
                }
                subtitleInputIndexes.forEach { index -> args.addAll(listOf("-map", "$index:s:0")) }
                if (subtitleInputIndexes.isNotEmpty()) args.addAll(listOf("-c:s", if (outputContainerMatroska) "copy" else "mov_text"))
                if (!outputContainerMatroska) args.addAll(listOf("-movflags", "+faststart"))
                args.addAll(listOf("-max_muxing_queue_size", "2048", "-f", if (outputContainerMatroska) "matroska" else "mp4"))
                if (videoDurationMs > 0L) args.addAll(listOf("-t", String.format(java.util.Locale.US, "%.3f", videoDurationMs / 1000.0)))
                args.add(output.absolutePath)
                val session = FFmpegKit.executeWithArguments(args.toTypedArray())
                if (!ReturnCode.isSuccess(session.returnCode) || !output.exists() || output.length() == 0L) {
                    throw IllegalStateException("ساخت خروجی ترک‌ها ناموفق بود: ${session.allLogsAsString.takeLast(1400)}")
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                output?.delete(); output = null
            } finally { tempFiles.forEach { it.delete() } }
            mainHandler.post {
                button.isEnabled = true
                val result = output
                if (result == null || !result.exists() || result.length() == 0L) {
                    statusText.text = "خروجی ترک‌ها ناموفق بود: ${error ?: "خطای ناشناخته"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show(); result?.delete(); return@post
                }
                try {
                    val ext = outputExtension()
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_Timeline_${System.currentTimeMillis()}.$ext")
                        put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
                        if (android.os.Build.VERSION.SDK_INT >= 29) { put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC"); put(MediaStore.Video.Media.IS_PENDING, 1) }
                    }
                    val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("ساخت فایل در Movies/EVC ناموفق بود")
                    contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(result).use { it.copyTo(out) } } ?: throw IllegalStateException("نوشتن خروجی ناموفق بود")
                    if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    result.delete()
                    statusText.text = "خروجی نوار ترک‌ها در Movies/EVC ذخیره شد؛ کدک ویدیوی اصلی بدون رمزگذاری مجدد حفظ شد. صداهای افزوده‌شده با صدای اصلی ترکیب شدند."
                    Toast.makeText(this, "خروجی نوار ترک‌ها ذخیره شد", Toast.LENGTH_LONG).show()
                } catch (e: Exception) { result.delete(); statusText.text = "ذخیره خروجی ناموفق بود: ${e.message}"; Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun exportWithExternalTracks() {
        val sourceUri = selectedUri
        val audioUri = externalAudioUri
        val subtitleUri = externalSubtitleUri
        if (sourceUri == null) {
            Toast.makeText(this, "ابتدا ویدیوی اصلی را انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        if (audioUri == null && subtitleUri == null) {
            Toast.makeText(this, "ابتدا با دکمه‌های «افزودن صدا» یا «افزودن زیرنویس» یک فایل انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        // Android supports reliable multi-track MP4 muxing through MediaMuxer starting
        // with API 26. On API 24-25, avoid invoking the native multi-track muxer because
        // adding a second audio track is not consistently supported; keep the established
        // FFmpeg path for those devices instead.
        if (audioUri != null && subtitleUri == null && !outputContainerMatroska &&
            android.os.Build.VERSION.SDK_INT >= 26) {
            exportAdditionalAudioTrackWithMediaMuxer(sourceUri, audioUri)
            return
        }
        val button = findViewById<Button>(R.id.outputChangesButton)
        button.isEnabled = false
        findViewById<Button>(R.id.addAudioButton).isEnabled = false
        findViewById<Button>(R.id.addSubtitleButton).isEnabled = false
        statusText.text = "در حال افزودن ترک‌های صوتی/زیرنویس بدون رمزگذاری مجدد ویدیو…"
        worker.execute {
            var sourceFile: File? = null
            var audioFile: File? = null
            var subtitleFile: File? = null
            var output: File? = null
            var error: String? = null
            try {
                val videoInputFile = copyUriToCache(sourceUri, "evc_track_video")
                sourceFile = videoInputFile
                val selectedAudioFile = if (audioUri != null) copyUriToCache(audioUri, "evc_track_audio") else null
                audioFile = selectedAudioFile
                val selectedSubtitleFile = if (subtitleUri != null) copyUriToCache(subtitleUri, "evc_track_subtitle") else null
                subtitleFile = selectedSubtitleFile
                val outputFile = File(cacheDir, "evc_tracks_${System.currentTimeMillis()}.${outputExtension()}")
                output = outputFile
                val args = mutableListOf("-y", "-i", videoInputFile.absolutePath)
                var audioInputIndex: Int? = null
                var subtitleInputIndex: Int? = null
                if (selectedAudioFile != null) {
                    audioInputIndex = 1
                    args.addAll(listOf("-i", selectedAudioFile.absolutePath))
                }
                if (selectedSubtitleFile != null) {
                    subtitleInputIndex = if (selectedAudioFile != null) 2 else 1
                    args.addAll(listOf("-i", selectedSubtitleFile.absolutePath))
                }
                args.addAll(listOf("-map", "0:v:0", "-map", "0:a?"))
                if (audioInputIndex != null) args.addAll(listOf("-map", "$audioInputIndex:a:0"))
                if (subtitleInputIndex != null) args.addAll(listOf("-map", "$subtitleInputIndex:s:0"))
                args.addAll(listOf("-c:v", "copy", "-c:a", "copy"))
                if (subtitleInputIndex != null) {
                    if (outputContainerMatroska) args.addAll(listOf("-c:s", "copy"))
                    else args.addAll(listOf("-c:s", "mov_text"))
                }
                args.addAll(listOf("-max_muxing_queue_size", "2048", "-f", if (outputContainerMatroska) "matroska" else "mp4", outputFile.absolutePath))
                val session = FFmpegKit.executeWithArguments(args.toTypedArray())
                if (!ReturnCode.isSuccess(session.returnCode) || !outputFile.exists() || outputFile.length() == 0L) {
                    throw IllegalStateException("ساخت خروجی ناموفق بود: ${session.allLogsAsString.takeLast(1200)}")
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                output?.delete()
                output = null
            } finally {
                sourceFile?.delete()
                audioFile?.delete()
                subtitleFile?.delete()
            }
            mainHandler.post {
                button.isEnabled = true
                findViewById<Button>(R.id.addAudioButton).isEnabled = true
                findViewById<Button>(R.id.addSubtitleButton).isEnabled = true
                val result = output
                if (result == null || !result.exists() || result.length() == 0L) {
                    statusText.text = "افزودن صدا/زیرنویس ناموفق بود: ${error ?: "خطای ناشناخته"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                    result?.delete()
                    return@post
                }
                try {
                    val ext = outputExtension()
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_tracks_${System.currentTimeMillis()}.$ext")
                        put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("ساخت فایل در Movies/EVC ممکن نشد")
                    contentResolver.openOutputStream(outUri)?.use { stream -> FileInputStream(result).use { it.copyTo(stream) } }
                        ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    }
                    result.delete()
                    statusText.text = "خروجی همراه ترک‌های انتخابی در Movies/EVC ذخیره شد؛ ویدیو بدون رمزگذاری مجدد کپی شد"
                    Toast.makeText(this, "خروجی در پوشهٔ Movies/EVC ذخیره شد", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    result.delete()
                    statusText.text = "ذخیرهٔ خروجی ناموفق بود: ${e.message ?: "خطا"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Adds a selected audio file as an additional MP4 audio track using Android's native muxer.
     * The source video/audio samples are copied unchanged. This route is used only when no
     * subtitle track is requested and the selected output container is MP4.
     */
    private fun exportAdditionalAudioTrackWithMediaMuxer(sourceUri: Uri, audioUri: Uri) {
        val button = findViewById<Button>(R.id.outputChangesButton)
        button.isEnabled = false
        findViewById<Button>(R.id.addAudioButton).isEnabled = false
        findViewById<Button>(R.id.addSubtitleButton).isEnabled = false
        statusText.text = "در حال افزودن ترک صوتی با موتور داخلی اندروید؛ ویدیو دوباره رمزگذاری نمی‌شود…"
        worker.execute {
            var sourceFile: File? = null
            var audioFile: File? = null
            var output: File? = null
            var error: String? = null
            try {
                sourceFile = copyUriToCache(sourceUri, "evc_mux_source")
                audioFile = copyUriToCache(audioUri, "evc_mux_audio")
                output = File(cacheDir, "evc_tracks_${System.currentTimeMillis()}.mp4")
                val sourceExtractor = MediaExtractor()
                val audioExtractor = MediaExtractor()
                var muxer: MediaMuxer? = null
                var muxerStarted = false
                try {
                    sourceExtractor.setDataSource(sourceFile.absolutePath)
                    audioExtractor.setDataSource(audioFile.absolutePath)
                    // Reject unsupported container codecs before passing their formats to
                    // MediaMuxer. Some Android/vendor muxer implementations terminate the
                    // process in native code for unsupported MP4 track formats.
                    val sourceFormats = (0 until sourceExtractor.trackCount).map { sourceExtractor.getTrackFormat(it) }
                    val allowedVideoMimes = setOf("video/avc", "video/hevc", "video/mp4v-es")
                    val allowedAudioMimes = setOf("audio/mp4a-latm")
                    val unsupportedMp4Formats = sourceFormats.mapNotNull { format ->
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        when {
                            mime.startsWith("video/") && mime !in allowedVideoMimes -> mime
                            mime.startsWith("audio/") && mime !in allowedAudioMimes -> mime
                            !mime.startsWith("video/") && !mime.startsWith("audio/") -> mime
                            else -> null
                        }
                    }.distinct()
                    if (unsupportedMp4Formats.isNotEmpty()) throw IllegalStateException(
                        "برای جلوگیری از بسته‌شدن برنامه، این ترک‌های ناسازگار با مسیر امن MP4 وارد خروجی نشدند: ${unsupportedMp4Formats.joinToString()}. از ویدیوی H.264/H.265 با صدای AAC استفاده کنید."
                    )
                    val selectedAudioMimes = (0 until audioExtractor.trackCount).mapNotNull { i ->
                        audioExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                            ?.takeIf { it.startsWith("audio/") }
                    }
                    if (selectedAudioMimes.none { it == "audio/mp4a-latm" }) throw IllegalStateException(
                        "فایل صدای انتخابی برای افزودن مستقیم به MP4 باید AAC باشد. ابتدا آن را به AAC تبدیل کنید؛ برنامه برای جلوگیری از قطع‌شدن، این مسیر ناسازگار را اجرا نکرد."
                    )
                    val sourceVideoTracks = (0 until sourceExtractor.trackCount).filter {
                        sourceExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                    }
                    if (sourceVideoTracks.isEmpty()) throw IllegalStateException("در فایل اصلی ترک ویدیویی پیدا نشد")
                    val unsupported = (0 until sourceExtractor.trackCount).mapNotNull { i ->
                        val mime = sourceExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/") || mime.startsWith("audio/")) null else "$i:$mime"
                    }
                    if (unsupported.isNotEmpty()) throw IllegalStateException(
                        "فایل اصلی ترک‌های غیرصوتی/تصویری دارد (${unsupported.joinToString()})؛ برای جلوگیری از حذف آن‌ها، خروجی لغو شد."
                    )
                    val selectedAudioTrack = (0 until audioExtractor.trackCount).firstOrNull {
                        audioExtractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                    } ?: throw IllegalStateException("در فایل انتخابی ترک صوتی پیدا نشد")

                    muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    val trackMap = mutableMapOf<Pair<Int, Int>, Int>()
                    for (i in 0 until sourceExtractor.trackCount) {
                        val mime = sourceExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                        if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                            try {
                                trackMap[0 to i] = muxer.addTrack(sourceExtractor.getTrackFormat(i))
                                sourceExtractor.selectTrack(i)
                            } catch (e: Exception) {
                                throw IllegalStateException("حفظ ترک اصلی $i با کدک $mime توسط MP4 اندروید پشتیبانی نشد؛ خروجی لغو شد.", e)
                            }
                        }
                    }
                    try {
                        trackMap[1 to selectedAudioTrack] = muxer.addTrack(audioExtractor.getTrackFormat(selectedAudioTrack))
                    } catch (e: Exception) {
                        throw IllegalStateException("فرمت صدای انتخابی با ظرف MP4 سازگار نیست؛ یک فایل صوتی AAC را امتحان کنید.", e)
                    }
                    audioExtractor.selectTrack(selectedAudioTrack)
                    muxer.start()
                    muxerStarted = true

                    val durationUs = (videoDurationMs.takeIf { it > 0L } ?: run {
                        val retriever = MediaMetadataRetriever()
                        try {
                            retriever.setDataSource(sourceFile.absolutePath)
                            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: Long.MAX_VALUE / 1000L
                        } finally { retriever.release() }
                    }) * 1000L
                    val extractors = listOf(sourceExtractor, audioExtractor)
                    val exhausted = BooleanArray(extractors.size)
                    val buffer = java.nio.ByteBuffer.allocate(8 * 1024 * 1024)
                    val info = MediaCodec.BufferInfo()
                    while (true) {
                        var chosenExtractor = -1
                        var chosenTrack = -1
                        var chosenTime = Long.MAX_VALUE
                        for (ei in extractors.indices) {
                            if (exhausted[ei]) continue
                            val ex = extractors[ei]
                            val ti = ex.sampleTrackIndex
                            val time = ex.sampleTime
                            if (ti >= 0 && time >= 0L && time < chosenTime) {
                                chosenExtractor = ei
                                chosenTrack = ti
                                chosenTime = time
                            }
                        }
                        if (chosenExtractor < 0) break
                        val ex = extractors[chosenExtractor]
                        if (chosenTime <= durationUs) {
                            buffer.clear()
                            val size = ex.readSampleData(buffer, 0)
                            if (size > buffer.capacity()) throw IllegalStateException("نمونهٔ صوتی/تصویری از ظرفیت بافر بیشتر است")
                            if (size > 0) {
                                val outputTrack = trackMap[chosenExtractor to chosenTrack]
                                    ?: throw IllegalStateException("نگاشت ترک خروجی پیدا نشد")
                                info.set(0, size, chosenTime, ex.sampleFlags)
                                muxer.writeSampleData(outputTrack, buffer, info)
                            }
                        }
                        if (!ex.advance()) exhausted[chosenExtractor] = true
                    }
                } finally {
                    if (muxerStarted) try { muxer?.stop() } catch (_: Exception) { }
                    try { muxer?.release() } catch (_: Exception) { }
                    sourceExtractor.release()
                    audioExtractor.release()
                }
                if (!output.exists() || output.length() == 0L) throw IllegalStateException("فایل خروجی ساخته نشد")
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                output?.delete()
                output = null
            } finally {
                sourceFile?.delete()
                audioFile?.delete()
            }
            mainHandler.post {
                button.isEnabled = true
                findViewById<Button>(R.id.addAudioButton).isEnabled = true
                findViewById<Button>(R.id.addSubtitleButton).isEnabled = true
                val result = output
                if (result == null || !result.exists() || result.length() == 0L) {
                    statusText.text = "افزودن ترک صوتی ناموفق بود: ${error ?: "خطای ناشناخته"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                    result?.delete()
                    return@post
                }
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_tracks_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("ساخت فایل در Movies/EVC ممکن نشد")
                    contentResolver.openOutputStream(outUri)?.use { stream -> FileInputStream(result).use { it.copyTo(stream) } }
                        ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    }
                    result.delete()
                    statusText.text = "ترک صوتی جدید به‌صورت جداگانه افزوده شد؛ صدای اصلی و ویدیو حفظ شدند و ویدیو دوباره رمزگذاری نشد."
                    Toast.makeText(this, "خروجی در Movies/EVC ذخیره شد", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    result.delete()
                    statusText.text = "ذخیرهٔ خروجی ناموفق بود: ${e.message ?: "خطا"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Replaces the source soundtrack with the selected audio through Media3 Transformer. */
    @androidx.media3.common.util.UnstableApi
    private fun exportAudioReplacementWithTransformer(
        sourceUri: Uri, audioUri: Uri, text: String, textSize: Int, textX: Int, textY: Int
    ) {
        val button = findViewById<Button>(R.id.outputChangesButton)
        button.isEnabled = false
        statusText.text = "در حال ساخت خروجی صدا با موتور داخلی اندروید…"
        val output = File(cacheDir, "evc_audio_${System.currentTimeMillis()}.mp4")
        try {
            val videoEffects = mutableListOf<androidx.media3.common.Effect>()
            if (text.isNotEmpty()) {
                val styledText = SpannableString(text).apply {
                    setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.BOLD), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                val anchorX = (-1f + 2f * textX.coerceIn(0, 100) / 100f).coerceIn(-1f, 1f)
                val anchorY = (1f - 2f * textY.coerceIn(0, 100) / 100f).coerceIn(-1f, 1f)
                val scale = (textSize / 100f).coerceIn(0.08f, 1.6f)
                val settings = StaticOverlaySettings.Builder()
                    .setOverlayFrameAnchor(-1f, 1f)
                    .setBackgroundFrameAnchor(anchorX, anchorY)
                    .setScale(scale, scale)
                    .build()
                videoEffects.add(OverlayEffect(listOf(TextOverlay.createStaticTextOverlay(styledText, settings))))
            }
            val videoItemBuilder = EditedMediaItem.Builder(MediaItem.fromUri(sourceUri)).setRemoveAudio(true)
            if (videoEffects.isNotEmpty()) videoItemBuilder.setEffects(Effects(emptyList(), videoEffects))
            val videoSequence = EditedMediaItemSequence.Builder(videoItemBuilder.build()).build()
            val targetDurationMs = videoDurationMs.takeIf { it > 0L } ?: run {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, sourceUri)
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                } finally { retriever.release() }
            }
            val audioMediaItem = if (targetDurationMs > 0L) {
                MediaItem.Builder().setUri(audioUri).setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder().setEndPositionMs(targetDurationMs).build()
                ).build()
            } else MediaItem.fromUri(audioUri)
            val audioItem = EditedMediaItem.Builder(audioMediaItem).setRemoveVideo(true).build()
            val audioSequence = EditedMediaItemSequence.Builder(audioItem).build()
            val composition = Composition.Builder(listOf(videoSequence, audioSequence)).build()
            val transformer = Transformer.Builder(this).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    activeTransformer = null
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_audio_${System.currentTimeMillis()}.mp4")
                            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                                put(MediaStore.Video.Media.IS_PENDING, 1)
                            }
                        }
                        val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("ساخت فایل در Movies/EVC ممکن نشد")
                        contentResolver.openOutputStream(outUri)?.use { stream -> FileInputStream(output).use { it.copyTo(stream) } }
                            ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                        }
                        output.delete()
                        button.isEnabled = true
                        statusText.text = if (text.isNotEmpty()) "متن و صدای انتخابی در Movies/EVC ذخیره شدند؛ صدای اصلی جایگزین شد." else "صدای انتخابی در Movies/EVC جایگزین صدای اصلی شد."
                        Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        output.delete(); button.isEnabled = true
                        statusText.text = "ذخیرهٔ خروجی صدا ناموفق بود: ${e.message ?: "خطا"}"
                        Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                    }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    activeTransformer = null
                    output.delete(); button.isEnabled = true
                    statusText.text = "ساخت خروجی صدا ناموفق بود: ${exportException.message ?: "خطای رمزگذاری"}"
                    Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                }
            }).build()
            activeTransformer = transformer
            transformer.start(composition, output.absolutePath)
        } catch (e: Exception) {
            activeTransformer = null
            output.delete(); button.isEnabled = true
            statusText.text = "شروع خروجی صدا ناموفق بود: ${e.message ?: e.javaClass.simpleName}"
            Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
        }
    }

    /** Text-only export using a transparent Android-rendered PNG and FFmpeg overlay. */
    private fun exportTextOnlyWithTransformer(sourceUri: Uri, text: String, textSize: Int, textX: Int, textY: Int, textStartMs: Long, textEndMs: Long, durationMs: Long) {
        val button = findViewById<Button>(R.id.outputChangesButton)
        button.isEnabled = false
        statusText.text = "در حال ساخت خروجی متن در بازهٔ زمانی انتخاب‌شده…"
        worker.execute {
            var output: File? = null
            var overlayPng: File? = null
            var sourceFile: File? = null
            var error: String? = null
            try {
                val retriever = MediaMetadataRetriever()
                val width: Int
                val height: Int
                try {
                    retriever.setDataSource(this, sourceUri)
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                        ?: throw IllegalStateException("عرض ویدیو خوانده نشد")
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                        ?: throw IllegalStateException("ارتفاع ویدیو خوانده نشد")
                } finally { retriever.release() }
                require(width > 0 && height > 0) { "ابعاد ویدیو نامعتبر است" }
                val fontPercent = textSize.coerceIn(8, 160)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.WHITE
                        style = Paint.Style.FILL
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        setTextSize((height * fontPercent / 100f).coerceIn(16f, height * 0.25f))
                        textAlign = Paint.Align.CENTER
                        setShadowLayer(max(2f, height * 0.004f), 1f, 1f, Color.BLACK)
                    }
                    val lines = text.split('\n')
                    val lineHeight = paint.fontSpacing
                    val totalHeight = lineHeight * lines.size
                    val centerX = width * textX.coerceIn(0, 100) / 100f
                    val centerY = height * textY.coerceIn(0, 100) / 100f
                    var baseline = centerY - totalHeight / 2f - paint.ascent()
                    for (line in lines) {
                        var shown = line
                        while (shown.isNotEmpty() && paint.measureText(shown) > width * 0.94f) {
                            shown = shown.dropLast(1)
                        }
                        canvas.drawText(shown, centerX.coerceIn(0f, width.toFloat()), baseline, paint)
                        baseline += lineHeight
                    }
                    overlayPng = File(cacheDir, "evc_text_overlay_${System.currentTimeMillis()}.png")
                    overlayPng!!.outputStream().use { stream -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream) }
                } finally { bitmap.recycle() }
                output = File(cacheDir, "evc_text_${System.currentTimeMillis()}.mp4")
                val startSec = "%.3f".format(java.util.Locale.US, textStartMs.coerceAtLeast(0L) / 1000.0)
                val endSec = "%.3f".format(java.util.Locale.US, textEndMs.coerceAtLeast(textStartMs) / 1000.0)
                val filter = "[0:v][1:v]overlay=0:0:enable='between(t\\,$startSec\\,$endSec)'[v]"
                val args = arrayListOf(
                    "-y", "-threads", "1", "-i", (copyUriToCache(sourceUri, "evc_text_source").also { sourceFile = it }).absolutePath,
                    "-loop", "1", "-i", overlayPng!!.absolutePath,
                    "-filter_complex", filter, "-map", "[v]", "-map", "0:a?",
                    "-c:v", "libx264", "-preset", "ultrafast", "-threads", "1", "-crf", "20",
                    "-pix_fmt", "yuv420p", "-c:a", "copy", "-t", "%.3f".format(java.util.Locale.US, durationMs / 1000.0),
                    "-movflags", "+faststart", output!!.absolutePath
                )
                val session = FFmpegKit.executeWithArguments(args.toTypedArray())
                if (!ReturnCode.isSuccess(session.returnCode) || !output!!.exists() || output!!.length() == 0L) {
                    throw IllegalStateException("خروجی متن ساخته نشد: ${session.allLogsAsString.takeLast(1000)}")
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                output?.delete(); output = null
            } finally {
                overlayPng?.delete()
                sourceFile?.delete()
            }
            mainHandler.post {
                button.isEnabled = true
                val result = output
                if (result == null || !result.exists() || result.length() == 0L) {
                    statusText.text = "ساخت خروجی متن ناموفق بود: ${error ?: "خطای ناشناخته"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                    result?.delete()
                    return@post
                }
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_Text_${System.currentTimeMillis()}.mp4")
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("ساخت فایل در Movies/EVC ممکن نشد")
                    contentResolver.openOutputStream(outUri)?.use { stream -> FileInputStream(result).use { it.copyTo(stream) } }
                        ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    }
                    result.delete()
                    statusText.text = "ویدیوی متن‌گذاری‌شده در Movies/EVC ذخیره شد؛ متن فقط در بازهٔ زمانی تعیین‌شده نمایش داده می‌شود."
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    result.delete()
                    statusText.text = "ذخیرهٔ خروجی متن ناموفق بود: ${e.message ?: "خطا"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showOverlayVideoPreview(uri: Uri) {
        releaseOverlayPreviewPlayer()
        overlayVideoLayer.visibility = View.VISIBLE
        overlayPreviewTexture.visibility = View.VISIBLE
        try {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, uri)
                    overlayPreviewVideoWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.coerceAtLeast(1) ?: 16
                    overlayPreviewVideoHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.coerceAtLeast(1) ?: 9
                } finally { retriever.release() }
            }
            updateOverlayPreviewLayout()
            if (overlayPreviewTexture.isAvailable) overlayPreviewTexture.surfaceTexture?.let { startOverlayPreviewOnSurface(it) }
        } catch (e: Exception) {
            overlayVideoLayer.visibility = View.GONE
            statusText.text = "پیش‌نمایش ویدیوی رویی باز نشد: ${e.message ?: "قالب یا دسترسی فایل پشتیبانی نمی‌شود"}"
            Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun startOverlayPreviewOnSurface(surfaceTexture: SurfaceTexture) {
        val uri = overlayVideoUri ?: return
        releaseOverlayPreviewPlayer()
        try {
            val player = MediaPlayer()
            overlayPreviewSurface = Surface(surfaceTexture)
            player.setSurface(overlayPreviewSurface)
            player.setDataSource(this, uri)
            player.isLooping = true
            player.setVolume(0f, 0f) // Preview is muted; the final output controls the actual soundtrack.
            player.setOnPreparedListener { mp ->
                overlayPreviewVideoWidth = mp.videoWidth.coerceAtLeast(1)
                overlayPreviewVideoHeight = mp.videoHeight.coerceAtLeast(1)
                updateOverlayPreviewLayout()
                try { mp.start() } catch (_: Exception) { }
            }
            player.setOnErrorListener { _, what, extra ->
                runOnUiThread {
                    statusText.text = "انتخاب شد، اما این ویدیو در پیش‌نمایش پخش نشد ($what/$extra)؛ خروجی را می‌توان جداگانه امتحان کرد"
                }
                true
            }
            overlayPreviewPlayer = player
            player.prepareAsync()
        } catch (e: Exception) {
            releaseOverlayPreviewPlayer()
            statusText.text = "پخش پیش‌نمایش ویدیوی رویی ناموفق بود: ${e.message ?: "قالب ویدیو پشتیبانی نمی‌شود"}"
        }
    }

    private fun updateOverlayPreviewLayout() {
        if (!::overlayVideoLayer.isInitialized || !::overlayPreviewTexture.isInitialized) return
        val layerWidth = overlayVideoLayer.width
        val layerHeight = overlayVideoLayer.height
        if (layerWidth <= 0 || layerHeight <= 0) {
            overlayVideoLayer.post { updateOverlayPreviewLayout() }
            return
        }
        val widthPct = overlayVideoWidthPercent.coerceIn(5, 90)
        val x = overlayVideoXPercent.coerceIn(0, 100 - widthPct)
        val y = overlayVideoYPercent.coerceIn(0, 95)
        val w = (layerWidth * widthPct / 100f).toInt().coerceAtLeast(1)
        val aspect = overlayPreviewVideoWidth.toFloat() / overlayPreviewVideoHeight.coerceAtLeast(1)
        val h = (w / aspect).toInt().coerceIn(1, layerHeight)
        val left = (layerWidth * x / 100f).toInt().coerceAtMost((layerWidth - w).coerceAtLeast(0))
        val top = (layerHeight * y / 100f).toInt().coerceAtMost((layerHeight - h).coerceAtLeast(0))
        val lp = overlayPreviewTexture.layoutParams as FrameLayout.LayoutParams
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = left
        lp.topMargin = top
        overlayPreviewTexture.layoutParams = lp
        // Keep the draggable selection frame aligned with the actual preview rectangle.
        val aspectForFrame = overlayPreviewVideoWidth.toFloat() / overlayPreviewVideoHeight.coerceAtLeast(1)
        val frameHeightPct = (widthPct * layerWidth.toFloat() / layerHeight.toFloat() / aspectForFrame * 100f).toInt().coerceIn(5, 95)
        if (::placementOverlay.isInitialized && placementMode == PlacementOverlayView.Mode.VIDEO) {
            placementOverlay.setSelection(x, y, widthPct, frameHeightPct)
        }
    }

    private fun releaseOverlayPreviewPlayer() {
        try { overlayPreviewPlayer?.setOnPreparedListener(null); overlayPreviewPlayer?.setOnErrorListener(null); overlayPreviewPlayer?.stop() } catch (_: Exception) { }
        try { overlayPreviewPlayer?.release() } catch (_: Exception) { }
        overlayPreviewPlayer = null
        try { overlayPreviewSurface?.release() } catch (_: Exception) { }
        overlayPreviewSurface = null
    }

    private fun chooseOverlayVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, PICK_OVERLAY_VIDEO) }
        catch (_: Exception) { Toast.makeText(this, "انتخاب‌گر ویدیو در دسترس نیست", Toast.LENGTH_LONG).show() }
    }

    /** Burns text and/or composites a second video over the selected source. */
    private fun exportTextAndVideoOverlay() {
        val sourceUri = selectedUri
        if (sourceUri == null) {
            Toast.makeText(this, "ابتدا ویدیوی اصلی را انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        val text = findViewById<EditText>(R.id.overlayTextInput).text.toString().trim()
        val secondUri = overlayVideoUri
        val audioUri = externalAudioUri
        if (text.isEmpty() && secondUri == null && audioUri == null) {
            Toast.makeText(this, "متن وارد کنید، ویدیوی رویی یا فایل صوتی انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        fun number(id: Int, fallback: Int, min: Int = 0, max: Int = 100): Int =
            findViewById<EditText>(id).text.toString().toIntOrNull()?.coerceIn(min, max) ?: fallback
        val textSize = liveTextSize.coerceIn(8, 160)
        val textX = textXPercent.coerceIn(0, 100)
        val textY = textYPercent.coerceIn(0, 100)
        val textStartMs = parseTimeInput(findViewById<EditText>(R.id.overlayTextStartTime).text.toString())
        val textEndMs = parseTimeInput(findViewById<EditText>(R.id.overlayTextEndTime).text.toString())
        if (text.isNotEmpty()) {
            if (textStartMs == null || textEndMs == null || textEndMs <= textStartMs) {
                Toast.makeText(this, "زمان شروع و پایان متن را درست وارد کنید؛ پایان باید بعد از شروع باشد", Toast.LENGTH_LONG).show()
                return
            }
            if (videoDurationMs > 0L && textEndMs > videoDurationMs) {
                Toast.makeText(this, "زمان پایان متن از مدت ویدیو بیشتر است", Toast.LENGTH_LONG).show()
                return
            }
        }
        val fullDurationText = text.isNotEmpty() && textStartMs == 0L &&
            (videoDurationMs <= 0L || (textEndMs ?: 0L) >= videoDurationMs)
        val videoWidth = overlayVideoWidthPercent.coerceIn(5, 90)
        val videoX = overlayVideoXPercent.coerceIn(0, 100 - videoWidth)
        val videoY = overlayVideoYPercent.coerceIn(0, 95)
        if (videoX + videoWidth > 100 || videoY >= 100) {
            Toast.makeText(this, "جایگاه یا عرض ویدیوی رویی نامعتبر است", Toast.LENGTH_LONG).show()
            return
        }
        // Use Android Media3 Transformer for text-only MP4 output. Split the source
        // into adjacent clips so the Android text overlay appears only in its time range.
        if (secondUri == null && !outputContainerMatroska && text.isNotEmpty() &&
            audioUri == null && videoDurationMs > 0L) {
            exportTextOnlyWithTransformer(
                sourceUri, text, textSize, textX, textY,
                textStartMs ?: 0L, textEndMs ?: videoDurationMs, videoDurationMs
            )
            return
        }
        if (secondUri == null && !outputContainerMatroska && text.isEmpty() && audioUri != null) {
            exportAudioReplacementWithTransformer(sourceUri, audioUri, text, textSize, textX, textY)
            return
        }
        val button = findViewById<Button>(R.id.outputChangesButton)
        button.isEnabled = false
        statusText.text = "در حال ساخت خروجی متن/ویدیو/صدا…"
        worker.execute {
            var baseFile: File? = null
            var overlayFile: File? = null
            var audioFile: File? = null
            var textFile: File? = null
            var output: File? = null
            var error: String? = null
            try {
                baseFile = copyUriToCache(sourceUri, "evc_overlay_base")
                if (secondUri != null) overlayFile = copyUriToCache(secondUri, "evc_overlay_video")
                if (audioUri != null) audioFile = copyUriToCache(audioUri, "evc_overlay_audio")
                if (text.isNotEmpty()) {
                    textFile = File(cacheDir, "evc_overlay_text_${System.currentTimeMillis()}.txt")
                    textFile.writeText(text, Charsets.UTF_8)
                }
                val ext = outputExtension()
                output = File(cacheDir, "evc_overlay_${System.currentTimeMillis()}.$ext")
                val graph = mutableListOf<String>()
                var current = "0:v"
                if (overlayFile != null) {
                    graph.add("[1:v][0:v]scale2ref=w=main_w*$videoWidth/100:h=ow/mdar[evc_small][evc_base]")
                    graph.add("[evc_base][evc_small]overlay=x='main_w*$videoX/100':y='main_h*$videoY/100':eof_action=pass[evc_with_video]")
                    current = "evc_with_video"
                }
                if (textFile != null) {
                    val safePath = textFile.absolutePath.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")
                    val startSeconds = ((textStartMs ?: 0L).toDouble() / 1000.0).let { String.format(java.util.Locale.US, "%.3f", it) }
                    val endSeconds = ((textEndMs ?: videoDurationMs).toDouble() / 1000.0).let { String.format(java.util.Locale.US, "%.3f", it) }
                    val timingFilter = if (text.isNotEmpty()) ":enable='between(t\\,$startSeconds\\,$endSeconds)'" else ""
                    val draw = "drawtext=fontfile='/system/fonts/Roboto-Regular.ttf':textfile='$safePath':fontsize=$textSize:fontcolor=white:borderw=2:bordercolor=black:x='w*$textX/100':y='h*$textY/100'$timingFilter"
                    graph.add("[$current]$draw[evc_text_out]")
                    current = "evc_text_out"
                }
                // Keep text/overlay encoding conservative on 32-bit ARM devices: one filter thread,
                // one encoder thread, no look-ahead/MB-tree, and a single reference frame.
                val args = mutableListOf(
                    "-y",
                    "-filter_threads", "1",
                    "-filter_complex_threads", "1",
                    "-threads", "1",
                    "-i", baseFile.absolutePath
                )
                var nextInputIndex = 1
                if (overlayFile != null) {
                    args.addAll(listOf("-stream_loop", "-1", "-i", overlayFile.absolutePath))
                    nextInputIndex++
                }
                val audioInputIndex = if (audioFile != null) nextInputIndex else null
                if (audioFile != null) args.addAll(listOf("-i", audioFile.absolutePath))
                if (graph.isNotEmpty()) {
                    args.addAll(listOf("-filter_complex", graph.joinToString(";"), "-map", "[$current]"))
                } else {
                    args.addAll(listOf("-map", "0:v:0"))
                }
                if (audioInputIndex != null) {
                    // The selected external audio replaces the source soundtrack; pad it to video duration.
                    args.addAll(listOf("-map", "$audioInputIndex:a:0", "-af", "apad", "-c:a", "aac", "-b:a", "192k", "-shortest"))
                } else {
                    args.addAll(listOf("-map", "0:a?", "-c:a", "copy"))
                }
                args.addAll(listOf(
                    "-c:v", "libx264",
                    "-preset", "ultrafast",
                    "-tune", "fastdecode",
                    "-threads", "1",
                    "-x264-params", "threads=1:sliced-threads=1:lookahead_threads=1:sync-lookahead=0:rc-lookahead=0:mbtree=0:ref=1:bframes=0:me=dia:subme=0:partitions=none:8x8dct=0:weightp=0:aq-mode=0",
                    "-crf", "20",
                    "-pix_fmt", "yuv420p"
                ))
                if (!outputContainerMatroska) args.addAll(listOf("-movflags", "+faststart"))
                args.addAll(listOf("-max_muxing_queue_size", "512", "-f", if (outputContainerMatroska) "matroska" else "mp4", output.absolutePath))
                val session = FFmpegKit.executeWithArguments(args.toTypedArray())
                if (!ReturnCode.isSuccess(session.returnCode) || !output.exists() || output.length() == 0L) {
                    throw IllegalStateException("ساخت خروجی متن/ویدیو ناموفق بود: ${session.allLogsAsString.takeLast(1400)}")
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                output?.delete(); output = null
            } finally {
                baseFile?.delete(); overlayFile?.delete(); audioFile?.delete(); textFile?.delete()
            }
            mainHandler.post {
                button.isEnabled = true
                val result = output
                if (result == null || !result.exists() || result.length() == 0L) {
                    statusText.text = "ساخت خروجی متن/ویدیو ناموفق بود: ${error ?: "خطای ناشناخته"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                    result?.delete()
                    return@post
                }
                try {
                    val ext = outputExtension()
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_overlay_${System.currentTimeMillis()}.$ext")
                        put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("ساخت فایل در Movies/EVC ممکن نشد")
                    contentResolver.openOutputStream(outUri)?.use { stream -> FileInputStream(result).use { it.copyTo(stream) } }
                        ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    }
                    result.delete()
                    statusText.text = when {
                        audioUri != null && text.isNotEmpty() && secondUri != null -> "متن، ویدیوی رویی و صدای انتخابی در Movies/EVC ذخیره شدند؛ صدای انتخابی جای صدای اصلی را گرفت."
                        audioUri != null && text.isNotEmpty() -> "متن و صدای انتخابی در Movies/EVC ذخیره شدند؛ صدای انتخابی جای صدای اصلی را گرفت."
                        audioUri != null && secondUri != null -> "ویدیوی رویی و صدای انتخابی در Movies/EVC ذخیره شدند؛ صدای انتخابی جای صدای اصلی را گرفت."
                        audioUri != null -> "ویدیو با صدای انتخابی در Movies/EVC ذخیره شد؛ صدای انتخابی جای صدای اصلی را گرفت."
                        text.isNotEmpty() && secondUri != null -> "متن و ویدیوی رویی ترکیب و در Movies/EVC ذخیره شدند؛ ویدیو دوباره رمزگذاری شده است."
                        text.isNotEmpty() -> "متن روی ویدیو در Movies/EVC ذخیره شد."
                        else -> "ویدیوی رویی در Movies/EVC ذخیره شد."
                    }
                    Toast.makeText(this, "خروجی در Movies/EVC ذخیره شد", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    result.delete()
                    statusText.text = "ذخیرهٔ خروجی ناموفق بود: ${e.message ?: "خطا"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Keeps effect selection and the blur controls visibly synchronized. Effects apply only to exported output. */

    /** Applies selected FFmpeg video filters to the full video, with optional rectangular blur. */

    private fun updateLiveTextPreview() {
        val text = findViewById<EditText>(R.id.overlayTextInput).text.toString()
        val panelOpen = findViewById<View>(R.id.textOverlayPanel).visibility == View.VISIBLE
        if (text.isNotBlank() && selectedUri != null && panelOpen) {
            placementMode = PlacementOverlayView.Mode.TEXT
            placementOverlay.setMode(PlacementOverlayView.Mode.TEXT)
            placementOverlay.setSelection(textXPercent, textYPercent, 40, 20)
            placementOverlay.setTextPreview(text, liveTextSize)
            placementOverlay.visibility = View.VISIBLE
            placementOverlay.bringToFront()
        } else if (placementMode == PlacementOverlayView.Mode.TEXT) {
            placementOverlay.visibility = View.GONE
        }
    }

    private fun activatePlacementMode(mode: PlacementOverlayView.Mode) {
        placementMode = mode
        val readPercent: (Int, Int) -> Int = { id, fallback -> findViewById<EditText>(id).text.toString().toIntOrNull()?.coerceIn(0, 100) ?: fallback }
        val x: Int
        val y: Int
        val w: Int
        val h: Int
        when (mode) {
            PlacementOverlayView.Mode.TEXT -> { x = textXPercent; y = textYPercent; w = 40; h = 20 }
            PlacementOverlayView.Mode.VIDEO -> { x = overlayVideoXPercent; y = overlayVideoYPercent; w = overlayVideoWidthPercent.coerceIn(5, 90); h = (w * 9 / 16).coerceAtLeast(8) }
            else -> { placementOverlay.visibility = View.GONE; return }
        }
        placementOverlay.setMode(mode)
        placementOverlay.setSelection(x, y, w, h)
        if (mode == PlacementOverlayView.Mode.TEXT) {
            val enteredText = findViewById<EditText>(R.id.overlayTextInput).text.toString()
            placementOverlay.setTextPreview(enteredText, liveTextSize)
        }
        placementOverlay.visibility = View.VISIBLE
        placementOverlay.bringToFront()
        if (mode == PlacementOverlayView.Mode.VIDEO) {
            overlayVideoLayer.visibility = View.VISIBLE
            updateOverlayPreviewLayout()
        }
        statusText.text = when (mode) {
            PlacementOverlayView.Mode.TEXT -> "جای متن را روی پلیر لمس کن و بکش؛ محل متن خودکار ثبت می‌شود"
            PlacementOverlayView.Mode.VIDEO -> "پیش‌نمایش واقعی ویدیوی رویی روی پلیر نمایش داده می‌شود؛ برای جابه‌جایی کادر را بکش و برای تغییر اندازه گوشه‌ها را بکش"
            else -> ""
        }
    }

    private fun chooseVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        try { startActivityForResult(intent, REQUEST_VIDEO) }
        catch (_: Exception) { Toast.makeText(this, "انتخاب‌گر فایل در دسترس نیست", Toast.LENGTH_LONG).show() }
    }

    private fun buildEmptyStoryboard() {
        storyboardRows.removeAllViews()
        frameViews.clear()
        imageSearchFrameTimes.clear()

        // Use explicit horizontal rows. GridLayout weights previously allowed cells to
        // expand across the entire storyboard width, making only every Nth label visible.
        val availableWidthDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density - 86f).coerceAtLeast(180f)
        val columns = ((availableWidthDp - 8f) / 68f).toInt().coerceIn(2, 6)
        val visibleIndices = (0 until activeStoryboardFrameCount).filter { it !in deletedStoryboardFrames }
        visibleIndices.chunked(columns).forEach { rowIndices ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            rowIndices.forEach { index ->
                val cell = makeFrameCell(index, null, "${index + 1}")
                val params = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    setMargins(dp(1), dp(1), dp(1), dp(1))
                }
                row.addView(cell, params)
                frameViews[index] = cell
            }
            storyboardRows.addView(row)
        }
        updateSelectionText()
    }

    private fun makeFrameCell(index: Int, bitmap: Bitmap?, label: String): FrameLayout {
        val cell = FrameLayout(this)
        cell.background = GradientDrawable().apply {
            setColor(Color.rgb(18, 35, 65)); cornerRadius = dp(2).toFloat()
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (bitmap != null) setImageBitmap(bitmap) else setImageResource(android.R.drawable.ic_media_play)
            if (bitmap == null) setColorFilter(Color.rgb(116, 150, 198))
            contentDescription = "فریم ${index + 1}"
        }
        cell.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val labelHeight = dp(12)
        val timeLabel = TextView(this).apply {
            text = label; textSize = 8f; setTextColor(Color.WHITE)
            setPadding(dp(2), 0, dp(2), 0); gravity = Gravity.CENTER
            // Semi-transparent label: the thumbnail remains visible beneath it.
            setBackgroundColor(0x6607142F)
        }
        cell.addView(timeLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, labelHeight, Gravity.BOTTOM or Gravity.START))
        val badge = TextView(this).apply {
            text = "✓"; textSize = 15f; setTextColor(Color.BLACK); gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(255, 210, 31)); visibility = View.GONE
        }
        val badgeParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END)
        cell.addView(badge, badgeParams)
        cell.tag = badge
        cell.foreground = makeBorder(index in selectedFrames)
        cell.setOnClickListener {
            if (selectedUri == null || videoDurationMs <= 0L) {
                Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!selectedFrames.add(index)) selectedFrames.remove(index)
            storyboardRangeAnchorIndex = index
            refreshCellStyle(index)
            updateSelectionText()
            seekToStoryboardFrame(index)
        }
        cell.setOnLongClickListener {
            if (selectedUri == null || videoDurationMs <= 0L) {
                Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_SHORT).show()
                return@setOnLongClickListener true
            }
            val anchor = storyboardRangeAnchorIndex
            if (anchor == null) {
                storyboardRangeAnchorIndex = index
                Toast.makeText(this, "ابتدا یک اسلاید را لمس کنید، سپس اسلاید مقصد را نگه دارید", Toast.LENGTH_SHORT).show()
            } else {
                val first = minOf(anchor, index)
                val last = maxOf(anchor, index)
                for (frameIndex in first..last) {
                    if (frameIndex in deletedStoryboardFrames || frameIndex !in frameViews) continue
                    if (!selectedFrames.add(frameIndex)) selectedFrames.remove(frameIndex)
                }
                // The long-pressed cell becomes the next range anchor.
                storyboardRangeAnchorIndex = index
                updateSelectionText()
                seekToStoryboardFrame(index)
            }
            true
        }
        return cell
    }

    private fun seekToStoryboardFrame(index: Int) {
        val rangeStart = storyboardStartMs.coerceIn(0L, videoDurationMs)
        val rangeEnd = (if (storyboardEndMs > rangeStart) storyboardEndMs else videoDurationMs).coerceIn(rangeStart, videoDurationMs)
        val timeMs = storyboardTimestampForIndex(index, activeStoryboardFrameCount, rangeStart, rangeEnd)
        timelineView.setPlayhead(timeMs)
        try { videoView.seekTo(timeMs.toInt()) } catch (_: Exception) { }
    }

    private fun makeBorder(selected: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = dp(2).toFloat()
        setStroke(dp(if (selected) 4 else 1), if (selected) Color.rgb(255, 210, 31) else Color.rgb(61, 91, 135))
    }

    /** Returns the timestamp represented by a storyboard cell. Blank interval keeps the automatic spacing. */
    private fun storyboardTimestampForIndex(index: Int, count: Int, startMs: Long, endMs: Long): Long {
        imageSearchFrameTimes[index]?.let { return it.coerceIn(startMs, endMs) }
        val interval = storyboardIntervalMs
        return if (interval != null && interval > 0L) {
            startMs + index.toLong() * interval
        } else if (count <= 1) startMs else startMs + (endMs - startMs) * index / count
    }

    private fun loadStoryboard(uri: Uri) {
        val generation = ++storyboardGeneration
        val countForThisLoad = activeStoryboardFrameCount
        worker.execute {
            var retriever: MediaMetadataRetriever? = null
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                mainHandler.post {
                    if (generation != storyboardGeneration) return@post
                    videoDurationMs = duration
                    findViewById<EditText>(R.id.overlayTextEndTime).setText(formatTime(duration))
                    updateLiveTextPreview()
                    if (storyboardEndMs <= storyboardStartMs || storyboardEndMs > duration) {
                        storyboardStartMs = 0L
                        storyboardEndMs = duration
                        findViewById<android.widget.EditText>(R.id.storyboardStartTime).setText("00:00")
                        findViewById<android.widget.EditText>(R.id.storyboardEndTime).setText(formatTime(duration))
                    }
                    // Initialize the timeline with the selected video's duration.
                    // Without this, TimelineView remains in its empty state and cannot draw thumbnails.
                    timelineView.setTimeline(duration, timelineSplitPoints.toList(), deletedTimelineRanges)
                    trackTimelineView.setClips(trackClips.toList(), duration)
                    statusText.text = if (duration > 0) "مدت ویدیو ${formatTime(duration)} • در حال استخراج فریم‌ها…" else "در حال استخراج فریم‌ها…"
                }
                // Generate a small, dedicated timeline strip first; it no longer waits for all 120
                // storyboard images and is not rebuilt/sorted after every individual frame.
                loadTimelineThumbnails(uri, duration, generation)

                val count = countForThisLoad
                for (index in 0 until count) {
                    if (generation != storyboardGeneration) break
                    if (index in deletedStoryboardFrames) continue
                    val rangeStart = storyboardStartMs.coerceIn(0L, duration)
                    val rangeEnd = (if (storyboardEndMs > rangeStart) storyboardEndMs else duration).coerceIn(rangeStart, duration)
                    val timestampMs = storyboardTimestampForIndex(index, count, rangeStart, rangeEnd)
                    if (timestampMs >= rangeEnd) continue
                    // Fast path: sync-frame seeking avoids decoding forward/backward to the
                    // exact requested timestamp for every one of the 120 storyboard cells.
                    // Thumbnails are deliberately small; the player still shows the original.
                    val bitmap: Bitmap? = try {
                        if (android.os.Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 128, 80)
                        } else {
                            retriever.getFrameAtTime(timestampMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    } catch (_: Exception) { null }
                    if (bitmap != null) {
                        val scaled = scaleBitmapPreservingAspect(bitmap, 128, 80)
                        mainHandler.post {
                            if (generation == storyboardGeneration) {
                                frameBitmaps[index] = scaled
                                val cell = frameViews[index]
                                val image = cell?.getChildAt(0) as? ImageView
                                image?.setImageBitmap(scaled)
                                image?.clearColorFilter()
                                (cell?.getChildAt(1) as? TextView)?.text = formatTime(timestampMs)
                                cell?.visibility = if (index in deletedStoryboardFrames) View.GONE else View.VISIBLE
                            } else scaled.recycle()
                        }
                    }
                }
                mainHandler.post {
                    if (generation == storyboardGeneration) {
                        statusText.text = "استوری‌بورد کامل آماده است: $activeStoryboardFrameCount اسلاید در ${((activeStoryboardFrameCount + 5) / 6)} ردیف تقریبی؛ اسلایدهای اضافه موقت‌اند و با بازگشت به فاصلهٔ خودکار حذف می‌شوند"
                    }
                }
            } catch (_: Exception) {
                mainHandler.post { if (generation == storyboardGeneration) statusText.text = "استخراج فریم‌ها ناموفق بود؛ پخش ویدیو همچنان در دسترس است" }
            } finally { try { retriever?.release() } catch (_: Exception) { } }
        }
    }


    /** Samples a compact strip independently from the slower 120-frame storyboard. */
    private fun loadTimelineThumbnails(uri: Uri, durationMs: Long, generation: Int) {
        timelineThumbnailWorker.execute {
            var retriever: MediaMetadataRetriever? = null
            val frames = mutableListOf<Pair<Long, Bitmap>>()
            try {
                retriever = MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val sampleCount = 16
                for (i in 0 until sampleCount) {
                    if (generation != storyboardGeneration) break
                    val time = if (sampleCount <= 1) 0L else durationMs * i / sampleCount
                    val bitmap = try {
                        if (android.os.Build.VERSION.SDK_INT >= 27) {
                            retriever.getScaledFrameAtTime(time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 90)
                        } else {
                            retriever.getFrameAtTime(time * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                    } catch (_: Exception) { null }
                    if (bitmap != null) frames.add(time to scaleBitmapPreservingAspect(bitmap, 160, 90))
                }
                mainHandler.post {
                    if (generation == storyboardGeneration) {
                        timelineView.setThumbnails(frames)
                    } else frames.forEach { if (!it.second.isRecycled) it.second.recycle() }
                }
            } catch (_: Exception) {
                frames.forEach { if (!it.second.isRecycled) it.second.recycle() }
            } finally { try { retriever?.release() } catch (_: Exception) { } }
        }
    }

    private fun showTimeMode() {
        storyboardStartMs = 0L
        storyboardEndMs = videoDurationMs
        storyboardIntervalMs = null
        activeStoryboardFrameCount = DEFAULT_STORYBOARD_FRAME_COUNT
        cleanupTemporaryStoryboardCells()
        selectedFrames.clear()
        storyboardRangeAnchorIndex = null
        findViewById<EditText>(R.id.storyboardIntervalTime).setText("")
        findViewById<android.widget.EditText>(R.id.storyboardStartTime).setText("00:00")
        findViewById<android.widget.EditText>(R.id.storyboardEndTime).setText(formatTime(videoDurationMs))
        frameViews.forEach { (index, view) -> view.visibility = if (index in deletedStoryboardFrames) View.GONE else View.VISIBLE }
        updateModeButtons()
        val uri = selectedUri
        if (uri != null && videoDurationMs > 0L) reloadStoryboardForRange(uri)
        statusText.text = "نمایش زمانی کامل: از 00:00 تا ${formatTime(videoDurationMs)}"
    }

    private fun parseTimeInput(value: String): Long? {
        val text = value.trim()
        if (text.isEmpty()) return null
        return try {
            val parts = text.split(":")
            val seconds = when (parts.size) {
                1 -> parts[0].toLong()
                2 -> parts[0].toLong() * 60L + parts[1].toLong()
                3 -> parts[0].toLong() * 3600L + parts[1].toLong() * 60L + parts[2].toLong()
                else -> return null
            }
            if (parts.any { it.toLongOrNull() == null } || seconds < 0L) null else seconds * 1000L
        } catch (_: Exception) { null }
    }

    /** Sets the storyboard range endpoint to the current playback position without opening a dialog. */
    private fun setStoryboardTimeFromPlayback(isStart: Boolean) {
        if (selectedUri == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا ویدیو را انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val position = try { videoView.currentPosition.toLong() } catch (_: Exception) { timelineView.playheadMs }
        val at = position.coerceIn(0L, videoDurationMs)
        val fieldId = if (isStart) R.id.storyboardStartTime else R.id.storyboardEndTime
        findViewById<android.widget.EditText>(fieldId).apply {
            setText(formatTime(at))
            error = null
        }
        statusText.text = if (isStart) {
            "زمان شروع استوری‌بورد روی ${formatTime(at)} ثبت شد؛ برای نمایش بازه، «اعمال زمان» را بزنید"
        } else {
            "زمان پایان استوری‌بورد روی ${formatTime(at)} ثبت شد؛ برای نمایش بازه، «اعمال زمان» را بزنید"
        }
    }

    private fun applyStoryboardTimeRange() {
        val uri = selectedUri
        if (uri == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا ویدیو را انتخاب کنید", Toast.LENGTH_SHORT).show(); return
        }
        val start = parseTimeInput(findViewById<android.widget.EditText>(R.id.storyboardStartTime).text.toString())
        val end = parseTimeInput(findViewById<android.widget.EditText>(R.id.storyboardEndTime).text.toString())
        val intervalField = findViewById<android.widget.EditText>(R.id.storyboardIntervalTime)
        val intervalText = intervalField.text.toString().trim()
        val interval = if (intervalText.isEmpty()) null else parseTimeInput(intervalText)
        if (start == null || end == null || start >= end || end > videoDurationMs) {
            findViewById<android.widget.EditText>(R.id.storyboardEndTime).error = "زمان پایان باید بعد از شروع و در محدودهٔ ویدیو باشد"
            Toast.makeText(this, "زمان شروع و پایان را به‌شکل دقیقه:ثانیه وارد کنید؛ مثلاً 01:25", Toast.LENGTH_LONG).show(); return
        }
        if (intervalText.isNotEmpty() && (interval == null || interval <= 0L)) {
            intervalField.error = "مثلاً 10 برای 10 ثانیه یا 01:30 برای یک دقیقه و نیم"
            Toast.makeText(this, "فاصله را به ثانیه یا دقیقه:ثانیه وارد کنید", Toast.LENGTH_LONG).show(); return
        }
        intervalField.error = null
        storyboardStartMs = start
        storyboardEndMs = end
        val rangeLength = end - start
        if (interval == null) {
            // Returning to automatic spacing removes any temporary extra storyboard cells.
            storyboardIntervalMs = null
            activeStoryboardFrameCount = DEFAULT_STORYBOARD_FRAME_COUNT
            cleanupTemporaryStoryboardCells()
        } else {
            val requestedCount = ((rangeLength + interval - 1L) / interval).coerceAtLeast(1L)
            activeStoryboardFrameCount = requestedCount.coerceAtMost(MAX_STORYBOARD_FRAME_COUNT.toLong()).toInt()
            // If a tiny interval would require an impractically large grid, widen it slightly
            // so the final cell still reaches the end of the requested video range.
            val effectiveInterval = if (requestedCount > MAX_STORYBOARD_FRAME_COUNT) {
                (rangeLength + activeStoryboardFrameCount - 1L) / activeStoryboardFrameCount
            } else interval
            storyboardIntervalMs = effectiveInterval
            if (effectiveInterval != interval) {
                intervalField.setText(formatTime(effectiveInterval))
                Toast.makeText(this, "برای نمایش کامل ویدیو، فاصله به ${formatTime(effectiveInterval)} تنظیم شد تا تعداد اسلایدها از $MAX_STORYBOARD_FRAME_COUNT بیشتر نشود", Toast.LENGTH_LONG).show()
            }
        }
        selectedFrames.clear()
        storyboardRangeAnchorIndex = null
        reloadStoryboardForRange(uri)
        statusText.text = if (interval == null) "استوری‌بورد بازهٔ ${formatTime(start)} تا ${formatTime(end)} با فاصلهٔ خودکار آماده می‌شود؛ اسلایدهای موقت قبلی حذف می‌شوند" else "در حال ساخت ${activeStoryboardFrameCount} اسلاید برای پوشش کامل بازهٔ ${formatTime(start)} تا ${formatTime(end)}…"
    }

    /** Forget per-cell state belonging to temporary cells once the grid returns to its base size. */
    private fun cleanupTemporaryStoryboardCells() {
        selectedFrames.removeAll { it >= DEFAULT_STORYBOARD_FRAME_COUNT }
        deletedStoryboardFrames.removeAll { it >= DEFAULT_STORYBOARD_FRAME_COUNT }
        frameColorProfiles.keys.removeAll { it >= DEFAULT_STORYBOARD_FRAME_COUNT }
        imageSearchFrameTimes.keys.removeAll { it >= DEFAULT_STORYBOARD_FRAME_COUNT }
    }

    private fun reloadStoryboardForRange(uri: Uri) {
        imageSearchFrameTimes.clear()
        frameBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        frameBitmaps.clear()
        // Rebuild the grid to match the newly computed cell count. Any temporary
        // extra cells from the previous custom interval disappear here.
        buildEmptyStoryboard()
        timelineView.setThumbnails(emptyList())
        previewBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        previewBitmaps.clear()
        loadStoryboard(uri)
    }

    private fun scaleBitmapPreservingAspect(source: Bitmap, maxWidth: Int = 160, maxHeight: Int = 100): Bitmap {
        val scale = minOf(maxWidth.toFloat() / source.width, maxHeight.toFloat() / source.height, 1f)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        if (width == source.width && height == source.height) return source
        return Bitmap.createScaledBitmap(source, width, height, true).also { if (it !== source && !source.isRecycled) source.recycle() }
    }

    private fun deleteSelectedStoryboardFrames() {
        recordUndoState()
        if (selectedFrames.isEmpty()) {
            Toast.makeText(this, "ابتدا فریم‌هایی را انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val toDelete = selectedFrames.toList()
        toDelete.forEach { index ->
            deletedStoryboardFrames.add(index)
            frameViews[index]?.let { cell -> (cell.parent as? ViewGroup)?.removeView(cell) }
            frameViews.remove(index)
            frameBitmaps.remove(index)?.let { if (!it.isRecycled) it.recycle() }
        }
        selectedFrames.clear()
        updateSelectionText()
        statusText.text = "${toDelete.size} بازهٔ زمانی برای حذف از خروجی علامت‌گذاری شد"
        Toast.makeText(this, "بازه‌های زمانی این اسلایدها از ویدیوی خروجی حذف می‌شوند؛ فایل اصلی دست‌نخورده می‌ماند", Toast.LENGTH_LONG).show()
    }


    /** Saves every Cut-delimited timeline interval as its own file, stream-copying tracks without re-encoding. */
    /** Writes a MediaInfo-style sidecar report from the actual saved output file. */
    private fun writeOutputInfoReport(videoUri: Uri) {
        try {
            val displayName = contentResolver.query(videoUri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "EVC_output.mp4"
            val size = contentResolver.openAssetFileDescriptor(videoUri, "r")?.use { it.length } ?: -1L
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, videoUri)
            fun meta(key: Int): String = retriever.extractMetadata(key)?.takeIf { it.isNotBlank() } ?: "Not available"
            val duration = meta(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull()?.let { "${it} ms" } ?: "Not available"
            val width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val frameRate = meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val date = meta(MediaMetadataRetriever.METADATA_KEY_DATE)
            val extractor = MediaExtractor()
            extractor.setDataSource(this, videoUri, null)
            val report = StringBuilder()
            report.append("General\nComplete name : ").append(displayName).append("\nFormat : ").append(if (displayName.endsWith(".mkv", true)) "Matroska (MKV)" else "MP4 container").append("\nFile size : ")
                .append(if (size >= 0) String.format(java.util.Locale.US, "%.2f KiB", size / 1024.0) else "Not available")
                .append("\nDuration : ").append(duration).append("\nEncoded date : ").append(date).append("\n\n")
            var videoIndex = 0
            var audioIndex = 0
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: "Not available"
                if (mime.startsWith("video/")) {
                    videoIndex++
                    report.append("Video #").append(videoIndex).append("\nFormat : ").append(mime).append("\nCodec ID : ").append(mime)
                    report.append("\nWidth : ").append(if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else width).append(" pixels")
                    report.append("\nHeight : ").append(if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else height).append(" pixels")
                    if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) report.append("\nFrame rate : ").append(f.getInteger(MediaFormat.KEY_FRAME_RATE)).append(" FPS") else report.append("\nFrame rate : ").append(frameRate)
                    report.append("\nBit rate : ").append(if (f.containsKey(MediaFormat.KEY_BIT_RATE)) "${f.getInteger(MediaFormat.KEY_BIT_RATE)} b/s" else "Not available")
                    report.append("\nProfile : ").append(if (f.containsKey(MediaFormat.KEY_PROFILE)) f.getInteger(MediaFormat.KEY_PROFILE) else "Not available")
                    report.append("\nColor format : ").append(if (f.containsKey(MediaFormat.KEY_COLOR_FORMAT)) f.getInteger(MediaFormat.KEY_COLOR_FORMAT) else "Not available").append("\n\n")
                } else if (mime.startsWith("audio/")) {
                    audioIndex++
                    report.append("Audio #").append(audioIndex).append("\nFormat : ").append(mime).append("\nCodec ID : ").append(mime)
                    report.append("\nBit rate : ").append(if (f.containsKey(MediaFormat.KEY_BIT_RATE)) "${f.getInteger(MediaFormat.KEY_BIT_RATE)} b/s" else "Not available")
                    report.append("\nSampling rate : ").append(if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) "${f.getInteger(MediaFormat.KEY_SAMPLE_RATE)} Hz" else "Not available")
                    report.append("\nChannel(s) : ").append(if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else "Not available")
                    report.append("\nLanguage : ").append(if (f.containsKey(MediaFormat.KEY_LANGUAGE)) f.getString(MediaFormat.KEY_LANGUAGE) else "Not available").append("\n\n")
                }
            }
            extractor.release(); retriever.release()
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName.substringBeforeLast('.', displayName) + "_Info.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EVC_Info")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val reportUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            contentResolver.openOutputStream(reportUri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(report.toString()) }
            if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(reportUri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (_: Exception) { /* Report failure must not invalidate a successfully exported video. */ }
    }

    /** Exports the timeline edit (cut points + deleted ranges) as one continuous video.
     *  This path is intentionally independent of storyboard frame selection.
     */
    private fun exportTimelineEditedVideo() {
        val source = selectedUri
        if (source == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا یک ویدیوی معتبر انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val deleted = synchronized(deletedTimelineRanges) { deletedTimelineRanges.toList() }
            .map { it.first.coerceIn(0L, videoDurationMs) to it.second.coerceIn(0L, videoDurationMs) }
            .filter { it.second > it.first }
            .sortedBy { it.first }
        val mergedDeleted = deleted.fold(mutableListOf<Pair<Long, Long>>()) { acc, range ->
            if (acc.isNotEmpty() && range.first <= acc.last().second) {
                val previous = acc.removeAt(acc.lastIndex)
                acc.add(previous.first to max(previous.second, range.second))
            } else acc.add(range)
            acc
        }
        val keptRanges = mutableListOf<Pair<Long, Long>>()
        var cursor = 0L
        for (range in mergedDeleted) {
            if (range.first > cursor) keptRanges.add(cursor to range.first)
            cursor = max(cursor, range.second)
        }
        if (cursor < videoDurationMs) keptRanges.add(cursor to videoDurationMs)
        if (keptRanges.isEmpty()) {
            Toast.makeText(this, "تمام بخش‌های نوار زمان حذف شده‌اند؛ چیزی برای خروجی باقی نمانده است", Toast.LENGTH_LONG).show()
            return
        }
        val button = findViewById<Button>(R.id.outputChangesButton)
        button.isEnabled = false
        statusText.text = "در حال ساخت یک خروجی از بخش‌های باقی‌ماندهٔ نوار زمان، بدون رمزگذاری مجدد…"
        worker.execute {
            var result: File? = null
            var error: String? = null
            try { result = muxRemainingRanges(source, keptRanges) }
            catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
            mainHandler.post {
                button.isEnabled = true
                val file = result
                if (file == null || !file.exists() || file.length() == 0L) {
                    statusText.text = "خروجی ویرایش نوار زمان ناموفق بود: ${error ?: "خطای ناشناخته"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                    file?.delete()
                    return@post
                }
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_timeline_edited_${System.currentTimeMillis()}.${outputExtension()}")
                        put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("ساخت فایل خروجی ممکن نشد")
                    contentResolver.openOutputStream(outUri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                        ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                    if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    writeOutputInfoReport(outUri)
                    file.delete()
                    statusText.text = "خروجی نوار زمان در Movies/EVC ذخیره شد؛ بخش‌های حذف‌شده کنار گذاشته شدند و استوری‌بورد دخالتی نداشت."
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    file.delete()
                    statusText.text = "ذخیرهٔ خروجی ناموفق بود: ${e.message ?: "خطا"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Central entry point for exports that should avoid video re-encoding where possible. */
    private fun showOriginalOutputMenu() {
        if (selectedUri == null) {
            Toast.makeText(this, "ابتدا ویدیوی ورودی را انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        val choices = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        choices += "انتخاب تکه‌های جدا (خروجی هر تکه جداگانه)"
        actions += { showOriginalRangeSelectionDialog(separate = true) }
        choices += "انتخاب تکه‌های چسبیده (یک بازهٔ پیوسته)"
        actions += { showOriginalRangeSelectionDialog(separate = false) }
        choices += "تقسیم فایل اصلی (Split؛ بدون رمزگذاری مجدد)"
        actions += { showOriginalSplitDialog() }
        if (trackClips.any { it.kind == TrackClipKind.AUDIO || it.kind == TrackClipKind.SUBTITLE }) {
            choices += "خروجی نوار ترک‌ها؛ حفظ کدک ویدیوی اصلی در صورت سازگاری"
            actions += { exportTrackTimeline() }
        }
        if (externalAudioUri != null || externalSubtitleUri != null) {
            choices += "افزودن ترک صوتی/زیرنویس به فایل اصلی بدون رمزگذاری مجدد ویدیو"
            actions += { exportWithExternalTracks() }
        }
        choices += "راهنمای خروجی اصلی"
        actions += {
            AlertDialog.Builder(this)
                .setTitle("Original Utpot • خروجی بدون تغییر کدک")
                .setMessage("این بخش خروجی‌هایی را متمرکز می‌کند که ویدیوی اصلی را با Stream Copy حفظ می‌کنند. خروجی‌های دارای برش زمانی دلخواه، فیلتر تصویری، رنگ، متن یا ترکیب تصویر ممکن است به رمزگذاری مجدد نیاز داشته باشند. حفظ ترک‌های خاص به قالب خروجی و سازگاری کدک آن‌ها بستگی دارد.")
                .setPositiveButton("باشه", null)
                .show()
        }
        AlertDialog.Builder(this)
            .setTitle("Original Utpot • خروجی‌های بدون تغییر کدک")
            .setItems(choices.toTypedArray()) { _, which -> actions[which].invoke() }
            .setNegativeButton("لغو", null)
            .show()
    }

    /** Offers stream-copy selection for disjoint ranges or one contiguous range. */
    private fun showOriginalRangeSelectionDialog(separate: Boolean) {
        if (selectedUri == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا ویدیوی ورودی را انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 8)
        }
        panel.addView(TextView(this).apply {
            text = if (separate)
                "بازه‌ها را با ویرگول جدا کنید؛ هر بازه به فایل جداگانه تبدیل می‌شود. قالب: شروع-پایان، با ثانیه یا زمان دقیقه:ثانیه. مثال: 0-10,20-35"
            else
                "زمان شروع و پایان را وارد کنید؛ یک تکهٔ پیوسته ساخته می‌شود. می‌توانید ثانیه یا دقیقه:ثانیه وارد کنید."
            textSize = 14f
        })
        val rangesInput = EditText(this).apply {
            hint = if (separate) "مثلاً 0-10,20-35,01:10-01:30" else "مثلاً 00:20-01:30"
            setSingleLine(false)
            minLines = if (separate) 2 else 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setText(if (separate) "0-10,20-35" else "0-30")
        }
        panel.addView(rangesInput, LinearLayout.LayoutParams(-1, if (separate) 88 else 56))
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (separate) "انتخاب تکه‌های جدا" else "انتخاب تکه‌های چسبیده")
            .setView(panel)
            .setNegativeButton("لغو", null)
            .setPositiveButton("Original Utpot", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = rangesInput.text.toString().trim()
                val parsed: List<Pair<Long, Long>> = try {
                    if (separate) {
                        raw.split(",", "،", ";", "؛").map { token ->
                            val pair = token.trim().split("-")
                            require(pair.size == 2) { "هر تکه باید به‌شکل شروع-پایان باشد" }
                            val start = parseTimeInput(pair[0].trim()) ?: throw IllegalArgumentException("زمان شروع نامعتبر است")
                            val end = parseTimeInput(pair[1].trim()) ?: throw IllegalArgumentException("زمان پایان نامعتبر است")
                            require(start < end && end <= videoDurationMs) { "بازه باید داخل مدت ویدیو باشد و پایان از شروع بیشتر باشد" }
                            start to end
                        }
                    } else {
                        val pair = raw.split("-")
                        require(pair.size == 2) { "زمان را به‌شکل شروع-پایان وارد کنید" }
                        val start = parseTimeInput(pair[0].trim()) ?: throw IllegalArgumentException("زمان شروع نامعتبر است")
                        val end = parseTimeInput(pair[1].trim()) ?: throw IllegalArgumentException("زمان پایان نامعتبر است")
                        require(start < end && end <= videoDurationMs) { "بازه باید داخل مدت ویدیو باشد و پایان از شروع بیشتر باشد" }
                        listOf(start to end)
                    }
                } catch (e: Exception) {
                    rangesInput.error = e.message ?: "بازهٔ زمانی نامعتبر است"
                    return@setOnClickListener
                }
                if (parsed.isEmpty() || parsed.size > 100) {
                    rangesInput.error = "تعداد بازه‌ها باید بین 1 تا 100 باشد"
                    return@setOnClickListener
                }
                dialog.dismiss()
                exportOriginalSelectedRanges(parsed, separate)
            }
        }
        dialog.show()
    }

    /** Exports chosen ranges with -map 0 and -c copy to preserve compatible source streams. */
    private fun exportOriginalSelectedRanges(ranges: List<Pair<Long, Long>>, separate: Boolean) {
        val sourceUri = selectedUri ?: return
        val button = findViewById<Button>(R.id.originalOutputButton)
        button.isEnabled = false
        statusText.text = "در حال ساخت ${ranges.size} خروجی با کپی مستقیم جریان‌ها…"
        worker.execute {
            var inputFile: File? = null
            var saved = 0
            val errors = mutableListOf<String>()
            try {
                inputFile = copyUriToCache(sourceUri, "evc_original_ranges")
                val sourceName = displayNameForUri(sourceUri)
                val sourceExt = sourceName.substringAfterLast('.', "mp4").lowercase(java.util.Locale.ROOT)
                    .filter { it.isLetterOrDigit() }.take(8).ifBlank { "mp4" }
                ranges.forEachIndexed { index, range ->
                    var temp: File? = null
                    var outUri: Uri? = null
                    try {
                        val startMs = range.first
                        val endMs = range.second
                        temp = File(cacheDir, "evc_original_range_${index + 1}_${System.currentTimeMillis()}.$sourceExt")
                        val args = listOf(
                            "-y", "-ss", String.format(java.util.Locale.US, "%.3f", startMs / 1000.0),
                            "-i", inputFile.absolutePath,
                            "-t", String.format(java.util.Locale.US, "%.3f", (endMs - startMs) / 1000.0),
                            "-map", "0", "-c", "copy", "-avoid_negative_ts", "make_zero", temp.absolutePath
                        )
                        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
                        if (!ReturnCode.isSuccess(session.returnCode) || !temp.exists() || temp.length() == 0L) {
                            throw IllegalStateException(session.failStackTrace ?: session.allLogsAsString.takeLast(500).ifBlank { "برش این بازه ناموفق بود" })
                        }
                        val label = if (separate) "EVC_original_piece" else "EVC_original_contiguous"
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, "${label}_${(index + 1).toString().padStart(3, '0')}_${System.currentTimeMillis()}.$sourceExt")
                            put(MediaStore.Video.Media.MIME_TYPE, "video/*")
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                                put(MediaStore.Video.Media.IS_PENDING, 1)
                            }
                        }
                        outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("ساخت فایل خروجی ناموفق بود")
                        contentResolver.openOutputStream(outUri)?.use { output -> temp.inputStream().use { it.copyTo(output) } }
                            ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                        }
                        saved++
                    } catch (e: Exception) {
                        outUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) { } }
                        errors += "تکهٔ ${index + 1}: ${e.message ?: e.javaClass.simpleName}"
                    } finally { temp?.delete() }
                }
            } catch (e: Exception) {
                errors += e.message ?: e.javaClass.simpleName
            } finally { inputFile?.delete() }
            mainHandler.post {
                button.isEnabled = true
                statusText.text = if (errors.isEmpty()) "$saved فایل در Movies/EVC ذخیره شد؛ کپی مستقیم جریان‌ها انجام شد. برش دقیق ممکن است به فریم کلیدی نزدیک شود." else "$saved فایل ذخیره شد؛ ${errors.size} خطا: ${errors.first()}"
                Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showOriginalSplitDialog() {
        if (selectedUri == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا ویدیوی ورودی را انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 12, 24, 8)
        }
        panel.addView(TextView(this).apply {
            text = "تقسیم بر اساس تعداد تکه‌ها یا فاصلهٔ زمانی را انتخاب کنید. ترک‌های فایل با کپی مستقیم و بدون رمزگذاری مجدد نگه داشته می‌شوند."
            textSize = 14f
        })
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val countMode = RadioButton(this).apply { id = View.generateViewId(); text = "تعداد تکه‌های مساوی"; isChecked = true }
        val intervalMode = RadioButton(this).apply { id = View.generateViewId(); text = "تقسیم بر اساس فاصلهٔ زمانی" }
        modeGroup.addView(countMode)
        modeGroup.addView(intervalMode)
        panel.addView(modeGroup)
        val valueInput = EditText(this).apply {
            hint = "تعداد تکه‌ها (مثلاً 4)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("2")
            selectAll()
        }
        panel.addView(valueInput, LinearLayout.LayoutParams(-1, 52))
        val unitGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL; visibility = View.GONE }
        val secondsUnit = RadioButton(this).apply { id = View.generateViewId(); text = "ثانیه"; isChecked = true }
        val minutesUnit = RadioButton(this).apply { id = View.generateViewId(); text = "دقیقه" }
        unitGroup.addView(secondsUnit)
        unitGroup.addView(minutesUnit)
        panel.addView(unitGroup)
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val byInterval = checkedId == intervalMode.id
            unitGroup.visibility = if (byInterval) View.VISIBLE else View.GONE
            valueInput.hint = if (byInterval) "فاصلهٔ زمانی (مثلاً 30)" else "تعداد تکه‌ها (مثلاً 4)"
            valueInput.setText(if (byInterval) "30" else "2")
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Split • تقسیم فایل اصلی")
            .setView(panel)
            .setNegativeButton("لغو", null)
            .setPositiveButton("Original Utpot", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = valueInput.text.toString().toIntOrNull()
                if (value == null || value <= 0) {
                    valueInput.error = "عدد مثبت وارد کنید"
                    return@setOnClickListener
                }
                if (countMode.isChecked) {
                    if (value !in 2..100) {
                        valueInput.error = "تعداد باید بین 2 تا 100 باشد"
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    exportOriginalVideoSplits(value)
                } else {
                    val intervalMs = value.toLong() * if (minutesUnit.isChecked) 60_000L else 1_000L
                    if (intervalMs > videoDurationMs) {
                        valueInput.error = "فاصلهٔ زمانی نباید از مدت ویدیو بیشتر باشد"
                        return@setOnClickListener
                    }
                    val parts = ((videoDurationMs + intervalMs - 1) / intervalMs).toInt()
                    if (parts !in 2..1000) {
                        valueInput.error = "این فاصله به $parts تکه می‌رسد؛ فاصله‌ای انتخاب کنید که حداکثر 1000 تکه بسازد"
                        return@setOnClickListener
                    }
                    dialog.dismiss()
                    exportOriginalVideoSplits(parts, intervalMs)
                }
            }
        }
        dialog.show()
    }

    /** Splits the input into equal-duration files while stream-copying every input stream. */
    private fun exportOriginalVideoSplits(partCount: Int, intervalMs: Long? = null) {
        val sourceUri = selectedUri ?: return
        val duration = videoDurationMs
        if (duration <= 0L || partCount < 2 || partCount > 1000 || (intervalMs == null && partCount > 100)) return
        val button = findViewById<Button>(R.id.splitOriginalButton)
        button.isEnabled = false
        statusText.text = "در حال تقسیم ویدیو به $partCount تکه با کپی مستقیم کدک و همهٔ ترک‌ها…"
        worker.execute {
            var inputFile: File? = null
            var saved = 0
            val errors = mutableListOf<String>()
            try {
                inputFile = copyUriToCache(sourceUri, "evc_original_split")
                val sourceName = displayNameForUri(sourceUri)
                val sourceExt = sourceName.substringAfterLast('.', "mp4").lowercase(java.util.Locale.ROOT)
                    .filter { it.isLetterOrDigit() }.take(8).ifBlank { "mp4" }
                for (index in 0 until partCount) {
                    var temp: File? = null
                    var outUri: Uri? = null
                    try {
                        val startMs = if (intervalMs == null) duration * index / partCount else intervalMs * index
                        val endMs = if (intervalMs == null) duration * (index + 1) / partCount else minOf(duration, intervalMs * (index + 1))
                        if (endMs <= startMs) throw IllegalStateException("مدت فایل برای این تعداد تکه کافی نیست")
                        temp = File(cacheDir, "evc_original_part_${index + 1}_${System.currentTimeMillis()}.$sourceExt")
                        val args = listOf(
                            "-y", "-ss", String.format(java.util.Locale.US, "%.3f", startMs / 1000.0),
                            "-i", inputFile.absolutePath,
                            "-t", String.format(java.util.Locale.US, "%.3f", (endMs - startMs) / 1000.0),
                            "-map", "0", "-c", "copy", "-avoid_negative_ts", "make_zero",
                            temp.absolutePath
                        )
                        val session = FFmpegKit.executeWithArguments(args.toTypedArray())
                        if (!ReturnCode.isSuccess(session.returnCode) || !temp.exists() || temp.length() == 0L) {
                            throw IllegalStateException(session.failStackTrace ?: session.allLogsAsString.takeLast(500).ifBlank { "FFmpeg نتوانست این تکه را ذخیره کند" })
                        }
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_original_part_${(index + 1).toString().padStart(3, '0')}_${System.currentTimeMillis()}.$sourceExt")
                            put(MediaStore.Video.Media.MIME_TYPE, "video/*")
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                                put(MediaStore.Video.Media.IS_PENDING, 1)
                            }
                        }
                        outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                            ?: throw IllegalStateException("ساخت فایل خروجی ناموفق بود")
                        contentResolver.openOutputStream(outUri)?.use { output -> temp.inputStream().use { it.copyTo(output) } }
                            ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                        }
                        saved++
                    } catch (e: Exception) {
                        outUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) { } }
                        errors.add("تکهٔ ${index + 1}: ${e.message ?: e.javaClass.simpleName}")
                    } finally { temp?.delete() }
                }
            } catch (e: Exception) {
                errors.add(e.message ?: e.javaClass.simpleName)
            } finally { inputFile?.delete() }
            mainHandler.post {
                button.isEnabled = true
                statusText.text = if (errors.isEmpty()) "$saved تکه در Movies/EVC ذخیره شد؛ همهٔ ترک‌ها با کپی مستقیم و بدون رمزگذاری مجدد نگه داشته شدند. توجه: برش stream-copy ممکن است به مرز کلیدفریم نزدیک شود." else "$saved تکه ذخیره شد؛ ${errors.size} مورد ناموفق بود: ${errors.first()}"
                Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun exportTimelineSegmentsSeparately() {
        val source = selectedUri
        if (source == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا یک ویدیوی معتبر انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val boundaries = (listOf(0L) + timelineSplitPoints.filter { it > 0L && it < videoDurationMs } + listOf(videoDurationMs)).distinct().sorted()
        if (boundaries.size < 2) {
            Toast.makeText(this, "ابتدا با دکمهٔ Cut نقاط تقسیم را روی نوار زمان ثبت کنید", Toast.LENGTH_LONG).show()
            return
        }
        val deleted = synchronized(deletedTimelineRanges) { deletedTimelineRanges.toList() }
        val segments = (0 until boundaries.size - 1).map { boundaries[it] to boundaries[it + 1] }
            .filter { (a, b) -> b > a && deleted.none { d -> d.first <= a && d.second >= b } }
        if (segments.isEmpty()) {
            Toast.makeText(this, "قطعهٔ قابل ذخیره‌ای باقی نمانده است", Toast.LENGTH_LONG).show()
            return
        }
        val button = findViewById<Button>(R.id.outputChangesButton)
        button.isEnabled = false
        statusText.text = "در حال ذخیرهٔ ${segments.size} قطعه به‌صورت فایل‌های جداگانه و بدون رمزگذاری مجدد…"
        worker.execute {
            var saved = 0
            val errors = mutableListOf<String>()
            segments.forEachIndexed { index, range ->
                var temp: File? = null
                var outUri: Uri? = null
                try {
                    temp = muxRemainingRanges(source, listOf(range))
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_segment_${(index + 1).toString().padStart(3, '0')}_${System.currentTimeMillis()}.${outputExtension()}")
                        put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("ساخت فایل قطعهٔ ${index + 1} ناموفق بود")
                    contentResolver.openOutputStream(outUri)?.use { output -> FileInputStream(temp).use { input -> input.copyTo(output) } }
                        ?: throw IllegalStateException("نوشتن قطعهٔ ${index + 1} ناموفق بود")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                        contentResolver.update(outUri, done, null, null)
                    }
                    writeOutputInfoReport(outUri!!)
                    saved++
                } catch (e: Exception) {
                    outUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) { } }
                    errors.add("قطعهٔ ${index + 1}: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    temp?.delete()
                }
            }
            mainHandler.post {
                button.isEnabled = true
                statusText.text = if (errors.isEmpty()) "$saved قطعه به‌صورت فایل‌های جداگانه در Movies/EVC ذخیره شد؛ بدون رمزگذاری مجدد" else "$saved قطعه ذخیره شد؛ ${errors.size} قطعه ناموفق بود: ${errors.first()}"
                Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Keep the actual video, crop handles, and placement overlay aligned to the source frame. */
    private fun updatePlayerViewport(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val container = findViewById<FrameLayout>(R.id.playerContainer)
        val availableWidth = (container.width - container.paddingLeft - container.paddingRight).coerceAtLeast(1)
        val maxHeight = (180f * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val aspect = videoWidth.toFloat() / videoHeight.toFloat()
        var viewportWidth = availableWidth
        var viewportHeight = (viewportWidth / aspect).toInt().coerceAtLeast(1)
        if (viewportHeight > maxHeight) {
            viewportHeight = maxHeight
            viewportWidth = (viewportHeight * aspect).toInt().coerceAtLeast(1)
        }
        videoView.layoutParams = FrameLayout.LayoutParams(viewportWidth, viewportHeight, android.view.Gravity.CENTER)
        cropOverlay.layoutParams = FrameLayout.LayoutParams(viewportWidth, viewportHeight, android.view.Gravity.CENTER)
        placementOverlay.layoutParams = FrameLayout.LayoutParams(viewportWidth, viewportHeight, android.view.Gravity.CENTER)
        overlayVideoLayer.layoutParams = FrameLayout.LayoutParams(viewportWidth, viewportHeight, android.view.Gravity.CENTER)
        findViewById<ImageView>(R.id.playerColorPreview).layoutParams = FrameLayout.LayoutParams(viewportWidth, viewportHeight, android.view.Gravity.CENTER)
        updateOverlayPreviewLayout()
    }

    /** Free Crop: drag the four corner handles directly over the player preview. */
    private fun toggleFreeCropMode() {
        if (selectedUri == null) {
            Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        freeCropMode = !freeCropMode
        if (freeCropMode) {
            cropOverlay.setMargins(cropLeftPct, cropTopPct, cropRightPct, cropBottomPct)
            cropOverlay.visibility = View.VISIBLE
            freeCropControls.visibility = View.VISIBLE
            cropSettingsSaved = false
            statusText.text = "Free Crop فعال است: چهار دستگیرهٔ گوشهٔ کادر را روی پلیر بکشید؛ سپس «ثبت محدوده» را بزنید و برای فایل نهایی از «خروجی تغییرات» استفاده کنید"
        } else {
            cropOverlay.visibility = View.GONE
            freeCropControls.visibility = View.GONE
            statusText.text = "حالت Free Crop بسته شد"
        }
    }

    private fun readCropOverlay() {
        val margins = cropOverlay.getMargins()
        cropLeftPct = margins[0]
        cropTopPct = margins[1]
        cropRightPct = margins[2]
        cropBottomPct = margins[3]
    }

    private fun validCropMargins(): Boolean {
        if (cropLeftPct + cropRightPct >= 90 || cropTopPct + cropBottomPct >= 90) {
            Toast.makeText(this, "باید بخشی از تصویر در هر دو جهت باقی بماند", Toast.LENGTH_LONG).show()
            return false
        }
        return true
    }

    private inner class FreeCropOverlayView : View(this@MainActivity) {
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(67, 217, 163); style = Paint.Style.STROKE; strokeWidth = 3f }
        private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt(); style = Paint.Style.FILL }
        private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 210, 31); style = Paint.Style.FILL }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f; textAlign = Paint.Align.CENTER }
        private var left = 0.08f
        private var top = 0.08f
        private var right = 0.92f
        private var bottom = 0.92f
        private var activeCorner = -1
        private var draggingFrame = false
        private var dragStartX = 0f
        private var dragStartY = 0f
        private var dragStartLeft = 0f
        private var dragStartTop = 0f
        private var dragStartRight = 0f
        private var dragStartBottom = 0f
        private val handleRadius get() = 16f * resources.displayMetrics.density

        init { visibility = View.GONE; isClickable = true }

        fun setMargins(l: Int, t: Int, r: Int, b: Int) {
            left = (l / 100f).coerceIn(0f, 0.44f)
            top = (t / 100f).coerceIn(0f, 0.44f)
            right = (1f - r / 100f).coerceIn(0.56f, 1f)
            bottom = (1f - b / 100f).coerceIn(0.56f, 1f)
            if (right - left < 0.12f) { left = 0.08f; right = 0.92f }
            if (bottom - top < 0.12f) { top = 0.08f; bottom = 0.92f }
            invalidate()
        }

        fun getMargins(): List<Int> = listOf(
            (left * 100).toInt().coerceIn(0, 44),
            (top * 100).toInt().coerceIn(0, 44),
            ((1f - right) * 100).toInt().coerceIn(0, 44),
            ((1f - bottom) * 100).toInt().coerceIn(0, 44)
        )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (width <= 0 || height <= 0) return
            val l = left * width; val t = top * height; val r = right * width; val b = bottom * height
            canvas.drawRect(0f, 0f, width.toFloat(), t, shadePaint)
            canvas.drawRect(0f, b, width.toFloat(), height.toFloat(), shadePaint)
            canvas.drawRect(0f, t, l, b, shadePaint)
            canvas.drawRect(r, t, width.toFloat(), b, shadePaint)
            canvas.drawRect(l, t, r, b, borderPaint)
            val points = arrayOf(l to t, r to t, l to b, r to b)
            points.forEach { (x, y) -> canvas.drawCircle(x, y, handleRadius, handlePaint); canvas.drawCircle(x, y, handleRadius * 0.48f, borderPaint) }
            canvas.drawText("گوشه‌ها را بکشید", width / 2f, (t + b) / 2f, labelPaint)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x.coerceIn(0f, width.toFloat()); val y = event.y.coerceIn(0f, height.toFloat())
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Lock the outer ScrollView for the complete crop gesture.
                    parent?.requestDisallowInterceptTouchEvent(true)
                    val corners = arrayOf(left * width to top * height, right * width to top * height, left * width to bottom * height, right * width to bottom * height)
                    activeCorner = corners.indices.minByOrNull { i ->
                        val dx = corners[i].first - x; val dy = corners[i].second - y; dx * dx + dy * dy
                    } ?: -1
                    val nearest = corners.getOrNull(activeCorner)
                    if (nearest == null || kotlin.math.hypot((nearest.first - x).toDouble(), (nearest.second - y).toDouble()) > handleRadius * 2.5) activeCorner = -1
                    // Touching inside the crop frame moves the whole frame; touching a corner resizes it.
                    draggingFrame = activeCorner == -1 && x in (left * width)..(right * width) && y in (top * height)..(bottom * height)
                    dragStartX = x; dragStartY = y
                    dragStartLeft = left; dragStartTop = top; dragStartRight = right; dragStartBottom = bottom
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val minX = 0.08f; val minY = 0.08f
                    if (draggingFrame) {
                        val dx = (x - dragStartX) / width.coerceAtLeast(1)
                        val dy = (y - dragStartY) / height.coerceAtLeast(1)
                        val frameW = dragStartRight - dragStartLeft
                        val frameH = dragStartBottom - dragStartTop
                        left = (dragStartLeft + dx).coerceIn(0f, 1f - frameW)
                        top = (dragStartTop + dy).coerceIn(0f, 1f - frameH)
                        right = left + frameW
                        bottom = top + frameH
                        invalidate(); return true
                    }
                    when (activeCorner) {
                        0 -> { left = (x / width).coerceIn(0f, right - minX); top = (y / height).coerceIn(0f, bottom - minY) }
                        1 -> { right = (x / width).coerceIn(left + minX, 1f); top = (y / height).coerceIn(0f, bottom - minY) }
                        2 -> { left = (x / width).coerceIn(0f, right - minX); bottom = (y / height).coerceIn(top + minY, 1f) }
                        3 -> { right = (x / width).coerceIn(left + minX, 1f); bottom = (y / height).coerceIn(top + minY, 1f) }
                    }
                    invalidate(); return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    activeCorner = -1
                    draggingFrame = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                    return true
                }
            }
            return true
        }

        override fun performClick(): Boolean { super.performClick(); return true }
    }

    /** Unified final export combining configured crop, color, text, overlay video, audio and subtitle. */
    private fun exportCombinedChanges() {
        val sourceUri = selectedUri
        if (sourceUri == null) {
            Toast.makeText(this, "ابتدا ویدیوی اصلی را انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        if (freeCropMode) {
            readCropOverlay()
            if (!validCropMargins()) return
            cropSettingsSaved = true
            freeCropMode = false
            cropOverlay.visibility = View.GONE
            freeCropControls.visibility = View.GONE
        }
        val text = findViewById<EditText>(R.id.overlayTextInput).text.toString().trim()
        val secondUri = overlayVideoUri
        val audioUri = externalAudioUri
        val subtitleUri = externalSubtitleUri
        val textStartMs = if (text.isNotEmpty()) parseTimeInput(findViewById<EditText>(R.id.overlayTextStartTime).text.toString()) else 0L
        val textEndMs = if (text.isNotEmpty()) parseTimeInput(findViewById<EditText>(R.id.overlayTextEndTime).text.toString()) else videoDurationMs
        if (text.isNotEmpty() && (textStartMs == null || textEndMs == null || textEndMs <= textStartMs)) {
            Toast.makeText(this, "زمان شروع و پایان متن را درست وارد کنید", Toast.LENGTH_LONG).show(); return
        }
        if (cropSettingsSaved && !validCropMargins()) return

        // Keep legacy timeline/storyboard-only workflows reachable from the single visible output button.
        val rgbNow = currentRgbProfile()
        val globalColorChanged = rgbNow != RgbProfile(1f, 1f, 1f, 1f)
        val hasCombinedEdits = (cropSettingsSaved && (cropLeftPct > 0 || cropTopPct > 0 || cropRightPct > 0 || cropBottomPct > 0)) ||
            text.isNotEmpty() || secondUri != null || audioUri != null || subtitleUri != null || globalColorChanged || autoColorEnabled
        val hasTimelineEdits = selectedTimelineRange != null || timelineSplitPoints.isNotEmpty() || deletedTimelineRanges.isNotEmpty()
        val hasStoryboardEdits = selectedFrames.isNotEmpty() || deletedStoryboardFrames.isNotEmpty()
        val otherVisualEdits = (cropSettingsSaved && (cropLeftPct > 0 || cropTopPct > 0 || cropRightPct > 0 || cropBottomPct > 0)) ||
            text.isNotEmpty() || secondUri != null || globalColorChanged || autoColorEnabled
        if (trackClips.isNotEmpty() && !otherVisualEdits && !hasTimelineEdits && !hasStoryboardEdits && frameColorProfiles.isEmpty()) {
            exportTrackTimeline()
            return
        }
        if (trackClips.isNotEmpty() && otherVisualEdits) {
            Toast.makeText(this, "جایگاه و برش ترک‌های نوار در خروجی مستقل اعمال می‌شود؛ برای حفظ زمان‌بندی ترک‌ها ابتدا خروجی ترک‌ها را بگیرید یا ویرایش تصویری را جداگانه صادر کنید.", Toast.LENGTH_LONG).show()
            return
        }
        if (hasTimelineEdits && !hasCombinedEdits) {
            when {
                selectedTimelineRange != null -> exportOnlyTimelineRange(selectedTimelineRange!!)
                else -> exportTimelineEditedVideo()
            }
            return
        }
        if (hasStoryboardEdits && !hasCombinedEdits && !hasTimelineEdits) {
            if (selectedFrames.isNotEmpty()) exportSelectedStoryboardFramesSeparately()
            else exportRemainingVideo(false)
            return
        }
        if (frameColorProfiles.isNotEmpty() && !hasCombinedEdits && !hasTimelineEdits && !hasStoryboardEdits) {
            exportColorizedVideo()
            return
        }
        if (hasTimelineEdits && hasCombinedEdits) {
            Toast.makeText(this, "برش‌های نوار زمان هنوز با افکت‌های تصویری/صدا در یک فیلتر واحد ترکیب نشده‌اند؛ ابتدا برش‌های نوار زمان را بازنشانی کنید یا فقط خروجی برش را بگیرید.", Toast.LENGTH_LONG).show()
            return
        }
        if (hasStoryboardEdits && hasCombinedEdits) {
            Toast.makeText(this, "انتخاب‌های خروجی استوری‌بورد با افکت‌های سراسری هنوز در یک خروجی ترکیب نمی‌شوند؛ ابتدا انتخاب استوری‌بورد را لغو کنید.", Toast.LENGTH_LONG).show()
            return
        }

        val cropChanged = cropSettingsSaved && (cropLeftPct > 0 || cropTopPct > 0 || cropRightPct > 0 || cropBottomPct > 0)
        // Keep this decision outside the try block so the completion callback can report it.
        val requiresVideoReencode = cropChanged || text.isNotEmpty() || secondUri != null || globalColorChanged || autoColorEnabled
        val button = findViewById<Button>(R.id.outputChangesButton)
        button.isEnabled = false
        statusText.text = "در حال ترکیب تغییرات تصویری و ترک‌های پشتیبانی‌شده در یک خروجی…"
        worker.execute {
            var baseFile: File? = null
            var overlayFile: File? = null
            var audioFile: File? = null
            var subtitleFile: File? = null
            var textFile: File? = null
            var output: File? = null
            var error: String? = null
            try {
                baseFile = copyUriToCache(sourceUri, "evc_combined_base")
                if (secondUri != null) overlayFile = copyUriToCache(secondUri, "evc_combined_overlay")
                if (audioUri != null) audioFile = copyUriToCache(audioUri, "evc_combined_audio")
                if (subtitleUri != null) subtitleFile = copyUriToCache(subtitleUri, "evc_combined_subtitle")
                if (text.isNotEmpty()) {
                    textFile = File(cacheDir, "evc_combined_text_${System.currentTimeMillis()}.txt")
                    textFile.writeText(text, Charsets.UTF_8)
                }
                val ext = outputExtension()
                output = File(cacheDir, "evc_changes_${System.currentTimeMillis()}.$ext")
                val args = mutableListOf("-y", "-filter_threads", "1", "-filter_complex_threads", "1", "-threads", "1", "-i", baseFile.absolutePath)
                var nextInput = 1
                val overlayIndex = if (overlayFile != null) nextInput++ else null
                if (overlayFile != null) args.addAll(listOf("-stream_loop", "-1", "-i", overlayFile.absolutePath))
                val audioIndex = if (audioFile != null) nextInput++ else null
                if (audioFile != null) args.addAll(listOf("-i", audioFile.absolutePath))
                val subtitleIndex = if (subtitleFile != null) nextInput++ else null
                if (subtitleFile != null) args.addAll(listOf("-i", subtitleFile.absolutePath))

                // Audio/subtitle-only exports must not touch the video bitstream. Use FFmpeg
                // stream copy unless a real visual edit requires filtering/re-encoding.
                val graph = mutableListOf<String>()
                var current = "0:v"
                if (requiresVideoReencode && cropChanged) {
                    val widthPct = (100 - cropLeftPct - cropRightPct).coerceIn(10, 100)
                    val heightPct = (100 - cropTopPct - cropBottomPct).coerceIn(10, 100)
                    graph.add("[$current]crop=iw*$widthPct/100:ih*$heightPct/100:iw*$cropLeftPct/100:ih*$cropTopPct/100[evc_crop]")
                    current = "evc_crop"
                }
                if (requiresVideoReencode && autoColorEnabled) {
                    // Heuristic colorization: bright pixels lean sky-blue, darker midtones green,
                    // and middle/high midtones warm. This is not semantic AI object recognition.
                    graph.add("[$current]format=gray,split=7[evc_sky_src][evc_green_src][evc_skin_src][evc_sky_luma][evc_green_luma][evc_skin_luma][evc_gray_final]")
                    graph.add("[evc_sky_src]colorize=hue=210:saturation=0.32:mix=0.62,format=yuv420p[evc_sky]")
                    graph.add("[evc_green_src]colorize=hue=105:saturation=0.28:mix=0.48,format=yuv420p[evc_green]")
                    graph.add("[evc_skin_src]colorize=hue=25:saturation=0.24:mix=0.34,format=yuv420p[evc_skin]")
                    graph.add("[evc_sky_luma]lut=y='if(gte(val,175),255,0)'[evc_sky_mask]")
                    graph.add("[evc_green_luma]lut=y='if(between(val,35,145),255,0)'[evc_green_mask]")
                    graph.add("[evc_skin_luma]lut=y='if(between(val,90,205),255,0)'[evc_skin_mask]")
                    graph.add("[evc_gray_final]format=yuv420p[evc_gray_colorbase]")
                    graph.add("[evc_gray_colorbase][evc_sky][evc_sky_mask]maskedmerge[evc_auto_sky]")
                    graph.add("[evc_auto_sky][evc_green][evc_green_mask]maskedmerge[evc_auto_green]")
                    graph.add("[evc_auto_green][evc_skin][evc_skin_mask]maskedmerge[evc_auto]")
                    current = "evc_auto"
                }
                val rgb = currentRgbProfile()
                if (requiresVideoReencode) {
                    graph.add("[$current]colorchannelmixer=rr=${rgb.red}:gg=${rgb.green}:bb=${rgb.blue},eq=saturation=${rgb.saturation}[evc_color]")
                    current = "evc_color"
                }
                if (requiresVideoReencode && overlayIndex != null) {
                    val w = overlayVideoWidthPercent.coerceIn(5, 90)
                    val x = overlayVideoXPercent.coerceIn(0, 100 - w)
                    val y = overlayVideoYPercent.coerceIn(0, 95)
                    graph.add("[$overlayIndex:v][$current]scale2ref=w=main_w*$w/100:h=ow/mdar[evc_small][evc_base]")
                    graph.add("[evc_base][evc_small]overlay=x='main_w*$x/100':y='main_h*$y/100':eof_action=pass[evc_overlay]")
                    current = "evc_overlay"
                }
                if (requiresVideoReencode && textFile != null) {
                    val safePath = textFile.absolutePath.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'")
                    val startSeconds = String.format(java.util.Locale.US, "%.3f", (textStartMs ?: 0L) / 1000.0)
                    val endSeconds = String.format(java.util.Locale.US, "%.3f", (textEndMs ?: videoDurationMs) / 1000.0)
                    val draw = "drawtext=fontfile='/system/fonts/Roboto-Regular.ttf':textfile='$safePath':fontsize=${liveTextSize.coerceIn(8,160)}:fontcolor=white:borderw=2:bordercolor=black:x='w*${textXPercent.coerceIn(0,100)}/100':y='h*${textYPercent.coerceIn(0,100)}/100':enable='between(t\\,$startSeconds\\,$endSeconds)'"
                    graph.add("[$current]$draw[evc_text]")
                    current = "evc_text"
                }
                if (requiresVideoReencode) {
                    args.addAll(listOf("-filter_complex", graph.joinToString(";"), "-map", "[$current]"))
                    args.addAll(listOf("-c:v", "libx264", "-preset", "ultrafast", "-tune", "fastdecode", "-threads", "1", "-crf", "20", "-pix_fmt", "yuv420p"))
                } else {
                    // No visual changes: retain the exact original video codec/bitstream.
                    args.addAll(listOf("-map", "0:v:0", "-c:v", "copy"))
                }
                if (audioIndex != null) {
                    // Only the selected audio is encoded if needed; the video remains stream-copied.
                    args.addAll(listOf("-map", "$audioIndex:a:0", "-af", "apad", "-c:a", "aac", "-b:a", "192k", "-shortest"))
                } else if (requiresVideoReencode) {
                    // Keep the legacy compatible audio encode only when the image is being rendered.
                    args.addAll(listOf("-map", "0:a?", "-c:a", "aac", "-b:a", "192k"))
                } else {
                    // Audio/subtitle-only export also preserves the source audio bitstream.
                    args.addAll(listOf("-map", "0:a?", "-c:a", "copy"))
                }
                if (subtitleIndex != null) {
                    args.addAll(listOf("-map", "$subtitleIndex:s:0"))
                    args.addAll(listOf("-c:s", if (outputContainerMatroska) "copy" else "mov_text"))
                }
                if (!outputContainerMatroska) args.addAll(listOf("-movflags", "+faststart"))
                args.addAll(listOf("-max_muxing_queue_size", "1024", "-f", if (outputContainerMatroska) "matroska" else "mp4", output.absolutePath))
                val session = FFmpegKit.executeWithArguments(args.toTypedArray())
                if (!ReturnCode.isSuccess(session.returnCode) || !output.exists() || output.length() == 0L) {
                    throw IllegalStateException("ساخت خروجی تغییرات ناموفق بود: ${session.allLogsAsString.takeLast(1600)}")
                }
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                output?.delete(); output = null
            } finally {
                baseFile?.delete(); overlayFile?.delete(); audioFile?.delete(); subtitleFile?.delete(); textFile?.delete()
            }
            mainHandler.post {
                button.isEnabled = true
                val result = output
                if (result == null || !result.exists() || result.length() == 0L) {
                    statusText.text = "خروجی تغییرات ناموفق بود: ${error ?: "خطای ناشناخته"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                    result?.delete()
                    return@post
                }
                try {
                    val finalExt = outputExtension()
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_Changes_${System.currentTimeMillis()}.$finalExt")
                        put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("ساخت فایل در Movies/EVC ممکن نشد")
                    contentResolver.openOutputStream(outUri)?.use { stream -> FileInputStream(result).use { it.copyTo(stream) } }
                        ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                    if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    result.delete()
                    val applied = mutableListOf<String>()
                    if (cropSettingsSaved && (cropLeftPct > 0 || cropTopPct > 0 || cropRightPct > 0 || cropBottomPct > 0)) applied.add("Crop")
                    applied.add("رنگ")
                    if (text.isNotEmpty()) applied.add("متن")
                    if (secondUri != null) applied.add("ویدیوی رویی")
                    if (audioUri != null) applied.add("صدای جایگزین")
                    if (subtitleUri != null) applied.add("زیرنویس")
                    statusText.text = if (!requiresVideoReencode) {
                        "خروجی (${applied.joinToString("، ")}) در Movies/EVC ذخیره شد؛ کدک ویدیوی اصلی بدون رمزگذاری مجدد حفظ شد."
                    } else {
                        "خروجی مشترک (${applied.joinToString("، ")}) در Movies/EVC ذخیره شد؛ برای اعمال تغییرات تصویری، ویدیو دوباره رمزگذاری شد."
                    }
                    Toast.makeText(this, "خروجی تغییرات در Movies/EVC ذخیره شد", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    result.delete()
                    statusText.text = "ذخیرهٔ خروجی تغییرات ناموفق بود: ${e.message ?: "خطا"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun exportCroppedVideo() {
        val source = selectedUri
        if (source == null) {
            Toast.makeText(this, "ابتدا یک ویدیو انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        if (!cropSettingsSaved) {
            Toast.makeText(this, "ابتدا تنظیمات Crop را ثبت کنید", Toast.LENGTH_LONG).show()
            return
        }
        val output = File(cacheDir, "evc_crop_${System.currentTimeMillis()}.mp4")
        val button = findViewById<Button>(R.id.cropButton)
        button.isEnabled = false
        statusText.text = "در حال اعمال Crop واقعی و رمزگذاری مجدد ویدیو…"
        try {
            val left = (-1f + 2f * cropLeftPct / 100f).coerceIn(-1f, 0.98f)
            val right = (1f - 2f * cropRightPct / 100f).coerceIn(-0.98f, 1f)
            val top = (1f - 2f * cropTopPct / 100f).coerceIn(-0.98f, 1f)
            val bottom = (-1f + 2f * cropBottomPct / 100f).coerceIn(-1f, 0.98f)
            val cropEffect = Crop(left, right, bottom, top)
            val item = EditedMediaItem.Builder(MediaItem.fromUri(source))
                .setEffects(Effects(emptyList(), listOf(cropEffect)))
                .build()
            val composition = Composition.Builder(EditedMediaItemSequence.Builder(item).build()).build()
            val transformer = Transformer.Builder(this)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        activeTransformer = null
                        try {
                            val values = ContentValues().apply {
                                put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_Crop_${System.currentTimeMillis()}.mp4")
                                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                                if (android.os.Build.VERSION.SDK_INT >= 29) {
                                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                                    put(MediaStore.Video.Media.IS_PENDING, 1)
                                }
                            }
                            val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                                ?: throw IllegalStateException("ساخت فایل در Movies/EVC ممکن نشد")
                            contentResolver.openOutputStream(outUri)?.use { out -> FileInputStream(output).use { it.copyTo(out) } }
                                ?: throw IllegalStateException("نوشتن فایل Crop ناموفق بود")
                            if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(outUri, ContentValues().apply {
                                put(MediaStore.Video.Media.IS_PENDING, 0)
                            }, null, null)
                            writeOutputInfoReport(outUri)
                            output.delete()
                            button.isEnabled = true
                            statusText.text = "خروجی Crop واقعی در Movies/EVC ذخیره شد. برای اعمال برش، ویدیو رمزگذاری مجدد شده است."
                            Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            output.delete(); button.isEnabled = true
                            statusText.text = "ذخیرهٔ Crop ناموفق بود: ${e.message ?: "خطا"}"
                            Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                        }
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        activeTransformer = null; output.delete(); button.isEnabled = true
                        statusText.text = "خروجی Crop ناموفق بود: ${exportException.message ?: "خطای رمزگذاری"}"
                        Toast.makeText(this@MainActivity, statusText.text, Toast.LENGTH_LONG).show()
                    }
                }).build()
            activeTransformer = transformer
            transformer.start(composition, output.absolutePath)
        } catch (e: Exception) {
            activeTransformer = null; output.delete(); button.isEnabled = true
            statusText.text = "شروع خروجی Crop ناموفق بود: ${e.message ?: "خطا"}"
            Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
        }
    }

    private fun exportRemainingVideo(onlySelected: Boolean = false) {
        // Storyboard selection has explicit priority when its mode is active. Do this
        // BEFORE checking the timeline range: a stale timeline selection must never
        // redirect a storyboard export into the whole-video/timeline export path.
        if (onlySelected) {
            selectedTimelineRange = null
            exportSelectedStoryboardFramesSeparately()
            return
        }
        // A range explicitly selected on the timeline belongs to the timeline section.
        // Export only that range; never mix it with storyboard deletions or other ranges.
        val timelineRange = selectedTimelineRange
        if (timelineRange != null) {
            exportOnlyTimelineRange(timelineRange)
            return
        }
        val source = selectedUri
        if (source == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا یک ویدیوی معتبر انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val total = activeStoryboardFrameCount
        val requestedFrames = selectedFrames.toSet()
        if (onlySelected && requestedFrames.isEmpty()) {
            Toast.makeText(this, "برای این حالت، ابتدا اسلایدهایی را که باید در خروجی باشند انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }
        val kept = if (onlySelected) requestedFrames.filter { it !in deletedStoryboardFrames } else (0 until total).filter { it !in deletedStoryboardFrames }
        if (kept.isEmpty()) {
            Toast.makeText(this, if (onlySelected) "هیچ اسلاید انتخاب‌شدهٔ قابل‌خروجی وجود ندارد" else "همهٔ بازه‌ها حذف شده‌اند؛ چیزی برای خروجی باقی نمانده است", Toast.LENGTH_LONG).show()
            return
        }
        // Current version exports the remaining intervals without re-encoding.
        // The target-size/re-encoding mode will be added separately to avoid silently
        // changing codec or quality in the original-quality mode.
        statusText.text = if (onlySelected) "در حال ساخت خروجی بدون تغییر کدک از اسلایدهای انتخاب‌شده…" else "در حال ساخت خروجی بدون تغییر کدک از بازه‌های باقی‌مانده…"
        findViewById<Button>(R.id.outputChangesButton).isEnabled = false
        worker.execute {
            var result: File? = null
            var error: String? = null
            try {
                val excluded = mutableListOf<Pair<Long, Long>>()
                var runStart: Int? = null
                for (i in 0 until total) {
                    val shouldExclude = if (onlySelected) i !in requestedFrames || i in deletedStoryboardFrames else i in deletedStoryboardFrames
                    if (shouldExclude && runStart == null) runStart = i
                    if ((!shouldExclude || i == total - 1) && runStart != null) {
                        val endIndex = if (shouldExclude && i == total - 1) i + 1 else i
                        val startMs = videoDurationMs * runStart!! / total
                        val endMs = videoDurationMs * endIndex / total
                        if (endMs > startMs) excluded.add(startMs to endMs)
                        runStart = null
                    }
                }
                // Storyboard export is isolated: do not import deletions from the timeline section.
                val mergedExcluded = excluded.sortedBy { it.first }.fold(mutableListOf<Pair<Long, Long>>()) { acc, r ->
                    if (acc.isNotEmpty() && r.first <= acc.last().second) {
                        val last = acc.removeAt(acc.lastIndex); acc.add(last.first to max(last.second, r.second))
                    } else acc.add(r)
                    acc
                }
                val keptRanges = mutableListOf<Pair<Long, Long>>()
                var cursor = 0L
                for (r in mergedExcluded) {
                    val a = r.first.coerceIn(0L, videoDurationMs); val b = r.second.coerceIn(0L, videoDurationMs)
                    if (a > cursor) keptRanges.add(cursor to a)
                    cursor = max(cursor, b)
                }
                if (cursor < videoDurationMs) keptRanges.add(cursor to videoDurationMs)
                result = muxRemainingRanges(source, keptRanges)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            mainHandler.post {
                findViewById<Button>(R.id.outputChangesButton).isEnabled = true
                val file = result
                if (file != null && file.exists() && file.length() > 0L) {
                    try {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_edited_${System.currentTimeMillis()}.${outputExtension()}")
                            put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
                            if (android.os.Build.VERSION.SDK_INT >= 29) {
                                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                                put(MediaStore.Video.Media.IS_PENDING, 1)
                            }
                        }
                        val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        if (outUri == null) throw IllegalStateException("ساخت فایل در پوشهٔ Movies/EVC ممکن نشد")
                        contentResolver.openOutputStream(outUri)?.use { out -> FileInputStream(file).use { input -> input.copyTo(out) } }
                            ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                            contentResolver.update(outUri, done, null, null)
                        }
                        writeOutputInfoReport(outUri)
                        file.delete()
                        statusText.text = "ویدیوی خروجی در Movies/EVC ذخیره شد (بدون رمزگذاری مجدد)"
                        Toast.makeText(this, "ویدیوی خروجی در پوشهٔ Movies/EVC ذخیره شد", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        statusText.text = "انتقال خروجی به پوشهٔ ویدیوها ناموفق بود: ${e.message ?: "خطا"}"
                        Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                    }
                } else {
                    statusText.text = "ساخت ویدیوی خروجی ناموفق بود: ${error ?: "فرمت یا برش پشتیبانی نشد"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }



    private fun exportOnlyTimelineRange(range: Pair<Long, Long>) {
        val source = selectedUri
        if (source == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا یک ویدیوی معتبر انتخاب کنید", Toast.LENGTH_SHORT).show(); return
        }
        val start = range.first.coerceIn(0L, videoDurationMs)
        val end = range.second.coerceIn(0L, videoDurationMs)
        if (end <= start) { Toast.makeText(this, "بازهٔ انتخاب‌شده معتبر نیست", Toast.LENGTH_SHORT).show(); return }
        val button = findViewById<Button>(R.id.outputChangesButton)
        button.isEnabled = false
        statusText.text = "در حال خروجی گرفتن فقط از ${formatTime(start)} تا ${formatTime(end)}…"
        worker.execute {
            var result: File? = null
            var error: String? = null
            try { result = muxRemainingRanges(source, listOf(start to end)) }
            catch (e: Exception) { error = e.message ?: e.javaClass.simpleName }
            mainHandler.post {
                button.isEnabled = true
                val file = result
                if (file == null || !file.exists() || file.length() == 0L) {
                    statusText.text = "خروجی بازهٔ نوار زمان ناموفق بود: ${error ?: "خطای ناشناخته"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show(); return@post
                }
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_timeline_${System.currentTimeMillis()}.${outputExtension()}")
                        put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
                        if (android.os.Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("ساخت فایل خروجی ممکن نشد")
                    contentResolver.openOutputStream(outUri)?.use { out -> FileInputStream(file).use { input -> input.copyTo(out) } }
                        ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                    if (android.os.Build.VERSION.SDK_INT >= 29) contentResolver.update(outUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                    writeOutputInfoReport(outUri)
                    file.delete()
                    selectedTimelineRange = null
                    statusText.text = "فقط بازهٔ انتخاب‌شده در Movies/EVC ذخیره شد؛ تنظیمات استوری‌بورد و حذف‌ها دخالت داده نشدند"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    statusText.text = "ذخیرهٔ فایل ناموفق بود: ${e.message ?: "خطا"}"
                    Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Exports ONLY the selected storyboard time intervals, concatenated in selection order.
     *  The intervals are built from the storyboard's active start/end range, not the full video.
     *  No complement/remaining-video ranges are ever added to this export.
     */
    private fun exportSelectedStoryboardFramesSeparately() {
        val source = selectedUri
        if (source == null || videoDurationMs <= 0L) {
            Toast.makeText(this, "ابتدا یک ویدیوی معتبر انتخاب کنید", Toast.LENGTH_SHORT).show()
            return
        }
        val total = activeStoryboardFrameCount
        val chosen = selectedFrames.toSet()
            .filter { it in 0 until total && it !in deletedStoryboardFrames }
            .sorted()
        if (chosen.isEmpty()) {
            statusText.text = "خروجی انجام نشد: هیچ اسلایدی برای خروجی انتخاب نشده است"
            Toast.makeText(this, "خروجی لغو شد؛ ابتدا اسلایدهای موردنظر را انتخاب کنید", Toast.LENGTH_LONG).show()
            return
        }

        val rangeStart = storyboardStartMs.coerceIn(0L, videoDurationMs)
        val rangeEnd = (if (storyboardEndMs > rangeStart) storyboardEndMs else videoDurationMs)
            .coerceIn(rangeStart, videoDurationMs)
        if (rangeEnd <= rangeStart) {
            Toast.makeText(this, "بازهٔ زمانی استوری‌بورد معتبر نیست", Toast.LENGTH_LONG).show()
            return
        }

        // Build output intervals ONLY from selected storyboard indices. Consecutive
        // selected cells are joined into one range; any unselected cell closes the range.
        // Do not compute the complement (all unselected/remaining intervals).
        val selectedRanges = mutableListOf<Pair<Long, Long>>()
        var runStartIndex: Int? = null
        var previousIndex: Int? = null
        fun appendSelectedRun(firstIndex: Int, lastIndex: Int) {
            val a = storyboardTimestampForIndex(firstIndex, total, rangeStart, rangeEnd)
                .coerceIn(rangeStart, rangeEnd)
            // Use the next cell boundary as the end, even when a custom interval is set.
            // This keeps the selected run from spilling into the next unselected cell.
            val b = storyboardTimestampForIndex(lastIndex + 1, total, rangeStart, rangeEnd)
                .coerceIn(rangeStart, rangeEnd)
            if (b > a) selectedRanges.add(a to b)
        }
        for (index in chosen) {
            if (runStartIndex == null) {
                runStartIndex = index
                previousIndex = index
            } else if (previousIndex != null && index == previousIndex!! + 1) {
                previousIndex = index
            } else {
                appendSelectedRun(runStartIndex!!, previousIndex!!)
                runStartIndex = index
                previousIndex = index
            }
        }
        if (runStartIndex != null && previousIndex != null) {
            appendSelectedRun(runStartIndex!!, previousIndex!!)
        }
        // Keep the ranges as constructed from selected runs. Do not merge across gaps:
        // a gap represents at least one unselected storyboard cell and must stay excluded.
        val normalizedSelectedRanges = selectedRanges.toList()
        if (selectedRanges.isEmpty()) {
            Toast.makeText(this, "بازهٔ زمانی معتبری در اسلایدهای انتخاب‌شده وجود ندارد", Toast.LENGTH_LONG).show()
            return
        }

        val button = findViewById<Button>(R.id.outputChangesButton)
        button.isEnabled = false
        statusText.text = "در حال ساخت خروجی فقط از ${normalizedSelectedRanges.size} بازهٔ پیوستهٔ انتخاب‌شده؛ اسلایدهای انتخاب‌نشده وارد خروجی نمی‌شوند…"
        worker.execute {
            var temp: File? = null
            var outUri: Uri? = null
            var error: String? = null
            try {
                temp = muxRemainingRanges(source, normalizedSelectedRanges)
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, "EVC_selected_storyboard_${System.currentTimeMillis()}.${outputExtension()}")
                    put(MediaStore.Video.Media.MIME_TYPE, outputMimeType())
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/EVC")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
                outUri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("ساخت فایل خروجی ناموفق بود")
                contentResolver.openOutputStream(outUri)?.use { output ->
                    FileInputStream(temp).use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException("نوشتن فایل خروجی ناموفق بود")
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    contentResolver.update(outUri, ContentValues().apply {
                        put(MediaStore.Video.Media.IS_PENDING, 0)
                    }, null, null)
                }
                writeOutputInfoReport(outUri!!)
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
                outUri?.let { try { contentResolver.delete(it, null, null) } catch (_: Exception) { } }
            } finally {
                temp?.delete()
            }
            mainHandler.post {
                button.isEnabled = true
                if (error == null && outUri != null) {
                    statusText.text = "فقط ${normalizedSelectedRanges.size} بازهٔ پیوستهٔ انتخاب‌شده در یک فایل ذخیره شد؛ بازه‌های انتخاب‌نشده وارد خروجی نشدند"
                } else {
                    statusText.text = "خروجی اسلایدهای انتخاب‌شده ناموفق بود: ${error ?: "خطای ناشناخته"}"
                }
                Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun outputExtension(): String = if (outputContainerMatroska) "mkv" else "mp4"
    private fun outputMimeType(): String = if (outputContainerMatroska) "video/x-matroska" else "video/mp4"

    /**
     * Builds an MKV output using FFmpeg stream-copy only: no audio/video re-encoding.
     * The source URI is copied to cache first so document-provider URIs work too.
     * Stream-copy cuts are keyframe-dependent and may not land on the exact requested frame.
     */
    private fun muxRangesToMatroska(source: Uri, ranges: List<Pair<Long, Long>>): File {
        val stamp = System.currentTimeMillis()
        val input = File(cacheDir, "evc_mkv_source_$stamp.bin")
        val segments = mutableListOf<File>()
        val concatList = File(cacheDir, "evc_mkv_concat_$stamp.txt")
        val output = File(cacheDir, "evc_export_$stamp.mkv")
        try {
            contentResolver.openInputStream(source)?.use { src ->
                input.outputStream().use { dst -> src.copyTo(dst) }
            } ?: throw IllegalStateException("خواندن فایل ویدیوی اصلی ممکن نشد")
            if (input.length() == 0L) throw IllegalStateException("فایل ویدیوی اصلی خالی است")

            ranges.filter { it.second > it.first }.forEachIndexed { index, range ->
                val segment = File(cacheDir, "evc_mkv_part_${stamp}_$index.mkv")
                val start = range.first.coerceAtLeast(0L) / 1000.0
                val duration = (range.second - range.first).coerceAtLeast(0L) / 1000.0
                if (duration <= 0.0) return@forEachIndexed
                val args = arrayOf(
                    "-y", "-ss", String.format(java.util.Locale.US, "%.3f", start),
                    "-i", input.absolutePath, "-t", String.format(java.util.Locale.US, "%.3f", duration),
                    "-map", "0", "-c", "copy", "-avoid_negative_ts", "make_zero",
                    "-f", "matroska", segment.absolutePath
                )
                val session = FFmpegKit.executeWithArguments(args)
                if (!ReturnCode.isSuccess(session.returnCode) || !segment.exists() || segment.length() == 0L) {
                    throw IllegalStateException("ساخت قطعهٔ MKV ناموفق بود: ${session.allLogsAsString.takeLast(900)}")
                }
                segments.add(segment)
            }
            if (segments.isEmpty()) throw IllegalStateException("هیچ بازهٔ معتبری برای خروجی MKV وجود ندارد")
            concatList.writeText(segments.joinToString("\n") { segment ->
                "file '${segment.absolutePath.replace("'", "'\\''")}'"
            } + "\n")
            val concatArgs = arrayOf(
                "-y", "-f", "concat", "-safe", "0", "-i", concatList.absolutePath,
                "-map", "0", "-c", "copy", "-f", "matroska", output.absolutePath
            )
            val concatSession = FFmpegKit.executeWithArguments(concatArgs)
            if (!ReturnCode.isSuccess(concatSession.returnCode) || !output.exists() || output.length() == 0L) {
                throw IllegalStateException("ادغام قطعات MKV ناموفق بود: ${concatSession.allLogsAsString.takeLast(900)}")
            }
            return output
        } catch (e: Exception) {
            output.delete()
            throw e
        } finally {
            input.delete()
            concatList.delete()
            segments.forEach { it.delete() }
        }
    }

    private fun muxRemainingRanges(source: Uri, ranges: List<Pair<Long, Long>>): File {
        if (outputContainerMatroska) return muxRangesToMatroska(source, ranges)
        val probe = MediaExtractor()
        probe.setDataSource(this, source, null)
        val videoTrack = (0 until probe.trackCount).firstOrNull {
            probe.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
        } ?: run { probe.release(); throw IllegalStateException("ترک ویدیویی پیدا نشد") }
        // Android's MPEG-4 MediaMuxer only supports a subset of MP4 tracks. Never silently
        // discard subtitles, timed metadata, or other non-A/V tracks during a modified export.
        val unsupportedTracks = mutableListOf<String>()
        for (i in 0 until probe.trackCount) {
            val format = probe.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown/unknown"
            if (!mime.startsWith("video/") && !mime.startsWith("audio/")) unsupportedTracks.add("#$i $mime")
        }
        if (unsupportedTracks.isNotEmpty()) {
            probe.release()
            throw IllegalStateException(
                "این ویدیو ترک‌های غیرصوتی/تصویری دارد (${unsupportedTracks.joinToString()})؛ " +
                "خروجی‌گیر فعلی اندروید قادر به حفظ مطمئن زیرنویس/داده‌های آن نیست. برای جلوگیری از حذف بی‌صدا، خروجی لغو شد. " +
                "برای حفظ همهٔ ترک‌ها باید خروجی‌گیر FFmpeg با پشتیبانی از کپی همهٔ ترک‌ها اضافه شود."
            )
        }
        val output = File(cacheDir, "evc_export_${System.currentTimeMillis()}.mp4")
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val trackMap = mutableMapOf<Int, Int>()
        for (i in 0 until probe.trackCount) {
            val format = probe.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                try {
                    trackMap[i] = muxer.addTrack(format)
                    probe.selectTrack(i)
                } catch (e: Exception) {
                    try { muxer.release() } catch (_: Exception) { }
                    probe.release()
                    throw IllegalStateException("حفظ ترک $i با کدک $mime توسط خروجی‌گیر MP4 اندروید پشتیبانی نشد؛ برای جلوگیری از حذف ترک، خروجی لغو شد.", e)
                }
            }
        }
        if (trackMap.isEmpty()) { probe.release(); muxer.release(); throw IllegalStateException("ترک قابل خروجی پیدا نشد") }
        muxer.start()
        val buffer = java.nio.ByteBuffer.allocate(8 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var outputBaseUs = 0L
        try {
            for ((requestedStartMs, requestedEndMs) in ranges) {
                if (requestedEndMs <= requestedStartMs) continue
                val syncProbe = MediaExtractor()
                syncProbe.setDataSource(this, source, null)
                syncProbe.selectTrack(videoTrack)
                syncProbe.seekTo(requestedStartMs * 1000L, MediaExtractor.SEEK_TO_NEXT_SYNC)
                val actualStartUs = if (syncProbe.sampleTrackIndex >= 0) syncProbe.sampleTime else -1L
                syncProbe.release()
                if (actualStartUs < 0L || actualStartUs >= requestedEndMs * 1000L) continue

                // First lightweight pass finds the first video PTS for timestamp normalization.
                val startProbe = MediaExtractor()
                startProbe.setDataSource(this, source, null)
                startProbe.selectTrack(videoTrack)
                startProbe.seekTo(actualStartUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
                val segmentStartUs = if (startProbe.sampleTrackIndex == videoTrack) startProbe.sampleTime else actualStartUs
                startProbe.release()

                // Start this output strictly at the selected slide's own sync frame.
                // Seeking to PREVIOUS_SYNC can land inside the preceding storyboard
                // interval; use NEXT_SYNC and still enforce the selected start timestamp.
                probe.seekTo(actualStartUs, MediaExtractor.SEEK_TO_NEXT_SYNC)
                while (probe.sampleTrackIndex >= 0) {
                    val track = probe.sampleTrackIndex
                    val pts = probe.sampleTime
                    if (pts >= requestedEndMs * 1000L) break
                    if (track in trackMap && pts >= actualStartUs) {
                        buffer.clear()
                        val size = probe.readSampleData(buffer, 0)
                        if (size > buffer.capacity()) throw IllegalStateException("نمونهٔ رسانه از ظرفیت بافر بیشتر است")
                        if (size > 0) {
                            info.set(0, size, outputBaseUs + (pts - segmentStartUs).coerceAtLeast(0L), probe.sampleFlags)
                            muxer.writeSampleData(trackMap.getValue(track), buffer, info)
                        }
                    }
                    if (!probe.advance()) break
                }
                outputBaseUs += (requestedEndMs * 1000L - segmentStartUs).coerceAtLeast(1L)
            }
        } finally {
            try { muxer.stop() } catch (_: Exception) { }
            muxer.release()
            probe.release()
        }
        if (!output.exists() || output.length() == 0L) {
            output.delete()
            throw IllegalStateException("هیچ بازهٔ قابل‌خروجی باقی نماند؛ ممکن است برش این کدک با حفظ کدک پشتیبانی نشود")
        }
        return output
    }

    private fun updateModeButtons() {
        timeModeButton.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(255, 210, 31))
        timeModeButton.setTextColor(Color.rgb(7, 20, 47))
    }

    private fun refreshSelectionStyles() { frameViews.keys.forEach { refreshCellStyle(it) } }
    private fun refreshCellStyle(index: Int) {
        val cell = frameViews[index] ?: return
        cell.foreground = makeBorder(index in selectedFrames)
        val badge = cell.tag as? TextView
        badge?.visibility = if (index in selectedFrames) View.VISIBLE else View.GONE
    }
    private fun updateSelectionText() {
        selectionText.text = "اسلایدهای انتخاب‌شده: ${selectedFrames.size} • باقی‌مانده: ${frameViews.size} از $activeStoryboardFrameCount؛ انتخاب‌ها مستقیماً برای حذف یا خروجی آماده‌اند"
        refreshSelectionStyles()
    }
    private fun formatTime(ms: Long): String {
        val seconds = max(0L, ms / 1000L)
        val hours = seconds / 3600L
        val minutes = (seconds % 3600L) / 60L
        val secs = seconds % 60L
        return if (hours > 0L) String.format("%02d:%02d:%02d", hours, minutes, secs)
        else String.format("%02d:%02d", minutes, secs)
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onPause() {
        super.onPause()
        if (::videoView.isInitialized && videoView.isPlaying) videoView.pause()
    }
    override fun onDestroy() {
        storyboardGeneration++
        releaseOverlayPreviewPlayer()
        worker.shutdownNow()
        frameBitmaps.values.forEach { if (!it.isRecycled) it.recycle() }
        super.onDestroy()
    }
    companion object {
        private const val REQUEST_VIDEO = 2101
        private const val DEFAULT_STORYBOARD_FRAME_COUNT = 120
        private const val MAX_STORYBOARD_FRAME_COUNT = 1200
    }
}


/** Interactive timeline: one-finger drag scrubs; pinch changes the visible time scale. */
class TimelineView(context: android.content.Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {
    var onSeek: ((Long) -> Unit)? = null
    var onSelectRange: ((Pair<Long, Long>) -> Unit)? = null
    var playheadMs: Long = 0L
        private set
    private var durationMs = 0L
    private var splitPoints = listOf<Long>()
    private var deletedRanges = listOf<Pair<Long, Long>>()
    private var thumbnails: List<Pair<Long, Bitmap>> = emptyList()
    private var zoom = 1f
    private var windowStartMs = 0L
    private var lastX = 0f
    private var downX = 0f
    private var downY = 0f
    private var initialSpan = 0f
    private var initialZoom = 1f
    private var moved = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(8, 17, 32) }
    private val density = resources.displayMetrics.density

    fun setTimeline(duration: Long, cuts: List<Long>, deleted: List<Pair<Long, Long>>) {
        durationMs = duration.coerceAtLeast(0L)
        splitPoints = cuts.sorted()
        deletedRanges = deleted.toList()
        windowStartMs = windowStartMs.coerceIn(0L, max(0L, durationMs - visibleDuration()))
        invalidate()
    }
    fun setThumbnails(frames: List<Pair<Long, Bitmap>>) {
        val next = frames.filter { !it.second.isRecycled }.sortedBy { it.first }
        val retained = next.map { it.second }.toSet()
        thumbnails.map { it.second }.distinct().forEach { old ->
            if (old !in retained && !old.isRecycled) old.recycle()
        }
        thumbnails = next
        invalidate()
    }
    fun setPlayhead(timeMs: Long) {
        playheadMs = timeMs.coerceIn(0L, durationMs)
        // Auto-follow playback: let the playhead travel across the visible strip,
        // then move the timeline window when it approaches the right edge.
        if (durationMs > 0L) {
            val visible = visibleDuration()
            val end = windowStartMs + visible
            val followThreshold = windowStartMs + (visible * 0.82f).toLong()
            if (playheadMs > followThreshold && playheadMs < durationMs) {
                windowStartMs = (playheadMs - (visible * 0.72f).toLong())
                    .coerceIn(0L, max(0L, durationMs - visible))
            } else if (playheadMs < windowStartMs && playheadMs > 0L) {
                windowStartMs = (playheadMs - (visible * 0.15f).toLong())
                    .coerceIn(0L, max(0L, durationMs - visible))
            }
        }
        invalidate()
    }
    private fun visibleDuration(): Long = if (durationMs <= 0L) 1L else (durationMs / zoom).toLong().coerceAtLeast(1L)
    private fun timeAt(x: Float): Long {
        val usable = (width - 16f * density).coerceAtLeast(1f)
        return (windowStartMs + (((x - 8f * density).coerceIn(0f, usable) / usable) * visibleDuration().toFloat()).toLong()).coerceIn(0L, durationMs)
    }
    private fun xAt(time: Long): Float {
        val usable = (width - 16f * density).coerceAtLeast(1f)
        return 8f * density + ((time - windowStartMs).toFloat() / visibleDuration()).coerceIn(0f, 1f) * usable
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 8f * density; val right = width - left
        val top = height * .03f; val bottom = height * .97f
        paint.color = Color.rgb(37, 69, 111); canvas.drawRoundRect(left, top, right, bottom, 5f * density, 5f * density, paint)
        if (durationMs <= 0L) {
            paint.color = Color.LTGRAY; paint.textSize = 11f * density
            canvas.drawText("نوار زمان پس از انتخاب ویدیو آماده می‌شود", left, height * .58f, paint); return
        }
        val start = windowStartMs; val end = minOf(durationMs, start + visibleDuration())
        // Render extracted video frames as one continuous thumbnail strip across the timeline.
        if (thumbnails.isNotEmpty()) {
            val save = canvas.save()
            canvas.clipRect(left, top, right, bottom)
            // Preserve the complete source frame inside each time cell; no stretch or crop.
            thumbnails.forEachIndexed { index, frame ->
                val centerX = xAt(frame.first)
                val prevX = if (index == 0) left else (xAt(thumbnails[index - 1].first) + centerX) / 2f
                val nextX = if (index == thumbnails.lastIndex) right else (centerX + xAt(thumbnails[index + 1].first)) / 2f
                if (nextX < left || prevX > right) return@forEachIndexed
                val dst = RectF(max(left, prevX), top, minOf(right, nextX), bottom)
                if (dst.width() > 0f && dst.height() > 0f) {
                    val bitmap = frame.second
                    // Fill each timeline time-cell like a thumbnail preview; do not shrink
                    // the cell or leave letterbox gaps. Center-crop only the image to the cell.
                    val bitmapRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
                    val targetRatio = dst.width() / dst.height()
                    val source = if (bitmapRatio > targetRatio) {
                        val cropWidth = (bitmap.height * targetRatio).toInt().coerceIn(1, bitmap.width)
                        val leftCrop = (bitmap.width - cropWidth) / 2
                        Rect(leftCrop, 0, leftCrop + cropWidth, bitmap.height)
                    } else {
                        val cropHeight = (bitmap.width / targetRatio).toInt().coerceIn(1, bitmap.height)
                        val topCrop = (bitmap.height - cropHeight) / 2
                        Rect(0, topCrop, bitmap.width, topCrop + cropHeight)
                    }
                    paint.color = Color.WHITE
                    canvas.drawBitmap(bitmap, source, dst, paint)
                }
            }
            canvas.restoreToCount(save)
            paint.color = Color.argb(18, 8, 17, 32)
            canvas.drawRoundRect(left, top, right, bottom, 5f * density, 5f * density, paint)
        }
        deletedRanges.forEach { r ->
            if (r.second >= start && r.first <= end) {
                paint.color = Color.rgb(181, 44, 69)
                canvas.drawRect(xAt(r.first), top, xAt(r.second), bottom, paint)
            }
        }
        splitPoints.filter { it in start..end }.forEach { cut ->
            paint.color = Color.rgb(255, 210, 31); paint.strokeWidth = 2f * density
            canvas.drawLine(xAt(cut), top - 5f * density, xAt(cut), bottom + 5f * density, paint)
        }
        paint.color = Color.WHITE; paint.textSize = 9f * density
        canvas.drawText(format(playheadMs), left, 10f * density + 2f, paint)
        paint.color = Color.rgb(67, 217, 163); paint.strokeWidth = 3f * density
        val px = xAt(playheadMs); canvas.drawLine(px, top, px, bottom, paint)
        paint.color = Color.LTGRAY; paint.textSize = 8f * density
        canvas.drawText(format(start), left, height - 3f * density, paint)
        val endText = format(end); canvas.drawText(endText, right - paint.measureText(endText), height - 3f * density, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; lastX = event.x; downY = event.y; moved = false; return true }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) { initialSpan = span(event); initialZoom = zoom; moved = true; return true }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val current = span(event)
                    if (initialSpan > 0f && current > 0f && durationMs > 0L) {
                        val focusTime = timeAt((event.getX(0) + event.getX(1)) / 2f)
                        zoom = (initialZoom * current / initialSpan).coerceIn(1f, 80f)
                        val frac = ((event.getX(0) + event.getX(1)) / 2f / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        windowStartMs = (focusTime - (visibleDuration() * frac).toLong()).coerceIn(0L, max(0L, durationMs - visibleDuration()))
                        invalidate()
                    }
                    return true
                }
                val dx = event.x - lastX
                if (kotlin.math.abs(event.x - downX) > 5f * density) moved = true
                if (durationMs > 0L && moved) {
                    val usable = (width - 16f * density).coerceAtLeast(1f)
                    val shift = (-dx / usable * visibleDuration()).toLong()
                    windowStartMs = (windowStartMs + shift).coerceIn(0L, max(0L, durationMs - visibleDuration()))
                    playheadMs = timeAt(event.x)
                    onSeek?.invoke(playheadMs)
                    invalidate()
                }
                lastX = event.x; return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && durationMs > 0L) {
                    val t = timeAt(event.x); playheadMs = t; onSeek?.invoke(t)
                    val boundaries = (listOf(0L) + splitPoints.filter { it in 0L..durationMs } + listOf(durationMs)).distinct().sorted()
                    val idx = (0 until boundaries.size - 1).firstOrNull { t >= boundaries[it] && t <= boundaries[it + 1] } ?: 0
                    if (boundaries.size > 1) onSelectRange?.invoke(boundaries[idx] to boundaries[idx + 1])
                    invalidate()
                }
                performClick(); return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    private fun span(e: MotionEvent): Float { if (e.pointerCount < 2) return 0f; val dx=e.getX(0)-e.getX(1); val dy=e.getY(0)-e.getY(1); return kotlin.math.sqrt(dx*dx+dy*dy) }
    private fun format(ms: Long): String { val s=(ms.coerceAtLeast(0L)/1000L); return String.format(java.util.Locale.US, "%02d:%02d", s/60, s%60) }
}


/** Two-lane timeline for externally added audio and subtitle clips. Drag a clip body to move it; drag either edge to trim. */
class TrackTimelineView(context: android.content.Context, attrs: android.util.AttributeSet? = null) : View(context, attrs) {
    var onClipSelected: ((TrackClip?) -> Unit)? = null
    var onClipsChanged: ((List<TrackClip>) -> Unit)? = null
    var selectedClipId: Long? = null
        private set
    val selectedClipIds: MutableSet<Long> = linkedSetOf()
    private var downAtMs = 0L
    private var longPressedSelection = false
    private var clips: List<TrackClip> = emptyList()
    private var videoDurationMs: Long = 0L
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var activeId: Long? = null
    private var mode = 0 // 0 move, 1 trim left, 2 trim right
    private var moved = false
    private var original: TrackClip? = null
    private var originalList: List<TrackClip> = emptyList()

    fun setClips(items: List<TrackClip>, duration: Long) {
        clips = items.toList(); videoDurationMs = duration.coerceAtLeast(1L)
        if (selectedClipId != null && clips.none { it.id == selectedClipId }) selectedClipId = null
        selectedClipIds.retainAll(clips.map { it.id }.toSet())
        invalidate()
    }
    fun selectClip(id: Long) { selectedClipId = id; selectedClipIds.clear(); selectedClipIds.add(id); onClipSelected?.invoke(clips.firstOrNull { it.id == id }); invalidate() }
    fun clearSelection() { selectedClipIds.clear(); selectedClipId = null; onClipSelected?.invoke(null); invalidate() }
    private fun xFor(ms: Long): Float = (ms.toDouble() / videoDurationMs.toDouble()).toFloat().coerceIn(0f, 1f) * width
    private fun timeFor(x: Float): Long = ((x / width.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f) * videoDurationMs).toLong()
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val laneH = height / 2f
        p.textSize = 9f * d
        for (lane in 0..1) {
            val top = lane * laneH
            p.color = if (lane == 0) Color.rgb(17, 37, 59) else Color.rgb(33, 27, 54)
            canvas.drawRect(0f, top, width.toFloat(), top + laneH, p)
            p.color = Color.rgb(180, 202, 229)
            canvas.drawText(if (lane == 0) "AUDIO" else "SUBTITLE", 4f * d, top + 10f * d, p)
        }
        if (videoDurationMs <= 1L) {
            p.color = Color.LTGRAY; canvas.drawText("پس از انتخاب ویدیو، ترک‌ها در اینجا نمایش داده می‌شوند", 8f * d, height / 2f, p); return
        }
        clips.forEach { clip ->
            val lane = if (clip.kind == TrackClipKind.AUDIO) 0 else 1
            val laneTop = lane * laneH
            val left = xFor(clip.startMs)
            val right = xFor(clip.startMs + clip.durationMs)
            val rect = RectF(left, laneTop + 14f * d, maxOf(left + 12f * d, right), laneTop + laneH - 3f * d)
            p.color = when {
                clip.id in selectedClipIds && clip.kind == TrackClipKind.AUDIO -> Color.rgb(255, 190, 48)
                clip.id in selectedClipIds -> Color.rgb(218, 166, 255)
                clip.kind == TrackClipKind.AUDIO -> Color.rgb(64, 151, 229)
                else -> Color.rgb(153, 103, 204)
            }
            canvas.drawRoundRect(rect, 4f * d, 4f * d, p)
            p.color = Color.BLACK; p.textSize = 8f * d
            val label = clip.name.take(22) + "  ${clip.durationMs / 1000}s"
            canvas.save(); canvas.clipRect(rect); canvas.drawText(label, rect.left + 5f * d, rect.centerY() + 3f * d, p); canvas.restore()
            p.color = Color.WHITE
            canvas.drawRect(rect.left, rect.top, minOf(rect.right, rect.left + 4f * d), rect.bottom, p)
            canvas.drawRect(maxOf(rect.left, rect.right - 4f * d), rect.top, rect.right, rect.bottom, p)
        }
        val playhead = try { (context as? MainActivity)?.let { it.findViewById<TimelineView>(R.id.timelineView).playheadMs } ?: 0L } catch (_: Exception) { 0L }
        p.color = Color.WHITE; p.strokeWidth = 1.2f * d; canvas.drawLine(xFor(playhead), 0f, xFor(playhead), height.toFloat(), p)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; moved = false
                downAtMs = android.os.SystemClock.uptimeMillis(); longPressedSelection = false
                val lane = if (event.y < height / 2f) TrackClipKind.AUDIO else TrackClipKind.SUBTITLE
                val hit = clips.asReversed().firstOrNull { clip ->
                    if (clip.kind != lane) false else {
                        val l = xFor(clip.startMs); val r = xFor(clip.startMs + clip.durationMs)
                        event.x >= l - 5f * d && event.x <= r + 5f * d
                    }
                }
                activeId = hit?.id; original = hit; originalList = clips.toList()
                if (hit != null) {
                    selectedClipId = hit.id
                    val left = xFor(hit.startMs); val right = xFor(hit.startMs + hit.durationMs)
                    mode = when { abs(event.x - left) <= 12f * d -> 1; abs(event.x - right) <= 12f * d -> 2; else -> 0 }
                    onClipSelected?.invoke(hit); invalidate()
                } else { selectedClipId = null; onClipSelected?.invoke(null); invalidate() }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val id = activeId ?: return true
                val clip = original ?: return true
                val delta = timeFor(event.x) - timeFor(downX)
                if (abs(event.x - downX) > 3f * d) moved = true
                if (!moved) return true
                val list = clips.toMutableList(); val i = list.indexOfFirst { it.id == id }; if (i < 0) return true
                val updated = when (mode) {
                    1 -> {
                        val newStart = (clip.startMs + delta).coerceIn(0L, clip.startMs + clip.durationMs - 500L)
                        val shift = newStart - clip.startMs
                        clip.copy(startMs = newStart, sourceStartMs = (clip.sourceStartMs + shift).coerceAtLeast(0L), durationMs = clip.durationMs - shift)
                    }
                    2 -> clip.copy(durationMs = (clip.durationMs + delta).coerceIn(500L, maxOf(500L, videoDurationMs - clip.startMs)))
                    else -> clip.copy(startMs = (clip.startMs + delta).coerceIn(0L, maxOf(0L, videoDurationMs - clip.durationMs)))
                }
                list[i] = updated; clips = list; invalidate(); onClipsChanged?.invoke(clips)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!moved && activeId != null) {
                    val held = android.os.SystemClock.uptimeMillis() - downAtMs
                    if (event.actionMasked == MotionEvent.ACTION_UP && held >= android.view.ViewConfiguration.getLongPressTimeout()) {
                        activeId?.let { id ->
                            if (!selectedClipIds.add(id)) selectedClipIds.remove(id)
                            selectedClipId = selectedClipIds.lastOrNull()
                            longPressedSelection = true
                            onClipSelected?.invoke(clips.firstOrNull { it.id == selectedClipId })
                        }
                    } else if (!longPressedSelection) {
                        selectedClipIds.clear(); activeId?.let { selectedClipIds.add(it) }
                        onClipSelected?.invoke(clips.firstOrNull { it.id == activeId })
                    }
                    invalidate()
                }
                activeId = null; original = null; parent?.requestDisallowInterceptTouchEvent(false)
                performClick(); return true
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
