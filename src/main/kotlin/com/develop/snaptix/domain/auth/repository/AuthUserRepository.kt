package com.develop.snaptix.domain.auth.repository

import com.develop.snaptix.domain.user.entity.UserRole
import com.develop.snaptix.domain.user.entity.UsersTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.springframework.stereotype.Repository

@Repository
class AuthUserRepository {
    fun existsByEmail(email: String): Boolean =
        transaction {
            UsersTable
                .selectAll()
                .where { UsersTable.email eq email }
                .limit(1)
                .any()
        }

    fun findByEmail(email: String): AuthUserRecord? =
        transaction {
            UsersTable
                .selectAll()
                .where { UsersTable.email eq email }
                .limit(1)
                .map {
                    AuthUserRecord(
                        id = it[UsersTable.id],
                        email = it[UsersTable.email],
                        password = it[UsersTable.password],
                        role = UserRole.valueOf(it[UsersTable.role]),
                    )
                }.firstOrNull()
        }

    fun saveUser(
        email: String,
        encodedPassword: String,
        role: UserRole = UserRole.USER,
    ): Long =
        transaction {
            UsersTable.insert {
                it[UsersTable.email] = email
                it[password] = encodedPassword
                it[UsersTable.role] = role.name
            }[UsersTable.id]
        }
}
