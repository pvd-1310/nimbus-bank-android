package com.example.final_term.domain;

import com.example.final_term.domain.DomainDtos.*;
import com.google.android.gms.tasks.Task;

public interface TransferService {
    Task<TransactionIntent> createTransferIntent(TransferRequest request);
    Task<AuthorizationResult> verifyOtp(OtpVerification verification);
    Task<TransactionReceipt> commitTransfer(TransactionIntent intent, AuthorizationResult authorization);
}
