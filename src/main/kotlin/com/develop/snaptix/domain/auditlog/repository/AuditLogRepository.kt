package com.develop.snaptix.domain.auditlog.repository

import com.develop.snaptix.domain.auditlog.entity.AuditLogsTable
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository

/**
 * 감사 로그 INSERT. (작업 명세서 §5.5 · §11-3)
 *
 * actorId=null → SYSTEM(스케줄러), actorId=관리자 userId → Admin 트리거.
 */
@Repository
class AuditLogRepository {
    fun insert(
        actorId: Long?,
        actionType: String,
        targetType: String?,
        targetId: Long?,
        details: JsonElement?,
    ) {
        transaction {
            AuditLogsTable.insert {
                it[AuditLogsTable.actorId] = actorId
                it[AuditLogsTable.actionType] = actionType
                it[AuditLogsTable.targetType] = targetType
                it[AuditLogsTable.targetId] = targetId
                it[AuditLogsTable.details] = details
            }
        }
    }
}
