package com.example.final_term.domain;

import com.example.final_term.domain.DomainDtos.UploadTicket;
import com.google.android.gms.tasks.Task;

public interface StorageService { Task<UploadTicket> createUploadTicket(String ownerUid, String fileName); }
