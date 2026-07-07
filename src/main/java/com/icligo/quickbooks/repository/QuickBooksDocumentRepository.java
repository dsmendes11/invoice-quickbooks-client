package com.icligo.quickbooks.repository;

import com.icligo.quickbooks.model.QuickBooksDocument;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QuickBooksDocumentRepository extends MongoRepository<QuickBooksDocument, String> {

    Optional<QuickBooksDocument> findByNaturalKey(String naturalKey);
}
