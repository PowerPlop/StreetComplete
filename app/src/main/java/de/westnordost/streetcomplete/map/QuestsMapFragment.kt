package de.westnordost.streetcomplete.map

import android.graphics.PointF
import android.graphics.RectF
import android.os.Bundle
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import androidx.annotation.DrawableRes
import androidx.lifecycle.lifecycleScope
import com.mapzen.tangram.MapData
import com.mapzen.tangram.geometry.Point
import de.westnordost.osmapi.map.data.Element
import de.westnordost.osmapi.map.data.LatLon
import de.westnordost.streetcomplete.Injector
import de.westnordost.streetcomplete.R
import de.westnordost.streetcomplete.data.osm.geometry.ElementGeometry
import de.westnordost.streetcomplete.data.quest.OsmNoteQuestKey
import de.westnordost.streetcomplete.data.quest.OsmQuestKey
import de.westnordost.streetcomplete.data.quest.Quest
import de.westnordost.streetcomplete.data.quest.QuestKey
import de.westnordost.streetcomplete.ktx.getBitmapDrawable
import de.westnordost.streetcomplete.ktx.toDp
import de.westnordost.streetcomplete.ktx.toPx
import de.westnordost.streetcomplete.map.QuestPinLayerManager.Companion.MARKER_ELEMENT_ID
import de.westnordost.streetcomplete.map.QuestPinLayerManager.Companion.MARKER_ELEMENT_TYPE
import de.westnordost.streetcomplete.map.QuestPinLayerManager.Companion.MARKER_NOTE_ID
import de.westnordost.streetcomplete.map.QuestPinLayerManager.Companion.MARKER_QUEST_TYPE
import de.westnordost.streetcomplete.map.components.PointMarkersMapComponent
import de.westnordost.streetcomplete.map.tangram.*
import de.westnordost.streetcomplete.util.distanceTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Manages a map that shows the quest pins, quest geometry */
class QuestsMapFragment : LocationAwareMapFragment() {

    @Inject internal lateinit var spriteSheet: TangramQuestSpriteSheet
    @Inject internal lateinit var questPinLayerManager: QuestPinLayerManager

    // layers
    private var questsLayer: MapData? = null
    private var geometryLayer: MapData? = null
    private var selectedQuestPinsLayer: MapData? = null

    private val questSelectionMarkers: MutableList<Marker> = mutableListOf()

    private var pointMarkersMapComponent: PointMarkersMapComponent? = null

    // for restoring position
    private var cameraPositionBeforeShowingQuest: CameraPosition? = null

    interface Listener {
        fun onClickedQuest(questKey: QuestKey)
        fun onClickedMapAt(position: LatLon, clickAreaSizeInMeters: Double)
    }
    private val listener: Listener? get() = parentFragment as? Listener ?: activity as? Listener

    /* ------------------------------------ Lifecycle ------------------------------------------- */

