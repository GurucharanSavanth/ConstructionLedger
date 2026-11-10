package com.constructioncost.nativeapp.data.local

import androidx.room.Dao
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.constructioncost.nativeapp.domain.*
import kotlinx.coroutines.flow.Flow

@Dao
internal interface LedgerDao {
    @RawQuery(observedEntities = [ProjectEntity::class, ProjectDeletionEntity::class, BudgetEntity::class]) fun projects(query: SupportSQLiteQuery): Flow<List<ProjectInfo>>
    @RawQuery(observedEntities = [ClassificationEntity::class]) fun classifications(query: SupportSQLiteQuery): Flow<List<ClassificationInfo>>
    @RawQuery(observedEntities = [VendorEntity::class, ExpenseEntity::class, PaymentAllocationEntity::class, PaymentEntity::class]) fun vendors(query: SupportSQLiteQuery): Flow<List<VendorInfo>>
    @RawQuery(observedEntities = [CommitmentEntity::class]) fun commitments(query: SupportSQLiteQuery): Flow<List<CommitmentInfo>>
    @RawQuery(observedEntities = [ProjectEntity::class, BudgetEntity::class, ExpenseEntity::class, PaymentEntity::class, PaymentAllocationEntity::class, InstalmentEntity::class, CommitmentEntity::class]) fun summary(query: SupportSQLiteQuery): Flow<LedgerSummary>
    @RawQuery(observedEntities = [ExpenseEntity::class, VendorEntity::class, PaymentAllocationEntity::class, PaymentEntity::class, ClassificationAllocationEntity::class, ClassificationEntity::class]) fun expenses(query: SupportSQLiteQuery): Flow<List<ExpenseRow>>
    @RawQuery(observedEntities = [ExpenseEntity::class, VendorEntity::class, PaymentAllocationEntity::class, PaymentEntity::class, ClassificationAllocationEntity::class, ClassificationEntity::class, ExpenseLineEntity::class]) fun filteredExpenseSummary(query: SupportSQLiteQuery): Flow<ExpenseSummary>
    @RawQuery(observedEntities = [PaymentEntity::class, PaymentAllocationEntity::class, VendorEntity::class]) fun payments(query: SupportSQLiteQuery): Flow<List<PaymentRow>>
    @RawQuery(observedEntities = [InstalmentEntity::class, PaymentEntity::class, ReminderEntity::class]) fun instalments(query: SupportSQLiteQuery): Flow<List<InstalmentRow>>
    @RawQuery(observedEntities = [AttachmentEntity::class, AttachmentLinkEntity::class]) fun attachments(query: SupportSQLiteQuery): Flow<List<AttachmentInfo>>
    @RawQuery(observedEntities = [ActivityEntity::class, ExpenseEntity::class, PaymentEntity::class, InstalmentEntity::class, CommitmentEntity::class, AttachmentEntity::class, VendorEntity::class, AuditEntity::class]) fun activity(query: SupportSQLiteQuery): Flow<List<ActivityRow>>
    @RawQuery(observedEntities = [ActivityEntity::class, ExpenseEntity::class, PaymentEntity::class, InstalmentEntity::class, CommitmentEntity::class, AttachmentEntity::class]) fun calendarDayCounts(query: SupportSQLiteQuery): Flow<List<CalendarDayCount>>
    @RawQuery(observedEntities = [ClassificationEntity::class, ClassificationAllocationEntity::class, ExpenseEntity::class, VendorEntity::class, ExpenseLineEntity::class]) fun insights(query: SupportSQLiteQuery): Flow<List<InsightRow>>
    @RawQuery(observedEntities = [ExpenseLineEntity::class, ExpenseEntity::class]) fun costNature(query: SupportSQLiteQuery): Flow<List<CostNatureRow>>
    @RawQuery(observedEntities = [PlannedClassificationEntity::class, ClassificationEntity::class, CommitmentEntity::class, InstalmentEntity::class, PaymentEntity::class]) fun plannedInsights(query: SupportSQLiteQuery): Flow<List<PlannedRow>>
    @RawQuery(observedEntities = [ExpenseEntity::class, PaymentEntity::class]) fun monthly(query: SupportSQLiteQuery): Flow<List<MonthlyRow>>
    @RawQuery(observedEntities = [AuditEntity::class]) fun audits(query: SupportSQLiteQuery): Flow<List<AuditInfo>>
    @RawQuery(observedEntities = [AuditEntity::class]) fun journalRevision(query: SupportSQLiteQuery): Flow<Long>
    @RawQuery(observedEntities = [ActivityEntity::class]) fun notes(query: SupportSQLiteQuery): Flow<List<ActivityNote>>
}
