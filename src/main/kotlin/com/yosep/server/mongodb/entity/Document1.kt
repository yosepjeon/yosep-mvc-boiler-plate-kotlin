package com.yosep.server.mongodb.entity

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

@Document(collection = "document_example1")
class Document1(
    @Id
    val id: String,
    val value1: String,
    val value2: Long,
    val value3: Int,
    val value4: Double
) {
}