    init {
        Injector.applicationComponent.inject(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(questPinLayerManager)
        questPinLayerManager.mapFragment = this
    }

    override suspend fun onMapReady() {
        val ctrl = controller ?: return
        ctrl.setPickRadius(1f)
        pointMarkersMapComponent = PointMarkersMapComponent(ctrl)
        geometryLayer = controller?.addDataLayer(GEOMETRY_LAYER)
        questsLayer = controller?.addDataLayer(QUESTS_LAYER)
        selectedQuestPinsLayer = controller?.addDataLayer(SELECTED_QUESTS_LAYER)
        questPinLayerManager.questsLayer = questsLayer
        super.onMapReady()
    }

    override fun onMapIsChanging(position: LatLon, rotation: Float, tilt: Float, zoom: Float) {
        super.onMapIsChanging(position, rotation, tilt, zoom)
        questPinLayerManager.onNewScreenPosition()
    }

    override fun onDestroy() {
        super.onDestroy()
        geometryLayer = null
        questsLayer = null
        selectedQuestPinsLayer = null
        questSelectionMarkers.clear()
    }

    /* ------------------------------------- Map setup ------------------------------------------ */

    override suspend fun onBeforeLoadScene() {
        super.onBeforeLoadScene()
        val questSceneUpdates = withContext(Dispatchers.IO) { spriteSheet.sceneUpdates }
        sceneMapComponent?.putSceneUpdates(questSceneUpdates)
    }

    /* -------------------------------- Picking quest pins -------------------------------------- */

    override fun onSingleTapConfirmed(x: Float, y: Float): Boolean {
        lifecycleScope.launch {
            val props = controller?.pickLabel(x, y)?.properties

            val noteId = props?.get(MARKER_NOTE_ID)?.toLong()
            val elementId = props?.get(MARKER_ELEMENT_ID)?.toLong()
            val elementType = props?.get(MARKER_ELEMENT_TYPE)?.let { Element.Type.valueOf(it) }
            val questTypeName = props?.get(MARKER_QUEST_TYPE)

            val questKey = when {
                noteId != null -> {
                    OsmNoteQuestKey(noteId)
                }
                elementId != null && elementType != null && questTypeName != null -> {
                    OsmQuestKey(elementType, elementId, questTypeName)
                }
                else -> null
            }

            if (questKey != null) {
                listener?.onClickedQuest(questKey)
            } else {
                val pickMarkerResult = controller?.pickMarker(x,y)
                if (pickMarkerResult == null) {
                    onClickedMap(x, y)
                }
            }
        }
        return true
    }

    private fun onClickedMap(x: Float, y: Float) {
        val context = context ?: return

        val clickPos = controller?.screenPositionToLatLon(PointF(x, y)) ?: return

        val fingerRadius = CLICK_AREA_SIZE_IN_DP.toFloat().toPx(context) / 2
        val fingerEdgeClickPos = controller?.screenPositionToLatLon(PointF(x + fingerRadius, y)) ?: return
        val fingerRadiusInMeters = clickPos.distanceTo(fingerEdgeClickPos)

        listener?.onClickedMapAt(clickPos, fingerRadiusInMeters)
    }

    /* --------------------------------- Focusing on quest -------------------------------------- */

    fun startFocusQuest(quest: Quest, offset: RectF) {
        zoomAndMoveToContain(quest.geometry, offset)
        showQuestSelectionMarkers(quest.markerLocations)
        putSelectedQuestPins(quest)
        putQuestGeometry(quest.geometry)
    }

    /** Clear focus on current quest but do not return to normal view yet */
    fun clearFocusQuest() {
        removeQuestGeometry()
        clearMarkersForCurrentQuest()
        hideQuestSelectionMarkers()
        removeSelectedQuestPins()
    }

    fun endFocusQuest() {
        clearFocusQuest()
        restoreCameraPosition()
        centerCurrentPositionIfFollowing()
    }

    private fun zoomAndMoveToContain(g: ElementGeometry, offset: RectF) {
        val controller = controller ?: return
        val pos = controller.getEnclosingCameraPosition(g.getBounds(), offset) ?: return
        val currentPos = controller.cameraPosition
        val targetZoom = min(pos.zoom, 20f)

        // do not zoom in if the element is already nicely in the view
        if (controller.screenAreaContains(g, RectF()) && targetZoom - currentPos.zoom < 2) return

        cameraPositionBeforeShowingQuest = currentPos

        val zoomTime = max(450L, (abs(currentPos.zoom - targetZoom) * 300).roundToLong())

        controller.updateCameraPosition(zoomTime, DecelerateInterpolator()) {
            position = pos.position
            zoom = targetZoom
        }
    }

    private fun restoreCameraPosition() {
        val controller = controller ?: return

        val pos = cameraPositionBeforeShowingQuest
        if (pos != null) {
            val currentPos = controller.cameraPosition
            val zoomTime = max(300L, (abs(currentPos.zoom - pos.zoom) * 300).roundToLong())

            controller.updateCameraPosition(zoomTime, AccelerateDecelerateInterpolator()) {
                position = pos.position
                zoom = pos.zoom
                tilt = pos.tilt
                rotation = pos.rotation
            }
        }
        cameraPositionBeforeShowingQuest = null
    }

    /* --------------------------------------  Quest Pins --------------------------------------- */

    var isShowingQuestPins: Boolean
        get() = questPinLayerManager.isVisible
        set(value) { questPinLayerManager.isVisible = value }

    /* ---------------------------------  Selected quest pins ----------------------------------- */

    private fun createQuestSelectionMarker(): Marker? {
        val ctx = context ?: return null

        val frame = ctx.resources.getBitmapDrawable(R.drawable.quest_selection_ring)
        val w = frame.intrinsicWidth.toFloat().toDp(ctx)
        val h = frame.intrinsicHeight.toFloat().toDp(ctx)

        val marker = controller?.addMarker() ?: return null
        marker.setStylingFromString("""
        {
            style: 'quest-selection',
            color: 'white',
            size: [${w}px, ${h}px],
            flat: false,
            collide: false,
            offset: ['0px', '-38px']
        }""".trimIndent())
        marker.setDrawable(frame)
        return marker
    }

    private fun showQuestSelectionMarkers(positions: Collection<LatLon>) {
        while (positions.size > questSelectionMarkers.size) {
            val marker = createQuestSelectionMarker() ?: return
            questSelectionMarkers.add(marker)
        }
        positions.forEachIndexed { index, pos ->
            val marker = questSelectionMarkers[index]
            marker.setPoint(pos)
            marker.isVisible = true
        }
    }

    private fun hideQuestSelectionMarkers() {
        questSelectionMarkers.forEach { it.isVisible = false }
    }

    private fun putSelectedQuestPins(quest: Quest) {
        val questIconName = resources.getResourceEntryName(quest.type.icon)
        val positions = quest.markerLocations
        val points = positions.map { position ->
            val properties = mapOf(
                "type" to "point",
                "kind" to questIconName
            )
            Point(position.toLngLat(), properties)
        }
        selectedQuestPinsLayer?.setFeatures(points)
    }

    private fun removeSelectedQuestPins() {
        selectedQuestPinsLayer?.clear()
    }

    /* ------------------------------  Geometry for current quest ------------------------------- */

    private fun putQuestGeometry(geometry: ElementGeometry) {
        geometryLayer?.setFeatures(geometry.toTangramGeometry())
    }

    private fun removeQuestGeometry() {
        geometryLayer?.clear()
    }

    /* -------------------------  Markers for current quest (split way) ------------------------- */

    fun putMarkerForCurrentQuest(pos: LatLon, @DrawableRes drawableResId: Int) {
        pointMarkersMapComponent?.put(pos, drawableResId)
    }

    fun deleteMarkerForCurrentQuest(pos: LatLon) {
        pointMarkersMapComponent?.delete(pos)
    }

    fun clearMarkersForCurrentQuest() {
        pointMarkersMapComponent?.clear()
    }

    /* --------------------------------- Position tracking -------------------------------------- */

    override fun shouldCenterCurrentPosition(): Boolean {
        // don't center position while displaying a quest
        return super.shouldCenterCurrentPosition() && cameraPositionBeforeShowingQuest == null
    }

    companion object {
        // see streetcomplete.yaml for the definitions of the below layers
        private const val GEOMETRY_LAYER = "streetcomplete_geometry"
        private const val QUESTS_LAYER = "streetcomplete_quests"
        private const val SELECTED_QUESTS_LAYER = "streetcomplete_selected_quests"
        private const val CLICK_AREA_SIZE_IN_DP = 48
    }
}
