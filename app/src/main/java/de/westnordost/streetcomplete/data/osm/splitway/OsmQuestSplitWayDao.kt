package de.westnordost.streetcomplete.data.osm.splitway

import android.database.Cursor
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.content.contentValuesOf
import de.westnordost.streetcomplete.data.ObjectRelationalMapping
import de.westnordost.streetcomplete.data.quest.QuestTypeRegistry
import de.westnordost.streetcomplete.data.osm.osmquest.OsmElementQuestType
import de.westnordost.streetcomplete.data.osm.splitway.OsmQuestSplitWayTable.NAME
import de.westnordost.streetcomplete.data.osm.splitway.OsmQuestSplitWayTable.Columns.QUEST_ID
import de.westnordost.streetcomplete.data.osm.splitway.OsmQuestSplitWayTable.Columns.QUEST_TYPE
import de.westnordost.streetcomplete.data.osm.splitway.OsmQuestSplitWayTable.Columns.SOURCE
import de.westnordost.streetcomplete.data.osm.splitway.OsmQuestSplitWayTable.Columns.SPLITS
import de.westnordost.streetcomplete.data.osm.splitway.OsmQuestSplitWayTable.Columns.WAY_ID
import de.westnordost.streetcomplete.ktx.*
import de.westnordost.streetcomplete.util.Serializer
import javax.inject.Inject
import kotlin.collections.ArrayList

class OsmQuestSplitWayDao @Inject constructor(
/** Stores OsmQuestSplitWay objects by quest ID - the solutions of "differs along the way" quest
 *  answers. */
    private val dbHelper: SQLiteOpenHelper,
    private val mapping: OsmQuestSplitWayMapping
) {
    private val db get() = dbHelper.writableDatabase

    fun getAll(): List<OsmQuestSplitWay> {
        return db.query(NAME) { mapping.toObject(it) }
    }

    fun get(questId: Long): OsmQuestSplitWay? {
        val selection = "$QUEST_ID = ?"
        val args = arrayOf(questId.toString())
        return db.queryOne(NAME, null, selection, args) { mapping.toObject(it) }
    }

    fun getCount(): Int {
        return db.queryOne(NAME, arrayOf("COUNT(*)")) { it.getInt(0) } ?: 0
    }

    fun put(quest: OsmQuestSplitWay) {
        db.replaceOrThrow(NAME, null, mapping.toContentValues(quest))
    }

    fun delete(questId: Long) {
        db.delete(NAME, "$QUEST_ID = $questId", null)
    }
}

class OsmQuestSplitWayMapping @Inject constructor(
    private val serializer: Serializer,
    private val questTypeList: QuestTypeRegistry
) : ObjectRelationalMapping<OsmQuestSplitWay> {

    override fun toContentValues(obj: OsmQuestSplitWay) = contentValuesOf(
        QUEST_ID to obj.questId,
        QUEST_TYPE to obj.questType.javaClass.simpleName,
        WAY_ID to obj.wayId,
        SOURCE to obj.source,
        SPLITS to serializer.toBytes(ArrayList(obj.splits))
    )

    override fun toObject(cursor: Cursor)= OsmQuestSplitWay(
            cursor.getLong(QUEST_ID),
            questTypeList.getByName(cursor.getString(QUEST_TYPE)) as OsmElementQuestType<*>,
            cursor.getLong(WAY_ID),
            cursor.getString(SOURCE),
            (serializer.toObject(cursor.getBlob(SPLITS)) as ArrayList<SplitPolylineAtPosition>)
    )
}
