package com.icligo.quickbooks.clients.quickbooks.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {
    private List<T> data;
    private Pagination pagination;
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Pagination {
        private Integer page;
        private Integer limit;
        private Integer total;
        private Integer totalPages;
    }
}
