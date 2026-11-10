package com.constructioncost.nativeapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ProjectEntity::class, ProjectDeletionEntity::class, BudgetEntity::class, ClassificationEntity::class, VendorEntity::class, CommitmentEntity::class, InstalmentEntity::class, ExpenseEntity::class, ExpenseLineEntity::class, ClassificationAllocationEntity::class, PlannedClassificationEntity::class, PaymentEntity::class, PaymentAllocationEntity::class, AttachmentEntity::class, ActivityEntity::class, AttachmentLinkEntity::class, ReminderEntity::class, AuditEntity::class, ImportEntity::class], version = 6, exportSchema = true)
internal abstract class LedgerDatabase : RoomDatabase() {
    abstract fun reads(): LedgerDao

    companion object {
        val DURABILITY_CALLBACK = object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE commitments ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE commitments ADD COLUMN calculationJson TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE instalments ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            }
        }
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS project_deletions (projectId TEXT NOT NULL PRIMARY KEY, phase TEXT NOT NULL, filesJson TEXT NOT NULL, startedAt INTEGER NOT NULL)")
            }
        }
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expense_lines ADD COLUMN nature TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE TABLE IF NOT EXISTS planned_classifications (id TEXT NOT NULL PRIMARY KEY, classificationId TEXT NOT NULL, commitmentId TEXT, instalmentId TEXT, FOREIGN KEY(classificationId) REFERENCES classifications(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(commitmentId) REFERENCES commitments(id) ON UPDATE NO ACTION ON DELETE NO ACTION, FOREIGN KEY(instalmentId) REFERENCES instalments(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_planned_classifications_classificationId ON planned_classifications(classificationId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_planned_classifications_commitmentId_classificationId ON planned_classifications(commitmentId, classificationId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_planned_classifications_instalmentId_classificationId ON planned_classifications(instalmentId, classificationId)")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("category", "status", "revisionLabel", "work", "place", "notes").forEach { column ->
                    db.execSQL("ALTER TABLE attachments ADD COLUMN $column TEXT NOT NULL DEFAULT ''")
                }
            }
        }
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE expenses ADD COLUMN invoiceNumber TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE reminders ADD COLUMN leadDays INTEGER NOT NULL DEFAULT 3")
            }
        }
    }
}
