package com.develop.snaptix.domain.auditlog.entity

import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.CurrentTimestamp
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

object AuditLogsTable : Table("audit_logs") {
    val id = long("id").autoIncrement()

    // 실행 주체 (Admin ID 또는 SYSTEM=null)
    val actorId = long("actor_id").nullable()

    // RECONCILE_RUN, CB_STATE_CHANGE, MANUAL_ALERT 등
    val actionType = varchar("action_type", 50)

    // RESERVATION, EVENT, ZONE, USER 등
    val targetType = varchar("target_type", 50).nullable()

    // 타겟 엔티티 내부 PK (내부 추적용)
    val targetId = long("target_id").nullable()

    // 상세 변경 내역 또는 에러 스택
    val details = jsonb<JsonElement>("details", kotlinx.serialization.json.Json).nullable()
    val createdAt = timestamp("created_at").defaultExpression(CurrentTimestamp)

    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_audit_action", false, actionType, createdAt)
        index("idx_audit_target", false, targetType, targetId)
    }
}
