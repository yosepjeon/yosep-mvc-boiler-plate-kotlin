package com.yosep.server.mongodb.repository.read

import com.yosep.server.mongodb.entity.Document1
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class Document1ReadRepositoryTest(
    @Autowired private val document1ReadRepository: Document1ReadRepository
) {

    @BeforeEach
    fun initBefore() {
        document1ReadRepository.deleteAll()
    }

    @AfterEach
    fun initAfter() {
        document1ReadRepository.deleteAll()
    }

    @Test
    fun saveDocument1Test() {
        val createdDocument1 = Document1(
            id = "id1",
            value1 = "value1",
            value2 = 3L,
            value3 = 4,
            value4 = 3.2,
        )

        val savedDocument1 = document1ReadRepository.save(createdDocument1)

        assertEquals(savedDocument1.id, savedDocument1.id)
        assertEquals(savedDocument1.value1, savedDocument1.value1)
        assertEquals(savedDocument1.value2, savedDocument1.value2)
        assertEquals(savedDocument1.value3, savedDocument1.value3)
        assertEquals(savedDocument1.value4, savedDocument1.value4)
    }

    @Test
    fun findByIdTest() {
        val createdDocument1 = Document1(
            id = "id1",
            value1 = "value1",
            value2 = 3L,
            value3 = 4,
            value4 = 3.2,
        )

        val savedDocument1 = document1ReadRepository.save(createdDocument1)
        val selectedDocument1 = document1ReadRepository.findById(savedDocument1.id).get()

        assertEquals(savedDocument1.id, selectedDocument1.id)
        assertEquals(savedDocument1.value1, selectedDocument1.value1)
        assertEquals(savedDocument1.value2, selectedDocument1.value2)
        assertEquals(savedDocument1.value3, selectedDocument1.value3)
        assertEquals(savedDocument1.value4, selectedDocument1.value4)
    }
}