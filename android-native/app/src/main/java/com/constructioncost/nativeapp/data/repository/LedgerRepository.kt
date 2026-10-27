package com.constructioncost.nativeapp.data.repository

import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import android.util.AtomicFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import com.constructioncost.nativeapp.data.local.LedgerDatabase
import com.constructioncost.nativeapp.data.local.LedgerRecoveryStateStore
import com.constructioncost.nativeapp.data.local.PreservingSQLiteOpenHelperFactory
import com.constructioncost.nativeapp.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

// Cursor ordering must match SQLite BINARY rather than Kotlin's UTF-16 string ordering.
private val sqliteBinaryTextOrder = Comparator<String> { left, right ->
    val a = left.toByteArray(Charsets.UTF_8)
    val b = right.toByteArray(Charsets.UTF_8)
    var comparison = 0
    var index = 0
    while (comparison == 0 && index < minOf(a.size, b.size)) {
        comparison = (a[index].toInt() and 255).compareTo(b[index].toInt() and 255)
        index++
    }
    if (comparison == 0) a.size.compareTo(b.size) else comparison
}

data class SnapshotFinancials(val recordedPaise: BigInteger, val paidPaise: BigInteger, val outstandingPaise: BigInteger)
data class SnapshotInfo(val counts: Map<String, Int>, val attachments: List<AttachmentInfo>, val financials: SnapshotFinancials? = null)
internal data class ProjectDeletionFile(val id: String, val storageName: String, val size: Long, val sha256: String)
internal data class ProjectDeletionJournal(val projectId: String, val phase: String, val files: List<ProjectDeletionFile>)
internal data class ProjectDeletionInventory(val summary: ProjectDeletionSummary, val files: List<AttachmentInfo>)

