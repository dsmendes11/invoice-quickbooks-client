package com.icligo.quickbooks.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonView;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Data
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Document(collection = "stored_tokens")
@JsonView({StoredTokens.class})
public class StoredTokens {
    @Id
    private String id;
    private String accessToken;
    private String refreshToken;
    private int expiresIn;
    private long refreshTokenExpiresIn;
}
