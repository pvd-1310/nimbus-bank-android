package com.example.final_term.domain;

import com.example.final_term.domain.DomainDtos.EkycRequest;
import com.example.final_term.domain.DomainDtos.EkycSubmission;
import com.google.android.gms.tasks.Task;

public interface EkycService {

    Task<EkycRequest> submit(EkycSubmission submission);

    Task<EkycRequest> getStatus(String customerUid);

    Task<Void> cancel(String requestId);
}