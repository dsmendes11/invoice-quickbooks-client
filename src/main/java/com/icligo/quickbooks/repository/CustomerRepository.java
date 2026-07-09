package com.icligo.quickbooks.repository;

import com.icligo.quickbooks.clients.quickbooks.model.Customer;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CustomerRepository extends MongoRepository<Customer, String> {
}
