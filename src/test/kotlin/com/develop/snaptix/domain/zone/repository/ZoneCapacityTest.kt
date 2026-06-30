package com.develop.snaptix.domain.zone.repository

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ZoneCapacityTest {
    @Test
    fun `should create ZoneCapacity with valid fields`() {
        // given
        val expectedId = 1L
        val expectedPublicId = UUID.randomUUID().toString()
        val expectedCapacity = 100

        // when
        val zoneCapacity =
            ZoneCapacity(
                id = expectedId,
                publicId = expectedPublicId,
                totalCapacity = expectedCapacity,
            )

        // then
        assertThat(zoneCapacity.id).isEqualTo(expectedId)
        assertThat(zoneCapacity.publicId).isEqualTo(expectedPublicId)
        assertThat(zoneCapacity.totalCapacity).isEqualTo(expectedCapacity)
    }

    @Test
    fun `should create ZoneCapacity even with zero or negative capacity`() {
        // given & when
        val zeroCapacity = ZoneCapacity(id = 2L, publicId = UUID.randomUUID().toString(), totalCapacity = 0)
        val negativeCapacity = ZoneCapacity(id = 3L, publicId = UUID.randomUUID().toString(), totalCapacity = -50)

        // then
        assertThat(zeroCapacity.totalCapacity).isEqualTo(0)
        assertThat(negativeCapacity.totalCapacity).isEqualTo(-50)
    }
}
