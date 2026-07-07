package com.icligo.quickbooks.repository;

import com.icligo.quickbooks.model.StoredTokens;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StoredTokensRepository extends MongoRepository<StoredTokens, String> {

}
