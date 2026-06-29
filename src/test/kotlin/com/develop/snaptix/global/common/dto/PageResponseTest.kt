package com.develop.snaptix.global.common.dto

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PageResponseTest {
    @Test
    fun `공통 페이지 요청과 도메인 DTO 목록을 공통 페이지 응답으로 합성할 수 있다`() {
        val request = PageRequestDto(page = 2, size = 10)

        val response =
            PageResponse.of(
                content =
                    listOf(
                        SampleListItem(id = "event-21", title = "SnapTix Concert"),
                        SampleListItem(id = "event-22", title = "SnapTix Festival"),
                    ),
                pageNumber = request.page,
                pageSize = request.size,
                totalElements = 45,
            )

        assertThat(response.content)
            .containsExactly(
                SampleListItem(id = "event-21", title = "SnapTix Concert"),
                SampleListItem(id = "event-22", title = "SnapTix Festival"),
            )
        assertThat(response.pageable.pageNumber).isEqualTo(request.page)
        assertThat(response.pageable.pageSize).isEqualTo(request.size)
        assertThat(response.pageable.totalElements).isEqualTo(45)
        assertThat(response.pageable.totalPages).isEqualTo(5)
    }

    @Test
    fun `도메인 DTO 목록과 페이징 메타데이터를 합성할 수 있다`() {
        val response =
            PageResponse.of(
                content =
                    listOf(
                        SampleListItem(id = "event-1", title = "SnapTix Concert"),
                        SampleListItem(id = "event-2", title = "SnapTix Festival"),
                    ),
                pageNumber = 0,
                pageSize = 10,
                totalElements = 12,
            )

        assertThat(response.content)
            .containsExactly(
                SampleListItem(id = "event-1", title = "SnapTix Concert"),
                SampleListItem(id = "event-2", title = "SnapTix Festival"),
            )
        assertThat(response.pageable.pageNumber).isZero()
        assertThat(response.pageable.pageSize).isEqualTo(10)
        assertThat(response.pageable.totalElements).isEqualTo(12)
        assertThat(response.pageable.totalPages).isEqualTo(2)
    }

    @Test
    fun `공통 페이지 응답은 content와 메타데이터를 생성한다`() {
        val response =
            PageResponse.of(
                content = listOf("event-1", "event-2"),
                pageNumber = 1,
                pageSize = 20,
                totalElements = 45,
            )

        assertThat(response.content).containsExactly("event-1", "event-2")
        assertThat(response.pageable.pageNumber).isEqualTo(1)
        assertThat(response.pageable.pageSize).isEqualTo(20)
        assertThat(response.pageable.totalElements).isEqualTo(45)
        assertThat(response.pageable.totalPages).isEqualTo(3)
    }

    @Test
    fun `전체 항목이 없으면 전체 페이지는 0이다`() {
        val response =
            PageResponse.of(
                content = emptyList<String>(),
                pageNumber = 0,
                pageSize = 20,
                totalElements = 0,
            )

        assertThat(response.pageable.totalPages).isZero()
    }

    @Test
    fun `전체 페이지는 올림으로 계산한다`() {
        val response =
            PageResponse.of(
                content = emptyList<String>(),
                pageNumber = 0,
                pageSize = 10,
                totalElements = 21,
            )

        assertThat(response.pageable.totalPages).isEqualTo(3)
    }

    @Test
    fun `pageSize는 1 이상이어야 한다`() {
        assertThatThrownBy {
            PageResponse.of(
                content = emptyList<String>(),
                pageNumber = 0,
                pageSize = 0,
                totalElements = 10,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("pageSize는 1 이상이어야 합니다.")
    }

    private data class SampleListItem(
        val id: String,
        val title: String,
    )
}