class LedgerRepository internal constructor(private val database: LedgerDatabase, private val context: Context) {
    internal var replacementCheckpoint: (String) -> Unit = {}
    private var restoreRevisionWatermark = -1L
    private val restoreRevisionFile by lazy {
        database.openHelper.databaseName?.let { AtomicFile(File(context.getDatabasePath(it).path + ".restore-revision")) }
    }
    constructor(context: Context) : this(Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, LedgerRecoveryStateStore.DATABASE_NAME).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).openHelperFactory(PreservingSQLiteOpenHelperFactory(context.applicationContext)).addMigrations(LedgerDatabase.MIGRATION_1_2, LedgerDatabase.MIGRATION_2_3, LedgerDatabase.MIGRATION_3_4, LedgerDatabase.MIGRATION_4_5, LedgerDatabase.MIGRATION_5_6).addCallback(LedgerDatabase.DURABILITY_CALLBACK).build(), context.applicationContext)
    private val dao get() = database.reads()
    private val sql get() = database.openHelper.writableDatabase
    private fun query(text: String, vararg args: Any?) = SimpleSQLiteQuery(text, args)
    private fun rows(text: String, vararg args: Any?): List<JSONObject> = sql.query(query(text, *args)).use { c ->
        buildList { while (c.moveToNext()) add(JSONObject().also { row ->
            c.columnNames.forEachIndexed { i, name -> row.put(name, when (c.getType(i)) {
                android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                android.database.Cursor.FIELD_TYPE_STRING -> c.getString(i)
                else -> error("Unexpected SQLite type for $name")
            }) }
        }) }
    }
    private fun row(table: String, id: String): JSONObject? = rows("SELECT * FROM $table WHERE id=?", id).singleOrNull()?.also { normalize(table, it) }
    private fun scalar(text: String, vararg args: Any?): Long = rows(text, *args).first().getLong("value")
    private fun insert(table: String, value: JSONObject) {
        validateRecord(table, value)
        ensureWritableOwner(table, value, null)
        val values = ContentValues()
        value.keys().forEach { key -> when (val v = value.get(key)) {
            JSONObject.NULL -> values.putNull(key)
            is String -> values.put(key, v)
            is Boolean -> values.put(key, if (v) 1 else 0)
            is Int -> values.put(key, v)
            is Long -> values.put(key, v)
            else -> error("Unsupported value for $key")
        } }
        sql.insert(table, android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values)
    }
    private fun update(table: String, value: JSONObject) {
        validateRecord(table, value)
        val old = row(table, value.getString("id")) ?: error("Record no longer exists")
        ensureWritableOwner(table, value, old)
        val changes = value.keys().asSequence().filter { it != "id" }.toList()
        sql.execSQL("UPDATE $table SET ${changes.joinToString { "$it=?" }} WHERE id=?", (changes.map { key ->
            when (val v = value.get(key)) { JSONObject.NULL -> null; is Boolean -> if (v) 1 else 0; else -> v }
        } + old.getString("id")).toTypedArray())
    }
    private fun json(vararg fields: Pair<String, Any?>) = JSONObject().also { j -> fields.forEach { (k, v) -> j.put(k, v ?: JSONObject.NULL) } }
    private fun audit(table: String, id: String, action: String, before: JSONObject?, after: JSONObject?) {
        val owner = auditProject(table, id, action, before?.toString() ?: "null", after?.toString() ?: "null")
        owner?.let(::requireProjectWritable)
        fun evidence(value: JSONObject?) = value?.let { JSONObject(it.toString()).apply { if (owner != null) put("_auditProjectId", owner) }.toString() } ?: "null"
        insert("audit_records", json("id" to newId(), "recordType" to table, "recordId" to id, "action" to action, "beforeJson" to evidence(before), "afterJson" to evidence(after), "createdAt" to System.currentTimeMillis()))
    }
    private fun auditProject(table: String, recordId: String, action: String, before: String, after: String): String? {
        val evidence = listOf(before, after).filter { it != "null" }.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
        val explicit = evidence.mapNotNull { it.nullable("_auditProjectId") ?: it.nullable("projectId") }.distinct()
        if (explicit.isNotEmpty()) return explicit.singleOrNull()
        if (table == "projects") return recordId
        val parent = when (table) {
            "expense_lines", "classification_allocations" -> "expenses" to if (action == "REPLACE") recordId else
                (evidence.firstNotNullOfOrNull { it.nullable("expenseId") } ?: row(table, recordId)?.nullable("expenseId"))
            "payment_allocations" -> "payments" to (evidence.firstNotNullOfOrNull { it.nullable("paymentId") }
                ?: if (action in setOf("ALLOCATE", "CORRECT")) recordId else row(table, recordId)?.nullable("paymentId"))
            "attachment_links" -> "attachments" to (evidence.firstNotNullOfOrNull { it.nullable("attachmentId") } ?: row(table, recordId)?.nullable("attachmentId"))
            "reminders" -> "instalments" to (evidence.firstNotNullOfOrNull { it.nullable("instalmentId") } ?: row(table, recordId)?.nullable("instalmentId"))
            "planned_classifications" -> {
                if (action == "REPLACE") {
                    val column = evidence.map { it.optString("parentColumn") }.distinct().singleOrNull()
                    when (column) { "commitmentId" -> "commitments" to recordId; "instalmentId" -> "instalments" to recordId; else -> return null }
                } else {
                    val existing = row(table, recordId)
                    (evidence.firstNotNullOfOrNull { it.nullable("commitmentId") } ?: existing?.nullable("commitmentId"))?.let { "commitments" to it }
                        ?: ("instalments" to (evidence.firstNotNullOfOrNull { it.nullable("instalmentId") } ?: existing?.nullable("instalmentId")))
                }
            }
            else -> if (table in setOf("budgets", "classifications", "vendors", "commitments", "instalments", "expenses", "payments", "attachments", "activity_entries")) table to recordId else return null
        }
        return parent.second?.let { row(parent.first, it)?.nullable("projectId") }
    }
    private fun ensureWritableOwner(table: String, value: JSONObject, before: JSONObject?) {
        val owner = if (table == "audit_records") auditProject(value.getString("recordType"), value.getString("recordId"), value.getString("action"), value.getString("beforeJson"), value.getString("afterJson"))
        else value.nullable("projectId") ?: auditProject(table, value.getString("id"), "SAVE", before?.toString() ?: "null", value.toString())
        owner?.let(::requireProjectWritable)
    }
    private fun requireProjectWritable(projectId: String) {
        check(scalar("SELECT COUNT(*) value FROM project_deletions WHERE projectId=?", projectId) == 0L) { "Project deletion is in progress" }
    }
    private fun save(table: String, next: JSONObject, action: String = "SAVE") {
        val id = next.getString("id"); val before = row(table, id)
        if (before == null) insert(table, next) else update(table, next)
        audit(table, id, action, before, next)
    }
    private fun revision(before: JSONObject?, expected: Long?): Long {
        if (before == null) { require(expected == null || expected == 0L) { "Revision conflict" }; return 0 }
        require(expected != null && expected == before.getLong("revision")) { "Revision conflict; reload this record" }
        return Math.addExact(expected, 1)
    }
    private fun id(value: String) { require(value.isNotBlank() && value.length <= 200 && value.none { it.isISOControl() }) { "Invalid identifier" } }
    private fun text(value: String) { require(value.isNotBlank() && value.length <= 10000) { "Enter a name or description" } }
    private fun invoiceNumber(value: String) { require(value.length <= 200 && value.none(Char::isISOControl)) { "Invalid invoice number" } }
    private fun date(value: String) { require(LocalDate.parse(value).toString() == value) { "Use an ISO date" } }
    private fun positive(value: Long) { require(value > 0) { "Amount must be positive" } }
    private fun project(value: String) { require(row("projects", value) != null) { "Project missing" } }
    private fun related(table: String, value: String?, projectId: String, vendorId: String? = null): JSONObject? {
        if (value == null) return null
        val target = row(table, value) ?: error("Missing $table record")
        require(target.getString("projectId") == projectId) { "References must belong to one project" }
        if (vendorId != null && target.has("vendorId") && !target.isNull("vendorId")) require(target.getString("vendorId") == vendorId) { "Vendor mismatch" }
        return target
    }
    private suspend fun <T> write(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        var original: Throwable? = null
        try {
            database.withTransaction {
                try { block() } catch (failure: Throwable) { original = failure; throw failure }
            }
        } catch (failure: Throwable) {
            val cause = original ?: failure
            // SQLITE_FULL can roll back automatically; Room's second rollback must not hide it.
            if (cause !== failure) cause.addSuppressed(failure)
            if (cause is android.database.sqlite.SQLiteFullException) throw IllegalStateException("Not enough storage space.", cause)
            throw cause
        }
    }
    private fun aggregateCheck(projectId: String) {
        // SQLite integer SUM fails on overflow, rolling back the complete command and its audit.
        listOf("expenses", "commitments", "instalments").forEach { table -> scalar("SELECT COALESCE(SUM(amountPaise),0) value FROM $table WHERE projectId=?", projectId) }
        // Bound gross original cash so signed sums stay exact; corrections must not consume that capacity again.
        scalar("SELECT COALESCE(SUM(amountPaise),0) value FROM payments WHERE projectId=? AND kind='PAYMENT'", projectId)
    }

    val projects: Flow<List<ProjectInfo>> get() = dao.projects(query("SELECT p.*,b.amountPaise budgetPaise FROM projects p LEFT JOIN budgets b ON b.projectId=p.id WHERE NOT EXISTS(SELECT 1 FROM project_deletions d WHERE d.projectId=p.id) ORDER BY p.archived,p.name,p.id"))
    // Kept for explicit disposable fixtures; application startup never seeds a default ledger.
    suspend fun ensureDefaultProject() = write { if (scalar("SELECT COUNT(*) value FROM projects") == 0L) save("projects", json("id" to "default-project", "name" to "My House Construction", "revision" to 0L, "archived" to false)) }
    internal suspend fun projectDeletionInventory(projectId: String): ProjectDeletionInventory = withContext(Dispatchers.IO) {
        database.withTransaction {
            val project = row("projects", projectId) ?: error("Project no longer exists")
            check(scalar("SELECT COUNT(*) value FROM project_deletions WHERE projectId=?", projectId) == 0L) { "Project deletion is already in progress" }
            val files = rows("SELECT * FROM attachments WHERE projectId=? ORDER BY id", projectId).map { normalize("attachments", it); attachmentInfo(it) }
            val recordCount = listOf("classifications", "vendors", "commitments", "instalments", "expenses", "payments", "activity_entries")
                .fold(0L) { total, table -> Math.addExact(total, scalar("SELECT COUNT(*) value FROM $table WHERE projectId=?", projectId)) }
            ProjectDeletionInventory(ProjectDeletionSummary(projectId, project.getString("name"), recordCount, files.size.toLong(),
                files.fold(0L) { total, file -> Math.addExact(total, file.size) }), files)
        }
    }
    internal suspend fun beginProjectDeletion(projectId: String): ProjectDeletionJournal = write {
        val existing = projectDeletionJournalsInternal().firstOrNull { it.projectId == projectId }
        if (existing != null) return@write existing
        project(projectId)
        val crossProjectLink = scalar("""SELECT COUNT(*) value FROM attachment_links l JOIN attachments a ON a.id=l.attachmentId
            LEFT JOIN expenses e ON e.id=l.expenseId LEFT JOIN payments p ON p.id=l.paymentId LEFT JOIN activity_entries n ON n.id=l.noteId
            WHERE a.projectId=? AND ((e.projectId IS NOT NULL AND e.projectId<>?) OR (p.projectId IS NOT NULL AND p.projectId<>?) OR (n.projectId IS NOT NULL AND n.projectId<>?))""",
            projectId, projectId, projectId, projectId)
        check(crossProjectLink == 0L) { "Project contains a document linked to another project" }
        val files = rows("SELECT id,storageName,size,sha256 FROM attachments WHERE projectId=? ORDER BY id", projectId).map { row ->
            ProjectDeletionFile(row.getString("id"), row.getString("storageName"), row.getLong("size"), row.getString("sha256"))
        }
        val encoded = JSONArray().also { json -> files.forEach { json.put(JSONObject().put("id", it.id).put("storageName", it.storageName).put("size", it.size).put("sha256", it.sha256)) } }.toString()
        val values = ContentValues().apply { put("projectId", projectId); put("phase", "PREPARED"); put("filesJson", encoded); put("startedAt", System.currentTimeMillis()) }
        check(sql.insert("project_deletions", android.database.sqlite.SQLiteDatabase.CONFLICT_ABORT, values) != -1L) { "Cannot record project deletion" }
        ProjectDeletionJournal(projectId, "PREPARED", files)
    }
    internal suspend fun projectDeletionJournals(): List<ProjectDeletionJournal> = withContext(Dispatchers.IO) { projectDeletionJournalsInternal() }
    private fun projectDeletionJournalsInternal(): List<ProjectDeletionJournal> =
        rows("SELECT * FROM project_deletions ORDER BY startedAt,projectId").map { row ->
            val files = JSONArray(row.getString("filesJson")).let { array -> (0 until array.length()).map { index ->
                array.getJSONObject(index).let { ProjectDeletionFile(it.getString("id"), it.getString("storageName"), it.getLong("size"), it.getString("sha256")) }
            } }
            ProjectDeletionJournal(row.getString("projectId"), row.getString("phase"), files)
        }
    internal suspend fun markProjectDeletionQuarantined(projectId: String) = write {
        sql.execSQL("UPDATE project_deletions SET phase='QUARANTINED' WHERE projectId=? AND phase='PREPARED'", arrayOf(projectId))
        check(scalar("SELECT changes() value") == 1L) { "Project deletion journal changed" }
    }
    internal suspend fun deleteProjectRows(projectId: String) = write {
        check(scalar("SELECT COUNT(*) value FROM project_deletions WHERE projectId=? AND phase='QUARANTINED'", projectId) == 1L) { "Project attachments are not safely quarantined" }
        sql.execSQL("PRAGMA defer_foreign_keys=ON")
        val attachments = "SELECT id FROM attachments WHERE projectId=?"
        val expenses = "SELECT id FROM expenses WHERE projectId=?"
        val payments = "SELECT id FROM payments WHERE projectId=?"
        val instalments = "SELECT id FROM instalments WHERE projectId=?"
        val commitments = "SELECT id FROM commitments WHERE projectId=?"
        val classifications = "SELECT id FROM classifications WHERE projectId=?"
        sql.execSQL("DELETE FROM attachment_links WHERE attachmentId IN ($attachments)", arrayOf(projectId))
        sql.execSQL("DELETE FROM reminders WHERE instalmentId IN ($instalments)", arrayOf(projectId))
        sql.execSQL("DELETE FROM planned_classifications WHERE classificationId IN ($classifications) OR commitmentId IN ($commitments) OR instalmentId IN ($instalments)", arrayOf(projectId, projectId, projectId))
        sql.execSQL("DELETE FROM payment_allocations WHERE paymentId IN ($payments) OR expenseId IN ($expenses)", arrayOf(projectId, projectId))
        sql.execSQL("DELETE FROM classification_allocations WHERE expenseId IN ($expenses) OR classificationId IN ($classifications)", arrayOf(projectId, projectId))
        sql.execSQL("DELETE FROM expense_lines WHERE expenseId IN ($expenses)", arrayOf(projectId))
        val owner = json("_auditProjectId" to projectId).toString().removePrefix("{").removeSuffix("}")
        val recordOwner = json("projectId" to projectId).toString().removePrefix("{").removeSuffix("}")
        sql.execSQL("DELETE FROM audit_records WHERE instr(beforeJson,?)>0 OR instr(afterJson,?)>0 OR instr(beforeJson,?)>0 OR instr(afterJson,?)>0 OR (recordType='projects' AND recordId=?)", arrayOf(owner, owner, recordOwner, recordOwner, projectId))
        listOf("payments", "expenses", "instalments", "commitments", "vendors", "classifications", "attachments", "activity_entries", "budgets").forEach { table ->
            sql.execSQL("DELETE FROM $table WHERE projectId=?", arrayOf(projectId))
        }
        check(sql.delete("projects", "id=?", arrayOf(projectId)) == 1) { "Project no longer exists" }
        sql.execSQL("UPDATE project_deletions SET phase='ROWS_DELETED' WHERE projectId=? AND phase='QUARANTINED'", arrayOf(projectId))
        check(scalar("SELECT changes() value") == 1L) { "Project deletion journal changed" }
    }
    internal suspend fun completeProjectDeletion(projectId: String) = write {
        check(sql.delete("project_deletions", "projectId=? AND phase='ROWS_DELETED'", arrayOf(projectId)) == 1) { "Project rows are not deleted" }
    }
    fun classifications(projectId: String): Flow<List<ClassificationInfo>> = dao.classifications(query("SELECT * FROM classifications WHERE projectId=? ORDER BY sortOrder,name", projectId))
    fun vendors(projectId: String): Flow<List<VendorInfo>> = dao.vendors(query("$SETTLEMENT SELECT v.*,COALESCE((SELECT SUM(e.amountPaise-COALESCE(s.amount,0)) FROM expenses e LEFT JOIN settlement s ON s.expenseId=e.id WHERE e.vendorId=v.id AND NOT e.voided),0) outstandingPaise FROM vendors v WHERE v.projectId=? ORDER BY v.name", projectId))
    fun commitments(projectId: String, includeArchived: Boolean = false): Flow<List<CommitmentInfo>> = dao.commitments(query("SELECT * FROM commitments WHERE projectId=? AND (? OR NOT archived) ORDER BY date DESC,id", projectId, if (includeArchived) 1 else 0))
    fun summary(projectId: String, today: LocalDate = LocalDate.now()): Flow<LedgerSummary> = dao.summary(query("""$SETTLEMENT,
        cash AS (SELECT COALESCE(SUM(CASE kind WHEN 'PAYMENT' THEN amountPaise ELSE -amountPaise END),0) total FROM payments WHERE projectId=?),
        costs AS (SELECT COALESCE(SUM(e.amountPaise),0) total,COALESCE(SUM(COALESCE(s.amount,0)),0) settled FROM expenses e LEFT JOIN settlement s ON s.expenseId=e.id WHERE e.projectId=? AND NOT e.voided)
        SELECT costs.total recordedPaise,cash.total paidPaise,costs.total-costs.settled outstandingPaise,cash.total-costs.settled advancePaise,
        (SELECT COALESCE(SUM(i.amountPaise-COALESCE((SELECT SUM(CASE p.kind WHEN 'PAYMENT' THEN p.amountPaise ELSE -p.amountPaise END) FROM payments p WHERE p.instalmentId=i.id),0)),0) FROM instalments i WHERE i.projectId=? AND NOT i.cancelled AND i.dueDate BETWEEN ? AND ?) upcomingPaise,
        (SELECT amountPaise FROM budgets WHERE projectId=?) budgetPaise,
        (SELECT COALESCE(SUM(amountPaise),0) FROM commitments WHERE projectId=? AND NOT archived) commitmentPaise FROM costs,cash""", projectId, projectId, projectId, today.toString(), today.plusDays(30).toString(), projectId, projectId))
    private data class ExpenseWhere(val tree: String, val clause: String, val args: List<Any?>)
    private fun expenseWhere(projectId: String, filter: ExpenseFilter): ExpenseWhere {
        val args = mutableListOf<Any?>(projectId)
        val conditions = mutableListOf("e.projectId=?")
        if (filter.query.isNotBlank()) {
            conditions += "(e.description LIKE ? OR e.note LIKE ? OR e.invoiceNumber LIKE ? OR v.name LIKE ? OR EXISTS(SELECT 1 FROM expense_lines l WHERE l.expenseId=e.id AND (l.description LIKE ? OR l.legacyJson LIKE ? OR l.nature LIKE ?)) OR EXISTS(SELECT 1 FROM classification_allocations a JOIN classifications c ON c.id=a.classificationId WHERE a.expenseId=e.id AND c.name LIKE ?))"
            repeat(8) { args += "%${filter.query}%" }
        }
        if (filter.fromDate.isNotBlank()) { date(filter.fromDate); conditions += "e.date>=?"; args += filter.fromDate }
        if (filter.toDate.isNotBlank()) { date(filter.toDate); conditions += "e.date<=?"; args += filter.toDate }
        filter.vendorId?.let { conditions += "e.vendorId=?"; args += it }
        if (filter.missingVendor) conditions += "e.vendorId IS NULL"
        if (filter.postedOnly) conditions += "NOT e.voided"
        filter.costNature?.let { nature ->
            require(nature in COST_NATURES || nature == "Unclassified / Legacy") { "Unknown cost nature" }
            conditions += "(EXISTS(SELECT 1 FROM expense_lines l WHERE l.expenseId=e.id AND COALESCE(NULLIF(l.nature,''),'Unclassified / Legacy')=?) OR (?='Unclassified / Legacy' AND NOT EXISTS(SELECT 1 FROM expense_lines l WHERE l.expenseId=e.id)))"
            args += nature.ifBlank { "Unclassified / Legacy" }; args += nature.ifBlank { "Unclassified / Legacy" }
        }
        filter.materialName?.let {
            conditions += "(EXISTS(SELECT 1 FROM expense_lines l WHERE l.expenseId=e.id AND COALESCE(NULLIF(trim(l.description),''),NULLIF(trim(e.description),''),'Unnamed item')=?) OR (NOT EXISTS(SELECT 1 FROM expense_lines l WHERE l.expenseId=e.id) AND COALESCE(NULLIF(trim(e.description),''),'Unnamed item')=?))"
            args += it; args += it
        }
        filter.classificationId?.let {
            if (filter.directClassification) { conditions += "EXISTS(SELECT 1 FROM classification_allocations a WHERE a.expenseId=e.id AND a.classificationId=?)"; args += it }
            else { conditions += "EXISTS(SELECT 1 FROM classification_allocations a WHERE a.expenseId=e.id AND a.classificationId IN (SELECT id FROM tree))"; args.add(0, it) }
        }
        conditions += when (filter.status) { "UNPAID" -> "NOT e.voided AND COALESCE(s.amount,0)=0"; "OUTSTANDING" -> "NOT e.voided AND COALESCE(s.amount,0)<e.amountPaise"; "PARTIAL" -> "NOT e.voided AND COALESCE(s.amount,0)>0 AND s.amount<e.amountPaise"; "PAID" -> "NOT e.voided AND s.amount=e.amountPaise"; "VOIDED" -> "e.voided"; "UNCLASSIFIED", "UNCLASSIFIED_WORK", "UNCLASSIFIED_PLACE" -> "NOT e.voided AND NOT EXISTS(SELECT 1 FROM classification_allocations a WHERE a.expenseId=e.id AND a.dimension='${when (filter.status) { "UNCLASSIFIED_WORK" -> "WORK"; "UNCLASSIFIED_PLACE" -> "PLACE"; else -> "COST" }}')"; "ALL" -> "1"; else -> error("Unknown expense status") }
        val tree = if (filter.classificationId == null || filter.directClassification) "" else ", tree(id) AS (SELECT id FROM classifications WHERE id=? UNION ALL SELECT c.id FROM classifications c JOIN tree t ON c.parentId=t.id)"
        return ExpenseWhere(tree, conditions.joinToString(" AND "), args)
    }
    fun expenses(projectId: String, filter: ExpenseFilter = ExpenseFilter(), limit: Int = 75, offset: Int = 0): Flow<List<ExpenseRow>> {
        require(limit > 0 && offset >= 0)
        val where = expenseWhere(projectId, filter)
        val order = when (filter.sort) { "DATE_DESC" -> "e.date DESC,e.id"; "DATE_ASC" -> "e.date,e.id"; "AMOUNT_DESC" -> "e.amountPaise DESC,e.id"; "AMOUNT_ASC" -> "e.amountPaise,e.id"; else -> error("Unknown sort") }
        return dao.expenses(query("${SETTLEMENT.replace("WITH ", "WITH RECURSIVE ")}${where.tree} SELECT e.id,e.projectId,e.description,e.amountPaise,e.date,e.vendorId,COALESCE(v.name,'') vendorName,COALESCE(s.amount,0) settledPaise,e.amountPaise-COALESCE(s.amount,0) outstandingPaise,e.note,e.mode,e.revision,e.voided,e.invoiceNumber FROM expenses e LEFT JOIN vendors v ON v.id=e.vendorId LEFT JOIN settlement s ON s.expenseId=e.id WHERE ${where.clause} ORDER BY $order LIMIT ? OFFSET ?", *(where.args + limit + offset).toTypedArray()))
    }
    fun filteredExpenseSummary(projectId: String, filter: ExpenseFilter = ExpenseFilter()): Flow<ExpenseSummary> {
        val where = expenseWhere(projectId, filter)
        return dao.filteredExpenseSummary(query("${SETTLEMENT.replace("WITH ", "WITH RECURSIVE ")}${where.tree} SELECT COUNT(*) count,COALESCE(SUM(CASE WHEN NOT e.voided THEN e.amountPaise ELSE 0 END),0) recordedPaise,COALESCE(SUM(CASE WHEN NOT e.voided THEN COALESCE(s.amount,0) ELSE 0 END),0) settledPaise,COALESCE(SUM(CASE WHEN NOT e.voided THEN e.amountPaise-COALESCE(s.amount,0) ELSE 0 END),0) outstandingPaise FROM expenses e LEFT JOIN vendors v ON v.id=e.vendorId LEFT JOIN settlement s ON s.expenseId=e.id WHERE ${where.clause}", *where.args.toTypedArray()))
    }
    private fun expenseRow(item: JSONObject) = ExpenseRow(id = item.getString("id"), projectId = item.getString("projectId"), description = item.getString("description"), amountPaise = item.getLong("amountPaise"), date = item.getString("date"), vendorId = item.nullable("vendorId"), vendorName = item.getString("vendorName"), settledPaise = item.getLong("settledPaise"), outstandingPaise = item.getLong("outstandingPaise"), note = item.getString("note"), mode = item.getString("mode"), revision = item.getLong("revision"), voided = item.getLong("voided") != 0L, invoiceNumber = item.getString("invoiceNumber"))
    suspend fun expenseRow(id: String): ExpenseRow? = withContext(Dispatchers.IO) {
        rows("$SETTLEMENT SELECT e.id,e.projectId,e.description,e.amountPaise,e.date,e.vendorId,COALESCE(v.name,'') vendorName,COALESCE(s.amount,0) settledPaise,e.amountPaise-COALESCE(s.amount,0) outstandingPaise,e.note,e.mode,e.revision,e.voided,e.invoiceNumber FROM expenses e LEFT JOIN vendors v ON v.id=e.vendorId LEFT JOIN settlement s ON s.expenseId=e.id WHERE e.id=? LIMIT 1", id).firstOrNull()?.let { item ->
            expenseRow(item)
        }
    }
    suspend fun invoiceDuplicate(projectId: String, invoiceNumber: String, vendorId: String?, excludeId: String? = null): List<ExpenseRow> = withContext(Dispatchers.IO) {
        id(projectId); this@LedgerRepository.invoiceNumber(invoiceNumber); vendorId?.let(::id); excludeId?.let(::id)
        if (invoiceNumber.isBlank()) emptyList() else rows("""WITH matching AS (
            SELECT id FROM expenses WHERE projectId=? AND vendorId IS ? AND NOT voided AND lower(trim(invoiceNumber))=lower(trim(?)) AND id IS NOT ? ORDER BY date DESC,id LIMIT 5),
            settlement AS (SELECT a.expenseId,SUM(CASE p.kind WHEN 'PAYMENT' THEN a.amountPaise ELSE -a.amountPaise END) amount FROM payment_allocations a JOIN matching m ON m.id=a.expenseId JOIN payments p ON p.id=a.paymentId GROUP BY a.expenseId)
            SELECT e.id,e.projectId,e.description,e.amountPaise,e.date,e.vendorId,COALESCE(v.name,'') vendorName,COALESCE(s.amount,0) settledPaise,e.amountPaise-COALESCE(s.amount,0) outstandingPaise,e.note,e.mode,e.revision,e.voided,e.invoiceNumber
            FROM matching m JOIN expenses e ON e.id=m.id LEFT JOIN vendors v ON v.id=e.vendorId LEFT JOIN settlement s ON s.expenseId=e.id ORDER BY e.date DESC,e.id""", projectId, vendorId, invoiceNumber.trim(), excludeId).map(::expenseRow)
    }
    fun payments(projectId: String): Flow<List<PaymentRow>> = dao.payments(query("SELECT p.id,p.projectId,p.amountPaise,p.date,p.vendorId,COALESCE(v.name,'') vendorName,p.reference,p.kind,p.originalPaymentId,COALESCE((SELECT SUM(a.amountPaise) FROM payment_allocations a WHERE a.paymentId=p.id),0) allocatedPaise,p.instalmentId,p.note FROM payments p LEFT JOIN vendors v ON v.id=p.vendorId WHERE p.projectId=? ORDER BY p.date DESC,p.id", projectId))
    fun instalments(projectId: String): Flow<List<InstalmentRow>> = dao.instalments(query("WITH paid AS (SELECT instalmentId,SUM(CASE kind WHEN 'PAYMENT' THEN amountPaise ELSE -amountPaise END) total FROM payments GROUP BY instalmentId) SELECT i.id,i.projectId,i.title,i.amountPaise,i.dueDate,i.commitmentId,i.vendorId,COALESCE(p.total,0) paidPaise,i.amountPaise-COALESCE(p.total,0) outstandingPaise,i.cancelled,i.remind,i.revision,i.note AS note,COALESCE(r.leadDays,3) reminderDays FROM instalments i LEFT JOIN paid p ON p.instalmentId=i.id LEFT JOIN reminders r ON r.instalmentId=i.id WHERE i.projectId=? ORDER BY i.dueDate,i.id", projectId))
    fun attachments(projectId: String): Flow<List<AttachmentInfo>> = dao.attachments(query("SELECT a.*,(SELECT COUNT(*) FROM attachment_links l WHERE l.attachmentId=a.id) referenceCount FROM attachments a WHERE a.projectId=? ORDER BY a.createdAt DESC", projectId))
    fun activity(projectId: String, limit: Int = 75, offset: Int = 0, fromDate: String = "", toDate: String = "", includeExpenses: Boolean = true,
                 query: String = "", recordType: String? = null, sortByRecordedTime: Boolean = false, expenseFilter: ExpenseFilter? = null, settlementStatus: String = "ALL", before: ActivityCursor? = null): Flow<List<ActivityRow>> {
        require(limit > 0 && offset >= 0)
        require(before == null || sortByRecordedTime && offset == 0)
        if (fromDate.isNotBlank()) date(fromDate)
        if (toDate.isNotBlank()) date(toDate)
        require(fromDate.isBlank() || toDate.isBlank() || fromDate <= toDate)
        require(query.length <= 200) { "Activity search is too long" }
        require(recordType == null || recordType in setOf("EXPENSE", "PAYMENT", "REFUND", "REVERSAL", "NOTE", "INSTALMENT", "COMMITMENT", "ATTACHMENT")) { "Unknown activity type" }
        require(settlementStatus in setOf("ALL", "OVERDUE", "SETTLED"))
        fun recorded(table: String, alias: String) = if (sortByRecordedTime)
            "(SELECT MIN(a.createdAt) FROM audit_records a WHERE a.recordId=$alias.id AND a.recordType='$table' AND a.action='SAVE')"
            else "NULL"
        val recordedTime = "COALESCE(event.recordedAt,CAST(strftime('%s',event.date) AS INTEGER)*1000)"
        val order = if (sortByRecordedTime) "$recordedTime DESC,event.type,event.id" else "event.date DESC,event.id,event.type"
        val cursorCondition = if (before == null) "" else " AND ($recordedTime<? OR ($recordedTime=? AND (event.type>? OR (event.type=? AND event.id>?))))"
        val cursorArgs = before?.let { listOf<Any?>(it.recordedAt,it.recordedAt,it.type,it.type,it.id) }.orEmpty()
        val needle = "%${query.trim()}%"
        val matching = expenseFilter?.let { expenseWhere(projectId,it) }
        val prefix = matching?.let { "${SETTLEMENT.replace("WITH ", "WITH RECURSIVE ")}${it.tree}, matchingExpenses AS (SELECT e.id FROM expenses e LEFT JOIN vendors v ON v.id=e.vendorId LEFT JOIN settlement s ON s.expenseId=e.id WHERE ${it.clause}) " }.orEmpty()
        val expenseCondition = if(matching==null) "" else " AND (event.type<>'EXPENSE' OR event.id IN (SELECT id FROM matchingExpenses))"
        val statusCondition = when(settlementStatus) {
            "OVERDUE" -> " AND event.type='INSTALMENT' AND event.date<date('now','localtime') AND event.amountPaise>0 AND EXISTS(SELECT 1 FROM instalments i WHERE i.id=event.id AND NOT i.cancelled)"
            "SETTLED" -> " AND (event.type='EXPENSE' OR (event.type='INSTALMENT' AND event.amountPaise=0 AND EXISTS(SELECT 1 FROM instalments i WHERE i.id=event.id AND NOT i.cancelled)))"
            else -> ""
        }
        return dao.activity(this.query("""${prefix}SELECT event.id AS id,event.type AS type,event.title AS title,event.date AS date,event.amountPaise AS amountPaise,COALESCE(event.recordedAt,CAST(strftime('%s',event.date) AS INTEGER)*1000) AS recordedAt,event.vendorName AS party FROM (
            SELECT e.id AS id,'EXPENSE' type,e.description title,e.date AS date,e.amountPaise AS amountPaise,COALESCE(v.name,'') vendorName,${recorded("expenses", "e")} recordedAt
                FROM expenses e LEFT JOIN vendors v ON v.id=e.vendorId WHERE e.projectId=? AND ?
            UNION ALL SELECT p.id,p.kind,CASE WHEN p.reference='' THEN p.kind ELSE p.reference END,p.date,CASE p.kind WHEN 'PAYMENT' THEN p.amountPaise ELSE -p.amountPaise END,COALESCE(v.name,''),${recorded("payments", "p")}
                FROM payments p LEFT JOIN vendors v ON v.id=p.vendorId WHERE p.projectId=?
            UNION ALL SELECT n.id,'NOTE',n.title,n.date,NULL,'',${recorded("activity_entries", "n")} FROM activity_entries n WHERE n.projectId=?
            UNION ALL SELECT i.id,'INSTALMENT',CASE WHEN i.cancelled THEN i.title || ' (cancelled)' ELSE i.title END,i.dueDate,
                CASE WHEN i.cancelled THEN 0 ELSE i.amountPaise-COALESCE((SELECT SUM(CASE p.kind WHEN 'PAYMENT' THEN p.amountPaise ELSE -p.amountPaise END) FROM payments p WHERE p.instalmentId=i.id),0) END,
                COALESCE(v.name,''),${recorded("instalments", "i")} FROM instalments i LEFT JOIN vendors v ON v.id=i.vendorId WHERE i.projectId=?
            UNION ALL SELECT c.id,'COMMITMENT',c.title,c.date,c.amountPaise,COALESCE(v.name,''),${recorded("commitments", "c")}
                FROM commitments c LEFT JOIN vendors v ON v.id=c.vendorId WHERE c.projectId=?
            UNION ALL SELECT d.id,'ATTACHMENT',d.name,date(d.createdAt/1000,'unixepoch','localtime'),NULL,'',${recorded("attachments", "d")} FROM attachments d WHERE d.projectId=?
            ) event WHERE (?='' OR event.date>=?) AND (?='' OR event.date<=?)
                AND (?='' OR event.title LIKE ? OR event.vendorName LIKE ? OR event.type LIKE ? ${if(matching!=null) "OR event.type='EXPENSE'" else ""})
                AND (? IS NULL OR event.type=?) $expenseCondition $statusCondition $cursorCondition ORDER BY $order LIMIT ? OFFSET ?""",
            *((matching?.args.orEmpty()) + listOf(projectId, if (includeExpenses) 1 else 0, projectId, projectId, projectId, projectId, projectId,
            fromDate, fromDate, toDate, toDate, query.trim(), needle, needle, needle, recordType, recordType) + cursorArgs + listOf(limit, offset)).toTypedArray()))
    }
    fun calendarDayCounts(projectId: String, fromDate: String, toDate: String): Flow<List<CalendarDayCount>> {
        date(fromDate); date(toDate)
        require(fromDate <= toDate)
        return dao.calendarDayCounts(query("""SELECT date,COUNT(*) eventCount FROM (
            SELECT date FROM expenses WHERE projectId=? AND date BETWEEN ? AND ?
            UNION ALL SELECT date FROM payments WHERE projectId=? AND date BETWEEN ? AND ?
            UNION ALL SELECT date FROM activity_entries WHERE projectId=? AND date BETWEEN ? AND ?
            UNION ALL SELECT dueDate FROM instalments WHERE projectId=? AND dueDate BETWEEN ? AND ?
            UNION ALL SELECT date FROM commitments WHERE projectId=? AND date BETWEEN ? AND ?
            UNION ALL SELECT date(createdAt/1000,'unixepoch','localtime') FROM attachments WHERE projectId=? AND date(createdAt/1000,'unixepoch','localtime') BETWEEN ? AND ?
            ) GROUP BY date""", projectId, fromDate, toDate, projectId, fromDate, toDate,
            projectId, fromDate, toDate, projectId, fromDate, toDate, projectId, fromDate, toDate,
            projectId, fromDate, toDate))
    }
    fun insights(projectId: String, parentId: String? = null, dimension: String = "WORK"): Flow<List<InsightRow>> {
        if (dimension == "SUPPLIER") return dao.insights(query("""SELECT e.vendorId AS id,
            COALESCE(v.name,'Unknown supplier') AS name,'SUPPLIER' AS kind,SUM(e.amountPaise) AS amountPaise,
            COUNT(*) AS expenseCount,NULL AS parentId FROM expenses e LEFT JOIN vendors v ON v.id=e.vendorId
            WHERE e.projectId=? AND NOT e.voided GROUP BY e.vendorId ORDER BY amountPaise DESC,name,id""", projectId))
        if (dimension == "MATERIAL") return dao.insights(query("""WITH material_lines AS (
            SELECT e.id AS expenseId,COALESCE(NULLIF(trim(l.description),''),NULLIF(trim(e.description),''),'Unnamed item') AS name,
                l.amountPaise AS amountPaise FROM expense_lines l JOIN expenses e ON e.id=l.expenseId WHERE e.projectId=? AND NOT e.voided
            UNION ALL SELECT e.id,COALESCE(NULLIF(trim(e.description),''),'Unnamed item'),e.amountPaise FROM expenses e
                WHERE e.projectId=? AND NOT e.voided AND NOT EXISTS(SELECT 1 FROM expense_lines l WHERE l.expenseId=e.id))
            SELECT name AS id,name AS name,'MATERIAL' AS kind,SUM(amountPaise) AS amountPaise,
                COUNT(DISTINCT expenseId) AS expenseCount,NULL AS parentId FROM material_lines GROUP BY name ORDER BY amountPaise DESC,name""", projectId, projectId))
        require(dimension in setOf("WORK", "COST", "PLACE"))
        return dao.insights(query("""WITH RECURSIVE visible AS (SELECT * FROM classifications WHERE projectId=? AND parentId IS ? AND $DIMENSION=?),
            tree(root,id) AS (SELECT id,id FROM visible UNION ALL SELECT t.root,c.id FROM tree t JOIN classifications c ON c.parentId=t.id),
            totals AS (SELECT t.root,SUM(a.amountPaise) amount,COUNT(DISTINCT e.id) count FROM tree t JOIN classification_allocations a ON a.classificationId=t.id JOIN expenses e ON e.id=a.expenseId WHERE NOT e.voided AND a.dimension=? GROUP BY t.root)
            SELECT v.id AS id,v.name AS name,v.kind AS kind,COALESCE(t.amount,0) amountPaise,COALESCE(t.count,0) expenseCount,v.parentId AS parentId FROM visible v LEFT JOIN totals t ON t.root=v.id
            UNION ALL SELECT NULL,'Unclassified',?,COALESCE(SUM(e.amountPaise),0),COUNT(*),NULL FROM expenses e WHERE ? IS NULL AND e.projectId=? AND NOT e.voided AND NOT EXISTS(SELECT 1 FROM classification_allocations a WHERE a.expenseId=e.id AND a.dimension=?)
            UNION ALL SELECT c.id,'Direct: '||c.name,'DIRECT_ALLOCATION',SUM(a.amountPaise),COUNT(DISTINCT e.id),c.parentId
            FROM classifications c JOIN classification_allocations a ON a.classificationId=c.id JOIN expenses e ON e.id=a.expenseId
            WHERE c.id=? AND c.projectId=? AND a.dimension=? AND NOT e.voided GROUP BY c.id""", projectId, parentId, dimension, dimension, dimension, parentId, projectId, dimension, parentId, projectId, dimension))
    }
    fun costNature(projectId: String): Flow<List<CostNatureRow>> = dao.costNature(query("""SELECT nature,SUM(amountPaise) amountPaise FROM (
        SELECT COALESCE(NULLIF(l.nature,''),'Unclassified / Legacy') nature,l.amountPaise FROM expense_lines l JOIN expenses e ON e.id=l.expenseId WHERE e.projectId=? AND NOT e.voided
        UNION ALL SELECT 'Unclassified / Legacy',e.amountPaise FROM expenses e WHERE e.projectId=? AND NOT e.voided AND NOT EXISTS(SELECT 1 FROM expense_lines l WHERE l.expenseId=e.id)
        ) GROUP BY nature ORDER BY amountPaise DESC,nature""", projectId, projectId))
    fun plannedInsights(projectId: String, classificationId: String? = null, limit: Int = 75, offset: Int = 0): Flow<List<PlannedRow>> {
        require(limit in 1..500 && offset >= 0)
        return dao.plannedInsights(query("""WITH RECURSIVE tree(id) AS (
        SELECT id FROM classifications WHERE id=? AND projectId=? UNION ALL SELECT c.id FROM classifications c JOIN tree t ON c.parentId=t.id)
        SELECT type,id,title,amountPaise,date,vendorId FROM (
        SELECT 'COMMITMENT' type,c.id,c.title,c.amountPaise,c.date,c.vendorId FROM commitments c WHERE c.projectId=? AND NOT c.archived AND (? IS NULL OR EXISTS(SELECT 1 FROM planned_classifications p JOIN tree t ON t.id=p.classificationId WHERE p.commitmentId=c.id))
        UNION ALL SELECT 'INSTALMENT',i.id,i.title,i.amountPaise-COALESCE((SELECT SUM(CASE p.kind WHEN 'PAYMENT' THEN p.amountPaise ELSE -p.amountPaise END) FROM payments p WHERE p.instalmentId=i.id),0),i.dueDate,i.vendorId FROM instalments i WHERE i.projectId=? AND NOT i.cancelled AND i.amountPaise>COALESCE((SELECT SUM(CASE p.kind WHEN 'PAYMENT' THEN p.amountPaise ELSE -p.amountPaise END) FROM payments p WHERE p.instalmentId=i.id),0) AND (? IS NULL OR EXISTS(SELECT 1 FROM planned_classifications p JOIN tree t ON t.id=p.classificationId WHERE p.instalmentId=i.id))
        ) ORDER BY date,id LIMIT ? OFFSET ?""", classificationId, projectId, projectId, classificationId, projectId, classificationId, limit, offset))
    }
    suspend fun plannedClassifications(type: String, id: String): List<String> = withContext(Dispatchers.IO) {
        val target = when (type) { "COMMITMENT" -> "commitmentId"; "INSTALMENT" -> "instalmentId"; else -> error("Unknown planned record type") }
        rows("SELECT classificationId FROM planned_classifications WHERE $target=? ORDER BY classificationId", id).map { it.getString("classificationId") }
    }
    fun monthly(projectId: String): Flow<List<MonthlyRow>> = dao.monthly(query("SELECT month,SUM(recordedPaise) recordedPaise,SUM(paidPaise) paidPaise FROM (SELECT substr(date,1,7) month,amountPaise recordedPaise,0 paidPaise FROM expenses WHERE projectId=? AND NOT voided UNION ALL SELECT substr(date,1,7),0,CASE kind WHEN 'PAYMENT' THEN amountPaise ELSE -amountPaise END FROM payments WHERE projectId=?) GROUP BY month ORDER BY month", projectId, projectId))
    fun monthlyCash(projectId: String): Flow<List<MonthlyRow>> = dao.monthly(query("SELECT * FROM (SELECT substr(date,1,7) AS month,0 AS recordedPaise,SUM(CASE kind WHEN 'PAYMENT' THEN amountPaise ELSE -amountPaise END) AS paidPaise FROM payments WHERE projectId=? GROUP BY month ORDER BY month DESC LIMIT 12) ORDER BY month", projectId))
    fun audits(recordId: String, recordType: String, projectId: String): Flow<List<AuditInfo>> {
        require(recordType in setOf("expenses", "payments", "instalments", "commitments", "activity_entries", "attachments", "vendors", "classifications")) { "Unsupported audit owner" }
        val extras = when (recordType) {
            "expenses" -> " OR (a.recordType IN ('expense_lines','classification_allocations') AND a.action='REPLACE')"
            "payments" -> " OR (a.recordType='payment_allocations' AND ((a.action='ALLOCATE' AND a.id LIKE 'allocation:%') OR (a.action='CORRECT' AND a.id LIKE 'allocation-correction:%')))"
            "instalments", "commitments" -> " OR (a.recordType='planned_classifications' AND a.action='REPLACE')"
            else -> ""
        }
        return dao.audits(query("SELECT a.* FROM audit_records a WHERE a.recordId=? AND (a.recordType=?$extras) AND EXISTS(SELECT 1 FROM $recordType owner WHERE owner.id=? AND owner.projectId=?) ORDER BY a.createdAt DESC,a.id", recordId, recordType, recordId, projectId)).map { entries ->
            withContext(Dispatchers.IO) { entries.filter {
                auditProject(it.recordType, it.recordId, it.action, it.beforeJson, it.afterJson) == projectId &&
                    (it.recordType != "planned_classifications" || plannedAuditMatches(it.beforeJson, it.afterJson, if (recordType == "commitments") "commitmentId" else "instalmentId"))
            } }
        }
    }
    fun projectAudits(projectId: String, search: String = "", fromDate: String = "", toDate: String = "", limit: Int = 75, before: ActivityCursor? = null): Flow<List<AuditInfo>> {
        id(projectId)
        require(search.length <= 200 && limit > 0)
        if (fromDate.isNotBlank()) date(fromDate)
        if (toDate.isNotBlank()) date(toDate)
        require(fromDate.isBlank() || toDate.isBlank() || fromDate <= toDate)
        val needle = "%${search.trim()}%"
        val auditOwner = json("_auditProjectId" to projectId).toString().removePrefix("{").removeSuffix("}")
        val recordOwner = json("projectId" to projectId).toString().removePrefix("{").removeSuffix("}")
        val cursorCondition = if (before == null) "" else " AND (createdAt<? OR (createdAt=? AND ('AUDIT'>? OR ('AUDIT'=? AND id>?))))"
        val cursorArgs = before?.let { listOf(it.recordedAt,it.recordedAt,it.type,it.type,it.id) }.orEmpty()
        return dao.audits(query("""SELECT * FROM audit_records WHERE
            (instr(beforeJson,?)>0 OR instr(afterJson,?)>0
                OR instr(beforeJson,?)>0 OR instr(afterJson,?)>0
                OR (recordType='projects' AND recordId=?)
                OR (recordType='payment_allocations' AND ((action='ALLOCATE' AND id LIKE 'allocation:%')
                    OR (action='CORRECT' AND id LIKE 'allocation-correction:%'))
                    AND recordId IN (SELECT id FROM payments WHERE projectId=?)))
            AND NOT (recordType='classifications' AND action='SAVE' AND beforeJson='null'
                AND (instr(recordId,?)=1 OR instr(recordId,?)=1 OR instr(recordId,?)=1))
            AND NOT (recordType='projects' AND recordId='default-project' AND action='SAVE' AND beforeJson='null')
            AND (?='' OR recordType LIKE ? OR action LIKE ? OR beforeJson LIKE ? OR afterJson LIKE ?)
            AND (?='' OR date(createdAt/1000,'unixepoch','localtime')>=?)
            AND (?='' OR date(createdAt/1000,'unixepoch','localtime')<=?)
            $cursorCondition ORDER BY createdAt DESC,id LIMIT ?""",*(listOf<Any?>(auditOwner,auditOwner,recordOwner,recordOwner,projectId,projectId,"$projectId:cost-","$projectId:work:","$projectId:place:",
            search.trim(),needle,needle,needle,needle,fromDate,fromDate,toDate,toDate) + cursorArgs + limit).toTypedArray())).map { records ->
            withContext(Dispatchers.IO) { records.filter { auditProject(it.recordType,it.recordId,it.action,it.beforeJson,it.afterJson)==projectId } }
        }
    }
    fun journalRevision(): Flow<Long> = dao.journalRevision(query("SELECT COALESCE(MAX(rowid),0) FROM audit_records"))

    fun journal(projectId: String, filter: ExpenseFilter, recordType: String = "ALL", includeAudits: Boolean = true,
                settlementStatus: String = "ALL", limit: Int = 76, before: ActivityCursor? = null): Flow<List<JournalRow>> {
        require(limit in 1..500)
        val events = if (recordType == "AUDIT") flowOf(emptyList()) else activity(projectId, limit,
            fromDate = filter.fromDate, toDate = filter.toDate, query = filter.query,
            recordType = recordType.takeUnless { it == "ALL" }, sortByRecordedTime = true,
            expenseFilter = filter, settlementStatus = settlementStatus, before = before)
        val audits = if (includeAudits && recordType in setOf("ALL", "AUDIT"))
            projectAudits(projectId, filter.query, filter.fromDate, filter.toDate, limit, before) else flowOf(emptyList())
        return combine(events, audits) { records, changes ->
            (records.map { JournalRow(it) } + changes.map { audit -> JournalRow(ActivityRow(audit.id, "AUDIT",
                "${audit.action.lowercase().replaceFirstChar { it.uppercase() }} · ${audit.recordType.replace('_',' ')}",
                java.time.Instant.ofEpochMilli(audit.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                recordedAt = audit.createdAt), audit) })
                .sortedWith(compareByDescending<JournalRow> { it.event.recordedAt }.thenBy { it.event.type }
                    .thenBy(sqliteBinaryTextOrder) { it.event.id })
                .take(limit)
        }
    }
    private fun plannedAuditMatches(before: String, after: String, parentColumn: String): Boolean {
        val targetType = if (parentColumn == "commitmentId") "COMMITMENT" else "INSTALMENT"
        return listOf(before, after).all { value ->
            runCatching { JSONObject(value) }.getOrNull()?.let { envelope ->
                envelope.optString("parentColumn") == parentColumn && envelope.optString("targetType") == targetType
            } == true
        }
    }
    fun notes(projectId: String): Flow<List<ActivityNote>> = dao.notes(query("SELECT * FROM activity_entries WHERE projectId=? ORDER BY date DESC,id", projectId))

    suspend fun saveProject(info: ProjectInfo, requireExisting: Boolean = false) = write {
        id(info.id); text(info.name); require(info.budgetPaise == null || info.budgetPaise >= 0)
        val before = row("projects", info.id)
        require(!requireExisting || before != null) { "Project no longer exists" }
        save("projects", json("id" to info.id, "name" to info.name, "revision" to revision(before, info.revision), "archived" to info.archived))
        val budget = row("budgets", info.id)
        if (info.budgetPaise == null) { if (budget != null) { sql.execSQL("DELETE FROM budgets WHERE id=?", arrayOf(info.id)); audit("budgets", info.id, "DELETE", budget, null) } }
        else save("budgets", json("id" to info.id, "projectId" to info.id, "amountPaise" to info.budgetPaise))
    }
    suspend fun saveVendor(info: VendorInfo) = write {
        id(info.id); project(info.projectId); text(info.name)
        row("vendors", info.id)?.let { require(it.getString("projectId") == info.projectId) }
        save("vendors", json("id" to info.id, "projectId" to info.projectId, "name" to info.name, "note" to info.note))
    }
    private fun requireUniqueClassificationName(info: ClassificationInfo) {
        if (info.archived) return
        val siblings = rows("SELECT id,name FROM classifications WHERE projectId=? AND kind=? AND parentId IS ? AND NOT archived AND id<>?", info.projectId, info.kind, info.parentId, info.id)
        require(siblings.none { it.getString("name").trim().equals(info.name.trim(), ignoreCase = true) }) { "A classification with this name already exists here" }
    }
    suspend fun saveClassification(info: ClassificationInfo, expected: ClassificationInfo? = null) = write {
        id(info.id); project(info.projectId); text(info.name); require(info.kind in KINDS)
        val before = row("classifications", info.id)
        before?.let { require(it.getString("projectId") == info.projectId && it.getString("kind") == info.kind) { "Classification type and project are immutable" } }
        if (before == null) require(expected == null) { "Classification changed; reload this record" }
        else require(expected == ClassificationInfo(before.getString("id"), before.getString("projectId"), before.getString("name"), before.getString("kind"), before.nullable("parentId"), before.getInt("sortOrder"), before.getBoolean("archived"), before.getString("nature"))) { "Classification changed; reload this record" }
        val parent = related("classifications", info.parentId, info.projectId)
        checkParent(info.kind, parent?.getString("kind"))
        var next = info.parentId; val visited = mutableSetOf(info.id)
        while (next != null) { require(visited.add(next)) { "Classification cycle" }; next = row("classifications", next)?.nullable("parentId") }
        requireUniqueClassificationName(info)
        save("classifications", json("id" to info.id, "projectId" to info.projectId, "name" to info.name, "kind" to info.kind, "parentId" to info.parentId, "sortOrder" to info.sortOrder, "archived" to info.archived, "nature" to info.nature))
    }
    suspend fun seedClassifications(projectId: String, nodes: List<ClassificationInfo>) = write {
        project(projectId)
        // shortcut: existing classifications block default seeding, add a catalog migration when defaults change.
        if (scalar("SELECT COUNT(*) value FROM classifications WHERE projectId=?", projectId) > 0L) return@write
        require(nodes.all { it.projectId == projectId } && nodes.map { it.id }.distinct().size == nodes.size)
        val pending = nodes.toMutableList()
        while (pending.isNotEmpty()) {
            val ready = pending.filter { it.parentId == null || row("classifications", it.parentId) != null }
            require(ready.isNotEmpty()) { "Invalid or cyclic classification catalog" }
            ready.forEach { info ->
                id(info.id); text(info.name); require(info.kind in KINDS)
                val parent = related("classifications", info.parentId, projectId)
                checkParent(info.kind, parent?.getString("kind"))
                require(row("classifications", info.id) == null) { "Classification already exists" }
                requireUniqueClassificationName(info)
                save("classifications", json("id" to info.id, "projectId" to projectId, "name" to info.name, "kind" to info.kind, "parentId" to info.parentId, "sortOrder" to info.sortOrder, "archived" to info.archived, "nature" to info.nature))
            }
            pending.removeAll(ready.toSet())
        }
    }
    suspend fun mergeClassification(sourceId: String, targetId: String) = remap(sourceId, targetId, true)
    suspend fun reassignClassification(sourceId: String, targetId: String) = remap(sourceId, targetId, false)
    private suspend fun remap(sourceId: String, targetId: String, archive: Boolean) = write {
        require(sourceId != targetId)
        val source = row("classifications", sourceId) ?: error("Source missing")
        val target = related("classifications", targetId, source.getString("projectId"))!!
        require(dimension(source.getString("kind")) == dimension(target.getString("kind"))) { "Cannot move between dimensions" }
        require(!target.getBoolean("archived")) { "Target classification is archived" }
        if (archive) require(scalar("SELECT COUNT(*) value FROM classifications WHERE parentId=?", sourceId) == 0L) { "Reparent source children before merging this classification" }
        val expenses = mutableSetOf<String>()
        var lastAllocation = ""
        while (true) {
            val batch = rows("SELECT * FROM classification_allocations WHERE classificationId=? AND id>? ORDER BY id LIMIT 32", sourceId, lastAllocation)
            if (batch.isEmpty()) break
            batch.forEach { a ->
                lastAllocation = a.getString("id")
                expenses += a.getString("expenseId")
                val matching = rows("SELECT * FROM classification_allocations WHERE expenseId=? AND classificationId=? AND dimension=?", a.getString("expenseId"), targetId, a.getString("dimension")).singleOrNull()
                audit("classification_allocations", a.getString("id"), "DELETE", a, null)
                sql.execSQL("DELETE FROM classification_allocations WHERE id=?", arrayOf(a.getString("id")))
                if (matching == null) save("classification_allocations", JSONObject(a.toString()).put("classificationId", targetId), "REASSIGN")
                else save("classification_allocations", matching.put("amountPaise", Math.addExact(matching.getLong("amountPaise"), a.getLong("amountPaise"))), "MERGE")
                audit("classifications", sourceId, "MAP", a, json("classificationId" to targetId, "expenseId" to a.getString("expenseId"), "amountPaise" to a.getLong("amountPaise")))
            }
        }
        var lastLine = ""
        while (true) {
            val batch = rows("SELECT l.* FROM expense_lines l JOIN expenses e ON e.id=l.expenseId WHERE e.projectId=? AND l.id>? AND l.legacyJson<>'' ORDER BY l.id LIMIT 8", source.getString("projectId"), lastLine)
            if (batch.isEmpty()) break
            batch.forEach { line ->
                lastLine = line.getString("id")
                val evidence = runCatching { JSONObject(line.getString("legacyJson")) }.getOrNull() ?: return@forEach
                val classifications = evidence.optJSONObject("_nativeClassifications") ?: return@forEach
                val changed = classifications.keys().asSequence().filter { classifications.opt(it) == sourceId }.toList()
                if (changed.isNotEmpty()) {
                    changed.forEach { classifications.put(it, targetId) }
                    save("expense_lines", line.put("legacyJson", evidence.toString()), "RECLASSIFY")
                    expenses += line.getString("expenseId")
                }
            }
        }
        expenses.forEach { expenseId ->
            val expense = row("expenses", expenseId)!!
            save("expenses", expense.put("revision", Math.addExact(expense.getLong("revision"), 1)), "RECLASSIFY")
        }
        if (archive) {
            val commitments = mutableSetOf<String>()
            val instalments = mutableSetOf<String>()
            var lastPlanned = ""
            while (true) {
                val batch = rows("SELECT * FROM planned_classifications WHERE classificationId=? AND id>? ORDER BY id LIMIT 32", sourceId, lastPlanned)
                if (batch.isEmpty()) break
                batch.forEach { planned ->
                    lastPlanned = planned.getString("id")
                    val commitmentId = planned.nullable("commitmentId")
                    val instalmentId = planned.nullable("instalmentId")
                    if (commitmentId != null) commitments += commitmentId
                    if (instalmentId != null) instalments += instalmentId
                    val duplicate = scalar("SELECT COUNT(*) value FROM planned_classifications WHERE classificationId=? AND commitmentId IS ? AND instalmentId IS ?", targetId, commitmentId, instalmentId) > 0
                    if (duplicate) {
                        audit("planned_classifications", planned.getString("id"), "DELETE", planned, null)
                        sql.execSQL("DELETE FROM planned_classifications WHERE id=?", arrayOf(planned.getString("id")))
                    } else save("planned_classifications", planned.put("classificationId", targetId), "MERGE")
                }
            }
            listOf("commitments" to commitments, "instalments" to instalments).forEach { (table, ids) -> ids.forEach { ownerId ->
                val owner = row(table, ownerId)!!
                save(table, owner.put("revision", Math.addExact(owner.getLong("revision"), 1)), "RECLASSIFY")
            } }
        }
        if (archive) save("classifications", source.put("archived", true), "MERGE")
    }

    private fun expenseRequest(input: ExpenseInput): JSONObject = json(
        "id" to input.id, "idempotencyKey" to input.idempotencyKey, "projectId" to input.projectId, "description" to input.description, "amountPaise" to input.amountPaise, "date" to input.date, "vendorId" to input.vendorId, "note" to input.note, "mode" to input.mode, "invoiceNumber" to input.invoiceNumber,
        "lines" to JSONArray(input.lines.map { line -> json("id" to line.id, "description" to line.description, "amountPaise" to line.amountPaise, "mode" to line.mode, "quantity" to line.quantity, "rate" to line.rate, "multiplier" to line.multiplier, "quantityUnit" to line.quantityUnit, "rateUnit" to line.rateUnit, "legacyJson" to line.legacyJson, "nature" to line.nature) }),
        "classifications" to JSONArray(input.classifications.map { json("classificationId" to it.classificationId, "dimension" to it.dimension, "amountPaise" to it.amountPaise) }), "attachmentIds" to JSONArray(input.attachmentIds), "paidPaise" to input.paidPaise, "paymentReference" to input.paymentReference, "commitmentId" to input.commitmentId, "instalmentId" to input.instalmentId, "revision" to input.revision, "calculation" to input.calculation?.let { calculationJson(it) })
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
    private fun retry(table: String, key: String, request: String): String? {
        id(key)
        val existing = rows("SELECT id,requestJson FROM $table WHERE idempotencyKey=?", key).singleOrNull() ?: return null
        require(existing.getString("requestJson") == request) { "Idempotency key was already used for different contents" }
        return existing.getString("id")
    }
    suspend fun saveExpense(input: ExpenseInput): String = write {
        val request = canonical(expenseRequest(input)); if (input.revision == null) retry("expenses", input.idempotencyKey, request)?.let { return@write it }
        id(input.id); project(input.projectId); text(input.description); invoiceNumber(input.invoiceNumber); positive(input.amountPaise); date(input.date); require(input.mode in MODES)
        if (input.mode == "CALCULATED") {
            val calculation = requireNotNull(input.calculation) { "Calculated expense needs its input values" }
            require(Money.calculate(calculation).totalPaise == input.amountPaise) { "Calculated total does not reconcile" }
        }
        related("vendors", input.vendorId, input.projectId); related("commitments", input.commitmentId, input.projectId, input.vendorId); related("instalments", input.instalmentId, input.projectId, input.vendorId)
        val before = row("expenses", input.id)
        require(before != null || input.revision == null) { "Saved expense no longer exists; discard this stale edit or create a new entry" }
        if (before != null) { require(before.getString("projectId") == input.projectId && !before.getBoolean("voided")); require(input.paidPaise == 0L) { "Post further cash as a separate payment" } }
        require(input.amountPaise >= settled(input.id)) { "Expense cannot be below settled funds" }
        val allocations = rows("SELECT p.vendorId FROM payment_allocations a JOIN payments p ON p.id=a.paymentId WHERE a.expenseId=?", input.id)
        require(allocations.all { input.vendorId == null || it.isNull("vendorId") || it.getString("vendorId") == input.vendorId }) { "Vendor conflicts with historical payment" }
        require(input.lines.map { it.id }.distinct().size == input.lines.size)
        if (input.lines.isNotEmpty()) require(Money.sum(input.lines.map { it.amountPaise }) == input.amountPaise) { "Lines must reconcile to expense total" }
        input.lines.forEach { line -> id(line.id); require(line.amountPaise >= 0 && line.mode in MODES && line.nature in COST_NATURES); if (line.mode == "CALCULATED" && line.legacyJson.isEmpty()) { require(line.quantity != null && line.rate != null); require(Money.calculate(line.quantity, line.rate, line.multiplier, quantityUnit = line.quantityUnit, rateUnit = line.rateUnit).totalPaise == line.amountPaise) { "Calculated line does not reconcile" } } }
        require(input.classifications.map { it.dimension to it.classificationId }.distinct().size == input.classifications.size)
        input.classifications.forEach { part -> positive(part.amountPaise); val c = related("classifications", part.classificationId, input.projectId)!!; require(part.dimension == dimension(c.getString("kind"))) }
        input.classifications.groupBy { it.dimension }.forEach { (_, parts) -> require(Money.sum(parts.map { it.amountPaise }) == input.amountPaise) { "Classification dimension must reconcile" } }
        val next = json("id" to input.id, "projectId" to input.projectId, "description" to input.description, "amountPaise" to input.amountPaise, "date" to input.date, "vendorId" to input.vendorId, "note" to input.note, "mode" to input.mode, "calculationJson" to (input.calculation?.let { calculationJson(it).toString() } ?: ""), "revision" to revision(before, input.revision), "voided" to false, "commitmentId" to input.commitmentId, "instalmentId" to input.instalmentId, "idempotencyKey" to (before?.getString("idempotencyKey") ?: input.idempotencyKey), "requestJson" to (before?.getString("requestJson") ?: request), "invoiceNumber" to input.invoiceNumber)
        save("expenses", next)
        replaceChildren("expense_lines", "expenseId", input.id, input.lines.map { json("id" to it.id, "expenseId" to input.id, "description" to it.description, "amountPaise" to it.amountPaise, "mode" to it.mode, "quantity" to it.quantity, "rate" to it.rate, "multiplier" to it.multiplier, "quantityUnit" to it.quantityUnit, "rateUnit" to it.rateUnit, "legacyJson" to it.legacyJson, "nature" to it.nature) })
        replaceChildren("classification_allocations", "expenseId", input.id, input.classifications.map { json("id" to newId(), "expenseId" to input.id, "classificationId" to it.classificationId, "dimension" to it.dimension, "amountPaise" to it.amountPaise) })
        rows("SELECT * FROM attachment_links WHERE expenseId=?", input.id).filter { it.getString("attachmentId") !in input.attachmentIds }
            .forEach(::unlink)
        input.attachmentIds.forEach { link(it, input.id, null, null) }
        require(input.paidPaise in 0..input.amountPaise)
        if (input.paidPaise > 0) savePaymentInternal(PaymentInput(id = "${input.id}-payment", idempotencyKey = "${input.idempotencyKey}-payment", projectId = input.projectId, amountPaise = input.paidPaise, date = input.date, vendorId = input.vendorId, reference = input.paymentReference, instalmentId = input.instalmentId, allocations = listOf(PaymentAllocationInput(input.id, input.paidPaise))))
        aggregateCheck(input.projectId); input.id
    }
    private fun replaceChildren(table: String, parent: String, id: String, next: List<JSONObject>) {
        val before = JSONArray(rows("SELECT * FROM $table WHERE $parent=?", id))
        sql.execSQL("DELETE FROM $table WHERE $parent=?", arrayOf(id)); next.forEach { insert(table, it) }
        val targetType = when (parent) { "expenseId" -> "EXPENSE"; "commitmentId" -> "COMMITMENT"; "instalmentId" -> "INSTALMENT"; else -> error("Unknown child owner") }
        audit(table, id, "REPLACE", json("parentColumn" to parent, "targetType" to targetType, "rows" to before),
            json("parentColumn" to parent, "targetType" to targetType, "rows" to JSONArray(next)))
    }
    private fun settled(expenseId: String): Long = scalar("$SETTLEMENT SELECT COALESCE((SELECT amount FROM settlement WHERE expenseId=?),0) value", expenseId)
    suspend fun voidExpense(id: String, revision: Long) = write {
        val before = row("expenses", id) ?: error("Expense missing"); require(!before.getBoolean("voided")); require(settled(id) == 0L) { "Reverse or refund settled payments before voiding" }
        val nextRevision = revision(before, revision); save("expenses", before.put("voided", true).put("revision", nextRevision), "VOID")
    }
    suspend fun deleteExpense(id: String, expectedRevision: Long) = write {
        val before = row("expenses",id) ?: error("Expense missing")
        require(!before.getBoolean("voided")) { "This bill was already deleted" }
        val nextRevision = revision(before,expectedRevision)
        rows("SELECT a.* FROM payment_allocations a JOIN payments p ON p.id=a.paymentId WHERE a.expenseId=? AND p.kind='PAYMENT'",id).forEach { allocation ->
            val keep = scalar("SELECT COALESCE(SUM(c.amountPaise),0) value FROM payment_allocations c JOIN payments correction ON correction.id=c.paymentId WHERE correction.originalPaymentId=? AND c.expenseId=?", allocation.getString("paymentId"), id)
            require(keep <= allocation.getLong("amountPaise")) { "Payment correction exceeds original allocation" }
            if (keep == 0L) {
                sql.execSQL("DELETE FROM payment_allocations WHERE id=?", arrayOf(allocation.getString("id")))
                audit("payment_allocations",allocation.getString("id"),"RELEASE",allocation,null)
            } else if (keep != allocation.getLong("amountPaise")) {
                save("payment_allocations", allocation.put("amountPaise", keep), "RELEASE")
            }
        }
        rows("SELECT * FROM attachment_links WHERE expenseId=?",id).forEach { attachment ->
            audit("attachment_links",attachment.getString("id"),"UNLINK",attachment,null)
        }
        sql.execSQL("DELETE FROM attachment_links WHERE expenseId=?",arrayOf(id))
        save("expenses",before.put("voided",true).put("revision",nextRevision),"DELETE")
        aggregateCheck(before.getString("projectId"))
    }
    private fun paymentRequest(input: PaymentInput) = json("id" to input.id, "idempotencyKey" to input.idempotencyKey, "projectId" to input.projectId, "amountPaise" to input.amountPaise, "date" to input.date, "vendorId" to input.vendorId, "reference" to input.reference, "note" to input.note, "instalmentId" to input.instalmentId, "allocations" to JSONArray(input.allocations.map { json("expenseId" to it.expenseId, "amountPaise" to it.amountPaise) }), "attachmentIds" to JSONArray(input.attachmentIds))
    suspend fun savePayment(input: PaymentInput): String = write { savePaymentInternal(input) }
    private fun paymentEditStateInternal(id: String): PaymentEditState {
        val original = row("payments", id) ?: error("Payment no longer exists.")
        require(original.getString("kind") == "PAYMENT") { "Only an original payment can be edited." }
        require(scalar("SELECT COUNT(*) value FROM payments WHERE originalPaymentId=?", id) == 0L) {
            "This payment has a refund or reversal. Open its history before making another correction."
        }
        val input = PaymentInput(id = id, projectId = original.getString("projectId"),
            amountPaise = original.getLong("amountPaise"), date = original.getString("date"),
            vendorId = original.nullable("vendorId"), reference = original.getString("reference"),
            note = original.getString("note"), instalmentId = original.nullable("instalmentId"),
            allocations = rows("SELECT expenseId,amountPaise FROM payment_allocations WHERE paymentId=? ORDER BY id", id)
                .map { PaymentAllocationInput(it.getString("expenseId"), it.getLong("amountPaise")) },
            attachmentIds = rows("SELECT attachmentId FROM attachment_links WHERE paymentId=? ORDER BY attachmentId", id)
                .map { it.getString("attachmentId") })
        // Project revisions advance on restore, including payments with no allocations.
        val evidence = canonical(json("payment" to original, "allocations" to allocationsFingerprint(id),
            "receipts" to JSONArray(input.attachmentIds), "projectRevision" to row("projects", input.projectId)!!.getLong("revision")))
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(evidence.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return PaymentEditState(input, fingerprint)
    }
    suspend fun paymentEditState(id: String): PaymentEditState = withContext(Dispatchers.IO) {
        database.withTransaction { paymentEditStateInternal(id) }
    }
    suspend fun replacePayment(originalId: String, expectedFingerprint: String, amountPaise: Long, date: String,
                               vendorId: String?, reference: String, note: String, reason: String,
                               idempotencyKey: String): String = write {
        id(idempotencyKey); text(reason)
        val operationId = "payment-edit:$idempotencyKey"
        val request = canonical(json("originalPaymentId" to originalId, "fingerprint" to expectedFingerprint,
            "amountPaise" to amountPaise, "date" to date, "vendorId" to vendorId,
            "reference" to reference, "note" to note, "reason" to reason))
        row("audit_records", operationId)?.let {
            require(it.getString("action") == "EDIT_PAYMENT" && it.getString("beforeJson") == request) {
                "Idempotency key was already used for different contents"
            }
            return@write JSONObject(it.getString("afterJson")).getString("replacementId")
        }
        val snapshot = paymentEditStateInternal(originalId)
        require(snapshot.fingerprint == expectedFingerprint) { "Payment changed; close this edit and reload the payment." }
        require(scalar("SELECT COUNT(*) value FROM payments WHERE idempotencyKey=?", idempotencyKey) == 0L) {
            "Idempotency key was already used for another payment"
        }
        val old = snapshot.payment
        val allocations = if (old.allocations.size == 1 && old.allocations.single().amountPaise == old.amountPaise)
            listOf(old.allocations.single().copy(amountPaise = amountPaise)) else old.allocations
        // Reversing on the original date corrects monthly cash without rewriting posted history.
        val reversalId = correctPaymentInternal(originalId, null, reason, old.date)
        val replacement = old.copy(id = newId(), idempotencyKey = idempotencyKey, amountPaise = amountPaise,
            date = date, vendorId = vendorId, reference = reference, note = note, allocations = allocations)
        val replacementId = savePaymentInternal(replacement)
        insert("audit_records", json("id" to operationId, "recordType" to "payments", "recordId" to originalId,
            "action" to "EDIT_PAYMENT", "beforeJson" to request,
            "afterJson" to canonical(json("projectId" to old.projectId, "replacementId" to replacementId, "reversalId" to reversalId)),
            "createdAt" to System.currentTimeMillis()))
        audit("payments", replacementId, "REPLACES", null, json("projectId" to old.projectId, "originalPaymentId" to originalId))
        replacementId
    }
    private fun allocationsFingerprint(paymentId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte()); digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte()); digest.update(bytes.size.toByte()); digest.update(bytes)
        }
        field("payment-allocations-v1"); field(paymentId)
        sql.query(query("SELECT a.id,a.expenseId,a.amountPaise,e.revision FROM payment_allocations a JOIN expenses e ON e.id=a.expenseId WHERE a.paymentId=? ORDER BY a.id", paymentId)).use { cursor ->
            while (cursor.moveToNext()) { field(cursor.getString(0)); field(cursor.getString(1)); field(cursor.getLong(2).toString()); field(cursor.getLong(3).toString()) }
        }
        // Operation IDs are append-only. Including them detects A-to-B-to-A edits with identical final rows.
        sql.query(query("SELECT id FROM audit_records WHERE recordType='payment_allocations' AND recordId=? AND ((action='ALLOCATE' AND id LIKE 'allocation:%') OR (action='CORRECT' AND id LIKE 'allocation-correction:%')) ORDER BY id", paymentId)).use { cursor ->
            while (cursor.moveToNext()) field(cursor.getString(0))
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
    suspend fun allocationCorrectionState(allocationId: String): AllocationCorrectionState? = withContext(Dispatchers.IO) {
        database.withTransaction {
            val allocation = row("payment_allocations", allocationId) ?: return@withTransaction null
            val paymentId = allocation.getString("paymentId")
            AllocationCorrectionState(allocationId, paymentId, allocation.getString("expenseId"), allocation.getLong("amountPaise"), allocationsFingerprint(paymentId))
        }
    }
    suspend fun paymentAllocationStates(paymentId: String, limit: Int = 200, offset: Int = 0): List<AllocationCorrectionState> = withContext(Dispatchers.IO) {
        require(limit in 1..500 && offset >= 0)
        database.withTransaction {
            require(row("payments", paymentId) != null) { "Payment missing" }
            val fingerprint = allocationsFingerprint(paymentId)
            rows("SELECT * FROM payment_allocations WHERE paymentId=? ORDER BY id LIMIT ? OFFSET ?", paymentId, limit, offset).map {
                AllocationCorrectionState(it.getString("id"), paymentId, it.getString("expenseId"), it.getLong("amountPaise"), fingerprint)
            }
        }
    }
    suspend fun correctAllocation(allocationId: String, expectedFingerprint: String, amountPaise: Long, targetExpenseId: String?, idempotencyKey: String): String = write {
        id(idempotencyKey)
        val operationId = "allocation-correction:$idempotencyKey"
        val request = canonical(json("allocationId" to allocationId, "expectedFingerprint" to expectedFingerprint, "amountPaise" to amountPaise, "targetExpenseId" to targetExpenseId))
        row("audit_records", operationId)?.let { existing ->
            require(existing.getString("recordType") == "payment_allocations" && existing.getString("action") == "CORRECT" && existing.getString("beforeJson") == request) { "Idempotency key was already used for different contents" }
            return@write operationId
        }
        require(Regex("[0-9a-f]{64}").matches(expectedFingerprint)) { "Invalid allocation snapshot" }
        positive(amountPaise)
        val source = requireNotNull(row("payment_allocations", allocationId)) { "Allocation missing" }
        val paymentId = source.getString("paymentId")
        val payment = row("payments", paymentId) ?: error("Payment missing")
        require(payment.getString("kind") == "PAYMENT") { "Only original payment allocations can be corrected" }
        require(scalar("SELECT COUNT(*) value FROM payments WHERE originalPaymentId=?", paymentId) == 0L) { "Payment has refund or reversal history" }
        require(allocationsFingerprint(paymentId) == expectedFingerprint) { "Allocation changed; reload payment" }
        require(amountPaise <= source.getLong("amountPaise")) { "Correction exceeds allocation" }
        val projectId = payment.getString("projectId")
        val sourceExpense = related("expenses", source.getString("expenseId"), projectId, payment.nullable("vendorId"))!!
        require(!sourceExpense.getBoolean("voided")) { "Source expense is voided" }
        val target = targetExpenseId?.let {
            require(it != source.getString("expenseId")) { "Choose another expense" }
            related("expenses", it, projectId, payment.nullable("vendorId"))!!.also { expense ->
                require(!expense.getBoolean("voided")) { "Target expense is voided" }
                require(Math.addExact(settled(it), amountPaise) <= expense.getLong("amountPaise")) { "Target expense would be overpaid" }
            }
        }
        val remainder = Math.subtractExact(source.getLong("amountPaise"), amountPaise)
        if (remainder == 0L) {
            sql.execSQL("DELETE FROM payment_allocations WHERE id=?", arrayOf(allocationId))
            audit("payment_allocations", allocationId, "DELETE", source, null)
        } else save("payment_allocations", source.put("amountPaise", remainder), "CORRECT")
        if (target != null) {
            val targetId = target.getString("id")
            val existing = rows("SELECT * FROM payment_allocations WHERE paymentId=? AND expenseId=?", paymentId, targetId).singleOrNull()
            if (existing == null) save("payment_allocations", json("id" to newId(), "paymentId" to paymentId, "expenseId" to targetId, "amountPaise" to amountPaise), "CORRECT")
            else save("payment_allocations", existing.put("amountPaise", Math.addExact(existing.getLong("amountPaise"), amountPaise)), "CORRECT")
        }
        insert("audit_records", json("id" to operationId, "recordType" to "payment_allocations", "recordId" to paymentId, "action" to "CORRECT", "beforeJson" to request,
            "afterJson" to canonical(json("sourceExpenseId" to sourceExpense.getString("id"), "targetExpenseId" to targetExpenseId, "sourceRemainderPaise" to remainder, "amountPaise" to amountPaise)), "createdAt" to System.currentTimeMillis()))
        aggregateCheck(projectId)
        operationId
    }
    suspend fun allocateAdvance(paymentId: String, expenseId: String, amountPaise: Long, idempotencyKey: String) = write {
        id(idempotencyKey)
        val operationId = "allocation:$idempotencyKey"
        val request = canonical(json("paymentId" to paymentId, "expenseId" to expenseId, "amountPaise" to amountPaise))
        row("audit_records", operationId)?.let { require(it.getString("beforeJson") == request) { "Idempotency key was already used for different contents" }; return@write }
        positive(amountPaise)
        val payment = row("payments", paymentId) ?: error("Payment missing")
        require(payment.getString("kind") == "PAYMENT") { "Only a payment can be allocated" }
        val expense = related("expenses", expenseId, payment.getString("projectId"), payment.nullable("vendorId"))!!
        require(!expense.getBoolean("voided")) { "Expense is voided" }
        require(payment.nullable("vendorId") == null || expense.nullable("vendorId") == null || payment.getString("vendorId") == expense.getString("vendorId")) { "Vendor mismatch" }
        val allocated = scalar("SELECT COALESCE(SUM(amountPaise),0) value FROM payment_allocations WHERE paymentId=?", paymentId)
        val correctedCash = scalar("SELECT COALESCE(SUM(amountPaise),0) value FROM payments WHERE originalPaymentId=?", paymentId)
        val correctedAllocation = scalar("SELECT COALESCE(SUM(a.amountPaise),0) value FROM payment_allocations a JOIN payments p ON p.id=a.paymentId WHERE p.originalPaymentId=?", paymentId)
        val available = Math.addExact(Math.subtractExact(Math.subtractExact(payment.getLong("amountPaise"), correctedCash), allocated), correctedAllocation)
        require(amountPaise <= available) { "Payment has insufficient advance" }
        require(Math.addExact(settled(expenseId), amountPaise) <= expense.getLong("amountPaise")) { "Expense overpayment" }
        val existing = rows("SELECT * FROM payment_allocations WHERE paymentId=? AND expenseId=?", paymentId, expenseId).singleOrNull()
        if (existing == null) save("payment_allocations", json("id" to newId(), "paymentId" to paymentId, "expenseId" to expenseId, "amountPaise" to amountPaise))
        else save("payment_allocations", existing.put("amountPaise", Math.addExact(existing.getLong("amountPaise"), amountPaise)))
        insert("audit_records", json("id" to operationId, "recordType" to "payment_allocations", "recordId" to paymentId, "action" to "ALLOCATE", "beforeJson" to request, "afterJson" to "null", "createdAt" to System.currentTimeMillis()))
        aggregateCheck(payment.getString("projectId"))
    }
    private fun savePaymentInternal(input: PaymentInput): String {
        val request = canonical(paymentRequest(input)); retry("payments", input.idempotencyKey, request)?.let { return it }
        id(input.id); project(input.projectId); positive(input.amountPaise); date(input.date); require(row("payments", input.id) == null) { "Payments are immutable" }
        related("vendors", input.vendorId, input.projectId)
        related("instalments", input.instalmentId, input.projectId, input.vendorId)?.let { plan ->
            require(!plan.getBoolean("cancelled")) { "Cannot pay a cancelled schedule" }
            require(Math.addExact(planPaid(plan.getString("id")), input.amountPaise) <= plan.getLong("amountPaise")) { "Schedule overpayment" }
        }
        require(input.allocations.map { it.expenseId }.distinct().size == input.allocations.size)
        Settlement.available(input.amountPaise, input.allocations.map { it.amountPaise })
        input.allocations.forEach { a ->
            val expense = related("expenses", a.expenseId, input.projectId, input.vendorId)!!
            require(!expense.getBoolean("voided")); require(Math.addExact(settled(a.expenseId), a.amountPaise) <= expense.getLong("amountPaise")) { "Expense overpayment" }
        }
        save("payments", json("id" to input.id, "projectId" to input.projectId, "amountPaise" to input.amountPaise, "date" to input.date, "vendorId" to input.vendorId, "reference" to input.reference, "note" to input.note, "kind" to "PAYMENT", "originalPaymentId" to null, "instalmentId" to input.instalmentId, "idempotencyKey" to input.idempotencyKey, "requestJson" to request))
        input.allocations.forEach { insert("payment_allocations", json("id" to newId(), "paymentId" to input.id, "expenseId" to it.expenseId, "amountPaise" to it.amountPaise)) }
        input.attachmentIds.forEach { link(it, null, input.id, null) }; aggregateCheck(input.projectId); return input.id
    }
    private fun planPaid(id: String): Long = scalar("SELECT COALESCE(SUM(CASE kind WHEN 'PAYMENT' THEN amountPaise ELSE -amountPaise END),0) value FROM payments WHERE instalmentId=?", id)
    suspend fun reversePayment(id: String, reason: String, date: String): String = correctPayment(id, null, reason, date)
    suspend fun refundPayment(id: String, amountPaise: Long, reason: String, date: String): String = correctPayment(id, amountPaise, reason, date)
    private suspend fun correctPayment(id: String, refund: Long?, reason: String, date: String): String = write {
        correctPaymentInternal(id, refund, reason, date)
    }
    private fun correctPaymentInternal(id: String, refund: Long?, reason: String, date: String): String {
        text(reason); date(date)
        val original = row("payments", id) ?: error("Original payment missing"); require(original.getString("kind") == "PAYMENT")
        val previous = rows("SELECT * FROM payments WHERE originalPaymentId=?", id)
        require(previous.none { it.getString("kind") == "REVERSAL" }) { "Payment already reversed" }
        val remaining = Math.subtractExact(original.getLong("amountPaise"), Money.sum(previous.map { it.getLong("amountPaise") }))
        val amount = refund ?: remaining; positive(amount); require(amount <= remaining) { "Refund exceeds remaining original funds" }
        val originalParts = rows("SELECT a.expenseId,a.amountPaise-COALESCE((SELECT SUM(x.amountPaise) FROM payment_allocations x JOIN payments q ON q.id=x.paymentId WHERE q.originalPaymentId=? AND x.expenseId=a.expenseId),0) amountPaise FROM payment_allocations a WHERE a.paymentId=? ORDER BY a.expenseId", id, id)
        val weights = originalParts.map { it.getLong("amountPaise") }
        require(weights.all { it >= 0 }) { "Original allocation is already over-refunded" }
        val allocated = Money.sum(weights)
        if (refund != null) require(amount <= allocated) { "Refund exceeds unreversed settled funds" }
        val portions = if (refund == null) weights else refundPortions(amount, weights)
        val result = newId(); val kind = if (refund == null) "REVERSAL" else "REFUND"
        save("payments", json("id" to result, "projectId" to original.getString("projectId"), "amountPaise" to amount, "date" to date, "vendorId" to original.nullable("vendorId"), "reference" to reason, "note" to reason, "kind" to kind, "originalPaymentId" to id, "instalmentId" to original.nullable("instalmentId"), "idempotencyKey" to result, "requestJson" to canonical(json("originalPaymentId" to id, "amountPaise" to amount, "kind" to kind, "reason" to reason, "date" to date))))
        originalParts.forEachIndexed { index, a -> if (portions[index] > 0) insert("payment_allocations", json("id" to newId(), "paymentId" to result, "expenseId" to a.getString("expenseId"), "amountPaise" to portions[index])) }
        aggregateCheck(original.getString("projectId")); return result
    }

    suspend fun saveCommitment(input: CommitmentInput): String = write {
        id(input.id); project(input.projectId); text(input.title); positive(input.amountPaise); date(input.date); related("vendors", input.vendorId, input.projectId)
        input.calculation?.let { require(Money.calculate(it).totalPaise == input.amountPaise) { "Calculated commitment does not reconcile" } }
        val before = row("commitments", input.id); before?.let { require(it.getString("projectId") == input.projectId && !it.getBoolean("archived")) { "Archived agreement cannot be edited" } }
        require(before != null || input.revision == null) { "Saved agreement no longer exists; discard this stale edit or create a new entry" }
        save("commitments", json("id" to input.id, "projectId" to input.projectId, "title" to input.title, "amountPaise" to input.amountPaise, "vendorId" to input.vendorId, "date" to input.date, "revision" to revision(before, input.revision), "archived" to false, "note" to input.note, "calculationJson" to (input.calculation?.let { calculationJson(it).toString() } ?: "")))
        savePlannedClassifications("COMMITMENT", input.id, input.projectId, input.classificationIds, before == null)
        input.schedules.forEach { require(it.commitmentId == null || it.commitmentId == input.id); saveInstalmentInternal(it.copy(commitmentId = input.id), checkCommitment = false) }
        require(scalar("SELECT COALESCE(SUM(amountPaise),0) value FROM instalments WHERE commitmentId=? AND NOT cancelled", input.id) <= input.amountPaise) { "Schedule exceeds commitment" }
        checkPlannedReferences(commitmentId = input.id)
        aggregateCheck(input.projectId); input.id
    }
    private fun checkPlannedReferences(commitmentId: String? = null, instalmentId: String? = null) {
        require(scalar("SELECT COUNT(*) value FROM instalments i JOIN commitments c ON c.id=i.commitmentId WHERE (i.id=? OR i.commitmentId=?) AND (i.projectId!=c.projectId OR (i.vendorId IS NOT NULL AND c.vendorId IS NOT NULL AND i.vendorId!=c.vendorId))", instalmentId, commitmentId) == 0L) {
            "The supplier must match existing schedules, including cancelled schedules."
        }
        require(scalar("SELECT COUNT(*) value FROM expenses e LEFT JOIN commitments c ON c.id=e.commitmentId LEFT JOIN instalments i ON i.id=e.instalmentId WHERE (e.commitmentId=? OR e.instalmentId=? OR e.instalmentId IN (SELECT id FROM instalments WHERE commitmentId=?)) AND ((c.id IS NOT NULL AND (e.projectId!=c.projectId OR (e.vendorId IS NOT NULL AND c.vendorId IS NOT NULL AND e.vendorId!=c.vendorId))) OR (i.id IS NOT NULL AND (e.projectId!=i.projectId OR (e.vendorId IS NOT NULL AND i.vendorId IS NOT NULL AND e.vendorId!=i.vendorId))))", commitmentId, instalmentId, commitmentId) == 0L) {
            "The supplier must match expenses already linked to this agreement or schedule."
        }
        require(scalar("SELECT COUNT(*) value FROM payments p JOIN instalments i ON i.id=p.instalmentId WHERE (i.id=? OR i.commitmentId=?) AND (p.projectId!=i.projectId OR (p.vendorId IS NOT NULL AND i.vendorId IS NOT NULL AND p.vendorId!=i.vendorId))", instalmentId, commitmentId) == 0L) {
            "The supplier must match the schedule's recorded payment history."
        }
    }
    suspend fun archiveCommitment(id: String, expectedRevision: Long) = write {
        val before = row("commitments", id) ?: error("Agreement missing")
        val nextRevision = revision(before, expectedRevision)
        require(!before.getBoolean("archived")) { "Agreement already archived" }
        save("commitments", before.put("archived", true).put("revision", nextRevision), "ARCHIVE")
    }
    suspend fun cancelCommitment(id: String, expectedRevision: Long, scheduleRevisions: Map<String, Long>) = write {
        val before = row("commitments", id) ?: error("Agreement missing")
        require(!before.getBoolean("archived")) { "Agreement already archived" }
        val nextRevision = revision(before, expectedRevision)
        val plans = rows("SELECT * FROM instalments WHERE commitmentId=? AND NOT cancelled ORDER BY id", id)
        require(plans.associate { it.getString("id") to it.getLong("revision") } == scheduleRevisions) {
            "Schedules changed; reload the commitment before cancelling."
        }
        plans.forEach { plan -> cancelInstalmentInternal(plan.getString("id"), plan.getLong("revision")) }
        save("commitments", before.put("archived", true).put("revision", nextRevision), "CANCEL")
    }
    suspend fun saveInstalment(input: InstalmentInput): String = write { saveInstalmentInternal(input) }
    private fun saveInstalmentInternal(input: InstalmentInput, checkCommitment: Boolean = true): String {
        id(input.id); project(input.projectId); text(input.title); positive(input.amountPaise); date(input.dueDate); require(input.reminderDays in 0..365) { "Reminder lead days must be 0 to 365" }; related("vendors", input.vendorId, input.projectId)
        val commitment = related("commitments", input.commitmentId, input.projectId, input.vendorId)
        val before = row("instalments", input.id); before?.let { require(it.getString("projectId") == input.projectId && !it.getBoolean("cancelled")); require(planPaid(input.id) <= input.amountPaise); require(it.nullable("vendorId") == input.vendorId || planPaid(input.id) == 0L) }
        require(before != null || input.revision == null) { "Saved instalment no longer exists; discard this stale edit or create a new entry" }
        save("instalments", json("id" to input.id, "projectId" to input.projectId, "title" to input.title, "amountPaise" to input.amountPaise, "dueDate" to input.dueDate, "commitmentId" to input.commitmentId, "vendorId" to input.vendorId, "cancelled" to false, "remind" to input.remind, "revision" to revision(before, input.revision), "note" to input.note))
        save("reminders", json("id" to input.id, "instalmentId" to input.id, "enabled" to input.remind, "leadDays" to input.reminderDays))
        savePlannedClassifications("INSTALMENT", input.id, input.projectId, input.classificationIds, before == null)
        checkPlannedReferences(instalmentId = input.id)
        if (checkCommitment && commitment != null) require(scalar("SELECT COALESCE(SUM(amountPaise),0) value FROM instalments WHERE commitmentId=? AND NOT cancelled", input.commitmentId) <= commitment.getLong("amountPaise")) { "Schedule exceeds commitment" }
        aggregateCheck(input.projectId); return input.id
    }
    private fun savePlannedClassifications(type: String, targetId: String, projectId: String, ids: List<String>?, creating: Boolean) {
        if (ids == null && !creating) return
        val selected = ids.orEmpty()
        require(selected.distinct().size == selected.size) { "Duplicate planned classification" }
        selected.forEach { related("classifications", it, projectId) }
        val column = if (type == "COMMITMENT") "commitmentId" else "instalmentId"
        replaceChildren("planned_classifications", column, targetId, selected.map {
            json("id" to newId(), "classificationId" to it, "commitmentId" to if (type == "COMMITMENT") targetId else null, "instalmentId" to if (type == "INSTALMENT") targetId else null)
        })
    }
    suspend fun cancelInstalment(id: String, revision: Long) = write {
        cancelInstalmentInternal(id, revision)
    }
    private fun cancelInstalmentInternal(id: String, revision: Long) {
        val before = row("instalments", id) ?: error("Schedule missing"); val nextRevision = revision(before, revision)
        save("instalments", before.put("cancelled", true).put("remind", false).put("revision", nextRevision), "CANCEL")
        val leadDays = row("reminders", id)?.getInt("leadDays") ?: 3
        save("reminders", json("id" to id, "instalmentId" to id, "enabled" to false, "leadDays" to leadDays))
    }
    suspend fun saveNote(note: ActivityNote) = write {
        id(note.id); project(note.projectId); text(note.title); date(note.date)
        val before = row("activity_entries", note.id); before?.let { require(it.getString("projectId") == note.projectId) }
        save("activity_entries", json("id" to note.id, "projectId" to note.projectId, "title" to note.title, "date" to note.date, "note" to note.note, "revision" to revision(before, note.revision)))
    }
    suspend fun deleteNote(id: String, revision: Long) = write {
        val before = row("activity_entries", id) ?: error("Note missing"); revision(before, revision)
        rows("SELECT * FROM attachment_links WHERE noteId=?", id).forEach(::unlink)
        sql.execSQL("DELETE FROM activity_entries WHERE id=?", arrayOf(id)); audit("activity_entries", id, "DELETE", before, null)
    }
    suspend fun attachment(id: String): AttachmentInfo? = withContext(Dispatchers.IO) { row("attachments", id)?.let { a -> attachmentInfo(a).copy(referenceCount = scalar("SELECT COUNT(*) value FROM attachment_links WHERE attachmentId=?", id).toInt()) } }
    suspend fun attachmentStorageNames(): Set<String> = withContext(Dispatchers.IO) {
        rows("SELECT storageName FROM attachments").map { it.getString("storageName") }.toSet()
    }
    suspend fun paymentAttachmentIds(paymentId: String): List<String> = withContext(Dispatchers.IO) {
        rows("SELECT attachmentId FROM attachment_links WHERE paymentId=? ORDER BY id", paymentId).map { it.getString("attachmentId") }
    }
    suspend fun attachmentLinks(id: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        rows("SELECT expenseId,paymentId,noteId FROM attachment_links WHERE attachmentId=? ORDER BY id LIMIT 500", id).map {
            when { !it.isNull("expenseId") -> "EXPENSE" to it.getString("expenseId")
                !it.isNull("paymentId") -> "PAYMENT" to it.getString("paymentId")
                else -> "NOTE" to it.getString("noteId") }
        }
    }
    suspend fun insertAttachment(info: AttachmentInfo) = insertAttachments(listOf(info))
    suspend fun insertAttachments(items: List<AttachmentInfo>, expenseId: String? = null) = write {
        require(items.size in 1..1000) { "Select between 1 and 1,000 documents" }
        items.forEach { info ->
        id(info.id); project(info.projectId); text(info.name); require(row("attachments", info.id) == null); validateAttachment(info.storageName, info.sha256, info.size); related("attachments", info.parentId, info.projectId)
        validateDocumentDetails(info.category, info.status, info.revisionLabel, info.work, info.place, info.notes)
        save("attachments", json("id" to info.id, "projectId" to info.projectId, "name" to info.name, "mimeType" to info.mimeType, "size" to info.size, "sha256" to info.sha256, "storageName" to info.storageName, "createdAt" to info.createdAt, "revision" to 0L, "parentId" to info.parentId, "category" to info.category, "status" to info.status, "revisionLabel" to info.revisionLabel, "work" to info.work, "place" to info.place, "notes" to info.notes))
        if (expenseId != null) link(info.id, expenseId, null, null)
        }
    }
    private fun validateDocumentDetails(category: String, status: String, revisionLabel: String, work: String, place: String, notes: String) {
        require(listOf(category, status, revisionLabel, work, place).all { it.length <= 200 && it.none(Char::isISOControl) } && notes.length <= 10000) { "Document details exceed supported length or contain invalid labels" }
    }
    suspend fun saveAttachmentDetails(id: String, expectedRevision: Long, category: String, status: String, revisionLabel: String, work: String, place: String, notes: String) = write {
        validateDocumentDetails(category, status, revisionLabel, work, place, notes)
        val before = row("attachments", id) ?: error("Attachment missing")
        val nextRevision = revision(before, expectedRevision)
        save("attachments", before.put("category", category).put("status", status).put("revisionLabel", revisionLabel).put("work", work).put("place", place).put("notes", notes).put("revision", nextRevision), "DETAILS")
    }
    suspend fun renameAttachment(id: String, name: String, revision: Long) = write { text(name); val before = row("attachments", id) ?: error("Attachment missing"); val nextRevision = revision(before, revision); save("attachments", before.put("name", name).put("revision", nextRevision), "RENAME") }
    suspend fun linkAttachment(attachmentId: String, expenseId: String? = null, paymentId: String? = null, noteId: String? = null) = write { link(attachmentId, expenseId, paymentId, noteId) }
    suspend fun unlinkAttachment(attachmentId: String, recordType: String, recordId: String) = write {
        val column = when (recordType) { "EXPENSE" -> "expenseId"; "PAYMENT" -> "paymentId"; "NOTE" -> "noteId"; else -> error("Unknown attachment target type") }
        val table = when (recordType) { "EXPENSE" -> "expenses"; "PAYMENT" -> "payments"; else -> "activity_entries" }
        val attachment = row("attachments", attachmentId) ?: error("Attachment missing")
        related(table, recordId, attachment.getString("projectId"))
        val links = rows("SELECT * FROM attachment_links WHERE attachmentId=? AND $column=? LIMIT 2", attachmentId, recordId)
        require(links.size == 1) { "Attachment link missing or ambiguous" }
        unlink(links.single())
    }
    private fun unlink(link: JSONObject) {
        val (recordType, recordId) = when {
            !link.isNull("expenseId") -> "EXPENSE" to link.getString("expenseId")
            !link.isNull("paymentId") -> "PAYMENT" to link.getString("paymentId")
            else -> "NOTE" to link.getString("noteId")
        }
        sql.execSQL("DELETE FROM attachment_links WHERE id=?", arrayOf(link.getString("id")))
        audit("attachments", link.getString("attachmentId"), "UNLINK", json("targetType" to recordType, "targetId" to recordId, "link" to link), null)
    }
    private fun link(attachmentId: String, expenseId: String?, paymentId: String?, noteId: String?) {
        require(listOf(expenseId, paymentId, noteId).count { it != null } == 1) { "Choose one attachment target" }
        val attachment = row("attachments", attachmentId) ?: error("Attachment missing"); val project = attachment.getString("projectId")
        related("expenses", expenseId, project); related("payments", paymentId, project); related("activity_entries", noteId, project)
        if (scalar("SELECT COUNT(*) value FROM attachment_links WHERE attachmentId=? AND expenseId IS ? AND paymentId IS ? AND noteId IS ?", attachmentId, expenseId, paymentId, noteId) == 0L)
            save("attachment_links", json("id" to newId(), "attachmentId" to attachmentId, "expenseId" to expenseId, "paymentId" to paymentId, "noteId" to noteId))
    }
    suspend fun deleteAttachmentMetadata(id: String) = write {
        require(scalar("SELECT COUNT(*) value FROM attachment_links WHERE attachmentId=?", id) == 0L) { "Attachment is still referenced" }
        require(scalar("SELECT COUNT(*) value FROM attachments WHERE parentId=?", id) == 0L) { "Attachment has derived copies" }
        val before = row("attachments", id) ?: error("Attachment missing"); sql.execSQL("DELETE FROM attachments WHERE id=?", arrayOf(id)); audit("attachments", id, "DELETE", before, null)
    }
    suspend fun expense(id: String): ExpenseInput? = withContext(Dispatchers.IO) {
        row("expenses", id)?.let { e -> ExpenseInput(id = id, idempotencyKey = e.getString("idempotencyKey"), projectId = e.getString("projectId"), description = e.getString("description"), amountPaise = e.getLong("amountPaise"), date = e.getString("date"), vendorId = e.nullable("vendorId"), note = e.getString("note"), mode = e.getString("mode"),
            lines = rows("SELECT * FROM expense_lines WHERE expenseId=? ORDER BY id", id).map { ExpenseLineInput(it.getString("id"), it.getString("description"), it.getLong("amountPaise"), it.getString("mode"), it.nullable("quantity"), it.nullable("rate"), it.getString("multiplier"), it.getString("quantityUnit"), it.getString("rateUnit"), it.getString("legacyJson"), it.getString("nature")) },
            classifications = rows("SELECT * FROM classification_allocations WHERE expenseId=? ORDER BY id", id).map { ClassificationPart(it.getString("classificationId"), it.getString("dimension"), it.getLong("amountPaise")) }, attachmentIds = rows("SELECT attachmentId FROM attachment_links WHERE expenseId=?", id).map { it.getString("attachmentId") }, commitmentId = e.nullable("commitmentId"), instalmentId = e.nullable("instalmentId"), revision = e.getLong("revision"), calculation = e.getString("calculationJson").takeIf { it.isNotEmpty() }?.let { parseCalculation(JSONObject(it)) }, invoiceNumber = e.getString("invoiceNumber")) }
    }

    suspend fun exportRecords(): JSONObject = write { JSONObject().also { records -> TABLES.forEach { table -> records.put(table, JSONArray(rows("SELECT * FROM $table ORDER BY id").map { normalize(table, it); it })) } } }
    suspend fun exportRecordsTo(file: File): SnapshotInfo = withContext(Dispatchers.IO) {
        try {
            database.withTransaction {
                val counts = linkedMapOf<String, Int>()
                val attachments = mutableListOf<AttachmentInfo>()
                JsonWriter(file.outputStream().bufferedWriter(Charsets.UTF_8)).use { writer ->
                    writer.beginObject()
                    TABLES.forEach { table ->
                        writer.name(table).beginArray()
                        sql.query("SELECT * FROM $table ORDER BY id").use { cursor ->
                            val columns = SCHEMA.getValue(table).map { (name, type) -> Triple(name, type, cursor.getColumnIndexOrThrow(name)) }
                            require(cursor.columnNames.toSet() == SCHEMA.getValue(table).keys)
                            var count = 0
                            while (cursor.moveToNext()) {
                                writer.beginObject()
                                columns.forEach { (name, type, index) ->
                                    writer.name(name)
                                    when (cursor.getType(index)) {
                                        android.database.Cursor.FIELD_TYPE_NULL -> writer.nullValue()
                                        android.database.Cursor.FIELD_TYPE_STRING -> writer.value(cursor.getString(index))
                                        android.database.Cursor.FIELD_TYPE_INTEGER -> if (type == "B") writer.value(cursor.getLong(index) == 1L) else writer.value(cursor.getLong(index))
                                        else -> error("Unexpected SQLite type for $table.$name")
                                    }
                                }
                                writer.endObject()
                                count++
                                if (table == "attachments") {
                                    require(attachments.size < 1000) { "Too many backup documents" }
                                    attachments += AttachmentInfo(cursor.getString(cursor.getColumnIndexOrThrow("id")), cursor.getString(cursor.getColumnIndexOrThrow("projectId")), cursor.getString(cursor.getColumnIndexOrThrow("name")), cursor.getString(cursor.getColumnIndexOrThrow("mimeType")), cursor.getLong(cursor.getColumnIndexOrThrow("size")), cursor.getString(cursor.getColumnIndexOrThrow("sha256")), cursor.getString(cursor.getColumnIndexOrThrow("storageName")), cursor.getLong(cursor.getColumnIndexOrThrow("createdAt")), cursor.getLong(cursor.getColumnIndexOrThrow("revision")), cursor.getString(cursor.getColumnIndexOrThrow("parentId")))
                                }
                            }
                            counts[table] = count
                        }
                        writer.endArray()
                    }
                    writer.endObject()
                }
                require(counts.values.sumOf { it.toLong() } <= 1_000_000L) { "Too many backup records" }
                SnapshotInfo(counts, attachments, snapshotFinancials())
            }
        } catch (failure: Throwable) { file.delete(); throw failure }
    }
    private fun readRecordsFile(file: File, schemaVersion: Int, storageNames: Map<String, String> = emptyMap(),
                                onProgress: (Int) -> Unit = {}, consume: (String, JSONObject, Long) -> Unit): SnapshotInfo {
        require(schemaVersion in 1..SNAPSHOT_VERSION && file.isFile && file.length() <= 256L * 1024 * 1024) { "Unsupported or oversized records file" }
        preflightRecordsFile(file)
        val expectedTables = if (schemaVersion == 1) TABLES - "planned_classifications" else TABLES
        val counts = linkedMapOf<String, Int>()
        val attachments = mutableListOf<AttachmentInfo>()
        val integerPattern = Regex("-?(?:0|[1-9][0-9]*)")
        var totalCount = 0
        JsonReader(file.inputStream().bufferedReader(Charsets.UTF_8)).use { reader ->
            reader.isLenient = false
            reader.beginObject()
            while (reader.hasNext()) {
                val table = reader.nextName()
                require(table in expectedTables && table !in counts) { "Unexpected or duplicate records table" }
                val expectedColumns = SCHEMA.getValue(table).keys - (if (schemaVersion == 1 && table == "expense_lines") setOf("nature") else emptySet()) - (if (schemaVersion < 3 && table == "attachments") DOCUMENT_FIELDS else emptySet()) - (if (schemaVersion < 4 && table == "expenses") setOf("invoiceNumber") else emptySet()) - (if (schemaVersion < 4 && table == "reminders") setOf("leadDays") else emptySet()) - (if (schemaVersion < 5) plannedDetailFields(table) else emptySet())
                var count = 0
                reader.beginArray()
                while (reader.hasNext()) {
                    val item = JSONObject()
                    var storedBytes = 0L
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val column = reader.nextName()
                        require(column in expectedColumns && !item.has(column)) { "Unexpected or duplicate $table column" }
                        val type = SCHEMA.getValue(table).getValue(column)
                        val value = when {
                            reader.peek() == JsonToken.NULL && type.endsWith('?') -> { reader.nextNull(); JSONObject.NULL }
                            type.removeSuffix("?") == "S" && reader.peek() == JsonToken.STRING -> reader.nextString().also { require(it.length <= 1_000_000) { "Oversized $table.$column" } }
                            type == "B" && reader.peek() == JsonToken.BOOLEAN -> reader.nextBoolean()
                            type.removeSuffix("?") in setOf("I", "L") && reader.peek() == JsonToken.NUMBER -> {
                                val raw = reader.nextString()
                                require(integerPattern.matches(raw)) { "Invalid integer $table.$column" }
                                if (type.removeSuffix("?") == "I") raw.toIntOrNull() else raw.toLongOrNull()
                            }
                            else -> null
                        }
                        require(value != null) { "Invalid type for $table.$column" }
                        storedBytes = Math.addExact(storedBytes, storedValueBytes(value))
                        require(storedBytes <= MAX_RECORD_BYTES) { "Record exceeds the supported 1 MiB storage and backup limit" }
                        item.put(column, value)
                    }
                    reader.endObject()
                    require(item.keys().asSequence().toSet() == expectedColumns) { "Missing $table columns" }
                    if (schemaVersion == 1 && table == "expense_lines") item.put("nature", "")
                    if (schemaVersion < 3 && table == "attachments") DOCUMENT_FIELDS.forEach { item.put(it, "") }
                    if (schemaVersion < 4 && table == "expenses") item.put("invoiceNumber", "")
                    if (schemaVersion < 4 && table == "reminders") item.put("leadDays", 3)
                    if (schemaVersion < 5) plannedDetailFields(table).forEach { item.put(it, "") }
                    validateRecord(table, item)
                    if (table == "attachments") {
                        require(attachments.size < 1000) { "Too many backup documents" }
                        attachments += attachmentInfo(item)
                        if (storageNames.isNotEmpty()) {
                            val replacement = storageNames[item.getString("id")] ?: error("Missing replacement document")
                            validateAttachment(replacement, item.getString("sha256"), item.getLong("size"))
                            item.put("storageName", replacement)
                        }
                    }
                    consume(table, item, storedBytes)
                    count++
                    totalCount++
                    require(totalCount <= 1_000_000) { "Too many backup records" }
                    if (totalCount % 1000 == 0) onProgress(totalCount)
                }
                reader.endArray()
                counts[table] = count
            }
            reader.endObject()
            require(reader.peek() == JsonToken.END_DOCUMENT && counts.keys == expectedTables.toSet()) { "Incomplete records file" }
        }
        require(storageNames.isEmpty() || storageNames.keys == attachments.map { it.id }.toSet()) { "Replacement document inventory differs" }
        if (totalCount % 1000 != 0 || totalCount == 0) onProgress(totalCount)
        return SnapshotInfo(counts, attachments)
    }
    private fun preflightRecordsFile(file: File) {
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            var inString = false
            var escaped = false
            var unicodeDigits = 0
            var stringLength = 0
            var primitiveLength = 0
            while (true) {
                val next = reader.read()
                if (next < 0) break
                val character = next.toChar()
                if (inString) {
                    when {
                        unicodeDigits > 0 -> {
                            require(character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F') { "Invalid JSON Unicode escape" }
                            unicodeDigits--
                        }
                        escaped -> {
                            require(character in "\"\\/bfnrtu") { "Invalid JSON escape" }
                            escaped = false
                            if (character == 'u') unicodeDigits = 4
                            stringLength++
                        }
                        character == '\\' -> escaped = true
                        character == '"' -> { inString = false; stringLength = 0 }
                        else -> stringLength++
                    }
                    require(stringLength <= 1_000_000) { "Oversized JSON string" }
                } else {
                    when {
                        character == '"' -> { inString = true; primitiveLength = 0 }
                        character.isWhitespace() || character in "{}[],:" -> primitiveLength = 0
                        else -> {
                            primitiveLength++
                            require(primitiveLength <= 32) { "Oversized JSON primitive" }
                        }
                    }
                }
            }
            require(!inString && !escaped && unicodeDigits == 0) { "Incomplete JSON string" }
        }
    }
    private fun attachmentInfo(item: JSONObject) = AttachmentInfo(item.getString("id"), item.getString("projectId"), item.getString("name"), item.getString("mimeType"), item.getLong("size"), item.getString("sha256"), item.getString("storageName"), item.getLong("createdAt"), item.getLong("revision"), item.nullable("parentId"), category = item.getString("category"), status = item.getString("status"), revisionLabel = item.getString("revisionLabel"), work = item.getString("work"), place = item.getString("place"), notes = item.getString("notes"))
    private fun importRecordsFile(file: File, schemaVersion: Int, storageNames: Map<String, String> = emptyMap(), onProgress: (Int) -> Unit = {}): SnapshotInfo {
        var activeTable: String? = null
        var columns = emptyList<String>()
        var statement: SupportSQLiteStatement? = null
        var statementRows = 0
        val pending = mutableListOf<JSONObject>()
        var pendingBytes = 0L
        fun flush() {
            if (pending.isEmpty()) return
            if (statementRows != pending.size) {
                statement?.close()
                val placeholders = columns.joinToString(",") { "?" }
                statement = sql.compileStatement("INSERT INTO $activeTable (${columns.joinToString(",")}) VALUES " +
                    List(pending.size) { "($placeholders)" }.joinToString(","))
                statementRows = pending.size
            }
            val insertStatement = checkNotNull(statement)
            var index = 1
            pending.forEach { item -> columns.forEach { column ->
                when (val value = item.get(column)) {
                    JSONObject.NULL -> insertStatement.bindNull(index)
                    is String -> insertStatement.bindString(index, value)
                    is Boolean -> insertStatement.bindLong(index, if (value) 1L else 0L)
                    is Int -> insertStatement.bindLong(index, value.toLong())
                    is Long -> insertStatement.bindLong(index, value)
                    else -> error("Unsupported $activeTable.$column value")
                }
                index++
            } }
            insertStatement.executeInsert()
            pending.clear()
            pendingBytes = 0L
        }
        try {
            val info = readRecordsFile(file, schemaVersion, storageNames, onProgress) { table, item, storedBytes ->
                if (table != activeTable) {
                    flush()
                    statement?.close()
                    statement = null
                    statementRows = 0
                    columns = SCHEMA.getValue(table).keys.toList()
                    activeTable = table
                }
                // Stay below SQLite's oldest supported bind limit and bound queued validated records.
                if (pendingBytes + storedBytes > 256 * 1024) flush()
                pending += item
                pendingBytes += storedBytes
                if (pending.size >= minOf(32, 999 / columns.size) || pendingBytes >= 256 * 1024) flush()
            }
            flush()
            return info
        } finally { statement?.close() }
    }
    suspend fun inspectRecordsFile(file: File, schemaVersion: Int, onProgress: (Int) -> Unit = {}): SnapshotInfo = withContext(Dispatchers.IO) {
        val name = "candidate-${UUID.randomUUID()}.db"
        val candidateContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDatabasePath(name: String): File = File(context.cacheDir, name)
        }
        val candidate = Room.databaseBuilder(candidateContext, LedgerDatabase::class.java, name).build()
        try {
            candidate.withTransaction {
                val repository = LedgerRepository(candidate, candidateContext)
                repository.sql.execSQL("PRAGMA defer_foreign_keys=ON")
                val info = repository.importRecordsFile(file, schemaVersion, onProgress = onProgress)
                repository.checkStoredState()
                info.copy(financials = repository.snapshotFinancials())
            }
        } finally {
            candidate.close()
            listOf("", "-wal", "-shm", "-journal").forEach { suffix -> File(context.cacheDir, name + suffix).delete() }
        }
    }
    private fun snapshotFinancials(): SnapshotFinancials {
        fun total(statement: String): BigInteger = rows(statement).fold(BigInteger.ZERO) { sum, item ->
            sum.add(BigInteger.valueOf(item.getLong("total")))
        }
        val paid = rows("""SELECT projectId,
            COALESCE(SUM(CASE WHEN kind='PAYMENT' THEN amountPaise ELSE 0 END),0) gross,
            COALESCE(SUM(CASE WHEN kind!='PAYMENT' THEN amountPaise ELSE 0 END),0) corrections
            FROM payments GROUP BY projectId""").fold(BigInteger.ZERO) { sum, item ->
            sum.add(BigInteger.valueOf(item.getLong("gross")).subtract(BigInteger.valueOf(item.getLong("corrections"))))
        }
        return SnapshotFinancials(
            total("SELECT projectId,COALESCE(SUM(amountPaise),0) total FROM expenses WHERE NOT voided GROUP BY projectId"),
            paid,
            total("""$SETTLEMENT SELECT e.projectId,COALESCE(SUM(e.amountPaise-COALESCE(s.amount,0)),0) total
                FROM expenses e LEFT JOIN settlement s ON s.expenseId=e.id WHERE NOT e.voided GROUP BY e.projectId""")
        )
    }
    suspend fun replaceRecordsFile(file: File, schemaVersion: Int, storageNames: Map<String, String> = emptyMap()) = withContext(Dispatchers.IO) {
        val staged = File.createTempFile("ledger-restore-", ".json", context.cacheDir)
        try {
            file.inputStream().use { input -> staged.outputStream().use { output -> input.copyTo(output) } }
            inspectRecordsFile(staged, schemaVersion)
            replaceInspectedRecordsFile(staged, schemaVersion, storageNames)
        } finally { staged.delete() }
    }
    // BackupService calls this for its private staged file after isolated cross-row validation.
    internal suspend fun replaceInspectedRecordsFile(file: File, schemaVersion: Int, storageNames: Map<String, String> = emptyMap(), onProgress: (Int) -> Unit = {}) {
        write {
            val revisions = restoreRevisionFloors()
            sql.execSQL("PRAGMA defer_foreign_keys=ON")
            // Replacement retires pre-restore commands atomically; their quarantined files remain untouched.
            sql.execSQL("DELETE FROM project_deletions")
            TABLES.reversed().forEach { sql.execSQL("DELETE FROM $it") }
            replacementCheckpoint("rows-cleared")
            importRecordsFile(file, schemaVersion, storageNames, onProgress)
            replacementCheckpoint("rows-imported")
            advanceRestoredRevisions(revisions)
        }
    }
    suspend fun forEachExportRow(projectId: String, consumer: (table: String, row: JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        database.withTransaction {
            fun emit(table: String, statement: String, vararg args: Any?, include: (JSONObject) -> Boolean = { true }) {
                sql.query(query(statement, *args)).use { cursor ->
                    while (cursor.moveToNext()) {
                        val item = JSONObject()
                        cursor.columnNames.forEachIndexed { index, name -> item.put(name, when (cursor.getType(index)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                            android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                            android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                            else -> error("Unexpected export field type")
                        }) }
                        normalize(table, item)
                        if (item.has("_hasLines")) item.put("_hasLines", item.getLong("_hasLines") != 0L)
                        if (item.has("_voided")) item.put("_voided", item.getLong("_voided") != 0L)
                        if (include(item)) consumer(table, item)
                    }
                }
            }
            emit("projects", "SELECT * FROM projects WHERE id=?", projectId)
            listOf("budgets", "classifications", "vendors", "commitments", "instalments", "expenses", "payments", "attachments", "activity_entries").forEach { table ->
                val statement = when (table) {
                    "expenses" -> "SELECT e.*,COALESCE((SELECT SUM(CASE p.kind WHEN 'PAYMENT' THEN a.amountPaise ELSE -a.amountPaise END) FROM payment_allocations a JOIN payments p ON p.id=a.paymentId WHERE a.expenseId=e.id),0) _settledPaise,EXISTS(SELECT 1 FROM expense_lines l WHERE l.expenseId=e.id) _hasLines,COALESCE(v.name,'') _vendorName FROM expenses e LEFT JOIN vendors v ON v.id=e.vendorId WHERE e.projectId=?"
                    "payments" -> "SELECT p.*,COALESCE(v.name,'') _vendorName FROM payments p LEFT JOIN vendors v ON v.id=p.vendorId WHERE p.projectId=?"
                    "instalments" -> "SELECT i.*,COALESCE(v.name,'') _vendorName,COALESCE((SELECT SUM(CASE p.kind WHEN 'PAYMENT' THEN p.amountPaise ELSE -p.amountPaise END) FROM payments p WHERE p.instalmentId=i.id),0) _paidPaise FROM instalments i LEFT JOIN vendors v ON v.id=i.vendorId WHERE i.projectId=?"
                    "commitments" -> "SELECT c.*,COALESCE(v.name,'') _vendorName FROM commitments c LEFT JOIN vendors v ON v.id=c.vendorId WHERE c.projectId=?"
                    else -> "SELECT * FROM $table WHERE projectId=?"
                }
                emit(table, statement, projectId)
            }
            emit("expense_lines", "SELECT l.*,e.date _expenseDate,COALESCE(v.name,'') _vendorName,e.voided _voided FROM expense_lines l JOIN expenses e ON e.id=l.expenseId LEFT JOIN vendors v ON v.id=e.vendorId WHERE e.projectId=?", projectId)
            emit("classification_allocations", "SELECT a.*,c.name _classificationName,e.description _expenseDescription,e.date _expenseDate,COALESCE(v.name,'') _vendorName,e.voided _voided FROM classification_allocations a JOIN expenses e ON e.id=a.expenseId JOIN classifications c ON c.id=a.classificationId LEFT JOIN vendors v ON v.id=e.vendorId WHERE e.projectId=?", projectId)
            emit("payment_allocations", "SELECT a.* FROM payment_allocations a JOIN payments p ON p.id=a.paymentId WHERE p.projectId=?", projectId)
            emit("planned_classifications", "SELECT p.* FROM planned_classifications p LEFT JOIN commitments c ON c.id=p.commitmentId LEFT JOIN instalments i ON i.id=p.instalmentId WHERE c.projectId=? OR i.projectId=?", projectId, projectId)
            emit("attachment_links", "SELECT l.* FROM attachment_links l JOIN attachments a ON a.id=l.attachmentId WHERE a.projectId=?", projectId)
            emit("reminders", "SELECT r.* FROM reminders r JOIN instalments i ON i.id=r.instalmentId WHERE i.projectId=?", projectId)
            emit("audit_records", "SELECT * FROM audit_records ORDER BY createdAt,id", include = { item ->
                auditProject(item.getString("recordType"), item.getString("recordId"), item.getString("action"),
                    item.getString("beforeJson"), item.getString("afterJson")) == projectId
            })
            if (projectId == "legacy-project") emit("import_records", "SELECT * FROM import_records")
        }
    }
    private fun validateState(records: JSONObject) {
        require(records.keys().asSequence().toSet() == TABLES.toSet()) { "Backup tables do not match this database version" }
        TABLES.forEach { table ->
            records.getJSONArray(table).objects().forEach { validateRecord(table, it) }
        }
    }
    private fun validateRecord(table: String, item: JSONObject) {
        var storedBytes = 0L
        val schema = SCHEMA.getValue(table)
        require(item.keys().asSequence().toSet() == schema.keys) { "Invalid $table columns" }
        schema.forEach { (key, type) ->
            val value = item.get(key)
            storedBytes = Math.addExact(storedBytes, storedValueBytes(value))
            require(storedBytes <= MAX_RECORD_BYTES) { "Record exceeds the supported 1 MiB storage and backup limit" }
            require(if (type.endsWith('?') && value == JSONObject.NULL) true else when (type.removeSuffix("?")) {
                "S" -> value is String
                "B" -> value is Boolean
                "I" -> value is Int || value is Long && value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                "L" -> value is Int || value is Long
                else -> false
            }) { "Invalid $table.$key" }
            if (key == "id" || key.endsWith("Id") && value != JSONObject.NULL || key == "idempotencyKey") id(value as String)
            if (key in setOf("date", "dueDate")) date(value as String)
        }
        if (table == "attachments") {
            validateAttachment(item.getString("storageName"), item.getString("sha256"), item.getLong("size"))
            validateDocumentDetails(item.getString("category"), item.getString("status"), item.getString("revisionLabel"), item.getString("work"), item.getString("place"), item.getString("notes"))
        }
        if (table == "expense_lines") require(item.getString("nature") in COST_NATURES) { "Invalid cost nature" }
        if (table == "reminders") require(item.getInt("leadDays") in 0..365) { "Invalid reminder lead days" }
        if (table == "commitments" && item.getString("calculationJson").isNotEmpty()) require(Money.calculate(parseCalculation(JSONObject(item.getString("calculationJson")))).totalPaise == item.getLong("amountPaise")) { "Calculated commitment does not reconcile" }
        if (table == "expenses") {
            invoiceNumber(item.getString("invoiceNumber"))
            val calculation = item.getString("calculationJson")
            if (calculation.isNotEmpty()) require(Money.calculate(parseCalculation(JSONObject(calculation))).totalPaise == item.getLong("amountPaise")) { "Calculated expense does not reconcile" }
            else require(item.getString("mode") == "DIRECT_AMOUNT") { "Calculated expense has no input evidence" }
        }
    }
    suspend fun validateRecords(records: JSONObject) = withContext(Dispatchers.IO) {
        validateState(records)
        val candidate = Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java).build()
        try { candidate.withTransaction { val repository = LedgerRepository(candidate, context); repository.insertRecords(records); repository.checkStoredState() } } finally { candidate.close() }
    }
    suspend fun replaceRecords(records: JSONObject) {
        // Freeze caller-owned JSON before validation so a concurrent mutation cannot bypass it.
        val frozen = JSONObject(records.toString()); validateRecords(frozen)
        write {
            val revisions = restoreRevisionFloors()
            sql.execSQL("PRAGMA defer_foreign_keys=ON")
            sql.execSQL("DELETE FROM project_deletions")
            TABLES.reversed().forEach { sql.execSQL("DELETE FROM $it") }
            replacementCheckpoint("rows-cleared")
            insertRecords(frozen)
            replacementCheckpoint("rows-imported")
            advanceRestoredRevisions(revisions)
            checkStoredState()
        }
    }
    private fun restoreRevisionFloors(): Map<String, Long> {
        val tables = SCHEMA.filterValues { "revision" in it }.keys
        val persisted = restoreRevisionFile?.let { file ->
            if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
                file.openRead().bufferedReader().use { it.readText().toLong().also { value -> require(value >= 0) } }
            } else -1L
        } ?: -1L
        val floor = Math.addExact(maxOf(persisted, restoreRevisionWatermark,
            tables.maxOf { scalar("SELECT COALESCE(MAX(revision),-1) value FROM $it") }), 1L)
        // Reserve before replacement; failed restores consume a harmless gap, never reuse revisions.
        persistRestoreRevision(floor)
        return tables.associateWith { floor }
    }
    private fun persistRestoreRevision(value: Long) {
        restoreRevisionFile?.let { file ->
            val output = file.startWrite()
            try {
                output.write(value.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
                file.finishWrite(output)
            } catch (failure: Throwable) { file.failWrite(output); throw failure }
            replacementCheckpoint("watermark-published")
            check(file.openRead().bufferedReader().use { it.readText().toLong() } == value) {
                "Restore revision could not be persisted"
            }
        }
        restoreRevisionWatermark = value
    }
    private fun advanceRestoredRevisions(floors: Map<String, Long>) {
        // Backup-local counters are untrusted; the new floor invalidates every pre-restore edit.
        floors.forEach { (table, floor) ->
            sql.execSQL("UPDATE $table SET revision=?", arrayOf(floor))
        }
        persistRestoreRevision(floors.values.first())
    }
    private fun insertRecords(records: JSONObject) {
        TABLES.forEach { table ->
            val pending = records.getJSONArray(table).objects().toMutableList()
            val parentColumn = when (table) { "classifications", "attachments" -> "parentId"; "payments" -> "originalPaymentId"; else -> null }
            while (pending.isNotEmpty()) {
                val ready = pending.filter { parentColumn == null || it.isNull(parentColumn) || row(table, it.getString(parentColumn)) != null }
                require(ready.isNotEmpty()) { "Cyclic parent references in $table" }; ready.forEach { insert(table, it) }; pending.removeAll(ready.toSet())
            }
        }
    }
    private fun checkStoredState() {
        sql.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "Foreign key validation failed" } }
        rows("SELECT id FROM projects").forEach { aggregateCheck(it.getString("id")) }
        fun valid(sqlText: String, reason: String) { sql.query("$sqlText LIMIT 1").use { require(!it.moveToFirst()) { reason } } }
        valid("SELECT id FROM projects WHERE revision<0 OR length(trim(name))=0", "Invalid project")
        valid("SELECT id FROM budgets WHERE amountPaise<0 OR id!=projectId", "Invalid budget")
        listOf("expenses", "payments", "commitments", "instalments").forEach { table ->
            valid("SELECT r.id FROM $table r JOIN vendors v ON v.id=r.vendorId WHERE r.projectId!=v.projectId", "Vendor belongs to another project")
        }
        valid("SELECT a.id FROM attachments a JOIN attachments p ON p.id=a.parentId WHERE a.projectId!=p.projectId", "Document revision belongs to another project")
        val attachmentParents = rows("SELECT id,parentId FROM attachments").associate { it.getString("id") to it.nullable("parentId") }
        val checkedAttachments = mutableSetOf<String>()
        attachmentParents.keys.forEach { start ->
            val visited = mutableSetOf<String>()
            var parent: String? = start
            while (parent != null && parent !in checkedAttachments) {
                require(visited.add(parent)) { "Document revision cycle" }
                parent = attachmentParents[parent]
            }
            checkedAttachments.addAll(visited)
        }
        valid("SELECT id FROM expenses WHERE amountPaise<=0 OR revision<0 OR mode NOT IN ('DIRECT_AMOUNT','CALCULATED') OR length(trim(description))=0 OR length(invoiceNumber)>200 OR invoiceNumber GLOB '*[' || char(1) || '-' || char(31) || ']*'", "Invalid expense")
        valid("SELECT l.id FROM expense_lines l JOIN expenses e ON e.id=l.expenseId WHERE l.amountPaise<0 OR l.mode NOT IN ('DIRECT_AMOUNT','CALCULATED') OR (SELECT COALESCE(SUM(amountPaise),0) FROM expense_lines WHERE expenseId=e.id)>e.amountPaise", "Invalid expense line")
        valid("SELECT id FROM expenses e WHERE EXISTS(SELECT 1 FROM expense_lines l WHERE l.expenseId=e.id) AND amountPaise!=(SELECT SUM(amountPaise) FROM expense_lines WHERE expenseId=e.id)", "Expense lines do not reconcile")
        valid("SELECT a.id FROM classification_allocations a JOIN expenses e ON e.id=a.expenseId JOIN classifications c ON c.id=a.classificationId WHERE a.amountPaise<=0 OR e.projectId!=c.projectId OR a.dimension!=$DIMENSION", "Invalid classification allocation")
        valid("SELECT e.id FROM expenses e JOIN classification_allocations a ON a.expenseId=e.id GROUP BY e.id,a.dimension HAVING SUM(a.amountPaise)!=e.amountPaise", "Classification total does not reconcile")
        valid("SELECT p.id FROM planned_classifications p LEFT JOIN commitments m ON m.id=p.commitmentId LEFT JOIN instalments i ON i.id=p.instalmentId JOIN classifications c ON c.id=p.classificationId WHERE (p.commitmentId IS NOT NULL)+(p.instalmentId IS NOT NULL)!=1 OR (m.id IS NOT NULL AND m.projectId!=c.projectId) OR (i.id IS NOT NULL AND i.projectId!=c.projectId)", "Invalid planned classification")
        valid("SELECT id FROM payments WHERE amountPaise<=0 OR kind NOT IN ('PAYMENT','REVERSAL','REFUND')", "Invalid payment")
        valid("SELECT a.id FROM payment_allocations a JOIN payments p ON p.id=a.paymentId JOIN expenses e ON e.id=a.expenseId WHERE a.amountPaise<=0 OR p.projectId!=e.projectId OR (p.vendorId IS NOT NULL AND e.vendorId IS NOT NULL AND p.vendorId!=e.vendorId)", "Invalid payment allocation")
        valid("SELECT p.id FROM payments p WHERE (SELECT COALESCE(SUM(amountPaise),0) FROM payment_allocations a WHERE a.paymentId=p.id)>p.amountPaise", "Payment overallocated")
        valid("$SETTLEMENT SELECT e.id FROM expenses e LEFT JOIN settlement s ON s.expenseId=e.id WHERE COALESCE(s.amount,0)<0 OR COALESCE(s.amount,0)>e.amountPaise OR (e.voided AND COALESCE(s.amount,0)!=0)", "Expense settlement is invalid")
        valid("SELECT p.id FROM payments p LEFT JOIN payments o ON o.id=p.originalPaymentId WHERE (p.kind='PAYMENT' AND p.originalPaymentId IS NOT NULL) OR (p.kind!='PAYMENT' AND (o.id IS NULL OR o.kind!='PAYMENT' OR p.projectId!=o.projectId OR p.vendorId IS NOT o.vendorId OR p.instalmentId IS NOT o.instalmentId))", "Invalid payment correction")
        valid("SELECT o.id FROM payments o WHERE o.kind='PAYMENT' AND ((SELECT COALESCE(SUM(r.amountPaise),0) FROM payments r WHERE r.originalPaymentId=o.id)>o.amountPaise OR (SELECT COUNT(*) FROM payments r WHERE r.originalPaymentId=o.id AND r.kind='REVERSAL')>1)", "Payment correction exceeds original")
        valid("SELECT o.id FROM payments o WHERE o.kind='PAYMENT' AND EXISTS(SELECT 1 FROM payments r WHERE r.originalPaymentId=o.id AND r.kind='REVERSAL') AND ((SELECT COALESCE(SUM(r.amountPaise),0) FROM payments r WHERE r.originalPaymentId=o.id)!=o.amountPaise OR (SELECT COALESCE(SUM(a.amountPaise),0) FROM payment_allocations a JOIN payments r ON r.id=a.paymentId WHERE r.originalPaymentId=o.id)!=(SELECT COALESCE(SUM(a.amountPaise),0) FROM payment_allocations a WHERE a.paymentId=o.id))", "Reversal does not restore original balances")
        valid("SELECT r.id FROM payments r WHERE r.kind='REFUND' AND r.amountPaise!=(SELECT COALESCE(SUM(a.amountPaise),0) FROM payment_allocations a WHERE a.paymentId=r.id)", "Refund must return settled funds")
        valid("SELECT a.id FROM payment_allocations a JOIN payments p ON p.id=a.paymentId WHERE p.kind!='PAYMENT' AND (SELECT COALESCE(SUM(x.amountPaise),0) FROM payment_allocations x WHERE x.paymentId=p.originalPaymentId AND x.expenseId=a.expenseId)<(SELECT COALESCE(SUM(x.amountPaise),0) FROM payment_allocations x JOIN payments r ON r.id=x.paymentId WHERE r.originalPaymentId=p.originalPaymentId AND x.expenseId=a.expenseId)", "Correction exceeds original allocation")
        valid("SELECT i.id FROM instalments i WHERE amountPaise<=0 OR revision<0 OR (SELECT COALESCE(SUM(CASE p.kind WHEN 'PAYMENT' THEN p.amountPaise ELSE -p.amountPaise END),0) FROM payments p WHERE p.instalmentId=i.id) NOT BETWEEN 0 AND i.amountPaise", "Invalid schedule balance")
        valid("SELECT id FROM reminders WHERE leadDays NOT BETWEEN 0 AND 365", "Invalid reminder lead days")
        valid("SELECT c.id FROM commitments c WHERE c.amountPaise<=0 OR c.revision<0 OR (SELECT COALESCE(SUM(i.amountPaise),0) FROM instalments i WHERE i.commitmentId=c.id AND NOT i.cancelled)>c.amountPaise", "Invalid commitment schedule")
        valid("SELECT i.id FROM instalments i JOIN commitments c ON c.id=i.commitmentId WHERE i.projectId!=c.projectId OR (i.vendorId IS NOT NULL AND c.vendorId IS NOT NULL AND i.vendorId!=c.vendorId)", "Invalid commitment reference")
        valid("SELECT e.id FROM expenses e LEFT JOIN commitments c ON c.id=e.commitmentId LEFT JOIN instalments i ON i.id=e.instalmentId WHERE (c.id IS NOT NULL AND (e.projectId!=c.projectId OR (e.vendorId IS NOT NULL AND c.vendorId IS NOT NULL AND e.vendorId!=c.vendorId))) OR (i.id IS NOT NULL AND (e.projectId!=i.projectId OR (e.vendorId IS NOT NULL AND i.vendorId IS NOT NULL AND e.vendorId!=i.vendorId)))", "Invalid expense reference")
        valid("SELECT p.id FROM payments p JOIN instalments i ON i.id=p.instalmentId WHERE p.projectId!=i.projectId OR (p.vendorId IS NOT NULL AND i.vendorId IS NOT NULL AND p.vendorId!=i.vendorId)", "Invalid payment schedule reference")
        valid("SELECT l.id FROM attachment_links l JOIN attachments a ON a.id=l.attachmentId LEFT JOIN expenses e ON e.id=l.expenseId LEFT JOIN payments p ON p.id=l.paymentId LEFT JOIN activity_entries n ON n.id=l.noteId WHERE (l.expenseId IS NOT NULL)+(l.paymentId IS NOT NULL)+(l.noteId IS NOT NULL)!=1 OR (e.id IS NOT NULL AND e.projectId!=a.projectId) OR (p.id IS NOT NULL AND p.projectId!=a.projectId) OR (n.id IS NOT NULL AND n.projectId!=a.projectId)", "Invalid attachment link")
        rows("SELECT * FROM classifications").forEach { node ->
            val parent = node.nullable("parentId")?.let { row("classifications", it) }
            require(node.getString("kind") in KINDS && (parent == null || parent.getString("projectId") == node.getString("projectId"))) { "Invalid classification" }
            checkParent(node.getString("kind"), parent?.getString("kind"))
            var next = node.nullable("parentId"); val visited = mutableSetOf(node.getString("id"))
            while (next != null) { require(visited.add(next)) { "Classification cycle" }; next = row("classifications", next)?.nullable("parentId") }
        }
    }
    // Check a consistent snapshot and the connection that enforces settings on writes.
    internal suspend fun validateStorage() = database.withTransaction {
        sql.query("PRAGMA integrity_check").use { cursor ->
            require(cursor.moveToFirst() && cursor.getString(0) == "ok" && !cursor.moveToNext()) { "SQLite integrity check failed" }
        }
        sql.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "Foreign key integrity check failed" } }
        sql.query("PRAGMA journal_mode").use { require(it.moveToFirst() && it.getString(0).equals("wal", true)) { "SQLite WAL mode is unavailable" } }
        val synchronous = sql.query("PRAGMA synchronous").use { cursor ->
            require(cursor.moveToFirst()) { "SQLite synchronization mode is unavailable" }
            cursor.getInt(0)
        }
        require(synchronous == 2) { "SQLite FULL synchronization is unavailable (reported $synchronous)" }
        sql.query("PRAGMA foreign_keys").use { require(it.moveToFirst() && it.getInt(0) == 1) { "SQLite foreign-key enforcement is unavailable" } }
        checkStoredState()
    }

    internal fun close() = database.close()

    companion object {
        private const val MAX_RECORD_BYTES = 1024L * 1024
        private fun storedValueBytes(value: Any): Long = if (value is String) {
            require(value.length <= 1_000_000) { "Text exceeds the supported backup limit" }
            value.toByteArray(Charsets.UTF_8).size.toLong() + 16
        } else 24L
        const val SNAPSHOT_VERSION = 5
        private fun plannedDetailFields(table: String) = when (table) { "commitments" -> setOf("note", "calculationJson"); "instalments" -> setOf("note"); else -> emptySet() }
        private val DOCUMENT_FIELDS = setOf("category", "status", "revisionLabel", "work", "place", "notes")
        val TABLES = listOf("projects", "budgets", "classifications", "vendors", "commitments", "instalments", "expenses", "expense_lines", "classification_allocations", "planned_classifications", "payments", "payment_allocations", "attachments", "activity_entries", "attachment_links", "reminders", "audit_records", "import_records")
        fun upgradeRecordsV1(records: JSONObject): JSONObject {
            require(records.keys().asSequence().toSet() == TABLES.toSet() - "planned_classifications") { "Invalid version 1 tables" }
            val upgraded = JSONObject(records.toString())
            TABLES.filter { it != "planned_classifications" }.forEach { table ->
                val expected = SCHEMA.getValue(table).keys - (if (table == "expense_lines") setOf("nature") else emptySet()) - (if (table == "attachments") DOCUMENT_FIELDS else emptySet()) - (if (table == "expenses") setOf("invoiceNumber") else emptySet()) - (if (table == "reminders") setOf("leadDays") else emptySet()) - plannedDetailFields(table)
                upgraded.getJSONArray(table).objects().forEach { require(it.keys().asSequence().toSet() == expected) { "Invalid version 1 $table columns" } }
            }
            upgraded.getJSONArray("expense_lines").objects().forEach { it.put("nature", "") }
            upgraded.getJSONArray("attachments").objects().forEach { item -> DOCUMENT_FIELDS.forEach { item.put(it, "") } }
            upgraded.getJSONArray("expenses").objects().forEach { it.put("invoiceNumber", "") }
            upgraded.getJSONArray("reminders").objects().forEach { it.put("leadDays", 3) }
            listOf("commitments", "instalments").forEach { table -> upgraded.getJSONArray(table).objects().forEach { item -> plannedDetailFields(table).forEach { item.put(it, "") } } }
            upgraded.put("planned_classifications", JSONArray())
            return upgraded
        }
        internal fun inMemory(context: Context) = LedgerRepository(Room.inMemoryDatabaseBuilder(context.applicationContext, LedgerDatabase::class.java).build(), context.applicationContext)
        private const val SETTLEMENT = "WITH settlement AS (SELECT a.expenseId,SUM(CASE p.kind WHEN 'PAYMENT' THEN a.amountPaise ELSE -a.amountPaise END) amount FROM payment_allocations a JOIN payments p ON p.id=a.paymentId GROUP BY a.expenseId)"
        private const val DIMENSION = "CASE kind WHEN 'STAGE' THEN 'WORK' WHEN 'SUBSTAGE' THEN 'WORK' WHEN 'MATERIAL' THEN 'WORK' WHEN 'PLACE' THEN 'PLACE' ELSE 'COST' END"
        private val KINDS = setOf("STAGE", "SUBSTAGE", "MATERIAL", "COST_HEAD", "COST_SUBHEAD", "COST_DETAIL", "PLACE")
        private val MODES = setOf("DIRECT_AMOUNT", "CALCULATED")
        private val COST_NATURES = setOf("", "Material", "Labour", "Professional Service", "Equipment Purchase", "Equipment Hire", "Utility / Connection", "Transport", "Fee / Approval", "Other")
        private fun dimension(kind: String) = when (kind) { "STAGE", "SUBSTAGE", "MATERIAL" -> "WORK"; "PLACE" -> "PLACE"; else -> "COST" }
        private fun checkParent(kind: String, parent: String?) {
            require(when (kind) { "STAGE", "COST_HEAD" -> parent == null; "SUBSTAGE" -> parent == "STAGE"; "MATERIAL" -> parent == "SUBSTAGE"; "COST_SUBHEAD" -> parent == "COST_HEAD"; "COST_DETAIL" -> parent == "COST_SUBHEAD"; "PLACE" -> parent == null || parent == "PLACE"; else -> false }) { "Invalid classification parent type" }
        }
        private fun validateAttachment(storageName: String, sha: String, size: Long) {
            require(Regex("[A-Za-z0-9_-]{1,180}(?:\\.[A-Za-z0-9]{1,12})?").matches(storageName) && Regex("[a-fA-F0-9]{64}").matches(sha) && size in 1..MAX_ATTACHMENT_BYTES) { "Invalid attachment metadata" }
        }
        private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
        private fun calculationJson(value: DetailedAmount) = JSONObject().apply {
            put("quantity", value.quantity); put("rate", value.rate); put("multiplier", value.multiplier)
            put("discountAmount", value.discountAmount); put("discountPercent", value.discountPercent)
            put("taxAmount", value.taxAmount); put("taxPercent", value.taxPercent)
            put("transport", value.transport); put("adjustment", value.adjustment)
            put("quantityUnit", value.quantityUnit); put("rateUnit", value.rateUnit)
        }
        private fun parseCalculation(value: JSONObject) = DetailedAmount(value.getString("quantity"), value.getString("rate"), value.getString("multiplier"), value.getString("discountAmount"), value.getString("discountPercent"), value.getString("taxAmount"), value.getString("taxPercent"), value.getString("transport"), value.getString("adjustment"), value.getString("quantityUnit"), value.getString("rateUnit"))
        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
        private fun normalize(table: String, row: JSONObject) { BOOLEAN_COLUMNS.getValue(table).forEach { if (row.get(it) !is Boolean) row.put(it, row.getLong(it) == 1L) } }
        private val BOOLEAN_COLUMNS by lazy { SCHEMA.mapValues { (_, fields) -> fields.filterValues { it == "B" }.keys } }
        private val SCHEMA = mapOf(
            "projects" to "id:S name:S revision:L archived:B", "budgets" to "id:S projectId:S amountPaise:L",
            "classifications" to "id:S projectId:S name:S kind:S parentId:S? sortOrder:I archived:B nature:S",
            "vendors" to "id:S projectId:S name:S note:S", "commitments" to "id:S projectId:S title:S amountPaise:L vendorId:S? date:S revision:L archived:B note:S calculationJson:S",
            "instalments" to "id:S projectId:S title:S amountPaise:L dueDate:S commitmentId:S? vendorId:S? cancelled:B remind:B revision:L note:S",
            "expenses" to "id:S projectId:S description:S amountPaise:L date:S vendorId:S? note:S mode:S calculationJson:S revision:L voided:B commitmentId:S? instalmentId:S? idempotencyKey:S requestJson:S invoiceNumber:S",
            "expense_lines" to "id:S expenseId:S description:S amountPaise:L mode:S quantity:S? rate:S? multiplier:S quantityUnit:S rateUnit:S legacyJson:S nature:S",
            "classification_allocations" to "id:S expenseId:S classificationId:S dimension:S amountPaise:L",
            "planned_classifications" to "id:S classificationId:S commitmentId:S? instalmentId:S?",
            "payments" to "id:S projectId:S amountPaise:L date:S vendorId:S? reference:S note:S kind:S originalPaymentId:S? instalmentId:S? idempotencyKey:S requestJson:S",
            "payment_allocations" to "id:S paymentId:S expenseId:S amountPaise:L",
            "attachments" to "id:S projectId:S name:S mimeType:S size:L sha256:S storageName:S createdAt:L revision:L parentId:S? category:S status:S revisionLabel:S work:S place:S notes:S",
            "activity_entries" to "id:S projectId:S title:S date:S note:S revision:L", "attachment_links" to "id:S attachmentId:S expenseId:S? paymentId:S? noteId:S?",
            "reminders" to "id:S instalmentId:S enabled:B leadDays:I", "audit_records" to "id:S recordType:S recordId:S action:S beforeJson:S afterJson:S createdAt:L", "import_records" to "id:S source:S originalJson:S createdAt:L"
        ).mapValues { (_, fields) -> fields.split(' ').associate { field -> field.substringBefore(':') to field.substringAfter(':') } }
    }
}
