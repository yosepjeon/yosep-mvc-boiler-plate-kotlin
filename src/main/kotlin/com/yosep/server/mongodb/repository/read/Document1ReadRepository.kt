package com.yosep.server.mongodb.repository.read

import com.yosep.server.mongodb.entity.Document1
import org.springframework.data.mongodb.repository.MongoRepository

interface Document1ReadRepository: MongoRepository<Document1, String>