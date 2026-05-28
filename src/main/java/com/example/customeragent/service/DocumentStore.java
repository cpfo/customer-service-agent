package com.example.customeragent.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

@Component
public class DocumentStore {

    private final List<Document> documents = new CopyOnWriteArrayList<>();

    public void addAll(List<Document> docs) {
        documents.addAll(docs);
    }

    public void add(Document doc) {
        documents.add(doc);
    }

    public List<Document> getAll() {
        return Collections.unmodifiableList(documents);
    }

    public int removeByFilename(String filename) {
        int before = documents.size();
        documents.removeIf(doc -> {
            Object src = doc.getMetadata().get("source");
            return src != null && src.toString().contains(filename);
        });
        return before - documents.size();
    }

    public int removeByIds(Set<String> ids) {
        int before = documents.size();
        documents.removeIf(doc -> {
            Object docId = doc.getMetadata().get("doc_id");
            return docId != null && ids.contains(docId.toString());
        });
        return before - documents.size();
    }

    public List<Document> findByFilename(String filename) {
        return documents.stream()
                .filter(doc -> {
                    Object src = doc.getMetadata().get("source");
                    return src != null && src.toString().contains(filename);
                })
                .collect(Collectors.toList());
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
