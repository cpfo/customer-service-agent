package com.example.customeragent.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class DocumentStore {

    private final List<Document> documents = new ArrayList<>();

    public void addAll(List<Document> docs) {
        documents.addAll(docs);
    }

    public List<Document> getAll() {
        return Collections.unmodifiableList(documents);
    }

    public void clear() {
        documents.clear();
    }

    public boolean isEmpty() {
        return documents.isEmpty();
    }

    public int size() {
        return documents.size();
    }
}
