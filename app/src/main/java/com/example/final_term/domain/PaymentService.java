package com.example.final_term.domain;

import com.example.final_term.domain.DomainDtos.PaymentRecord;
import com.example.final_term.domain.DomainDtos.PaymentRequest;
import com.google.android.gms.tasks.Task;

public interface PaymentService { Task<PaymentRecord> createPayment(PaymentRequest request); }
